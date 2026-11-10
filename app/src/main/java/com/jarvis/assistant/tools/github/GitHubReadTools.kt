package com.jarvis.assistant.tools.github

import com.google.gson.JsonObject
import com.jarvis.assistant.tools.Risk
import com.jarvis.assistant.tools.ToolContext
import com.jarvis.assistant.tools.ToolParam
import com.jarvis.assistant.tools.ToolResult
import com.jarvis.assistant.tools.ToolSpec
import com.jarvis.assistant.tools.builtin.int
import com.jarvis.assistant.tools.builtin.str
import com.jarvis.assistant.tools.github.GitHubClient.Companion.enc
import com.jarvis.assistant.tools.github.GitHubClient.Companion.encPath
import com.jarvis.assistant.tools.github.GitHubClient.Companion.repoPath

private const val G = "github"
private const val MAX_FILE_BYTES = 2_000_000L

class GhStatusTool(client: GitHubClient, private val tokenSource: GitHubTokenSource) : GhTool(client) {
    override val spec = ToolSpec("github_status", "Check the GitHub connection: who the saved token belongs to, its scopes and the remaining rate limit.", emptyList(), Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        if (tokenSource.get().isNullOrBlank()) {
            return ToolResult.Ok("GitHub is not connected: no access token is saved. Public repositories can still be read anonymously (60 requests per hour). Add a token in Settings → GitHub Integration to use private repositories or make changes.")
        }
        val r = client.get("/user", needsToken = true)
        val u = r.obj()
        val scopes = r.header("x-oauth-scopes")
        return ToolResult.Ok(buildString {
            append("Connected as ${u.text("login") ?: "unknown"}").append(u.text("name")?.let { " ($it)" } ?: "").append('\n')
            append(if (scopes != null) "Token scopes: ${scopes.ifBlank { "(none)" }}\n" else "Token type: fine-grained or app token (permissions are set per repository).\n")
            val rem = r.header("x-ratelimit-remaining"); val lim = r.header("x-ratelimit-limit")
            if (rem != null && lim != null) append("Rate limit: $rem of $lim requests left this hour.")
        }.trim())
    }
}

class GhListReposTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_list_repos", "List repositories: yours (when connected) or those of a given user or organisation.",
        listOf(ToolParam("owner", description = "User or organisation. Omit to list your own repositories."),
            ToolParam("sort", description = "updated, created, pushed or full_name", enumValues = listOf("updated", "created", "pushed", "full_name"))) + PAGING_PARAMS, Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val owner = args.str("owner")?.trim()?.takeIf { it.isNotEmpty() }
        val sort = args.str("sort")?.takeIf { it in setOf("updated", "created", "pushed", "full_name") } ?: "updated"
        val resp = if (owner == null) client.get("/user/repos", paging(args, mapOf("sort" to sort, "affiliation" to "owner,collaborator,organization_member")), needsToken = true)
        else client.get("/users/${enc(GhRepoRef.parse("$owner/x").first)}/repos", paging(args, mapOf("sort" to sort)))
        return ToolResult.Ok(formatRepos(resp.json().arr()?.mapNotNull { it.obj() }.orEmpty()) + moreHint(resp))
    }
}

class GhSearchReposTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_search_repos", "Search public GitHub repositories by keyword.",
        listOf(ToolParam("query", required = true, description = "Search text; GitHub qualifiers such as language:kotlin are allowed."),
            ToolParam("sort", description = "stars, forks or updated (default: best match)")) + PAGING_PARAMS, Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val q = need(args, "query")
        val extra = mutableMapOf("q" to q)
        args.str("sort")?.takeIf { it in setOf("stars", "forks", "updated") }?.let { extra["sort"] = it }
        val resp = client.get("/search/repositories", paging(args, extra))
        val total = resp.obj().num("total_count") ?: 0
        return ToolResult.Ok("$total matching repositories.\n" + formatRepos(resp.obj().list("items").mapNotNull { it.obj() }) + moreHint(resp))
    }
}

private fun formatRepos(repos: List<JsonObject>): String =
    if (repos.isEmpty()) "No repositories found." else repos.joinToString("\n") { r ->
        "${r.text("full_name")}${if (r.flag("private") == true) " [private]" else ""}${if (r.flag("archived") == true) " [archived]" else ""}" +
            " ★${r.num("stargazers_count") ?: 0}${r.text("language")?.let { " · $it" } ?: ""} · updated ${r.text("updated_at")?.take(10) ?: "?"}" +
            (r.text("description")?.let { " — ${it.oneLine(100)}" } ?: "")
    }

class GhRepoInfoTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_repo_info", "Show a repository's details: description, default branch, visibility, size, topics and your permissions.", listOf(REPO), Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val j = client.get(repoPath(o, r)).obj()
        val perms = j.child("permissions")
        return ToolResult.Ok(buildString {
            append("${j.text("full_name")}${if (j.flag("private") == true) " (private)" else " (public)"}\n")
            j.text("description")?.let { append("$it\n") }
            append("Default branch: ${j.text("default_branch")} · Language: ${j.text("language") ?: "n/a"} · License: ${j.child("license")?.text("spdx_id") ?: "none"}\n")
            append("Stars ${j.num("stargazers_count") ?: 0} · Forks ${j.num("forks_count") ?: 0} · Open issues ${j.num("open_issues_count") ?: 0} · Size ${j.num("size") ?: 0} KB\n")
            append("Last push: ${j.text("pushed_at") ?: "?"}${if (j.flag("archived") == true) " · ARCHIVED (read-only)" else ""}\n")
            val topics = j.list("topics").mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            if (topics.isNotEmpty()) append("Topics: ${topics.joinToString()}\n")
            if (perms != null) append("Your access: ${listOf("admin", "push", "pull").filter { perms.flag(it) == true }.joinToString().ifEmpty { "read-only" }}\n")
            j.text("html_url")?.let { append(it) }
        }.trim())
    }
}

class GhListFilesTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_list_files", "List the files and folders at a path in a repository.",
        listOf(REPO, ToolParam("path", description = "Folder path in the repository. Omit for the root."), ToolParam("ref", description = "Branch, tag or commit. Default: the default branch.")), Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val path = args.str("path")?.trim()?.trim('/').orEmpty()
        val url = repoPath(o, r) + "/contents" + if (path.isEmpty()) "" else "/" + encPath(GhTool.checkRepoPath(path))
        val j = client.get(url, args.str("ref")?.let { mapOf("ref" to GhTool.checkRefName(it, "ref")) }.orEmpty()).json()
        val items = j.arr() ?: return ToolResult.Error("\"${path}\" is a file, not a folder. Use github_read_file.")
        val rows = items.mapNotNull { it.obj() }.sortedWith(compareBy({ it.text("type") != "dir" }, { it.text("name")?.lowercase() }))
        if (rows.isEmpty()) return ToolResult.Ok("(empty folder)")
        return ToolResult.Ok(rows.take(300).joinToString("\n") { f -> if (f.text("type") == "dir") "${f.text("name")}/" else "${f.text("name")}  (${f.num("size") ?: 0} bytes)" })
    }
}

class GhReadFileTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_read_file", "Read a text file from a repository.",
        listOf(REPO, ToolParam("path", required = true, description = "File path in the repository."), ToolParam("ref", description = "Branch, tag or commit."),
            ToolParam("max_chars", "integer", "Stop after this many characters (default 20000).")), Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val path = GhTool.checkRepoPath(need(args, "path"))
        val ref = args.str("ref")?.let { GhTool.checkRefName(it, "ref") }
        val j = client.get(repoPath(o, r) + "/contents/" + encPath(path), ref?.let { mapOf("ref" to it) }.orEmpty()).json()
        if (j.isJsonArray) return ToolResult.Error("$path is a folder. Use github_list_files.")
        val f = j.obj() ?: return ToolResult.Error("Unexpected answer from GitHub.")
        if (f.text("type") != "file") return ToolResult.Error("$path is a ${f.text("type") ?: "special entry"}, not a regular file.")
        val sha = f.text("sha").orEmpty()
        val size = f.num("size") ?: 0
        if (size > MAX_FILE_BYTES) return ToolResult.Error("$path is ${size / 1024} KB, too large to read here. Download the repository or search it instead.")
        var content = f.text("content").orEmpty()
        val bytes = if (content.isNotBlank() && f.text("encoding") == "base64") GhTool.decode(content)
        else GhTool.decode(client.get(repoPath(o, r) + "/git/blobs/" + enc(sha)).obj().text("content").orEmpty())
        if (GhTool.isBinary(bytes)) return ToolResult.Error("$path is a binary file and can not be read as text.")
        val text = String(bytes, Charsets.UTF_8)
        client.contentCache.put(o, r, ref, path, ContentCache.Entry(text, sha))
        val limit = (args.int("max_chars") ?: 20_000).coerceIn(1, 200_000)
        val shown = if (text.length > limit) text.take(limit) + "\n… (${text.length - limit} more characters)" else text
        return ToolResult.Ok("$UNTRUSTED_NOTE\n$o/$r/$path${ref?.let { " @ $it" } ?: ""} (${bytes.size} bytes, blob ${sha.short()})\n$shown")
    }
}

class GhSearchCodeTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_search_code", "Search code on GitHub (needs a connected token). Restrict to one repository with repo.",
        listOf(ToolParam("query", required = true), ToolParam("repo", description = "Limit to owner/name.")) + PAGING_PARAMS, Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        var q = need(args, "query")
        args.str("repo")?.takeIf { it.isNotBlank() }?.let { val (o, r) = GhRepoRef.parse(it); q += " repo:$o/$r" }
        val resp = client.get("/search/code", paging(args, mapOf("q" to q)), accept = "application/vnd.github.text-match+json", needsToken = true)
        val items = resp.obj().list("items").mapNotNull { it.obj() }
        if (items.isEmpty()) return ToolResult.Ok("No code matches.")
        return ToolResult.Ok("$UNTRUSTED_NOTE\n" + items.joinToString("\n") { i ->
            val frag = i.list("text_matches").firstOrNull().obj()?.text("fragment")?.oneLine(160)
            "${i.child("repository")?.text("full_name")}: ${i.text("path")}" + (frag?.let { "\n    $it" } ?: "")
        } + moreHint(resp))
    }
}

class GhListCommitsTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_list_commits", "List recent commits, optionally for one branch or file.",
        listOf(REPO, ToolParam("branch", description = "Branch, tag or commit to start from."), ToolParam("path", description = "Only commits touching this file or folder.")) + PAGING_PARAMS, Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val extra = mutableMapOf<String, String>()
        args.str("branch")?.takeIf { it.isNotBlank() }?.let { extra["sha"] = GhTool.checkRefName(it, "branch") }
        args.str("path")?.takeIf { it.isNotBlank() }?.let { extra["path"] = GhTool.checkRepoPath(it) }
        val resp = client.get(repoPath(o, r) + "/commits", paging(args, extra))
        val rows = resp.json().arr()?.mapNotNull { it.obj() }.orEmpty()
        if (rows.isEmpty()) return ToolResult.Ok("No commits.")
        return ToolResult.Ok(rows.joinToString("\n") { c ->
            val cm = c.child("commit")
            "${c.text("sha").orEmpty().short()} ${cm?.child("author")?.text("date")?.take(10) ?: ""} ${cm?.child("author")?.text("name") ?: ""} — ${cm?.text("message").orEmpty().oneLine(100)}"
        } + moreHint(resp))
    }
}

class GhListBranchesTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_list_branches", "List a repository's branches.", listOf(REPO) + PAGING_PARAMS, Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val resp = client.get(repoPath(o, r) + "/branches", paging(args))
        val rows = resp.json().arr()?.mapNotNull { it.obj() }.orEmpty()
        return ToolResult.Ok(if (rows.isEmpty()) "No branches." else rows.joinToString("\n") { b ->
            "${b.text("name")}  ${b.child("commit")?.text("sha").orEmpty().short()}${if (b.flag("protected") == true) "  [protected]" else ""}"
        } + moreHint(resp))
    }
}

class GhListPrsTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_list_prs", "List pull requests.",
        listOf(REPO, ToolParam("state", description = "open, closed or all (default open)", enumValues = listOf("open", "closed", "all"))) + PAGING_PARAMS, Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val state = args.str("state")?.takeIf { it in setOf("open", "closed", "all") } ?: "open"
        val resp = client.get(repoPath(o, r) + "/pulls", paging(args, mapOf("state" to state)))
        val rows = resp.json().arr()?.mapNotNull { it.obj() }.orEmpty()
        if (rows.isEmpty()) return ToolResult.Ok("No $state pull requests.")
        return ToolResult.Ok("$UNTRUSTED_NOTE\n" + rows.joinToString("\n") { p ->
            "#${p.num("number")} ${p.text("title").orEmpty().oneLine(90)} [${if (p.flag("draft") == true) "draft" else p.text("state")}] " +
                "${p.child("head")?.text("ref")} → ${p.child("base")?.text("ref")} by ${p.child("user")?.text("login")} · ${p.text("updated_at")?.take(10)}"
        } + moreHint(resp))
    }
}

class GhListIssuesTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_list_issues", "List issues (pull requests are left out).",
        listOf(REPO, ToolParam("state", description = "open, closed or all (default open)", enumValues = listOf("open", "closed", "all")),
            ToolParam("labels", description = "Comma-separated label names.")) + PAGING_PARAMS, Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val extra = mutableMapOf("state" to (args.str("state")?.takeIf { it in setOf("open", "closed", "all") } ?: "open"))
        args.str("labels")?.takeIf { it.isNotBlank() }?.let { extra["labels"] = it }
        val resp = client.get(repoPath(o, r) + "/issues", paging(args, extra))
        val rows = resp.json().arr()?.mapNotNull { it.obj() }.orEmpty().filter { !it.has("pull_request") }
        if (rows.isEmpty()) return ToolResult.Ok("No ${extra["state"]} issues.")
        return ToolResult.Ok("$UNTRUSTED_NOTE\n" + rows.joinToString("\n") { i ->
            val labels = i.list("labels").mapNotNull { it.obj()?.text("name") }
            "#${i.num("number")} ${i.text("title").orEmpty().oneLine(90)} [${i.text("state")}]${if (labels.isNotEmpty()) " {${labels.joinToString()}}" else ""} " +
                "by ${i.child("user")?.text("login")} · ${i.num("comments") ?: 0} comments"
        } + moreHint(resp))
    }
}

class GhPrDiffTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_get_pr_diff", "Show a pull request's details and its full diff.",
        listOf(REPO, ToolParam("number", "integer", "Pull request number.", true), ToolParam("max_chars", "integer", "Stop after this many characters (default 20000).")), Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val n = args.int("number")?.takeIf { it > 0 } ?: return ToolResult.Error("\"number\" must be a positive pull request number.")
        val base = repoPath(o, r) + "/pulls/$n"
        val meta = client.get(base).obj()
        val diff = client.get(base, accept = GitHubClient.DIFF).text
        val limit = (args.int("max_chars") ?: 20_000).coerceIn(1, 100_000)
        val shown = if (diff.length > limit) diff.take(limit) + "\n… (${diff.length - limit} more characters)" else diff
        return ToolResult.Ok("$UNTRUSTED_NOTE\n#$n ${meta.text("title").orEmpty()} [${meta.text("state")}] ${meta.child("head")?.text("ref")} → ${meta.child("base")?.text("ref")}\n" +
            "${meta.num("changed_files") ?: "?"} files, +${meta.num("additions") ?: "?"} -${meta.num("deletions") ?: "?"}\n\n$shown")
    }
}

class GhCompareTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_compare", "Compare two branches, tags or commits: ahead/behind counts, commits and the changed files with their patches.",
        listOf(REPO, ToolParam("base", required = true, description = "Base branch/tag/commit."), ToolParam("head", required = true, description = "Head branch/tag/commit."),
            ToolParam("max_chars", "integer", "Limit for the patches (default 20000).")), Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val base = GhTool.checkRefName(need(args, "base"), "base"); val head = GhTool.checkRefName(need(args, "head"), "head")
        val j = client.get(repoPath(o, r) + "/compare/" + base.split('/').joinToString("/") { enc(it) } + "..." + head.split('/').joinToString("/") { enc(it) }).obj()
        val limit = (args.int("max_chars") ?: 20_000).coerceIn(1, 100_000)
        val sb = StringBuilder("$UNTRUSTED_NOTE\n")
        sb.append("$base...$head: ${j.text("status")} · ahead ${j.num("ahead_by")} · behind ${j.num("behind_by")} · ${j.num("total_commits")} commit(s)\n")
        j.list("commits").mapNotNull { it.obj() }.take(20).forEach { c -> sb.append("  ${c.text("sha").orEmpty().short()} ${c.child("commit")?.text("message").orEmpty().oneLine(90)}\n") }
        val files = j.list("files").mapNotNull { it.obj() }
        sb.append("${files.size} file(s) changed:\n")
        var budget = limit
        for (f in files) {
            sb.append("\n${f.text("status")} ${f.text("filename")} (+${f.num("additions") ?: 0} -${f.num("deletions") ?: 0})\n")
            val patch = f.text("patch")
            if (patch == null) sb.append("(no patch: binary or too large)\n")
            else if (budget > 0) { val p = patch.take(budget); sb.append(p).append('\n'); budget -= p.length; if (p.length < patch.length) sb.append("… (patch shortened)\n") }
            else sb.append("(patch omitted: output limit reached)\n")
        }
        if (j.list("files").size() >= 300) sb.append("\n(GitHub lists at most 300 files.)")
        return ToolResult.Ok(sb.toString().trimEnd())
    }
}

class GhActionsRunsTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_actions_runs", "Inspect GitHub Actions: list recent workflow runs, or pass run_id to see its jobs and step results.",
        listOf(REPO, ToolParam("branch"), ToolParam("status", description = "queued, in_progress, completed, success, failure ..."), ToolParam("run_id", "integer", "Show jobs and steps of this run.")) + PAGING_PARAMS, Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val runId = args.int("run_id")
        if (runId != null) {
            if (runId <= 0) return ToolResult.Error("\"run_id\" must be a positive number.")
            val run = client.get(repoPath(o, r) + "/actions/runs/$runId").obj()
            val jobs = client.get(repoPath(o, r) + "/actions/runs/$runId/jobs", mapOf("per_page" to "50")).obj().list("jobs").mapNotNull { it.obj() }
            val sb = StringBuilder("Run #${run.num("run_number")} ${run.text("name")} — ${run.text("status")}/${run.text("conclusion") ?: "pending"} on ${run.text("head_branch")} (${run.text("head_sha").orEmpty().short()})\n")
            for (job in jobs) {
                sb.append("\nJob ${job.text("name")}: ${job.text("status")}/${job.text("conclusion") ?: "pending"}\n")
                for (s in job.list("steps").mapNotNull { it.obj() }) {
                    val c = s.text("conclusion")
                    sb.append("  ${when (c) { "success" -> "✓"; "failure" -> "✗"; "skipped" -> "-"; null -> "…"; else -> "?" }} ${s.num("number")}. ${s.text("name")}${if (c == "failure") "  ← FAILED" else ""}\n")
                }
            }
            run.text("html_url")?.let { sb.append("\n$it") }
            return ToolResult.Ok(sb.toString().trimEnd())
        }
        val extra = mutableMapOf<String, String>()
        args.str("branch")?.takeIf { it.isNotBlank() }?.let { extra["branch"] = GhTool.checkRefName(it) }
        args.str("status")?.takeIf { it.matches(Regex("[a-z_]{3,20}")) }?.let { extra["status"] = it }
        val resp = client.get(repoPath(o, r) + "/actions/runs", paging(args, extra))
        val runs = resp.obj().list("workflow_runs").mapNotNull { it.obj() }
        if (runs.isEmpty()) return ToolResult.Ok("No workflow runs.")
        return ToolResult.Ok(runs.joinToString("\n") { w ->
            "run ${w.num("id")} #${w.num("run_number")} ${w.text("name")} — ${w.text("status")}/${w.text("conclusion") ?: "pending"} · ${w.text("head_branch")} ${w.text("head_sha").orEmpty().short()} · ${w.text("event")} · ${w.text("created_at")?.take(16)}"
        } + moreHint(resp))
    }
}

class GhReleaseAssetsTool(client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("github_release_assets", "List a release's downloadable assets (latest release unless a tag is given). Use github_download_release_asset to download one.",
        listOf(REPO, ToolParam("tag", description = "Release tag. Default: the latest release.")), Risk.NETWORK, G)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val tag = args.str("tag")?.takeIf { it.isNotBlank() }?.let { GhTool.checkRefName(it, "tag") }
        val rel = client.get(repoPath(o, r) + if (tag == null) "/releases/latest" else "/releases/tags/${enc(tag)}").obj()
        val assets = rel.list("assets").mapNotNull { it.obj() }
        return ToolResult.Ok("Release ${rel.text("tag_name")} — ${rel.text("name").orEmpty()}${if (rel.flag("prerelease") == true) " (pre-release)" else ""}\n" +
            if (assets.isEmpty()) "No downloadable assets." else assets.joinToString("\n") { a ->
                "id ${a.num("id")}: ${a.text("name")} (${(a.num("size") ?: 0) / 1024} KB, ${a.text("content_type")}, ${a.num("download_count") ?: 0} downloads)"
            })
    }
}
