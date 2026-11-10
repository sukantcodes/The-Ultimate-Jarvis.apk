package com.jarvis.assistant.tools.github

import com.google.gson.JsonObject
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.builtin.bool
import com.jarvis.assistant.tools.builtin.str
import com.jarvis.assistant.tools.github.GitHubClient.Companion.enc
import com.jarvis.assistant.tools.github.GitHubClient.Companion.encPath
import com.jarvis.assistant.tools.github.GitHubClient.Companion.repoPath
import java.io.File
import java.util.Base64

private const val GW = "github"

/** Creates a branch. Changes the remote repository, so it asks first. */
class GhCreateBranchTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_create_branch", "Create a new branch in a repository (from another branch, default: the default branch).",
        listOf(REPO, ToolParam("branch", required = true), ToolParam("from", description = "Branch, tag or commit to start from.")), Risk.WRITE, GW)
    override fun describe(args: JsonObject) = "Create branch \"${args.str("branch")}\" from \"${args.str("from") ?: "the default branch"}\" in ${args.str("repo")}"
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        requireConnected()
        val (o, r) = repoOf(args)
        val branch = GhTool.checkRefName(need(args, "branch"))
        val from = args.str("from")?.let { GhTool.checkRefName(it, "ref") } ?: client.defaultBranch(o, r)
        val sha = client.get(repoPath(o, r) + "/commits/" + enc(from)).obj().text("sha")
            ?: return ToolResult.Error("Could not find \"$from\" in $o/$r.")
        client.send("POST", repoPath(o, r) + "/git/refs", JsonObject().apply { addProperty("ref", "refs/heads/$branch"); addProperty("sha", sha) })
        return ToolResult.Ok("Created branch $branch at ${sha.short()} in $o/$r.")
    }
}

/**
 * Creates or updates one file as a commit. To change an existing file the file must have been read first with
 * github_read_file: the confirmation prompt then shows the exact diff, and if the file changed on GitHub since it
 * was read the commit is refused (a conflict) instead of overwriting someone else's work.
 */
class GhPutFileTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_put_file", "Create or update a file in a repository as a commit (one file per call).",
        listOf(REPO, ToolParam("path", required = true), ToolParam("content", required = true, description = "Complete new file contents."),
            ToolParam("message", required = true, description = "Commit message."), ToolParam("branch", description = "Branch to commit to (default: the default branch).")),
        Risk.WRITE, GW)

    override fun describe(args: JsonObject): String {
        val repo = args.str("repo").orEmpty(); val path = args.str("path").orEmpty(); val branch = args.str("branch")
        val content = args.str("content").orEmpty()
        val head = "Commit to $repo${branch?.let { " on branch $it" } ?: " on the default branch"}: \"${args.str("message").orEmpty().oneLine(80)}\"\n$path"
        val cached = runCatching { val (o, r) = GhRepoRef.parse(repo); client.contentCache.get(o, r, branch, path) ?: client.contentCache.get(o, r, null, path) }.getOrNull()
        return if (cached == null) "$head (${content.length} characters; no earlier version was read, so this creates a new file)"
        else head + "\n" + DiffUtil.truncate(DiffUtil.unified(cached.text, content, path), 3000)
    }

    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        requireConnected()
        val (o, r) = repoOf(args)
        val path = GhTool.checkRepoPath(need(args, "path"))
        val message = need(args, "message").also { if (it.length > 500) throw GitHubException(GhErrorKind.INVALID, "The commit message is too long.") }
        val branch = args.str("branch")?.let { GhTool.checkRefName(it) }
        val content = args.str("content") ?: ""
        val url = repoPath(o, r) + "/contents/" + encPath(path)
        val current = try { client.get(url, branch?.let { mapOf("ref" to it) }.orEmpty()).json() } catch (e: GitHubException) { if (e.kind == GhErrorKind.NOT_FOUND) null else throw e }
        val body = JsonObject().apply {
            addProperty("message", message)
            addProperty("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
            branch?.let { addProperty("branch", it) }
        }
        if (current != null) {
            val f = current.obj() ?: return ToolResult.Error("$path is a folder in $o/$r.")
            val liveSha = f.text("sha") ?: return ToolResult.Error("Could not read the current version of $path.")
            val seen = client.contentCache.get(o, r, branch, path) ?: client.contentCache.get(o, r, null, path)
                ?: return ToolResult.Error("$path already exists. Read it first with github_read_file so the change can be reviewed, then try again.")
            if (seen.sha != liveSha) {
                return ToolResult.Error("Conflict: $path changed on GitHub (now ${liveSha.short()}, you read ${seen.sha.short()}). Read it again, merge the changes, and retry.")
            }
            body.addProperty("sha", liveSha)
        }
        val res = try { client.send("PUT", url, body).obj() } catch (e: GitHubException) {
            if (e.kind == GhErrorKind.CONFLICT) return ToolResult.Error("Conflict: the file or branch changed while committing. Read the latest version and retry.")
            throw e
        }
        val sha = res.child("commit")?.text("sha").orEmpty()
        val newBlob = res.child("content")?.text("sha")
        if (newBlob != null) client.contentCache.put(o, r, branch, path, ContentCache.Entry(content, newBlob))
        return ToolResult.Ok("${if (current == null) "Created" else "Updated"} $path in $o/$r${branch?.let { " on $it" } ?: ""} (commit ${sha.short()}).")
    }
}

