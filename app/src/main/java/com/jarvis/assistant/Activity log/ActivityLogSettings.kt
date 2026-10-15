package com.jarvis.assistant.activitylog

import android.content.Context

/** Settings for Jarvis's on-device activity memory. */
class ActivityLogSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_activity_log_settings", Context.MODE_PRIVATE)

    /** When off, nothing new is recorded. Defaults to on. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) {
            prefs.edit().putBoolean("enabled", value).apply()
        }

    /** How many hours of activity are kept before older entries are deleted. Defaults to 48 (two days). */
    var windowHours: Int
        get() = prefs.getInt("window_hours", 48).coerceIn(6, 168)
        set(value) {
            prefs.edit().putInt("window_hours", value.coerceIn(6, 168)).apply()
        }
}
