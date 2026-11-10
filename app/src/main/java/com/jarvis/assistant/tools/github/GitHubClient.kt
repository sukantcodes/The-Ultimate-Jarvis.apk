package com.jarvis.assistant.tools.github

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.jarvis.assistant.provider.HttpBody
import com.jarvis.assistant.provider.HttpRequestSpec
import com.jarvis.assistant.provider.HttpTransport
import com.jarvis.assistant.provider.TransportException
import com.jarvis.assistant.provider.TransportFailure
import com.jarvis.assistant.tools.SecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Supplies the GitHub access token. The app implements it over encrypted storage; the token is never kept here. */
interface GitHubTokenSource {
    fun get(): String?
}

class FixedTokenSource(@Volatile var token: String?) : GitHubTokenSource {
    override fun get(): String? = token
}

enum class GhErrorKind { NO_TOKEN, UNAUTHORIZED, FORBIDDEN, RATE_LIMITED, NOT_FOUND, CONFLICT, INVALID, SERVER, NETWORK, TOO_LARGE, UNSAFE, OTHER }

/** A GitHub problem described for the user. The message never contains the token, request headers or a URL with a query. */
class GitHubException(val kind: GhErrorKind, message: String, val status: Int? = null, val resetAtMs: Long? = null) : IOException(message)

class GhResponse(val code: Int, val text: String, headers: Map<String, String>) {
    private val lower: Map<String, String> = headers.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
    fun header(name: String): String? = lower[name.lowercase(Locale.ROOT)]

    fun json(): JsonElement = try {
        JsonParser.parseString(text)
    } catch (e: JsonParseException) {
        throw GitHubException(GhErrorKind.OTHER, "GitHub returned an answer that could not be read.", code)
    }

    fun obj(): JsonObject = json().takeIf { it.isJsonObject }?.asJsonObject
        ?: throw GitHubException(GhErrorKind.OTHER, "GitHub returned an unexpected answer.", code)
}

/** Registers the token with the redactor so it is masked everywhere output is shown, logged or sent to a model. */
object GitHubSecrets {
    fun register(redactor: SecretRedactor?, source: GitHubTokenSource) {
        redactor?.register(source.get())
    }
}

/** Downloads a (possibly large, binary) file. Separate from HttpTransport, whose answers are text and never follow redirects. */
interface BinaryDownloader {
    suspend fun download(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray
}

/**
 * java.net downloader that follows redirects by hand: HTTPS only, at most [maxRedirects] hops, and the
 * Authorization header is dropped as soon as a redirect leaves the original host (GitHub sends downloads on to a
 * storage host). Error messages never contain the URL, because redirect URLs can carry signed query strings.
 */
class JavaNetDownloader(
    private val requireHttps: Boolean = true,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
    private val maxRedirects: Int = 5
) : BinaryDownloader {

    override suspend fun download(url: String, headers: Map<String, String>, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        var current = try { URL(url) } catch (e: Exception) { throw GitHubException(GhErrorKind.INVALID, "The download address is not valid.") }
        var hdrs = headers
        var hops = 0
        while (true) {
            if (current.protocol != "https" && (requireHttps || current.protocol != "http")) {
                throw GitHubException(GhErrorKind.UNSAFE, "Refusing to download over an insecure connection.")
            }
            val conn = try { current.openConnection() as HttpURLConnection } catch (e: IOException) {
                throw GitHubException(GhErrorKind.NETWORK, "Could not reach GitHub.")
            }
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                hdrs.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = try { conn.responseCode } catch (e: IOException) {
                    throw GitHubException(GhErrorKind.NETWORK, "Could not reach GitHub (the connection failed or timed out).")
                }
                if (code in intArrayOf(301, 302, 303, 307, 308)) {
                    if (++hops > maxRedirects) throw GitHubException(GhErrorKind.UNSAFE, "The download was redirected too many times.")
                    val location = conn.getHeaderField("Location") ?: throw GitHubException(GhErrorKind.OTHER, "GitHub sent an incomplete redirect.")
                    val next = try { URL(current, location) } catch (e: Exception) { throw GitHubException(GhErrorKind.UNSAFE, "GitHub sent an invalid redirect.") }
                    if (!next.host.equals(current.host, ignoreCase = true) || next.port != current.port) {
                        hdrs = hdrs.filterKeys { !it.equals("Authorization", ignoreCase = true) }
                    }
                    current = next
                    continue
                }
                if (code !in 200..299) throw GitHubException(statusKind(code), "The download failed (HTTP $code).", code)
                val declared = conn.contentLengthLong
                if (declared > maxBytes) throw GitHubException(GhErrorKind.TOO_LARGE, "The file is ${declared / 1_048_576} MB, larger than the ${maxBytes / 1_048_576} MB limit.")
                return@withContext readLimited(conn, maxBytes)
            } finally {
                conn.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
    }

    private fun readLimited(conn: HttpURLConnection, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        try {
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw GitHubException(GhErrorKind.TOO_LARGE, "The file is larger than the ${maxBytes / 1_048_576} MB limit.")
                    out.write(buf, 0, n)
                }
            }
        } catch (e: IOException) {
            if (e is GitHubException) throw e
            throw GitHubException(GhErrorKind.NETWORK, "The download was interrupted.")
        }
        return out.toByteArray()
    }