/** Deletes one file from a repository (a commit that removes it). Always asks. */
class GhDeleteFileTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_delete_file", "Delete a file from a repository (creates a commit that removes it).",
        listOf(REPO, ToolParam("path", required = true), ToolParam("message", required = true), ToolParam("branch")), Risk.DESTRUCTIVE, GW)
    override fun describe(args: JsonObject) = "DELETE ${args.str("path")} from ${args.str("repo")}${args.str("branch")?.let { " on branch $it" } ?: ""} (commit: \"${args.str("message").orEmpty().oneLine(80)}\")"
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        requireConnected()
        val (o, r) = repoOf(args)
        val path = GhTool.checkRepoPath(need(args, "path"))
        val branch = args.str("branch")?.let { GhTool.checkRefName(it) }
        val url = repoPath(o, r) + "/contents/" + encPath(path)
        val f = client.get(url, branch?.let { mapOf("ref" to it) }.orEmpty()).json().obj() ?: return ToolResult.Error("$path is a folder; only single files can be deleted.")
        val sha = f.text("sha") ?: return ToolResult.Error("Could not read $path.")
        val body = JsonObject().apply { addProperty("message", need(args, "message")); addProperty("sha", sha); branch?.let { addProperty("branch", it) } }
        val res = client.send("DELETE", url, body).obj()
        return ToolResult.Ok("Deleted $path from $o/$r (commit ${res.child("commit")?.text("sha").orEmpty().short()}).")
    }
}

class GhCreatePrTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_create_pr", "Open a pull request.",
        listOf(REPO, ToolParam("title", required = true), ToolParam("head", required = true, description = "Branch with the changes."),
            ToolParam("base", description = "Branch to merge into (default: the default branch)."), ToolParam("body"), ToolParam("draft", "boolean")), Risk.WRITE, GW)
    override fun describe(args: JsonObject) = "Open a pull request in ${args.str("repo")}: \"${args.str("title").orEmpty().oneLine(80)}\" (${args.str("head")} → ${args.str("base") ?: "default branch"})"
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        requireConnected()
        val (o, r) = repoOf(args)
        val head = GhTool.checkRefName(need(args, "head"), "head branch")
        val base = args.str("base")?.let { GhTool.checkRefName(it, "base branch") } ?: client.defaultBranch(o, r)
        val body = JsonObject().apply {
            addProperty("title", need(args, "title")); addProperty("head", head); addProperty("base", base)
            args.str("body")?.let { addProperty("body", it) }; if (args.bool("draft") == true) addProperty("draft", true)
        }
        val pr = client.send("POST", repoPath(o, r) + "/pulls", body).obj()
        return ToolResult.Ok("Opened pull request #${pr.num("number")} in $o/$r: ${pr.text("html_url").orEmpty()}")
    }
}

