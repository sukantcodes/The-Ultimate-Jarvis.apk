package com.jarvis.assistant.tools.appcontrol

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One thing that changed inside the app. Values of secret-looking keys are never kept, only the fact that they changed. */
data class AppChange(
    val time: Long,
    /** Which store changed: a preference file name, or "api" for provider settings. */
    val area: String,
    val key: String,
    val before: String?,
    val after: String?,
    /** "jarvis" when Jarvis made the change, otherwise "user or app". */
    val actor: String,
    val valueHidden: Boolean
)

/**
 * The app's memory of what changed and who changed it. It keeps the most recent changes in memory and appends every
 * one to a file, so Jarvis can answer "what changed" after a restart too. Writing the file can never break a change.
 */
class ChangeJournal(
    private val file: File?,
    private val maxInMemory: Int = 1000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val entries = ArrayDeque<AppChange>()

    init { loadTail() }

    @Synchronized
    fun record(area: String, key: String, before: String?, after: String?, actor: String) {
        val hidden = isSensitive(key)
        val change = AppChange(
            clock(), area, key,
            if (hidden) null else before?.take(MAX_VALUE),
            if (hidden) null else after?.take(MAX_VALUE),
            actor, hidden
        )
        entries.addLast(change)
        while (entries.size > maxInMemory) entries.removeFirst()
        append(change)
    }

    @Synchronized
    fun recent(limit: Int = 30, search: String? = null, actor: String? = null, sinceMs: Long? = null): List<AppChange> {
        val needle = search?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return entries.filter { c ->
            (actor == null || c.actor.startsWith(actor, ignoreCase = true)) &&
                (sinceMs == null || c.time >= sinceMs) &&
                (needle.isEmpty() || c.key.lowercase(Locale.ROOT).contains(needle) || c.area.lowercase(Locale.ROOT).contains(needle) ||
                    c.before.orEmpty().lowercase(Locale.ROOT).contains(needle) || c.after.orEmpty().lowercase(Locale.ROOT).contains(needle))
        }.takeLast(limit.coerceIn(1, 200))
    }

    @Synchronized fun count(): Int = entries.size

    fun describe(c: AppChange): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(c.time))
        val what = when {
            c.valueHidden -> "changed (value hidden)"
            c.before == null && c.after == null -> "changed"
            c.before == null -> "set to ${quote(c.after)}"
            c.after == null -> "removed (was ${quote(c.before)})"
            else -> "${quote(c.before)} -> ${quote(c.after)}"
        }
        return "$stamp  [${c.actor}]  ${c.area}/${c.key}: $what"
    }

    private fun quote(v: String?): String = "\"${v.orEmpty()}\""

    private fun append(c: AppChange) {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            if (f.exists() && f.length() > MAX_FILE_BYTES) f.renameTo(File(f.path + ".old"))
            val o = JsonObject().apply {
                addProperty("t", c.time); addProperty("area", c.area); addProperty("key", c.key)
                c.before?.let { addProperty("before", it) }
                c.after?.let { addProperty("after", it) }
                addProperty("actor", c.actor); addProperty("hidden", c.valueHidden)
            }
            f.appendText(o.toString() + "\n")
        } catch (_: Exception) { /* the journal must never break a change */ }
    }

    private fun loadTail() {
        val f = file?.takeIf { it.isFile } ?: return
        try {
            f.readLines().takeLast(maxInMemory).forEach { line ->
                val o = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return@forEach
                fun s(n: String): String? = o.get(n)?.takeIf { it.isJsonPrimitive }?.asString
                val t = o.get("t")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@forEach
                entries.addLast(AppChange(t, s("area").orEmpty(), s("key").orEmpty(), s("before"), s("after"), s("actor") ?: "user or app", o.get("hidden")?.asBoolean ?: false))
            }
        } catch (_: Exception) { /* a damaged journal starts again empty */ }
    }

    companion object {
        const val MAX_VALUE = 300
        const val MAX_FILE_BYTES = 1_000_000L

        private val SECRET_WORDS = setOf("pin", "token", "secret", "password", "passcode", "credential", "credentials", "otp", "jwt", "cookie", "session")
        private val SECRET_PARTS = listOf("apikey", "api_key", "api-key", "embedding", "voice_profile", "owner_voice_samples", "chat_history", "profiles", "face_")

        /** Keys whose values must not be copied anywhere: secrets, voice prints and whole chat histories. */
        fun isSensitive(key: String): Boolean {
            val k = key.lowercase(Locale.ROOT)
            if (SECRET_PARTS.any { k.contains(it) }) return true
            return k.split(Regex("[^a-z0-9]+")).any { it in SECRET_WORDS }
        }

        /** Keys that change constantly without meaning anything (counters, drag positions), so they stay out of the journal. */
        fun isNoise(key: String): Boolean {
            val k = key.lowercase(Locale.ROOT)
            return NOISE_PARTS.any { k.contains(it) }
        }

        private val NOISE_PARTS = listOf("chat_history", "cumulative_chat_tokens", "chat_tokens_approximate", "song_counts", "task_overlay_x", "task_overlay_y", "last_seen", "heartbeat", "revision", "timestamp", "last_run", "last_check")
    }
}
