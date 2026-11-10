package com.jarvis.assistant.tools

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * GitHub repository tools. Requests are pinned to api.github.com (never model-supplied hosts),
 * redirects are disabled and responses are bounded. Optional encrypted credentials allow access to private repos.
 * Remote writes are deliberately not exposed in this first integration layer.
 */
object GitHubTools {
    private const val MAX_API_BYTES = 2_000_000L
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val MAX_TEXT_OUTPUT = 24_000

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var credentialContext: Context? = null\n\n    fun all(context: Context): List<Tool> { credentialContext = context.applicationContext; return listOf(RepositoryInfo, ListBranches, ReadFile, ImportFile) }

    internal fun validRepositoryPart(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && value != "." && value != ".."

    internal fun validRepoPath(value: String): Boolean {
        if (value.isBlank() || value.startsWith("/") || value.contains('\\') || value.contains('\u0000')) return false
        return value.split('/').all { it.isNotBlank() && it != "." && it != ".." && it.length <= 180 }
    }

    private fun arg(args: JsonObject, key: String): String =
        args.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim().orEmpty()

    private fun repoUrl(owner: String, repo: String, endpoint: String, ref: String? = null): HttpUrl? {
        if (!validRepositoryPart(owner) || !validRepositoryPart(repo)) return null
        val url = "https://api.github.com/repos/$owner/$repo/$endpoint".toHttpUrlOrNull() ?: return null
        return if (ref.isNullOrBlank()) url else url.newBuilder().addQueryParameter("ref", ref.take(200)).build()
    }

    private fun get(url: HttpUrl): String {
        if (url.host != "api.github.com" || url.scheme != "https" || url.port != 443) {
            throw IOException("GitHub requests are restricted to the official HTTPS API host.")
        }
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "JARVIS-ChatBox")
            .apply { credentialContext?.let { app -> GitHubCredentialStore.get(app)?.let { header("Authorization", "Bearer $it") } } }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (response.code in 300..399) throw IOException("GitHub returned a redirect; it was not followed.")
            if (response.code == 404) throw IOException("Repository or file not found, or the saved token does not have access.")
            if (response.code == 403 || response.code == 429) throw IOException("GitHub API rate limit or access restriction reached. Try again later.")
            if (!response.isSuccessful) throw IOException("GitHub API returned HTTP ${response.code}.")
            val body = response.body ?: throw IOException("GitHub returned an empty response.")
            if (body.contentLength() > MAX_API_BYTES) throw IOException("GitHub response exceeded the safety size limit.")
            val bytes = body.bytes()
            if (bytes.size > MAX_API_BYTES) throw IOException("GitHub response exceeded the safety size limit.")
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun error(e: Exception) = ToolResult.Error(e.message ?: "GitHub request failed.")

    object RepositoryInfo : Tool {
        override val spec = ToolSpec("github_repository_info",
            "Read metadata for a GitHub repository the user can access: description, default branch, visibility, archive status and stars.",
            listOf(ToolParam("owner", required = true, description = "Repository owner or organisation."),
                ToolParam("repo", required = true, description = "Repository name.")),
            Risk.NETWORK, "github")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
            val url = repoUrl(arg(args, "owner"), arg(args, "repo"), "") ?: return ToolResult.Error("Invalid GitHub owner or repository name.")
            val obj = JsonParser.parseString(get(url)).asJsonObject
            ToolResult.Ok(buildString {
                appendLine("Repository: ${obj.get("full_name")?.asString.orEmpty()}")
                appendLine("Description: ${obj.get("description")?.takeIf { !it.isJsonNull }?.asString ?: "No description"}")
                appendLine("Default branch: ${obj.get("default_branch")?.asString.orEmpty()}")
                appendLine("Private: ${obj.get("private")?.asBoolean ?: false}")
                appendLine("Archived: ${obj.get("archived")?.asBoolean ?: false}")
                appendLine("Stars: ${obj.get("stargazers_count")?.asInt ?: 0}")
                append("URL: ${obj.get("html_url")?.asString.orEmpty()}")
            })
        } catch (e: Exception) { error(e) }
    }

