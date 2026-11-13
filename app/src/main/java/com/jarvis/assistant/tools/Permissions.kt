package com.jarvis.assistant.tools

import com.google.gson.JsonObject
import java.io.File

enum class Mode { ALLOW, ASK, DENY }

enum class Decision { ALLOW_ONCE, ALLOW_TASK, ALLOW_SESSION, DENY }

/** What the user is shown before something consequential happens. */
data class ConfirmRequest(
    val tool: String,
    val group: String,
    val risk: Risk,
    val summary: String,
    val destructive: Boolean,
    val source: String
)

/** The app implements this with a dialog. It must never auto-approve. */
interface Confirmer {
    suspend fun confirm(request: ConfirmRequest): Decision
}

/** Denies everything: the safe default when no screen is available to ask (for example a background run). */
object DenyingConfirmer : Confirmer {
    override suspend fun confirm(request: ConfirmRequest) = Decision.DENY
}

/**
 * The one permission policy for every AI provider and every tool. Defaults: reading and web access are allowed,
 * changing files and running programs ask first, and destructive actions always ask (this can not be turned off).
 * "Allow for this session" is a trust shortcut for safe, repetitive work: it is remembered per tool and never for
 * destructive calls, and it is forgotten when the session ends.
 */
class PermissionPolicy(private val store: SettingsStore) {

    interface SettingsStore {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    private val sessionGrants = HashSet<String>()
    private val taskGrants = HashSet<String>()

    fun isAutoApproveAll(): Boolean = store.get(KEY_AUTO_APPROVE_ALL) == "true"

    /**
     * The master internet switch (ChatBox + menu → Workspace tools). It is checked before every other rule, so it
     * also holds under Auto-Approve All: when it is off, no tool may use the internet.
     */
    fun isInternetAllowed(): Boolean = store.get(KEY_INTERNET_ENABLED) != "false"

    fun setInternetAllowed(enabled: Boolean) {
        store.put(KEY_INTERNET_ENABLED, enabled.toString())
    }

    fun setAutoApproveAll(enabled: Boolean) {
        store.put(KEY_AUTO_APPROVE_ALL, enabled.toString())
    }

    fun modeFor(risk: Risk, group: String): Mode {
        if (risk == Risk.NETWORK && !isInternetAllowed()) return Mode.DENY
        if (isAutoApproveAll()) return Mode.ALLOW
        store.get("mode.$group.${risk.name}")?.let { v -> Mode.values().firstOrNull { it.name == v }?.let { return enforce(risk, it) } }
        store.get("mode.${risk.name}")?.let { v -> Mode.values().firstOrNull { it.name == v }?.let { return enforce(risk, it) } }
        return defaultFor(risk)
    }

    fun setMode(risk: Risk, mode: Mode, group: String? = null) {
        store.put(if (group == null) "mode.${risk.name}" else "mode.$group.${risk.name}", enforce(risk, mode).name)
    }

    @Synchronized fun grantForSession(tool: String) { sessionGrants += tool }
    @Synchronized fun hasSessionGrant(tool: String): Boolean = tool in sessionGrants

    @Synchronized fun grantForTask(source: String) { if (source.isNotBlank()) taskGrants += source }
    @Synchronized fun hasTaskGrant(source: String): Boolean = source.isNotBlank() && source in taskGrants

    @Synchronized fun endSession() {
        sessionGrants.clear()
        taskGrants.clear()
    }

    private fun defaultFor(risk: Risk) = when (risk) {
        Risk.READ, Risk.NETWORK -> Mode.ALLOW
        Risk.WRITE, Risk.EXECUTE, Risk.DESTRUCTIVE -> Mode.ASK
    }

    /** Destructive actions can be denied but never auto-allowed. */
    private fun enforce(risk: Risk, mode: Mode) = if (risk == Risk.DESTRUCTIVE && mode == Mode.ALLOW) Mode.ASK else mode

    companion object {
        const val KEY_AUTO_APPROVE_ALL = "auto_approve_all"
        const val KEY_INTERNET_ENABLED = "internet_enabled"

        /** Tool groups that reach the internet whatever their risk label says (GitHub pushes are "destructive", not "network"). */
        val INTERNET_GROUPS = setOf("web", "github", "browser")
    }
}

/** One line per decision. Contains no secrets: summaries are redacted before they are written. */
class AuditLog(private val file: File?, private val redactor: SecretRedactor, private val maxInMemory: Int = 500, private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(val time: Long, val tool: String, val source: String, val risk: Risk, val decision: String, val summary: String, val outcome: String)

    private val entries = ArrayDeque<Entry>()

    @Synchronized fun record(tool: String, source: String, risk: Risk, decision: String, summary: String, outcome: String) {
        val e = Entry(clock(), tool, source, risk, decision, redactor.redact(summary).take(400), redactor.redact(outcome).take(400))
        entries.addLast(e)
        while (entries.size > maxInMemory) entries.removeFirst()
        file?.let {
            try {
                it.parentFile?.mkdirs()
                if (it.exists() && it.length() > MAX_FILE_BYTES) it.renameTo(File(it.path + ".old"))
                val o = JsonObject().apply {
                    addProperty("t", e.time); addProperty("tool", e.tool); addProperty("src", e.source); addProperty("risk", e.risk.name)
                    addProperty("decision", e.decision); addProperty("summary", e.summary); addProperty("outcome", e.outcome)
                }
                it.appendText(o.toString() + "\n")
            } catch (_: Exception) { /* auditing must never break the action */ }
        }
    }

    @Synchronized fun recent(limit: Int = 100): List<Entry> = entries.toList().takeLast(limit)

    companion object { const val MAX_FILE_BYTES = 1_000_000L }
}
