package com.jarvis.assistant.voice

import android.content.Context

/**
 * Remembers which voice gender the user picked (male, female, or prefer not to say) and which voice to use for each.
 * With "prefer not to say" (the default) nothing changes: Jarvis keeps using the voice chosen in the existing settings.
 */
class VoiceGenderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_voice_gender", Context.MODE_PRIVATE)

    var gender: VoiceGender
        get() = VoiceGender.fromKey(prefs.getString("gender", null))
        set(value) {
            prefs.edit().putString("gender", value.key).apply()
        }

    var maleVoice: String
        get() = validOrDefault(prefs.getString("male_voice", null), VoiceCatalog.male, VoiceCatalog.DEFAULT_MALE)
        set(value) {
            prefs.edit().putString("male_voice", value).apply()
        }

    var femaleVoice: String
        get() = validOrDefault(prefs.getString("female_voice", null), VoiceCatalog.female, VoiceCatalog.DEFAULT_FEMALE)
        set(value) {
            prefs.edit().putString("female_voice", value).apply()
        }

    private fun validOrDefault(saved: String?, list: List<VoiceInfo>, fallback: String): String =
        if (saved != null && list.any { it.name == saved }) saved else fallback

    /** The Gemini voice to speak with. With "prefer not to say" it returns [currentVoice] unchanged. */
    fun voiceFor(currentVoice: String): String = when (gender) {
        VoiceGender.MALE -> maleVoice
        VoiceGender.FEMALE -> femaleVoice
        VoiceGender.UNSPECIFIED -> currentVoice
    }

    /** Pitch for the phone's own voice (used by Groq mode and the battery alerts), which has no gender setting. */
    fun pitch(): Float = when (gender) {
        VoiceGender.MALE -> 0.88f
        VoiceGender.FEMALE -> 1.12f
        VoiceGender.UNSPECIFIED -> 1.0f
    }

    /** Added to the system prompt so Jarvis also speaks about itself with the matching grammar in Hindi and Hinglish. */
    fun promptSection(): String = when (gender) {
        VoiceGender.MALE ->
            "\n\n=== VOICE AND SELF-REFERENCE ===\n" +
                "The user chose a male voice for you. Speak as a male assistant. When you talk about yourself in Hindi or " +
                "Hinglish, always use masculine forms (for example 'main kar sakta hoon', 'main kar deta hoon', " +
                "'main aa gaya'), never feminine ones ('sakti', 'karti', 'gayi'). Keep your name, personality and every " +
                "other instruction exactly as they are. This only changes your voice and the grammatical gender you use " +
                "about yourself.\n"
        VoiceGender.FEMALE ->
            "\n\n=== VOICE AND SELF-REFERENCE ===\n" +
                "The user chose a female voice for you. Speak as a female assistant. When you talk about yourself in Hindi " +
                "or Hinglish, always use feminine forms (for example 'main kar sakti hoon', 'main kar deti hoon', " +
                "'main aa gayi'), never masculine ones ('sakta', 'karta', 'gaya'). Keep your name, personality and every " +
                "other instruction exactly as they are. This only changes your voice and the grammatical gender you use " +
                "about yourself.\n"
        VoiceGender.UNSPECIFIED -> ""
    }

    /** One line for the Settings row. */
    fun summary(): String = when (gender) {
        VoiceGender.MALE -> "Male voice: " + maleVoice
        VoiceGender.FEMALE -> "Female voice: " + femaleVoice
        VoiceGender.UNSPECIFIED -> "Prefer not to say (your current voice)"
    }
}
