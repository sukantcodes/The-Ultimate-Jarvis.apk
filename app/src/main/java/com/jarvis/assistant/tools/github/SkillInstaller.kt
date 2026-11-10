package com.jarvis.assistant.tools.github

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.builtin.bool
import com.jarvis.assistant.tools.builtin.str
import com.jarvis.assistant.tools.github.GitHubClient.Companion.enc
import com.jarvis.assistant.tools.github.GitHubClient.Companion.repoPath
import java.io.File
import java.security.MessageDigest

data class SkillEntry(val name: String, val path: String, val description: String)

data class SkillReview(
    val owner: String, val repo: String, val ref: String, val commit: String,
    val skills: List<SkillEntry>, val findings: List<Finding>, val permissions: RequestedPermissions,
    val installNotes: List<String>, val filesReviewed: List<String>, val filesSkipped: List<String>
) {
    val highest: Severity? get() = SkillScanner.highest(findings)
    val hasHigh: Boolean get() = highest == Severity.HIGH

    fun format(): String = buildString {
        appendLine("$owner/$repo @ $ref (${commit.take(7)})")
        appendLine(if (skills.isEmpty()) "Skills found: none (no SKILL.md or skills manifest)." else "Skills found: " + skills.joinToString { it.name })
        appendLine("It would need permission to: ${permissions.describe()}.")
        if (installNotes.isNotEmpty()) { appendLine("Install instructions in the README:"); installNotes.forEach { appendLine("  $it") } }
        if (findings.isEmpty()) appendLine("Safety scan: nothing suspicious found in ${filesReviewed.size} files (this is not a guarantee).")
        else {
            appendLine("Safety scan: ${findings.size} finding(s), highest ${highest}.")
            findings.take(25).forEach { appendLine("  [${it.severity}] ${it.rule} — ${it.file}:${it.line}  ${it.excerpt}") }
        }
        if (filesSkipped.isNotEmpty()) appendLine("Not reviewed (too large or binary): ${filesSkipped.take(10).joinToString()}")
        appendLine("Nothing was installed or run.")
    }
}

data class InstalledSkill(
    val id: String, val owner: String, val repo: String, val ref: String, val commit: String, val location: String,
    val skills: List<String>, val permissions: String, val acknowledgedRisks: Boolean, val installedAt: Long, val fileHashes: Map<String, String>
)

/** Where installed skills are recorded, so JARVIS remembers them across restarts and can notice later tampering. */
class InstalledSkillRegistry(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    @Synchronized fun list(): List<InstalledSkill> = try {
        if (!file.exists()) emptyList() else JsonParser.parseString(file.readText()).asJsonArray.mapNotNull { runCatching { decode(it.asJsonObject) }.getOrNull() }
    } catch (e: Exception) { emptyList() }

    @Synchronized fun add(s: InstalledSkill) { write(list().filter { it.id != s.id } + s) }
    @Synchronized fun remove(id: String): Boolean { val all = list(); if (all.none { it.id == id }) return false; write(all.filter { it.id != id }); return true }
    fun get(id: String): InstalledSkill? = list().firstOrNull { it.id == id }
    fun now(): Long = clock()

    /** Files that are missing or changed since installation. */
    fun verify(s: InstalledSkill, workspace: Workspace): List<String> {
        val problems = ArrayList<String>()
        val root = workspace.resolve(s.location)
        for ((rel, hash) in s.fileHashes) {
            val f = File(root, rel)
            if (!f.isFile) problems += "missing: $rel" else if (sha256(f.readBytes()) != hash) problems += "changed: $rel"
        }
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') }.filter { it !in s.fileHashes }.take(20).forEach { problems += "added: $it" }
        return problems
    }

    private fun write(list: List<InstalledSkill>) {
        file.parentFile?.mkdirs()
        file.writeText(JsonArray().apply { list.forEach { add(encode(it)) } }.toString())
    }

    private fun encode(s: InstalledSkill) = JsonObject().apply {
        addProperty("id", s.id); addProperty("owner", s.owner); addProperty("repo", s.repo); addProperty("ref", s.ref); addProperty("commit", s.commit)
        addProperty("location", s.location); addProperty("permissions", s.permissions); addProperty("ack", s.acknowledgedRisks); addProperty("at", s.installedAt)
        add("skills", JsonArray().apply { s.skills.forEach { add(it) } })
        add("hashes", JsonObject().apply { s.fileHashes.forEach { (k, v) -> addProperty(k, v) } })
    }

    private fun decode(o: JsonObject) = InstalledSkill(
        o.text("id")!!, o.text("owner").orEmpty(), o.text("repo").orEmpty(), o.text("ref").orEmpty(), o.text("commit").orEmpty(), o.text("location")!!,
        o.list("skills").map { it.asString }, o.text("permissions").orEmpty(), o.flag("ack") == true, o.num("at") ?: 0L,
        o.child("hashes")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
    )

    companion object { fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }
}