/** Clone and download use GitHub's archive download, validated and unpacked safely, because a git program is not guaranteed on Android. */
class GhCloneTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_clone_repo",
        "Clone a repository into the workspace (projects/<name>). This downloads the repository files at a branch or tag as a validated archive; it does not include git history, and nothing from it is executed.",
        listOf(REPO, ToolParam("ref", description = "Branch, tag or commit (default: the default branch)."), ToolParam("name", description = "Folder name (default: the repository name)."),
            ToolParam("replace", "boolean", "Replace the folder if it already exists.")), Risk.WRITE, GW)
    override fun describe(args: JsonObject) = "Download ${args.str("repo")}${args.str("ref")?.let { " @ $it" } ?: ""} into projects/${args.str("name") ?: "<repository name>"}"
    override fun isDestructive(args: JsonObject, ctx: ToolContext) = args.bool("replace") == true && runCatching {
        ctx.workspace.project(args.str("name") ?: GhRepoRef.parse(args.str("repo") ?: "").second).let { it.exists() && (it.list()?.isNotEmpty() == true) }
    }.getOrDefault(false)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val ref = args.str("ref")?.let { GhTool.checkRefName(it, "ref") }
        val dest = ctx.workspace.project(args.str("name") ?: r)
        if (dest.list()?.isNotEmpty() == true) {
            if (args.bool("replace") != true) return ToolResult.Error("projects/${dest.name} already exists and is not empty. Use replace=true to replace it, or choose another name.")
            dest.deleteRecursively(); dest.mkdirs()
        }
        ctx.progress("Downloading $o/$r")
        val bytes = client.downloadBytes(repoPath(o, r) + "/zipball" + (ref?.let { "/" + enc(it) } ?: ""), "application/vnd.github+json", MAX_REPO_BYTES)
        val res = try { SafeZip.extract(bytes, dest) } catch (e: GitHubException) { dest.deleteRecursively(); throw e }
        return ToolResult.Ok("Cloned $o/$r${ref?.let { " @ $it" } ?: ""} into ${ctx.workspace.relative(dest)} (${res.files} files, ${res.bytes / 1024} KB). The files were not run.")
    }
    companion object { const val MAX_REPO_BYTES = 100L * 1024 * 1024 }
}

class GhDownloadRepoTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_download_repo", "Download a repository as a .zip file into the workspace (downloads/).",
        listOf(REPO, ToolParam("ref")), Risk.WRITE, GW)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val ref = args.str("ref")?.let { GhTool.checkRefName(it, "ref") }
        val bytes = client.downloadBytes(repoPath(o, r) + "/zipball" + (ref?.let { "/" + enc(it) } ?: ""), "application/vnd.github+json", GhCloneTool.MAX_REPO_BYTES)
        val file = uniqueFile(ctx.workspace.resolve("downloads"), "${o}-${r}${ref?.let { "-" + it.replace('/', '-') } ?: ""}.zip")
        file.parentFile?.mkdirs(); file.writeBytes(bytes)
        return ToolResult.Ok("Saved ${ctx.workspace.relative(file)} (${bytes.size / 1024} KB).", listOf(ToolArtifact(file.path, file.name, "application/zip")))
    }
}

class GhDownloadAssetTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_download_release_asset", "Download one release asset (see github_release_assets for ids) into downloads/. It is saved, never run.",
        listOf(REPO, ToolParam("asset_id", "integer", required = true), ToolParam("filename")), Risk.WRITE, GW)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val id = args.get("asset_id")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: return ToolResult.Error("asset_id must be a number.")
        val meta = client.get(repoPath(o, r) + "/releases/assets/$id").obj()
        val name = safeName(args.str("filename") ?: meta.text("name") ?: "asset-$id")
        val bytes = client.downloadBytes(repoPath(o, r) + "/releases/assets/$id", "application/octet-stream", 200L * 1024 * 1024)
        val file = uniqueFile(ctx.workspace.resolve("downloads"), name)
        file.parentFile?.mkdirs(); file.writeBytes(bytes)
        return ToolResult.Ok("Saved ${ctx.workspace.relative(file)} (${bytes.size / 1024} KB). It was not opened or run.", listOf(ToolArtifact(file.path, file.name, "application/octet-stream")))
    }
}

internal fun safeName(raw: String): String {
    val n = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_').take(100)
    return n.ifEmpty { "download" }
}

internal fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, safeName(name)); var i = 1
    val base = f.nameWithoutExtension; val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
    while (f.exists()) f = File(dir, "$base-${i++}$ext")
    return f
}
