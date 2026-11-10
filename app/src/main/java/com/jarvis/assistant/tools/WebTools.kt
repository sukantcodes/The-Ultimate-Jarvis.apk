package com.jarvis.assistant.tools

import com.google.gson.JsonObject
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** HTTPS-only search and page retrieval. Private/local addresses and redirects are rejected to limit SSRF. */
object WebTools {
    private const val MAX_RESPONSE_BYTES = 1_000_000
    private const val MAX_OUTPUT_CHARS = 24_000

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .dns(Dns { host ->
                val addresses = InetAddress.getAllByName(host).toList()
                if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
                    throw IOException("The destination resolves to a private or restricted address.")
                }
                addresses
            })
            .build()
    }

    fun all(): List<Tool> = listOf(Search, Fetch)

    private fun arg(args: JsonObject, key: String): String? =
        args.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun safeUrl(raw: String): HttpUrl? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.host.equals("localhost", true) || url.host.endsWith(".local", true)) return null
        return url
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> {
                val a = bytes[0]; val b = bytes[1]
                !(a == 0 || a == 10 || a == 100 && b in 64..127 || a == 127 ||
                    a == 169 && b == 254 || a == 172 && b in 16..31 ||
                    a == 192 && b == 168 || a == 198 && b in 18..19 || a >= 224)
            }
            is Inet6Address -> {
                val first = bytes[0]
                !(first and 0xfe == 0xfc || first == 0xff || first == 0xfe && (bytes[1] and 0xc0) == 0x80)
            }
            else -> false
        }
    }

    private fun request(url: HttpUrl, stripHtmlOutput: Boolean = true): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/html, text/plain, application/json, application/xml;q=0.9")
            .header("User-Agent", "JARVIS-ChatBox/1.0 (web research)")
            .get()
            .build()
        client.newCall(req).execute().use { response ->
            if (response.code in 300..399) throw IOException("The website redirected the request; redirects are not followed for safety.")
            if (!response.isSuccessful) throw IOException("Website returned HTTP ${response.code}.")
            val body = response.body ?: throw IOException("The website returned an empty response.")
            val type = body.contentType()?.let { "${it.type}/${it.subtype}" }?.lowercase().orEmpty()
            if (type !in setOf("text/html", "text/plain", "application/json", "application/xml", "text/xml")) {
                throw IOException("Unsupported page type: ${type.ifBlank { "unknown" }}.")
            }
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("The page is too large to fetch safely.")
            val out = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > MAX_RESPONSE_BYTES) throw IOException("The page exceeded the 1 MB safety limit.")
                    out.write(buffer, 0, count)
                }
            }
            val raw = out.toString(StandardCharsets.UTF_8.name())
            return if (type == "text/html" && stripHtmlOutput) stripHtml(raw) else raw
        }
    }

    private fun stripHtml(html: String): String {
        val withoutActive = html.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
        val text = withoutActive.replace(Regex("(?s)<[^>]+>"), " ")
        return decodeEntities(text).replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ", true)
        .replace("&amp;", "&", true)
        .replace("&quot;", "\"", true)
        .replace("&#39;", "'", true)
        .replace("&lt;", "<", true)
        .replace("&gt;", ">", true)

    object Fetch : Tool {
        override val spec = ToolSpec("fetch_url", "Fetch a public HTTPS webpage or text document and extract readable text.",
            listOf(ToolParam("url", required = true, description = "Public HTTPS URL on port 443.")),
            Risk.NETWORK, "web")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val url = safeUrl(arg(args, "url").orEmpty()) ?: return ToolResult.Error("Only public HTTPS URLs on port 443 are allowed. Local, private, credential-bearing and non-HTTPS URLs are blocked.")
            return try {
                ToolResult.Ok(request(url).take(MAX_OUTPUT_CHARS))
            } catch (e: Exception) {
                ToolResult.Error(e.message ?: "The page could not be fetched.")
            }
        }
    }

    object Search : Tool {
        override val spec = ToolSpec("web_search", "Search the public web and return a short list of result titles and links.",
            listOf(
                ToolParam("query", required = true, description = "What to search for."),
                ToolParam("max_results", "integer", "Maximum number of results (1 to 10).")
            ), Risk.NETWORK, "web")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val query = arg(args, "query")?.trim().orEmpty()
            if (query.isBlank()) return ToolResult.Error("Enter a search query.")
            val limit = args.get("max_results")?.let { runCatching { it.asInt }.getOrNull() }?.coerceIn(1, 10) ?: 5
            val url = "https://html.duckduckgo.com/html/".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("q", query.take(300)).build()
            return try {
                val html = request(url, stripHtmlOutput = false)
                val results = Regex("(?is)<a\\b([^>]*)>(.*?)</a>").findAll(html)
                    .filter { it.groupValues[1].contains("result__a", true) }
                    .mapNotNull { match ->
                        val href = Regex("(?i)href=[\"']([^\"']+)[\"']").find(match.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
                        val title = decodeEntities(match.groupValues[2].replace(Regex("(?s)<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
                        if (title.isBlank()) return@mapNotNull null
                        val destination = unwrapSearchUrl(href) ?: return@mapNotNull null
                        "$title\n$destination"
                    }.take(limit).toList()
                ToolResult.Ok(if (results.isEmpty()) "No structured results were found. Search page excerpt:\n${stripHtml(html).take(5000)}" else results.mapIndexed { i, item -> "${i + 1}. $item" }.joinToString("\n\n"))
            } catch (e: Exception) {
                ToolResult.Error(e.message ?: "Web search failed.")
            }
        }

        private fun unwrapSearchUrl(href: String): String? {
            val absolute = when { href.startsWith("//") -> "https:$href"; href.startsWith("/") -> "https://duckduckgo.com$href"; else -> href }
            val parsed = absolute.toHttpUrlOrNull() ?: return null
            if (parsed.host.endsWith("duckduckgo.com", true)) {
                val target = parsed.queryParameter("uddg") ?: return null
                val decoded = runCatching { URLDecoder.decode(target, "UTF-8") }.getOrDefault(target)
                return safeUrl(decoded)?.toString()
            }
            return safeUrl(absolute)?.toString()
        }
    }
}
