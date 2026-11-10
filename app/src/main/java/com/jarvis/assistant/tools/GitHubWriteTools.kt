package com.jarvis.assistant.tools

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicitly approved GitHub write operations. No operation merges a pull request or writes to a default branch. */
object GitHubWriteTools {
    /** Performs a real authenticated /user check without exposing the saved token. */
    suspend fun validateConnection(context: Context): String = withContext(Dispatchers.IO) {
        val user = request(context.applicationContext, "GET", url("user"))
        user.get("login")?.asString?.takeIf { it.isNotBlank() } ?: throw IOException("GitHub did not return an account name.")
    }

    private const val MAX_RESPONSE = 2_000_000L
    private const val MAX_FILE = 1_000_000L
    private const val MAX_BATCH_FILES = 25
    private const val MAX_BATCH_BYTES = 5_000_000L
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
    }

    fun all(context: Context): List<Tool> {
        val app = context.applicationContext
        return listOf(ConnectionStatus(app), CreateBranch(app), CommitWorkspaceFile(app), CommitWorkspaceFiles(app), CreatePullRequest(app))
    }

    internal fun pullRequestPreflightError(headBranch: String, baseBranch: String, aheadBy: Int): String? {
        if (!validBranchName(headBranch) || !validBranchName(baseBranch) || headBranch == baseBranch) {
            return "Pull request head and base must be two different valid branch names."
        }
        if (aheadBy <= 0) return "The head branch has no commits ahead of the base branch; there is nothing new to propose."
        return null
    }

    internal fun validBranchName(value: String): Boolean {
        if (value.isBlank() || value.length > 100 || value.startsWith("/") || value.endsWith("/") ||
            value.endsWith(".") || value.contains("..") || value.contains("//") || value.contains("@{") ||
            value.any { it.isWhitespace() || it == '~' || it == '^' || it == ':' || it == '?' || it == '*' || it == '[' || it == '\\' }) return false
        return value.matches(Regex("[A-Za-z0-9._/-]+")) &&
            value.split('/').all { it.isNotBlank() && !it.startsWith(".") && !it.endsWith(".lock") }
    }

    /** Conservative content-level secret guard; path checks alone cannot catch credentials copied into source files. */
    internal fun containsCredentialMaterial(bytes: ByteArray): Boolean {
        val text = bytes.toString(Charsets.UTF_8)
        if (text.contains("-----BEGIN ") && text.contains("PRIVATE KEY-----")) return true
        val patterns = listOf(
            Regex("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b"),
            Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
            Regex("\\bAKIA[0-9A-Z]{16}\\b")
        )
        return patterns.any { it.containsMatchIn(text) }
    }

    internal data class CommitFileInput(
        val workspacePath: String,
        val repositoryPath: String,
        val bytes: ByteArray
    )

    /** Pure validation shared by the batch commit tool and unit tests. */
    internal fun validateCommitBatch(files: List<CommitFileInput>): String? {
        if (files.isEmpty()) return "Select at least one workspace file to commit."
        if (files.size > MAX_BATCH_FILES) return "A single GitHub commit is limited to " + MAX_BATCH_FILES + " files."
        val local = files.map { it.workspacePath.lowercase(java.util.Locale.ROOT) }
        val remote = files.map { it.repositoryPath.lowercase(java.util.Locale.ROOT) }
        if (local.toSet().size != local.size || remote.toSet().size != remote.size) {
            return "Duplicate workspace or repository paths are not allowed."
        }
        for (paths in listOf(local, remote)) {
            for (i in paths.indices) for (j in i + 1 until paths.size) {
                if (paths[i].startsWith(paths[j] + "/") || paths[j].startsWith(paths[i] + "/")) {
                    return "A file path conflicts with a folder path in the selected batch."
                }
            }
        }
        var total = 0L
        for (file in files) {
            if (!GitHubTools.validRepoPath(file.workspacePath) || !GitHubTools.validRepoPath(file.repositoryPath)) {
                return "Every workspace and repository path must be a safe relative path."
            }
            if (GitHubTools.isSensitiveRepositoryPath(file.workspacePath) ||
                GitHubTools.isSensitiveRepositoryPath(file.repositoryPath)) {
                return "Credential, key-store and private-key paths cannot be committed."
            }
            if (file.bytes.isEmpty() || file.bytes.size.toLong() > MAX_FILE) {
                return "Each selected file must contain between 1 byte and 1 MB."
            }
            if (file.bytes.any { it == 0.toByte() }) return "Binary files are not supported by GitHub workspace commits."
            val validUtf8 = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(file.bytes))
            }.isSuccess
            if (!validUtf8) return "Only valid UTF-8 text files can be committed."
            if (containsCredentialMaterial(file.bytes)) {
                return "A selected file appears to contain credential material, such as a private key or recognizable access token."
            }
            total += file.bytes.size.toLong()
            if (total > MAX_BATCH_BYTES) return "The selected files exceed the 5 MB per-commit limit."
        }
        return null
    }

    private fun stringArray(args: JsonObject, name: String): List<String> {
        val value = args.get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return value.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()
        }
    }

    private fun arg(args: JsonObject, name: String): String =
        args.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim().orEmpty()

    private fun url(vararg parts: String): HttpUrl {
        val builder = "https://api.github.com".toHttpUrlOrNull()!!.newBuilder()
        parts.forEach(builder::addPathSegment)
        return builder.build()
    }

    private fun refUrl(owner: String, repo: String, branch: String) =
        url("repos", owner, repo, "git", "ref", "heads", branch)

    private fun request(context: Context, method: String, endpoint: HttpUrl, payload: JsonObject? = null): JsonObject {
        if (endpoint.scheme != "https" || endpoint.host != "api.github.com" || endpoint.port != 443)
            throw IOException("GitHub requests are restricted to the official HTTPS API host.")
        val token = GitHubCredentialStore.get(context) ?: throw IOException("Connect GitHub with a token before using repository write features.")
        val builder = Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "JARVIS-ChatBox")
            .header("Authorization", "Bearer " + token)
        if (method == "GET") builder.get()
        else builder.method(method, (payload ?: JsonObject()).toString().toRequestBody(jsonType))
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 401) throw IOException("GitHub rejected the saved token. Reconnect with a valid token.")
            if (response.code == 403 || response.code == 429) throw IOException("GitHub denied the operation or its rate limit was reached. Check token permissions.")
            if (response.code == 404) throw IOException("Repository or branch not found, or the token lacks access.")
            if (response.code in 300..399) throw IOException("GitHub returned a redirect; it was not followed.")
            if (!response.isSuccessful) throw IOException("GitHub API returned HTTP " + response.code + ".")
            val body = response.body ?: throw IOException("GitHub returned an empty response.")
            if (body.contentLength() > MAX_RESPONSE) throw IOException("GitHub response exceeded the safety limit.")
            val bytes = body.bytes()
            if (bytes.size > MAX_RESPONSE) throw IOException("GitHub response exceeded the safety limit.")
            return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        }
    }

    private fun repo(context: Context, owner: String, name: String): JsonObject {
        if (!GitHubTools.validRepositoryPart(owner) || !GitHubTools.validRepositoryPart(name))
            throw IOException("Invalid GitHub owner or repository name.")
        return request(context, "GET", url("repos", owner, name))
    }

    private fun failure(e: Exception) = ToolResult.Error(e.message ?: "GitHub operation failed.")

    private class ConnectionStatus(private val context: Context) : Tool {
        override val spec = ToolSpec("github_connection_status",
            "Verify the saved GitHub token and report the authenticated account. Never reveals the token.",
            emptyList(), Risk.NETWORK, "github")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
            val user = request(context, "GET", url("user"))
            ToolResult.Ok("GitHub token is valid. Connected account: " + (user.get("login")?.asString ?: "unknown") + ". Token value was not exposed.")
        } catch (e: Exception) { failure(e) }
        }
    }

    private class CreateBranch(private val context: Context) : Tool {
        override val spec = ToolSpec("github_create_branch",
            "Create a feature branch from an existing branch. Requires Contents write permission and explicit user approval.",
            listOf(ToolParam("owner", required = true), ToolParam("repo", required = true),
                ToolParam("new_branch", required = true), ToolParam("base_branch", description = "Defaults to the repository default branch.")),
            Risk.DESTRUCTIVE, "github")
        override fun describe(args: JsonObject) = "Create branch " + arg(args, "new_branch") + " in " + arg(args, "owner") + "/" + arg(args, "repo")
        override fun isDestructive(args: JsonObject, ctx: ToolContext) = true
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
            val owner = arg(args, "owner"); val name = arg(args, "repo")
            val branch = arg(args, "new_branch")
            if (!GitHubWriteTools.validBranchName(branch)) return ToolResult.Error("Invalid new branch name.")
            val repository = repo(context, owner, name)
            if (repository.get("archived")?.asBoolean == true) return ToolResult.Error("Archived repositories cannot be changed.")
            val base = arg(args, "base_branch").ifBlank { repository.get("default_branch")?.asString.orEmpty() }
            if (!GitHubWriteTools.validBranchName(base)) return ToolResult.Error("Invalid base branch.")
            val source = request(context, "GET", refUrl(owner, name, base))
            val sha = source.getAsJsonObject("object")?.get("sha")?.asString ?: return ToolResult.Error("Could not resolve the base branch.")
            val payload = JsonObject().apply { addProperty("ref", "refs/heads/" + branch); addProperty("sha", sha) }
            request(context, "POST", url("repos", owner, name, "git", "refs"), payload)
            ToolResult.Ok("Created feature branch " + branch + " from " + base + ".")
        } catch (e: Exception) { failure(e) }
        }
    }

    private class CommitWorkspaceFile(private val context: Context) : Tool {
        override val spec = ToolSpec("github_commit_workspace_file",
            "Commit one text file from the isolated ChatBox workspace to an existing non-default branch. Requires Contents write permission and explicit user approval.",
            listOf(ToolParam("owner", required = true), ToolParam("repo", required = true),
                ToolParam("branch", required = true), ToolParam("workspace_path", required = true),
                ToolParam("repository_path", required = true), ToolParam("message", required = true)),
            Risk.DESTRUCTIVE, "github")
        override fun describe(args: JsonObject) = "Commit workspace/" + arg(args, "workspace_path") + " to " +
            arg(args, "owner") + "/" + arg(args, "repo") + ":" + arg(args, "branch") + "/" + arg(args, "repository_path")
        override fun isDestructive(args: JsonObject, ctx: ToolContext) = true
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
            val owner = arg(args, "owner"); val name = arg(args, "repo"); val branch = arg(args, "branch")
            val localPath = arg(args, "workspace_path"); val remotePath = arg(args, "repository_path")
            val message = arg(args, "message")
            if (!GitHubWriteTools.validBranchName(branch) || !GitHubTools.validRepoPath(localPath) ||
                !GitHubTools.validRepoPath(remotePath) || GitHubTools.isSensitiveRepositoryPath(remotePath) ||
                message.isBlank() || message.length > 200) return ToolResult.Error("Invalid branch, path or commit message. Sensitive repository paths are blocked.")
            val file = ctx.workspace.resolve(localPath)
            if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return ToolResult.Error("Workspace source must be an existing regular file.")
            if (file.length() !in 1..MAX_FILE) return ToolResult.Error("Only files from 1 byte to 1 MB can be committed.")
            val bytes = file.readBytes()
            if (bytes.any { it == 0.toByte() }) return ToolResult.Error("Binary files are not supported.")
            if (containsCredentialMaterial(bytes)) return ToolResult.Error("The file appears to contain a private key or recognizable access token. Remove credentials before committing.")
            val repository = repo(context, owner, name)
            if (repository.get("archived")?.asBoolean == true) return ToolResult.Error("Archived repositories cannot be changed.")
            val defaultBranch = repository.get("default_branch")?.asString.orEmpty()
            if (branch == defaultBranch) return ToolResult.Error("Direct commits to the default branch are disabled. Create a feature branch first.")
            val head = request(context, "GET", refUrl(owner, name, branch))
            val parent = head.getAsJsonObject("object")?.get("sha")?.asString ?: return ToolResult.Error("Could not resolve the feature branch.")
            val parentCommit = request(context, "GET", url("repos", owner, name, "git", "commits", parent))
            val baseTree = parentCommit.getAsJsonObject("tree")?.get("sha")?.asString ?: return ToolResult.Error("Could not resolve the branch tree.")
            ctx.progress("Preparing GitHub file blob")
            val blobPayload = JsonObject().apply {
                addProperty("content", Base64.getEncoder().encodeToString(bytes))
                addProperty("encoding", "base64")
            }
            val blob = request(context, "POST", url("repos", owner, name, "git", "blobs"), blobPayload)
            val blobSha = blob.get("sha")?.asString ?: return ToolResult.Error("GitHub did not return a file blob.")
            val entry = JsonObject().apply {
                addProperty("path", remotePath); addProperty("mode", "100644")
                addProperty("type", "blob"); addProperty("sha", blobSha)
            }
            val entries = JsonArray().apply { add(entry) }
            val treePayload = JsonObject().apply { addProperty("base_tree", baseTree); add("tree", entries) }
            val tree = request(context, "POST", url("repos", owner, name, "git", "trees"), treePayload)
            val treeSha = tree.get("sha")?.asString ?: return ToolResult.Error("GitHub did not return a tree.")
            val parents = JsonArray().apply { add(JsonPrimitive(parent)) }
            val commitPayload = JsonObject().apply {
                addProperty("message", message); addProperty("tree", treeSha); add("parents", parents)
            }
            val commit = request(context, "POST", url("repos", owner, name, "git", "commits"), commitPayload)
            val newSha = commit.get("sha")?.asString ?: return ToolResult.Error("GitHub did not return a commit.")
            ctx.progress("Updating the feature branch")
            val update = JsonObject().apply { addProperty("sha", newSha); addProperty("force", false) }
            request(context, "PATCH", refUrl(owner, name, branch), update)
            ToolResult.Ok("Committed " + bytes.size + " bytes from workspace/" + localPath + " to " +
                owner + "/" + name + ":" + branch + "/" + remotePath + ". Commit: " + newSha)
        } catch (e: Exception) { failure(e) }
        }
    }

    private class CommitWorkspaceFiles(private val context: Context) : Tool {
        override val spec = ToolSpec("github_commit_workspace_files",
            "Commit a selected batch of UTF-8 text files from the isolated ChatBox workspace in one Git commit to an existing non-default branch. Requires explicit user approval; blocks credentials, binary files, unsafe paths and oversized batches.",
            listOf(
                ToolParam("owner", required = true),
                ToolParam("repo", required = true),
                ToolParam("branch", required = true),
                ToolParam("workspace_paths", "array", "Workspace-relative source paths, one for each repository_paths entry.", required = true),
                ToolParam("repository_paths", "array", "Repository-relative destination paths in the same order as workspace_paths.", required = true),
                ToolParam("message", required = true, description = "Commit message, up to 200 characters.")
            ), Risk.DESTRUCTIVE, "github")

        override fun describe(args: JsonObject): String {
            val sources = stringArray(args, "workspace_paths")
            val destinations = stringArray(args, "repository_paths")
            val pairs = sources.indices.joinToString { index ->
                "workspace/" + sources[index] + " → " + (destinations.getOrNull(index) ?: "(missing destination)")
            }
            return "Commit " + sources.size + " selected workspace file(s) to " +
                arg(args, "owner") + "/" + arg(args, "repo") + ":" + arg(args, "branch") +
                " [" + pairs + "]"
        }

        override fun isDestructive(args: JsonObject, ctx: ToolContext) = true

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            return try {
                val owner = arg(args, "owner")
                val name = arg(args, "repo")
                val branch = arg(args, "branch")
                val message = arg(args, "message")
                val sources = stringArray(args, "workspace_paths")
                val destinations = stringArray(args, "repository_paths")
                if (!GitHubTools.validRepositoryPart(owner) || !GitHubTools.validRepositoryPart(name)) {
                    return ToolResult.Error("Invalid GitHub owner or repository name.")
                }
                if (!validBranchName(branch) || message.isBlank() || message.length > 200) {
                    return ToolResult.Error("Choose a valid feature branch and a commit message of at most 200 characters.")
                }
                if (sources.size != destinations.size || sources.isEmpty() || sources.size > MAX_BATCH_FILES) {
                    return ToolResult.Error("Provide matching workspace_paths and repository_paths arrays containing 1 to " +
                        MAX_BATCH_FILES + " files.")
                }

                val files = ArrayList<CommitFileInput>(sources.size)
                for (index in sources.indices) {
                    if (ctx.cancelled) throw kotlinx.coroutines.CancellationException("GitHub commit cancelled.")
                    val path = sources[index]
                    if (!GitHubTools.validRepoPath(path)) return ToolResult.Error("Invalid workspace path: " + path)
                    val file = ctx.workspace.resolve(path)
                    if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) {
                        return ToolResult.Error("Workspace source is not a regular file: " + path)
                    }
                    if (file.length() > MAX_FILE) return ToolResult.Error("File exceeds the 1 MB limit: " + path)
                    files += CommitFileInput(path, destinations[index], file.readBytes())
                }
                validateCommitBatch(files)?.let { return ToolResult.Error(it) }

                val repository = repo(context, owner, name)
                if (repository.get("archived")?.asBoolean == true) {
                    return ToolResult.Error("Archived repositories cannot be changed.")
                }
                val defaultBranch = repository.get("default_branch")?.asString.orEmpty()
                if (branch == defaultBranch) {
                    return ToolResult.Error("Direct commits to the default branch are disabled. Create a feature branch first.")
                }

                val head = request(context, "GET", refUrl(owner, name, branch))
                val parent = head.getAsJsonObject("object")?.get("sha")?.asString
                    ?: return ToolResult.Error("Could not resolve the feature branch.")
                val parentCommit = request(context, "GET", url("repos", owner, name, "git", "commits", parent))
                val baseTree = parentCommit.getAsJsonObject("tree")?.get("sha")?.asString
                    ?: return ToolResult.Error("Could not resolve the feature branch tree.")

                val treeEntries = JsonArray()
                files.forEachIndexed { index, file ->
                    if (ctx.cancelled) throw kotlinx.coroutines.CancellationException("GitHub commit cancelled.")
                    ctx.progress("Uploading file " + (index + 1) + " of " + files.size)
                    val blobPayload = JsonObject().apply {
                        addProperty("content", Base64.getEncoder().encodeToString(file.bytes))
                        addProperty("encoding", "base64")
                    }
                    val blob = request(context, "POST", url("repos", owner, name, "git", "blobs"), blobPayload)
                    val blobSha = blob.get("sha")?.asString
                        ?: return ToolResult.Error("GitHub did not return a blob for " + file.workspacePath)
                    treeEntries.add(JsonObject().apply {
                        addProperty("path", file.repositoryPath)
                        addProperty("mode", "100644")
                        addProperty("type", "blob")
                        addProperty("sha", blobSha)
                    })
                }

                ctx.progress("Creating one Git commit for the selected files")
                val treePayload = JsonObject().apply {
                    addProperty("base_tree", baseTree)
                    add("tree", treeEntries)
                }
                val tree = request(context, "POST", url("repos", owner, name, "git", "trees"), treePayload)
                val treeSha = tree.get("sha")?.asString
                    ?: return ToolResult.Error("GitHub did not return the new repository tree.")
                val parents = JsonArray().apply { add(JsonPrimitive(parent)) }
                val commitPayload = JsonObject().apply {
                    addProperty("message", message)
                    addProperty("tree", treeSha)
                    add("parents", parents)
                }
                val commit = request(context, "POST", url("repos", owner, name, "git", "commits"), commitPayload)
                val newSha = commit.get("sha")?.asString
                    ?: return ToolResult.Error("GitHub did not return the new commit.")
                ctx.progress("Updating feature branch without force-pushing")
                val update = JsonObject().apply {
                    addProperty("sha", newSha)
                    addProperty("force", false)
                }
                // force=false makes this a fast-forward-only update: if the branch moved after we read it,
                // GitHub rejects this update rather than overwriting someone else's commit.
                request(context, "PATCH", refUrl(owner, name, branch), update)
                ToolResult.Ok(
                    "Committed " + files.size + " file(s) (" +
                        files.sumOf { it.bytes.size.toLong() } + " bytes) to " +
                        owner + "/" + name + ":" + branch + ". Commit: " + newSha +
                        "\nFiles: " + files.joinToString { it.repositoryPath } +
                        "\nNo default branch was changed and no pull request was merged."
                )
            } catch (e: Exception) {
                failure(e)
            }
        }
    }

    private class CreatePullRequest(private val context: Context) : Tool {
        override val spec = ToolSpec("github_create_pull_request",
            "Open a pull request from a feature branch into a target branch. Requires Pull requests write permission and explicit approval. Does not merge.",
            listOf(ToolParam("owner", required = true), ToolParam("repo", required = true),
                ToolParam("head_branch", required = true), ToolParam("base_branch", required = true),
                ToolParam("title", required = true), ToolParam("body", description = "Summary and test status.")),
            Risk.DESTRUCTIVE, "github")
        override fun describe(args: JsonObject) = "Open pull request " + arg(args, "head_branch") + " → " +
            arg(args, "base_branch") + " in " + arg(args, "owner") + "/" + arg(args, "repo")
        override fun isDestructive(args: JsonObject, ctx: ToolContext) = true
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult { return try {
            val owner = arg(args, "owner"); val name = arg(args, "repo")
            val head = arg(args, "head_branch"); val base = arg(args, "base_branch")
            val title = arg(args, "title"); val body = arg(args, "body")
            if (title.isBlank() || title.length > 200 || body.length > 10_000) {
                return ToolResult.Error("Pull request title must be 1–200 characters and body must be at most 10,000 characters.")
            }
            val repository = repo(context, owner, name)
            if (repository.get("archived")?.asBoolean == true) {
                return ToolResult.Error("Archived repositories cannot accept new pull requests.")
            }
            ctx.progress("Checking that both branches exist and the head contains new commits")
            val comparison = request(context, "GET",
                url("repos", owner, name, "compare", "$base...$head"))
            val aheadBy = comparison.get("ahead_by")?.asInt ?: 0
            pullRequestPreflightError(head, base, aheadBy)?.let { return ToolResult.Error(it) }
            val payload = JsonObject().apply {
                addProperty("head", head); addProperty("base", base)
                addProperty("title", title); addProperty("body", body)
            }
            val created = request(context, "POST", url("repos", owner, name, "pulls"), payload)
            ToolResult.Ok("Pull request #" + (created.get("number")?.asInt ?: 0) + " created: " +
                (created.get("html_url")?.asString ?: "URL unavailable") + ". It has not been merged.")
        } catch (e: Exception) { failure(e) }
        }
    }
}
