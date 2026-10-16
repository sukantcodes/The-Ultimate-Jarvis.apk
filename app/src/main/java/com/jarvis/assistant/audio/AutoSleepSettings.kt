package com.jarvis.assistant.audio

import android.content.Context

/** When Jarvis goes back to standby (listening only for "Jarvis") after waking up. */
class AutoSleepSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_auto_sleep", Context.MODE_PRIVATE)

    /** Seconds without real conversation before Jarvis sleeps. 0 means never. Defaults to 60 (one minute). */
    var timeoutSeconds: Int
        get() = prefs.getInt("timeout_seconds", 60).coerceIn(0, 3600)
        set(value) {
            prefs.edit().putInt("timeout_seconds", value.coerceIn(0, 3600)).apply()
        }

    /** When on, the time is counted from the moment Jarvis woke up, even if you are still talking to it. */
    var countFromWake: Boolean
        get() = prefs.getBoolean("count_from_wake", false)
        set(value) {
            prefs.edit().putBoolean("count_from_wake", value).apply()
        }

    companion object {
        val CHOICES_SECONDS = intArrayOf(30, 60, 120, 300, 1800, 0)

        fun labelFor(seconds: Int): String = when (seconds) {
            0 -> "Never"
            30 -> "30 seconds"
            60 -> "1 minute"
            120 -> "2 minutes"
            300 -> "5 minutes"
            1800 -> "30 minutes"
            else -> seconds.toString() + " seconds"
        }
    }
}
