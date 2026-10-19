package com.jarvis.assistant.notifications

import android.content.Context

/** Switches for the WhatsApp, Instazen and Instagram message announcements. */
class MessageAlertSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_message_alerts", Context.MODE_PRIVATE)

    /** Announce the sender's name when a new message arrives. Defaults to on. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) {
            prefs.edit().putBoolean("enabled", value).apply()
        }

    /** What the message listener did last, shown under Notification Access in Settings. */
    var lastStatus: String
        get() = prefs.getString("last_status", "") ?: ""
        set(value) {
            prefs.edit().putString("last_status", value).apply()
        }

    /** Use Jarvis's own voice while a live session is open. Otherwise (or when off) the phone's voice is used. */
    var useJarvisVoice: Boolean
        get() = prefs.getBoolean("use_jarvis_voice", false)
        set(value) {
            prefs.edit().putBoolean("use_jarvis_voice", value).apply()
        }
}