/**
 * Inspects a GitHub repository for skills and installs it into the workspace after review. Installing downloads and
 * unpacks files (validated, no links, no path tricks) and records them; it never executes anything from the repository.
 */
class SkillInstaller(private val client: GitHubClient, private val registry: InstalledSkillRegistry) {

    suspend fun inspect(owner: String, repo: String, refArg: String?): SkillReview {
        val ref = refArg ?: client.defaultBranch(owner, repo)
        val tree = client.get(repoPath(owner, repo) + "/git/trees/" + enc(ref), mapOf("recursive" to "1")).obj()
        val commit = tree.text("sha").orEmpty()
        val entries = tree.list("tree").mapNotNull { it.obj() }.filter { it.text("type") == "blob" }
            .map { (it.text("path") ?: "") to (it.num("size") ?: 0L) }.filter { it.first.isNotEmpty() && !it.first.contains("..") }

        val skillRoots = entries.map { it.first }.filter { it.substringAfterLast('/').equals("SKILL.md", true) }
        val toRead = LinkedHashMap<String, Long>()
        val skipped = ArrayList<String>()
        for ((path, size) in entries) {
            val name = path.substringAfterLast('/').lowercase(); val ext = name.substringAfterLast('.', "")
            val interesting = name == "skill.md" || name in INTERESTING_NAMES || ext in SCRIPT_EXTS || name == "readme.md" || name == "skills.json" || name == "manifest.json"
            if (!interesting) continue
            if (size > MAX_FILE_BYTES || toRead.size >= MAX_FILES) { skipped += path; continue }
            toRead[path] = size
        }
        val texts = LinkedHashMap<String, String>()
        for (path in toRead.keys) {
            val f = client.get(repoPath(owner, repo) + "/contents/" + GitHubClient.encPath(path), mapOf("ref" to ref)).json().obj() ?: continue
            val bytes = f.text("content")?.takeIf { f.text("encoding") == "base64" }?.let { GhTool.decode(it) } ?: continue
            if (GhTool.isBinary(bytes)) { skipped += path; continue }
            texts[path] = String(bytes, Charsets.UTF_8)
        }
        val findings = SkillScanner.scan(texts)
        val skills = skillRoots.map { p ->
            val dir = p.substringBeforeLast('/', "")
            val body = texts[p].orEmpty()
            val name = Regex("""(?m)^name:\s*(.+)$""").find(body)?.groupValues?.get(1)?.trim()?.trim('"', '\'') ?: dir.substringAfterLast('/').ifEmpty { repo }
            val desc = Regex("""(?m)^description:\s*(.+)$""").find(body)?.groupValues?.get(1)?.trim()?.trim('"', '\'').orEmpty()
            SkillEntry(name, dir, desc.take(200))
        }
        val notes = texts.entries.firstOrNull { it.key.equals("README.md", true) }?.value?.let { installNotesOf(it) }.orEmpty()
        return SkillReview(owner, repo, ref, commit, skills, findings, SkillScanner.permissions(texts, findings), notes, texts.keys.toList(), skipped)
    }

    /** Pulls out lines near "install"/"setup" headings and fenced commands, shown as information only. */
    private fun installNotesOf(readme: String): List<String> {
        val out = ArrayList<String>(); var inSection = false; var fence = false
        for (raw in readme.lineSequence()) {
            val l = raw.trimEnd()
            if (l.startsWith("#")) { inSection = Regex("""(?i)install|setup|getting started|usage""").containsMatchIn(l); continue }
            if (l.trim().startsWith("```")) { fence = !fence; continue }
            if (inSection && (fence || l.trim().startsWith("$ ")) && l.isNotBlank()) out += l.trim().take(160)
            if (out.size >= 12) break
        }
        return out
    }

    fun verify(rec: InstalledSkill, workspace: Workspace): List<String> = registry.verify(rec, workspace)

