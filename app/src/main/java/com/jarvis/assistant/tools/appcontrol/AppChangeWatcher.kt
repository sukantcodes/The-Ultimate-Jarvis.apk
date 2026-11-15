package com.jarvis.assistant.tools.appcontrol

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Watches the app's own preference files so every change is journaled, whether the user made it on a Settings screen,
 * another part of the app made it, or Jarvis made it. Nothing here needs an Android permission: these files belong
 * to this app. Secrets are never copied (see [ChangeJournal.isSensitive]).
 */
object AppChangeWatcher {
    /** Preference files that hold settings. Encrypted key storage, face data and order histories are left out on purpose. */
    val WATCHED_FILES: List<String> = listOf(
        "jarvis_prefs", "tool_permissions", "jarvis_providers", "jarvis_chatbox_preferences", "jarvis_chatbox_tools",
        "jarvis_ai_engine", "jarvis_voice_prefs", "jarvis_voice_mode", "jarvis_voice_gender", "jarvis_maya_mode",
        "jarvis_noise_cancel", "jarvis_alert_settings", "jarvis_message_alerts", "jarvis_auto_sleep",
        "jarvis_activity_log_settings", "jarvis_clock", "jarvis_token_mode_preferences", "tts_engine_settings", "jarvis_skills"
    )

    private const val JARVIS_WINDOW_MS = 2_000L
    private const val BIG_VALUE = 2_000

    @Volatile private var journalRef: ChangeJournal? = null
    private val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val snapshots = ConcurrentHashMap<String, Map<String, String>>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-change-journal").apply { isDaemon = true } }
    @Volatile private var jarvisUntil = 0L
    @Volatile private var jarvisLabel = ""

    /** Called just before Jarvis writes a setting, so the change that follows is credited to Jarvis. */
    fun markJarvisWrite(label: String) {
        jarvisLabel = label
        jarvisUntil = SystemClock.elapsedRealtime() + JARVIS_WINDOW_MS
    }

    private fun currentActor(): String =
        if (SystemClock.elapsedRealtime() <= jarvisUntil) "jarvis" else "user or app"

    /** Starts watching (once) and returns the journal. Safe to call from anywhere, any number of times. */
    @Synchronized
    fun start(context: Context): ChangeJournal {
        journalRef?.let { return it }
        val app = context.applicationContext
        val journal = ChangeJournal(File(app.filesDir, "workspace/.jarvis/app_changes.jsonl"))
        journalRef = journal
        WATCHED_FILES.forEach { name ->
            val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            worker.execute { snapshots[name] = snapshotOf(sp) }
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
                val actor = currentActor()
                val label = jarvisLabel
                worker.execute { diff(name, prefs, actor, label, journal) }
            }
            sp.registerOnSharedPreferenceChangeListener(listener)
            listeners += listener   // SharedPreferences holds listeners weakly, so they are kept here
        }
        return journal
    }

    fun journal(context: Context): ChangeJournal = journalRef ?: start(context)

    private fun snapshotOf(sp: SharedPreferences): Map<String, String> {
        val out = HashMap<String, String>()
        for ((k, v) in sp.all) {
            if (ChangeJournal.isNoise(k)) continue
            out[k] = compact(k, v)
        }
        return out
    }

    /** Long or secret values are kept only as a fingerprint, enough to notice a change without keeping the content. */
    private fun compact(key: String, value: Any?): String {
        val text = when (value) {
            null -> ""
            is Set<*> -> value.map { it.toString() }.sorted().joinToString(",")
            else -> value.toString()
        }
        return if (ChangeJournal.isSensitive(key) || text.length > BIG_VALUE) "#${text.length}:${text.hashCode()}" else text
    }

    private fun diff(name: String, sp: SharedPreferences, actor: String, label: String, journal: ChangeJournal) {
        val old = snapshots[name] ?: emptyMap()
        val now = snapshotOf(sp)
        snapshots[name] = now
        val who = if (actor == "jarvis" && label.isNotBlank()) "jarvis" else actor
        for ((k, v) in now) {
            val was = old[k]
            if (was == v) continue
            journal.record(name, k, was?.takeUnless { it.startsWith("#") }, v.takeUnless { it.startsWith("#") }, who)
        }
        for ((k, was) in old) {
            if (k !in now) journal.record(name, k, was.takeUnless { it.startsWith("#") }, null, who)
        }
    }
}