    private fun statusKind(code: Int) = when (code) {
        401 -> GhErrorKind.UNAUTHORIZED; 403 -> GhErrorKind.FORBIDDEN; 404 -> GhErrorKind.NOT_FOUND
        in 500..599 -> GhErrorKind.SERVER; else -> GhErrorKind.OTHER
    }
}

/** A repository named as `owner/name` or as a github.com address. */
object GhRepoRef {
    private val NAME = Regex("^[A-Za-z0-9_.-]{1,100}$")

    fun parse(text: String): Pair<String, String> {
        var s = text.trim()
        if (s.isEmpty()) throw GitHubException(GhErrorKind.INVALID, "Give the repository as owner/name, for example octocat/Hello-World.")
        val urlMatch = Regex("^(?:https?://)?(?:www\\.)?github\\.com/([^/\\s]+)/([^/\\s#?]+)", RegexOption.IGNORE_CASE).find(s)
        if (urlMatch != null) {
            s = urlMatch.groupValues[1] + "/" + urlMatch.groupValues[2]
        } else if (Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(s) || s.startsWith("git@")) {
            throw GitHubException(GhErrorKind.INVALID, "Only github.com repositories are supported.")
        }
        val parts = s.removeSuffix(".git").split('/')
        if (parts.size != 2 || !NAME.matches(parts[0]) || !NAME.matches(parts[1]) || parts[1] == "." || parts[1] == "..") {
            throw GitHubException(GhErrorKind.INVALID, "\"$text\" is not a valid repository. Use owner/name.")
        }
        return parts[0] to parts[1]
    }
}

/** Small cache of file contents the model has read, so a later edit can be shown as a diff before it is committed. */
class ContentCache(private val capacity: Int = 32) {
    class Entry(val text: String, val sha: String)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }

    @Synchronized fun put(owner: String, repo: String, ref: String?, path: String, entry: Entry) { map[key(owner, repo, ref, path)] = entry }
    @Synchronized fun get(owner: String, repo: String, ref: String?, path: String): Entry? = map[key(owner, repo, ref, path)]

    private fun key(owner: String, repo: String, ref: String?, path: String) = "$owner/$repo@${ref.orEmpty()}:$path".lowercase(Locale.ROOT)
}

