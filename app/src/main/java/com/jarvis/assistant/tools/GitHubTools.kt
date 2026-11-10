package com.jarvis.assistant.tools

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * GitHub repository tools. Requests are pinned to api.github.com (never model-supplied hosts),
 * redirects are disabled and responses are bounded. Optional encrypted credentials allow access to private repos.
 * Remote repository changes are not exposed in this read/import integration layer yet.
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

    @Volatile private var credentialContext: Context? = null

    fun all(context: Context): List<Tool> {
        credentialContext = context.applicationContext
        return listOf(RepositoryInfo, ListBranches, ListDirectory, ReadFile, CompareBranches, ImportFile, ImportRepository)
    }

    internal fun validRepositoryPart(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && value != "." && value != ".."

    internal fun isUtf8Text(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
        }.isSuccess
    }

    internal fun fitsWorkspaceBudget(existingBytes: Long, incomingBytes: Long): Boolean =
        existingBytes >= 0L && incomingBytes >= 0L &&
            incomingBytes <= MAX_IMPORT_BYTES &&
            existingBytes <= MAX_WORKSPACE_BYTES &&
            incomingBytes <= MAX_WORKSPACE_BYTES - existingBytes

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
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
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
    }

    object ListBranches : Tool {
        override val spec = ToolSpec("github_list_branches",
            "List branch names in a public GitHub repository. Does not change or check out any branch.",
            listOf(ToolParam("owner", required = true), ToolParam("repo", required = true),
                ToolParam("limit", "integer", "Maximum results, 1 to 100.")),
            Risk.NETWORK, "github")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
            val url = repoUrl(arg(args, "owner"), arg(args, "repo"), "branches")!!
                .newBuilder().addQueryParameter("per_page", (args.get("limit")?.let { runCatching { it.asInt }.getOrNull() } ?: 30).coerceIn(1, 100).toString()).build()
            val arr = JsonParser.parseString(get(url)).asJsonArray
            ToolResult.Ok(arr.mapNotNull { it.takeIf { v -> v.isJsonObject }?.asJsonObject?.get("name")?.asString }
                .joinToString("\n").ifBlank { "No branches were returned." })
        } catch (e: Exception) { error(e) }
        }
    }

    object ReadFile : Tool {
        override val spec = ToolSpec("github_read_file",
            "Read a text file from a public GitHub repository at a specified branch or ref. Does not modify the repository.",
            listOf(ToolParam("owner", required = true), ToolParam("repo", required = true),
                ToolParam("path", required = true, description = "Repository-relative file path."),
                ToolParam("ref", description = "Branch, tag or commit SHA; defaults to the repository default branch.")),
            Risk.NETWORK, "github")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
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
            if (!isUtf8Text(bytes)) return ToolResult.Error("Binary or invalid UTF-8 files are not supported by this tool.")
            ToolResult.Ok("File: ${owner}/${repo}/${path} @ ${arg(args, "ref").ifBlank { "default branch" }}\n\n${text.take(MAX_TEXT_OUTPUT)}${if (text.length > MAX_TEXT_OUTPUT) "\n\n[Output truncated; file is available through GitHub.] " else ""}")
        } catch (e: Exception) { error(e) }
        }
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
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
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
            if (bytes.size > MAX_FILE_BYTES || !isUtf8Text(bytes)) return ToolResult.Error("Binary, invalid UTF-8 or oversized files are not supported.")
            val target = ctx.workspace.resolve(destination)
            if (target.exists()) return ToolResult.Error("Destination already exists. Choose another path or explicitly approve an overwrite.")
            if (ctx.workspace.sizeOf() + bytes.size > 50L * 1024L * 1024L) return ToolResult.Error("ChatBox workspace quota (50 MB) would be exceeded.")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            ToolResult.Ok("Imported ${owner}/${repo}/${path} into workspace at ${ctx.workspace.relative(target)} (${bytes.size} bytes).")
        } catch (e: Exception) { error(e) }
        }
    }

    object ListDirectory : Tool {
        override val spec = ToolSpec("github_list_directory",
            "Browse files and folders at a path in a GitHub repository branch. Read-only; returns relative paths and file sizes.",
            listOf(ToolParam("owner", required = true), ToolParam("repo", required = true),
                ToolParam("path", description = "Optional directory path inside the repository."),
                ToolParam("ref", description = "Branch or tag; defaults to the repository default branch."),
                ToolParam("limit", "integer", "Maximum entries, 1 to 200.")),
            Risk.NETWORK, "github")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
            val owner = arg(args, "owner"); val repo = arg(args, "repo")
            val path = arg(args, "path").trim('/'); val ref = arg(args, "ref").ifBlank {
                val infoUrl = repoUrl(owner, repo, "") ?: return ToolResult.Error("Invalid repository.")
                JsonParser.parseString(get(infoUrl)).asJsonObject.get("default_branch")?.asString.orEmpty()
            }
            if (path.isNotEmpty() && !validRepoPath(path)) return ToolResult.Error("Invalid repository directory path.")
            if (!validRepositoryPart(owner) || !validRepositoryPart(repo) || ref.isBlank()) return ToolResult.Error("Invalid repository or branch.")
            val branchUrl = "https://api.github.com".toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo)
                .addPathSegment("branches").addPathSegment(ref).build()
            val commitSha = JsonParser.parseString(get(branchUrl)).asJsonObject
                .getAsJsonObject("commit")?.get("sha")?.asString ?: return ToolResult.Error("Branch commit was not found.")
            val treeUrl = "https://api.github.com".toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo)
                .addPathSegment("git").addPathSegment("trees").addPathSegment(commitSha)
                .addQueryParameter("recursive", "1").build()
            val tree = JsonParser.parseString(get(treeUrl)).asJsonObject
            val entries = tree.getAsJsonArray("tree") ?: return ToolResult.Error("GitHub returned no repository tree.")
            val prefix = if (path.isBlank()) "" else "$path/"
            val children = linkedMapOf<String, String>()
            entries.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val item = element.asJsonObject
                val itemPath = item.get("path")?.asString ?: return@forEach
                if (!itemPath.startsWith(prefix) || itemPath == path) return@forEach
                val remainder = itemPath.removePrefix(prefix)
                val first = remainder.substringBefore('/')
                if (first.isBlank()) return@forEach
                val isDirectory = remainder.contains('/')
                if (isDirectory) children.putIfAbsent(first, "DIR")
                else if (item.get("type")?.asString == "blob") {
                    val size = item.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    children[first] = "FILE:$size"
                }
            }
            val limit = args.get("limit")?.let { runCatching { it.asInt }.getOrNull() }?.coerceIn(1, 200) ?: 100
            val output = children.entries.sortedWith(compareBy({ if (it.value == "DIR") 0 else 1 }, { it.key.lowercase() }))
                .take(limit).map { (name, kind) -> if (kind == "DIR") "DIR  $name/" else "FILE $name (${kind.substringAfter(':')} bytes)" }
            ToolResult.Ok(buildString {
                appendLine("Repository: $owner/$repo")
                appendLine("Branch: $ref")
                appendLine("Directory: ${if (path.isBlank()) "/" else path}")
                append(output.joinToString("\n").ifBlank { "No entries found." })
                if (children.size > limit) appendLine("\n[Showing $limit of ${children.size} entries]")
                if (tree.get("truncated")?.asBoolean == true) appendLine("\n[GitHub marked this repository tree as truncated]")
            })
        } catch (e: Exception) { error(e) }
        }
    }


    internal fun isSensitiveRepositoryPath(path: String): Boolean {
        val parts = path.lowercase().split('/')
        val name = parts.lastOrNull().orEmpty()
        return parts.any { it == ".git" || it == ".ssh" } ||
            name == ".env" || name.startsWith(".env.") ||
            name in setOf("id_rsa", "id_ed25519", "credentials", "credentials.json", "secrets.json", "service-account.json") ||
            name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") ||
            name.endsWith(".pfx") || name.endsWith(".jks") || name.endsWith(".keystore")
    }

    object CompareBranches : Tool {
        override val spec = ToolSpec(
            "github_compare_branches",
            "Read-only comparison of two branches in the same GitHub repository. Reports ahead/behind counts and a bounded changed-file summary. Does not modify the repository.",
            listOf(
                ToolParam("owner", required = true),
                ToolParam("repo", required = true),
                ToolParam("base_branch", required = true),
                ToolParam("head_branch", required = true)
            ), Risk.READ_ONLY, "github"
        )

        override fun describe(args: JsonObject) =
            "Compare " + arg(args, "head_branch") + " against " + arg(args, "base_branch") +
                " in " + arg(args, "owner") + "/" + arg(args, "repo")

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            return try {
                val owner = arg(args, "owner")
                val repo = arg(args, "repo")
                val base = arg(args, "base_branch")
                val head = arg(args, "head_branch")
                if (!validRepositoryPart(owner) || !validRepositoryPart(repo) ||
                    !GitHubWriteTools.validBranchName(base) || !GitHubWriteTools.validBranchName(head) || base == head) {
                    return ToolResult.Error("Choose a valid repository and two different branch names.")
                }
                val endpoint = "https://api.github.com".toHttpUrlOrNull()!!.newBuilder()
                    .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo)
                    .addPathSegment("compare").addPathSegment("$base...$head")
                    .addQueryParameter("per_page", "100")
                    .build()
                val comparison = JsonParser.parseString(get(endpoint)).asJsonObject
                val status = comparison.get("status")?.asString ?: "unknown"
                val ahead = comparison.get("ahead_by")?.asInt ?: 0
                val behind = comparison.get("behind_by")?.asInt ?: 0
                val total = comparison.get("total_commits")?.asInt ?: ahead
                val files = comparison.getAsJsonArray("files")
                val fileLines = files?.mapNotNull { item ->
                    if (!item.isJsonObject) return@mapNotNull null
                    val file = item.asJsonObject
                    val path = file.get("filename")?.asString ?: return@mapNotNull null
                    val state = file.get("status")?.asString ?: "modified"
                    val additions = file.get("additions")?.asInt ?: 0
                    val deletions = file.get("deletions")?.asInt ?: 0
                    "$state  $path  (+$additions / -$deletions)"
                }.orEmpty()
                ToolResult.Ok(buildString {
                    appendLine("Repository: $owner/$repo")
                    appendLine("Comparison: $head → $base")
                    appendLine("Status: $status")
                    appendLine("Head ahead: $ahead commit(s); base ahead: $behind commit(s)")
                    appendLine("Commits in comparison: $total")
                    appendLine("Changed files returned by GitHub: " + fileLines.size)
                    if (fileLines.isEmpty()) append("No changed-file list was returned.")
                    else append(fileLines.take(100).joinToString("\n"))
                    if (files == null) appendLine("\nGitHub did not include a changed-file list in this response.")
                })
            } catch (e: Exception) {
                error(e)
            }
        }
    }

    object ImportRepository : Tool {
        override val spec = ToolSpec("github_import_repository",
            "Import a bounded snapshot of a GitHub repository branch into a new isolated ChatBox workspace folder. Skips secrets, binary files and oversized files. It does not run imported code.",
            listOf(ToolParam("owner", required = true), ToolParam("repo", required = true),
                ToolParam("ref", description = "Branch or tag; defaults to the repository default branch."),
                ToolParam("destination", required = true, description = "New workspace folder, for example repos/my-project."),
                ToolParam("max_files", "integer", "Maximum files to import, 1 to 100; defaults to 40.")),
            Risk.DESTRUCTIVE, "github")
        override fun describe(args: JsonObject) =
            "Import a repository snapshot from ${arg(args, "owner")}/${arg(args, "repo")} into ChatBox workspace folder ${arg(args, "destination")}"
        override fun isDestructive(args: JsonObject, ctx: ToolContext) = true

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val owner = arg(args, "owner")
            val repo = arg(args, "repo")
            val destination = arg(args, "destination")
            if (!validRepositoryPart(owner) || !validRepositoryPart(repo)) return ToolResult.Error("Invalid GitHub owner or repository.")
            if (!validRepoPath(destination)) return ToolResult.Error("Choose a new relative destination folder inside the ChatBox workspace.")
            val maxFiles = (args.get("max_files")?.let { runCatching { it.asInt }.getOrNull() } ?: 40).coerceIn(1, 100)
            var targetDir: File? = null
            val written = ArrayList<File>()
            return try {
                val repository = JsonParser.parseString(get(repoUrl(owner, repo, "") ?: return ToolResult.Error("Invalid repository."))).asJsonObject
                val ref = arg(args, "ref").ifBlank { repository.get("default_branch")?.asString.orEmpty() }
                if (ref.isBlank()) return ToolResult.Error("GitHub did not provide a default branch.")
                val branchUrl = "https://api.github.com".toHttpUrlOrNull()!!.newBuilder()
                    .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo)
                    .addPathSegment("branches").addPathSegment(ref).build()
                val commitSha = JsonParser.parseString(get(branchUrl)).asJsonObject
                    .getAsJsonObject("commit")?.get("sha")?.asString
                    ?: return ToolResult.Error("Could not resolve the selected branch.")
                val treeUrl = "https://api.github.com".toHttpUrlOrNull()!!.newBuilder()
                    .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo)
                    .addPathSegment("git").addPathSegment("trees").addPathSegment(commitSha)
                    .addQueryParameter("recursive", "1").build()
                val tree = JsonParser.parseString(get(treeUrl)).asJsonObject
                if (tree.get("truncated")?.asBoolean == true) return ToolResult.Error("GitHub truncated this repository tree. Choose a smaller repository or import individual files.")
                val entries = tree.getAsJsonArray("tree") ?: return ToolResult.Error("GitHub returned no repository tree.")
                val candidates = entries.mapNotNull { item ->
                    if (!item.isJsonObject) return@mapNotNull null
                    val obj = item.asJsonObject
                    val path = obj.get("path")?.asString ?: return@mapNotNull null
                    if (obj.get("type")?.asString != "blob" || !validRepoPath(path) || isSensitiveRepositoryPath(path)) return@mapNotNull null
                    val size = obj.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: return@mapNotNull null
                    if (size !in 1L..MAX_FILE_BYTES) return@mapNotNull null
                    Triple(path, size, obj.get("sha")?.asString.orEmpty())
                }.take(maxFiles)
                if (candidates.isEmpty()) return ToolResult.Error("No eligible text-sized files were found in this repository snapshot.")
                val dir = ctx.workspace.resolve(destination)
                if (dir.exists()) return ToolResult.Error("The destination already exists. Choose a new folder to avoid overwriting existing work.")
                val existingWorkspaceBytes = ctx.workspace.sizeOf()
                val estimatedImportBytes = candidates.sumOf { it.second }
                if (estimatedImportBytes > MAX_IMPORT_BYTES) {
                    return ToolResult.Error("The selected files exceed the 20 MB import budget. Lower max_files or import selected files individually.")
                }
                if (!fitsWorkspaceBudget(existingWorkspaceBytes, estimatedImportBytes)) {
                    return ToolResult.Error("The import would exceed the ChatBox workspace's 50 MB total storage limit. Free workspace space or import fewer files.")
                }
                dir.mkdirs()
                targetDir = dir
                var totalBytes = 0L
                var imported = 0
                var skippedBinary = 0
                for ((path, declaredSize, _) in candidates) {
                    if (ctx.cancelled) throw kotlinx.coroutines.CancellationException("Repository import cancelled.")
                    val urlBuilder = "https://api.github.com".toHttpUrlOrNull()!!.newBuilder()
                        .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo).addPathSegment("contents")
                    path.split('/').forEach { urlBuilder.addPathSegment(it) }
                    urlBuilder.addQueryParameter("ref", commitSha)
                    val obj = JsonParser.parseString(get(urlBuilder.build())).asJsonObject
                    if (obj.get("type")?.asString != "file" || obj.get("encoding")?.asString != "base64") {
                        skippedBinary++
                        continue
                    }
                    val bytes = Base64.getDecoder().decode(obj.get("content")?.asString.orEmpty().replace("\n", "").replace("\r", ""))
                    if (bytes.size.toLong() != declaredSize || bytes.size.toLong() > MAX_FILE_BYTES || !isUtf8Text(bytes)) {
                        skippedBinary++
                        continue
                    }
                    if (totalBytes + bytes.size > MAX_IMPORT_BYTES) break
                    if (!fitsWorkspaceBudget(existingWorkspaceBytes, totalBytes + bytes.size)) {
                        throw IOException("The workspace reached its 50 MB storage limit during import; the partial import was rolled back.")
                    }
                    val target = ctx.workspace.resolve("$destination/$path")
                    if (target.exists()) continue
                    target.parentFile?.mkdirs()
                    target.writeBytes(bytes)
                    written += target
                    totalBytes += bytes.size
                    imported++
                    ctx.progress("Imported $imported/$maxFiles files from $owner/$repo")
                }
                if (imported == 0) {
                    dir.deleteRecursively()
                    return ToolResult.Error("No supported text files could be imported.")
                }
                ToolResult.Ok("Imported $imported files ($totalBytes bytes) from $owner/$repo at ref $ref into ${ctx.workspace.relative(dir)}. Skipped $skippedBinary binary or unsupported files. Imported code was not executed.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                targetDir?.deleteRecursively()
                throw e
            } catch (e: Exception) {
                targetDir?.deleteRecursively()
                error(e)
            }
        }
    }

    private const val MAX_IMPORT_BYTES = 20L * 1024L * 1024L
    private const val MAX_WORKSPACE_BYTES = 50L * 1024L * 1024L

}