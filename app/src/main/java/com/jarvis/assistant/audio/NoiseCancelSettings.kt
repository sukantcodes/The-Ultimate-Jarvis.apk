package com.jarvis.assistant.audio

import android.content.Context

enum class NoiseLevel(val key: String, val label: String) {
    OFF("off", "Off"),
    STANDARD("standard", "Standard (removes steady noise)"),
    STRONG("strong", "Strong (also quiets far-away voices)");

    companion object {
        fun fromKey(key: String?): NoiseLevel = values().firstOrNull { it.key == key } ?: STRONG
    }
}

/** How strongly background noise is removed from what Jarvis hears. Defaults to Strong. */
class NoiseCancelSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_noise_cancel", Context.MODE_PRIVATE)

    var level: NoiseLevel
        get() = NoiseLevel.fromKey(prefs.getString("level", null))
        set(value) {
            prefs.edit().putString("level", value.key).apply()
        }
}