class GitHubClient(
    private val transport: HttpTransport,
    val tokenSource: GitHubTokenSource,
    val redactor: SecretRedactor? = null,
    val downloader: BinaryDownloader? = null,
    private val baseUrl: String = "https://api.github.com",
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 25_000L
) {
    val contentCache = ContentCache()

    init {
        require(baseUrl.startsWith("https://")) { "GitHub must be reached over HTTPS." }
    }

    /** The token, checked for characters that could break a header, and registered for redaction. Null when not connected. */
    fun token(): String? {
        val t = tokenSource.get()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!t.all { it.code in 0x21..0x7E }) throw GitHubException(GhErrorKind.INVALID, "The saved GitHub token contains invalid characters. Add it again.")
        redactor?.register(t)
        return t
    }

    val isConnected: Boolean get() = tokenSource.get()?.isNotBlank() == true

    suspend fun get(path: String, query: Map<String, String> = emptyMap(), accept: String = JSON, needsToken: Boolean = false): GhResponse =
        request("GET", path, query, null, accept, needsToken)

    /** Changes something on GitHub. Always needs a token. */
    suspend fun send(method: String, path: String, body: JsonObject? = null): GhResponse =
        request(method, path, emptyMap(), body, JSON, true)

    private suspend fun request(method: String, path: String, query: Map<String, String>, body: JsonObject?, accept: String, needsToken: Boolean): GhResponse {
        val token = token()
        if (token == null && needsToken) {
            throw GitHubException(GhErrorKind.NO_TOKEN, "GitHub is not connected. Add a personal access token in Settings → GitHub Integration.")
        }
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = accept
        headers["X-GitHub-Api-Version"] = API_VERSION
        headers["User-Agent"] = USER_AGENT
        if (token != null) headers["Authorization"] = "Bearer $token"
        val q = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val spec = HttpRequestSpec(method, baseUrl + path + q, headers, body?.let { HttpBody.Text("application/json", it.toString()) }, timeoutMs)
        val response = try {
            transport.execute(spec)
        } catch (e: TransportException) {
            throw GitHubException(GhErrorKind.NETWORK, when (e.kind) {
                TransportFailure.TIMEOUT -> "GitHub did not answer in time. Check the connection and try again."
                TransportFailure.TLS -> "A secure connection to GitHub could not be made."
                else -> "Could not reach GitHub. Check the internet connection."
            })
        }
        val r = GhResponse(response.code, response.body, response.headers)
        if (response.isSuccessful) return r
        throw mapError(r, token != null)
    }

    /** Downloads a binary GitHub answer (zipball, release asset). The token is sent only to GitHub's API host. */
    suspend fun downloadBytes(path: String, accept: String, maxBytes: Long): ByteArray {
        val dl = downloader ?: throw GitHubException(GhErrorKind.OTHER, "File downloads are not available in this build.")
        val token = token()
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = accept
        headers["X-GitHub-Api-Version"] = API_VERSION
        headers["User-Agent"] = USER_AGENT
        if (token != null) headers["Authorization"] = "Bearer $token"
        return dl.download(baseUrl + path, headers, maxBytes)
    }

    suspend fun defaultBranch(owner: String, repo: String): String =
        get(repoPath(owner, repo)).obj().get("default_branch")?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw GitHubException(GhErrorKind.OTHER, "Could not find the default branch of $owner/$repo.")

    private fun mapError(r: GhResponse, hadToken: Boolean): GitHubException {
        val message = messageOf(r.text)
        val remaining = r.header("x-ratelimit-remaining")?.toLongOrNull()
        val resetMs = r.header("x-ratelimit-reset")?.toLongOrNull()?.times(1000)
        val retryAfterMs = r.header("retry-after")?.toLongOrNull()?.times(1000)
        val limited = r.code == 429 || (r.code == 403 && (remaining == 0L || message.contains("rate limit", true) || retryAfterMs != null))
        return when {
            limited -> {
                val until = resetMs ?: retryAfterMs?.let { clock() + it }
                val wait = until?.let { ((it - clock()).coerceAtLeast(0) + 59_999) / 60_000 }
                GitHubException(GhErrorKind.RATE_LIMITED,
                    "GitHub's rate limit was reached" + (if (wait != null) "; it resets in about ${if (wait <= 1) "a minute" else "$wait minutes"}" else "") +
                        (if (!hadToken) ". Connecting a token raises the limit." else "."), r.code, until)
            }
            r.code == 401 -> GitHubException(GhErrorKind.UNAUTHORIZED,
                "GitHub rejected the access token (401). It may be wrong, expired or revoked. Replace it in Settings → GitHub Integration.", 401)
            r.code == 403 -> GitHubException(GhErrorKind.FORBIDDEN,
                "GitHub refused this action (403)" + (if (message.isNotBlank()) ": ${message.take(200)}" else "") + ". " +
                    (if (r.header("x-github-sso") != null) "The token must be authorised for this organisation's single sign-on. "
                    else "The token may not have permission for this repository. ") +
                    (r.header("x-accepted-github-permissions")?.let { "Needed permission: ${it.take(120)}. " } ?: ""), 403)
            r.code == 404 -> GitHubException(GhErrorKind.NOT_FOUND,
                "Not found. The repository, branch, file or number does not exist, or the token can not see it" +
                    (if (!hadToken) " (private items need a connected token)." else " (GitHub also answers 404 for private items without access)."), 404)
            r.code == 409 -> GitHubException(GhErrorKind.CONFLICT,
                "GitHub reports a conflict (409)" + (if (message.isNotBlank()) ": ${message.take(200)}" else "") + ". The file or branch may have changed; read it again and retry.", 409)
            r.code == 422 -> GitHubException(GhErrorKind.INVALID, "GitHub could not process the request (422): ${validationDetail(r.text, message)}", 422)
            r.code in 500..599 -> GitHubException(GhErrorKind.SERVER, "GitHub is having trouble right now (HTTP ${r.code}). Try again in a moment.", r.code)
            else -> GitHubException(GhErrorKind.OTHER, "GitHub answered HTTP ${r.code}" + (if (message.isNotBlank()) ": ${message.take(200)}" else "."), r.code)
        }
    }

    private fun messageOf(body: String): String = try {
        JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject?.get("message")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    } catch (e: Exception) { "" }

    private fun validationDetail(body: String, fallback: String): String = try {
        val errors = JsonParser.parseString(body).asJsonObject.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
        val parts = errors?.mapNotNull { e ->
            if (e.isJsonPrimitive) e.asString
            else e.takeIf { it.isJsonObject }?.asJsonObject?.let { o -> o.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: o.get("code")?.takeIf { it.isJsonPrimitive }?.asString }
        }.orEmpty()
        (listOf(fallback) + parts).filter { it.isNotBlank() }.joinToString("; ").ifBlank { "invalid input" }.take(300)
    } catch (e: Exception) { fallback.ifBlank { "invalid input" } }

    companion object {
        const val JSON = "application/vnd.github+json"
        const val DIFF = "application/vnd.github.diff"
        const val SHA = "application/vnd.github.sha"
        const val RAW = "application/vnd.github.raw+json"
        const val API_VERSION = "2022-11-28"
        const val USER_AGENT = "JARVIS-Android"

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** Encodes each path segment but keeps the slashes. Rejects traversal and git internals. */
        fun encPath(path: String): String {
            val segs = path.trim('/').split('/')
            if (segs.any { it.isEmpty() || it == "." || it == ".." }) throw GitHubException(GhErrorKind.INVALID, "\"$path\" is not a valid path inside a repository.")
            return segs.joinToString("/") { enc(it) }
        }

        fun repoPath(owner: String, repo: String) = "/repos/${enc(owner)}/${enc(repo)}"
    }
}
