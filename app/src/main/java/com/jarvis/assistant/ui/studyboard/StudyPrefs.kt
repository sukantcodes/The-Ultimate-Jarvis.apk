package com.jarvis.assistant.ui.studyboard

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.study.StudyProfile

/** Study Board settings: who is learning, how they want to be taught, and how the board behaves. */
class StudyPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("jarvis_study_prefs", Context.MODE_PRIVATE)

    var level: String
        get() = p.getString("level", "Auto") ?: "Auto"
        set(v) { p.edit().putString("level", v).apply() }
    var language: String
        get() = p.getString("language", "Auto") ?: "Auto"
        set(v) { p.edit().putString("language", v).apply() }
    var depth: StudyProfile.Depth
        get() = runCatching { StudyProfile.Depth.valueOf(p.getString("depth", "AUTO") ?: "AUTO") }.getOrDefault(StudyProfile.Depth.AUTO)
        set(v) { p.edit().putString("depth", v.name).apply() }
    var voiceOn: Boolean
        get() = p.getBoolean("voice_on", true)
        set(v) { p.edit().putBoolean("voice_on", v).apply() }
    /** Auto, English or Hindi: the language of narration and of the microphone. */
    var voiceLanguage: String
        get() = p.getString("voice_lang", "Auto") ?: "Auto"
        set(v) { p.edit().putString("voice_lang", v).apply() }
    var speed: Float
        get() = p.getFloat("speed", 1f)
        set(v) { p.edit().putFloat("speed", v).apply() }
    var notesOn: Boolean
        get() = p.getBoolean("notes_on", true)
        set(v) { p.edit().putBoolean("notes_on", v).apply() }

    /** The small "Thinking / Writing / Reading" label in the top bar (never on the board itself). */
    var statusOn: Boolean
        get() = p.getBoolean("status_on", true)
        set(v) { p.edit().putBoolean("status_on", v).apply() }
    /** The pen tip drawn on the board while it writes. Off by default: the board stays clean. */
    var penOn: Boolean
        get() = p.getBoolean("pen_on", false)
        set(v) { p.edit().putBoolean("pen_on", v).apply() }

    /** Which AI answers the board: your Gemini API key (default) or the active custom provider. */
    var provider: String
        get() = p.getString("ai_provider", PROVIDER_GEMINI) ?: PROVIDER_GEMINI
        set(v) { p.edit().putString("ai_provider", v).apply() }

    companion object {
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_CUSTOM = "custom"
    }
    /** Whether the teacher remembers earlier questions of this session when answering the next one. */
    var memoryOn: Boolean
        get() = p.getBoolean("memory_on", true)
        set(v) { p.edit().putBoolean("memory_on", v).apply() }
    var memoryJson: String
        get() = p.getString("memory_json", "") ?: ""
        set(v) { p.edit().putString("memory_json", v).apply() }

    fun profile(): StudyProfile = StudyProfile(
        level = level, language = language, depth = depth,
        userAge = runCatching { JarvisApp.instance.preferences.userAge }.getOrDefault("")
    )
}
