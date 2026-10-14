package com.jarvis.assistant.security

import android.content.Context

/**
 * The in-app switch between "Owner voice only" (ignore every other voice) and "Everyone" (answer any voice).
 * Defaults to owner-only, which keeps the behaviour you already had.
 */
class VoiceModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_voice_mode", Context.MODE_PRIVATE)

    var ownerOnly: Boolean
        get() = prefs.getBoolean("owner_only", true)
        set(value) {
            prefs.edit().putBoolean("owner_only", value).apply()
        }
}