    object ListBranches : Tool {
        override val spec = ToolSpec("github_list_branches",
            "List branch names in a public GitHub repository. Does not change or check out any branch.",
            listOf(ToolParam("owner", required = true), ToolParam("repo", required = true),
                ToolParam("limit", "integer", "Maximum results, 1 to 100.")),
            Risk.NETWORK, "github")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
            val url = repoUrl(arg(args, "owner"), arg(args, "repo"), "branches")!!
                .newBuilder().addQueryParameter("per_page", (args.get("limit")?.let { runCatching { it.asInt }.getOrNull() } ?: 30).coerceIn(1, 100).toString()).build()
            val arr = JsonParser.parseString(get(url)).asJsonArray
            ToolResult.Ok(arr.mapNotNull { it.takeIf { v -> v.isJsonObject }?.asJsonObject?.get("name")?.asString }
                .joinToString("\n").ifBlank { "No branches were returned." })
        } catch (e: Exception) { error(e) }
    }

    object ReadFile : Tool {
        override val spec = ToolSpec("github_read_file",
            "Read a text file from a public GitHub repository at a specified branch or ref. Does not modify the repository.",
            listOf(ToolParam("owner", required = true), ToolParam("repo", required = true),
                ToolParam("path", required = true, description = "Repository-relative file path."),
                ToolParam("ref", description = "Branch, tag or commit SHA; defaults to the repository default branch.")),
            Risk.NETWORK, "github")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
            val owner = arg(args, "owner"); val repo = arg(args, "repo"); val path = arg(args, "path")
            if (!validRepoPath(path)) return ToolResult.Error("Invalid repository-relative path.")
            if (!validRepositoryPart(owner) || !validRepositoryPart(repo)) return ToolResult.Error("Invalid GitHub owner or repository name.")
            val urlBuilder = "https://api.github.com".toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo).addPathSegment("contents")
            path.split('/').forEach { urlBuilder.addPathSegment(it) }
            if (arg(args, "ref").isNotBlank()) urlBuilder.addQueryParameter("ref", arg(args, "ref").take(200))
            val obj = JsonParser.parseString(get(urlBuilder.build())).asJsonObject
            if (obj.get("type")?.asString != "file") return ToolResult.Error("The requested path is not a file.")
            val size = obj.get("size")?.asLong ?: 0L
            if (size > MAX_FILE_BYTES) return ToolResult.Error("File exceeds the 1 MB read limit.")
            if (obj.get("encoding")?.asString != "base64") return ToolResult.Error("GitHub did not return a supported text encoding.")
            val encoded = obj.get("content")?.asString.orEmpty().replace("\n", "").replace("\r", "")
            val bytes = Base64.getDecoder().decode(encoded)
            if (bytes.size > MAX_FILE_BYTES) return ToolResult.Error("File exceeds the 1 MB read limit.")
            val text = bytes.toString(Charsets.UTF_8)
            if (text.contains('\u0000')) return ToolResult.Error("Binary files are not supported by this tool.")
            ToolResult.Ok("File: ${owner}/${repo}/${path} @ ${arg(args, "ref").ifBlank { "default branch" }}\n\n${text.take(MAX_TEXT_OUTPUT)}${if (text.length > MAX_TEXT_OUTPUT) "\n\n[Output truncated; file is available through GitHub.] " else ""}")
        } catch (e: Exception) { error(e) }
    }

    object ImportFile : Tool {
        override val spec = ToolSpec("github_import_file",
            "Copy one text file from a public GitHub repository into the isolated ChatBox workspace for local editing. Existing destination files require confirmation.",
            listOf(ToolParam("owner", required = true), ToolParam("repo", required = true),
                ToolParam("path", required = true, description = "Repository-relative source file path."),
                ToolParam("destination", required = true, description = "Workspace-relative destination path."),
                ToolParam("ref", description = "Branch, tag or commit SHA; defaults to the repository default branch.")),
            Risk.WRITE, "github")
        override fun describe(args: JsonObject) = "Import ${arg(args, "owner")}/${arg(args, "repo")}/${arg(args, "path")} into ChatBox workspace at ${arg(args, "destination")}"
        override fun isDestructive(args: JsonObject, ctx: ToolContext): Boolean =
            runCatching { ctx.workspace.resolve(arg(args, "destination")).exists() }.getOrDefault(true)
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
            val owner = arg(args, "owner"); val repo = arg(args, "repo"); val path = arg(args, "path")
            val destination = arg(args, "destination")
            if (!validRepoPath(path) || !validRepoPath(destination)) return ToolResult.Error("Invalid source or destination path.")
            if (!validRepositoryPart(owner) || !validRepositoryPart(repo)) return ToolResult.Error("Invalid GitHub owner or repository name.")
            val urlBuilder = "https://api.github.com".toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo).addPathSegment("contents")
            path.split('/').forEach(urlBuilder::addPathSegment)
            if (arg(args, "ref").isNotBlank()) urlBuilder.addQueryParameter("ref", arg(args, "ref").take(200))
            val obj = JsonParser.parseString(get(urlBuilder.build())).asJsonObject
            if (obj.get("type")?.asString != "file") return ToolResult.Error("The requested path is not a file.")
            if ((obj.get("size")?.asLong ?: 0L) > MAX_FILE_BYTES) return ToolResult.Error("File exceeds the 1 MB import limit.")
            if (obj.get("encoding")?.asString != "base64") return ToolResult.Error("Only base64 text files can be imported.")
            val bytes = Base64.getDecoder().decode(obj.get("content")?.asString.orEmpty().replace("\n", "").replace("\r", ""))
            if (bytes.size > MAX_FILE_BYTES || bytes.any { it == 0.toByte() }) return ToolResult.Error("Binary or oversized files are not supported.")
            val target = ctx.workspace.resolve(destination)
            if (target.exists()) return ToolResult.Error("Destination already exists. Choose another path or explicitly approve an overwrite.")
            if (ctx.workspace.sizeOf() + bytes.size > 50L * 1024L * 1024L) return ToolResult.Error("ChatBox workspace quota (50 MB) would be exceeded.")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            ToolResult.Ok("Imported ${owner}/${repo}/${path} into workspace at ${ctx.workspace.relative(target)} (${bytes.size} bytes).")
        } catch (e: Exception) { error(e) }
    }
}
