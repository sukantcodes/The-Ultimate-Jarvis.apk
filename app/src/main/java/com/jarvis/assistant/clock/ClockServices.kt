package com.jarvis.assistant.clock

import android.content.Context
import android.content.SharedPreferences
import java.time.ZonedDateTime

/** SharedPreferences-backed storage. A write only counts once Android reports it saved (commit), never "probably saved". */
class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        if (!prefs.edit().putString(key, value).commit()) {
            throw IllegalStateException("The phone could not save '$key'.")
        }
    }
}

/**
 * The one shared [ClockEngine] for the whole app. The Clock screens, the AI tools, the voice paths, the alarm receiver and
 * the ringing service all get it from here, so every part sees the same alarms at once.
 */
object ClockServices {
    private const val PREFS = "jarvis_clock"

    @Volatile private var engine: ClockEngine? = null

    fun engine(context: Context): ClockEngine = engine ?: synchronized(this) {
        engine ?: create(context.applicationContext).also { engine = it }
    }

    private fun create(app: Context): ClockEngine {
        val kv = PrefsKeyValueStore(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
        return ClockEngine(AlarmStore(kv), ClockSettingsStore(kv), AlarmScheduler(app)) { ZonedDateTime.now() }
    }
}
