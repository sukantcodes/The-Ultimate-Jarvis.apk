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

/** Explicitly approved GitHub write operations. No operation merges a pull request or writes to a default branch. */
object GitHubWriteTools {
    private const val MAX_RESPONSE = 2_000_000L
    private const val MAX_FILE = 1_000_000L
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).build()
    }

    fun all(context: Context): List<Tool> {
        val app = context.applicationContext
        return listOf(ConnectionStatus(app), CreateBranch(app), CommitWorkspaceFile(app), CreatePullRequest(app))
    }

    internal fun validBranchName(value: String): Boolean {
        if (value.isBlank() || value.length > 100 || value.startsWith("/") || value.endsWith("/") ||
            value.endsWith(".") || value.contains("..") || value.contains("//") || value.contains("@{") ||
            value.any { it.isWhitespace() || it == '~' || it == '^' || it == ':' || it == '?' || it == '*' || it == '[' || it == '\\' }) return false
        return value.matches(Regex("[A-Za-z0-9._/-]+")) &&
            value.split('/').all { it.isNotBlank() && !it.startsWith(".") && !it.endsWith(".lock") }
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
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
            val user = request(context, "GET", url("user"))
            ToolResult.Ok("GitHub token is valid. Connected account: " + (user.get("login")?.asString ?: "unknown") + ". Token value was not exposed.")
        } catch (e: Exception) { failure(e) }
    }

    private class CreateBranch(private val context: Context) : Tool {
        override val spec = ToolSpec("github_create_branch",
            "Create a feature branch from an existing branch. Requires Contents write permission and explicit user approval.",
            listOf(ToolParam("owner", required = true), ToolParam("repo", required = true),
                ToolParam("new_branch", required = true), ToolParam("base_branch", description = "Defaults to the repository default branch.")),
            Risk.DESTRUCTIVE, "github")
        override fun describe(args: JsonObject) = "Create branch " + arg(args, "new_branch") + " in " + arg(args, "owner") + "/" + arg(args, "repo")
        override fun isDestructive(args: JsonObject, ctx: ToolContext) = true
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
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
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
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
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
            val owner = arg(args, "owner"); val name = arg(args, "repo")
            val head = arg(args, "head_branch"); val base = arg(args, "base_branch")
            val title = arg(args, "title"); val body = arg(args, "body")
            if (!GitHubWriteTools.validBranchName(head) || !GitHubWriteTools.validBranchName(base) ||
                head == base || title.isBlank() || title.length > 200 || body.length > 10_000)
                return ToolResult.Error("Invalid pull request branches or text.")
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