    suspend fun install(review: SkillReview, acknowledgeRisks: Boolean, workspace: Workspace): InstalledSkill {
        if (review.hasHigh && !acknowledgeRisks) throw GitHubException(GhErrorKind.UNSAFE, "The safety scan found high-risk code. Review the findings and set acknowledge_risks to true only if you trust this repository.")
        val id = safeName("${review.owner}-${review.repo}")
        val dest = workspace.resolve("skills/$id")
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        val bytes = client.downloadBytes(repoPath(review.owner, review.repo) + "/zipball/" + enc(review.commit.ifEmpty { review.ref }), "application/vnd.github+json", GhCloneTool.MAX_REPO_BYTES)
        try { SafeZip.extract(bytes, dest) } catch (e: Exception) { dest.deleteRecursively(); throw e }
        val hashes = dest.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dest).path.replace(File.separatorChar, '/') to InstalledSkillRegistry.sha256(it.readBytes()) }
        val record = InstalledSkill(id, review.owner, review.repo, review.ref, review.commit, "skills/$id", review.skills.map { it.name },
            review.permissions.describe(), acknowledgeRisks && review.hasHigh, registry.now(), hashes)
        registry.add(record)
        return record
    }

    companion object {
        const val MAX_FILE_BYTES = 200_000L; const val MAX_FILES = 60
        private val INTERESTING_NAMES = setOf("package.json", "setup.py", "setup.cfg", "pyproject.toml", "requirements.txt", "makefile", "dockerfile", "install", "setup", "build.gradle", "build.gradle.kts")
        private val SCRIPT_EXTS = setOf("sh", "bash", "zsh", "py", "js", "mjs", "cjs", "ts", "rb", "pl", "php", "ps1", "bat", "cmd")
    }
}

private const val GS = "skills"

class SkillInspectTool(client: GitHubClient, private val installer: SkillInstaller) : GhTool(client) {
    override val spec = ToolSpec("skill_inspect_repo", "Inspect a GitHub repository as a skill source: find its skills, read its install instructions and scan its scripts for unsafe behaviour. Installs and runs nothing.",
        listOf(REPO, ToolParam("ref")), Risk.NETWORK, GS)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        return ToolResult.Ok(UNTRUSTED_NOTE + "\n" + installer.inspect(o, r, args.str("ref")?.let { GhTool.checkRefName(it, "ref") }).format())
    }
}

class SkillInstallTool(client: GitHubClient, private val installer: SkillInstaller) : GhTool(client) {
    override val spec = ToolSpec("skill_install", "Install a skill repository into the workspace (skills/<name>) after a safety review. Nothing from it is executed. High-risk findings need acknowledge_risks=true.",
        listOf(REPO, ToolParam("ref"), ToolParam("acknowledge_risks", "boolean", "Confirm you reviewed high-risk findings.")), Risk.WRITE, GS)
    override fun describe(args: JsonObject) = "Install skill repository ${args.str("repo")}${args.str("ref")?.let { " @ $it" } ?: ""} into skills/ (files are saved, never run). Run skill_inspect_repo first to see the safety review."
    override fun isDestructive(args: JsonObject, ctx: ToolContext) = args.bool("acknowledge_risks") == true
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val (o, r) = repoOf(args)
        val review = installer.inspect(o, r, args.str("ref")?.let { GhTool.checkRefName(it, "ref") })
        val rec = installer.install(review, args.bool("acknowledge_risks") == true, ctx.workspace)
        val problems = installer.verify(rec, ctx.workspace)
        return ToolResult.Ok("Installed ${rec.owner}/${rec.repo} (${rec.commit.take(7)}) at ${rec.location}. Skills: ${rec.skills.joinToString().ifEmpty { "none declared" }}.\nPermissions it would need: ${rec.permissions}.\n" +
            (if (problems.isEmpty()) "Verified: ${rec.fileHashes.size} files match what was downloaded. Nothing was run." else "Verification problems: ${problems.joinToString()}"))
    }
}

class SkillListTool(private val registry: InstalledSkillRegistry, client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("skill_list", "List installed skills with their source, location and whether their files still match what was installed.", emptyList(), Risk.READ, GS)
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val all = registry.list()
        if (all.isEmpty()) return ToolResult.Ok("No skills installed.")
        return ToolResult.Ok(all.joinToString("\n") { s ->
            val problems = registry.verify(s, ctx.workspace)
            "${s.id}: ${s.owner}/${s.repo} @ ${s.commit.take(7)} at ${s.location} — skills: ${s.skills.joinToString().ifEmpty { "-" }} — " + if (problems.isEmpty()) "files intact" else "MODIFIED (${problems.take(3).joinToString()})"
        })
    }
}

class SkillRemoveTool(private val registry: InstalledSkillRegistry, client: GitHubClient) : GhTool(client) {
    override val spec = ToolSpec("skill_remove", "Remove an installed skill and delete its files.", listOf(ToolParam("id", required = true)), Risk.DESTRUCTIVE, GS)
    override fun describe(args: JsonObject) = "REMOVE installed skill \"${args.str("id")}\" and delete its files"
    override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
        val s = registry.get(need(args, "id")) ?: return ToolResult.Error("No installed skill with that id.")
        ctx.workspace.resolve(s.location).deleteRecursively()
        registry.remove(s.id)
        return ToolResult.Ok("Removed ${s.id}.")
    }
}
