package com.jarvis.assistant.voice

/** The three choices in Settings > Voice & Gender. "Prefer not to say" keeps Jarvis exactly as it was. */
enum class VoiceGender(val key: String, val label: String) {
    UNSPECIFIED("unspecified", "Prefer not to say"),
    MALE("male", "Male"),
    FEMALE("female", "Female");

    companion object {
        fun fromKey(key: String?): VoiceGender = values().firstOrNull { it.key == key } ?: UNSPECIFIED
    }
}

data class VoiceInfo(val name: String, val style: String)

/**
 * The Gemini Live voices, grouped into male and female voices. Google does not label voices by gender, so the
 * grouping follows how the voices are used in the Maya voice set. If a voice sounds wrong in its group, it can
 * be moved by editing the two lists below.
 */
object VoiceCatalog {
    const val DEFAULT_MALE = "Charon"
    const val DEFAULT_FEMALE = "Kore"

    val female: List<VoiceInfo> = listOf(
        VoiceInfo("Aoede", "Breezy"),
        VoiceInfo("Autonoe", "Bright"),
        VoiceInfo("Callirrhoe", "Easy-going"),
        VoiceInfo("Despina", "Smooth"),
        VoiceInfo("Erinome", "Clear"),
        VoiceInfo("Gacrux", "Mature"),
        VoiceInfo("Kore", "Firm"),
        VoiceInfo("Laomedeia", "Upbeat"),
        VoiceInfo("Leda", "Youthful"),
        VoiceInfo("Pulcherrima", "Forward"),
        VoiceInfo("Sulafat", "Warm"),
        VoiceInfo("Vindemiatrix", "Gentle"),
        VoiceInfo("Zephyr", "Bright")
    )

    val male: List<VoiceInfo> = listOf(
        VoiceInfo("Achernar", "Soft"),
        VoiceInfo("Achird", "Friendly"),
        VoiceInfo("Algenib", "Gravelly"),
        VoiceInfo("Algieba", "Smooth"),
        VoiceInfo("Alnilam", "Firm"),
        VoiceInfo("Charon", "Informative"),
        VoiceInfo("Enceladus", "Breathy"),
        VoiceInfo("Fenrir", "Excitable"),
        VoiceInfo("Iapetus", "Clear"),
        VoiceInfo("Orus", "Firm"),
        VoiceInfo("Puck", "Upbeat"),
        VoiceInfo("Rasalgethi", "Informative"),
        VoiceInfo("Sadachbia", "Lively"),
        VoiceInfo("Sadaltager", "Knowledgeable"),
        VoiceInfo("Schedar", "Even"),
        VoiceInfo("Umbriel", "Easy-going"),
        VoiceInfo("Zubenelgenubi", "Casual")
    )

    fun voicesFor(gender: VoiceGender): List<VoiceInfo> = when (gender) {
        VoiceGender.MALE -> male
        VoiceGender.FEMALE -> female
        VoiceGender.UNSPECIFIED -> emptyList()
    }
}
