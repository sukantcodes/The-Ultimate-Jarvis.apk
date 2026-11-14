package com.jarvis.assistant.tts.model

/**
 * Speaker profile modeling voice identity, language, gender, and tone across engines.
 */
data class SpeakerProfile(
    val id: String,
    val speakerId: Int,
    val displayName: String,
    val language: String,
    val gender: String,
    val tone: String,
    val engineType: VoiceEngineType,
    val isDefault: Boolean = false,
    val cloudVoiceIdentifier: String = ""
) {
    companion object {
        val GEMINI_VOICES = listOf(
            SpeakerProfile(
                id = "gemini_charon",
                speakerId = 0,
                displayName = "Charon",
                language = "English",
                gender = "Male",
                tone = "Deep, British Butler Cadence",
                engineType = VoiceEngineType.CLOUD_GEMINI,
                isDefault = true,
                cloudVoiceIdentifier = "Charon"
            ),
            SpeakerProfile(
                id = "gemini_orus",
                speakerId = 1,
                displayName = "Orus",
                language = "English",
                gender = "Male",
                tone = "Firm, Decisive, Grounded",
                engineType = VoiceEngineType.CLOUD_GEMINI,
                cloudVoiceIdentifier = "Orus"
            ),
            SpeakerProfile(
                id = "gemini_kore",
                speakerId = 2,
                displayName = "Kore",
                language = "English",
                gender = "Female",
                tone = "Clear, Crisp, Expressive",
                engineType = VoiceEngineType.CLOUD_GEMINI,
                cloudVoiceIdentifier = "Kore"
            ),
            SpeakerProfile(
                id = "gemini_puck",
                speakerId = 3,
                displayName = "Puck",
                language = "English",
                gender = "Male",
                tone = "Upbeat, Playful, Quick-Paced",
                engineType = VoiceEngineType.CLOUD_GEMINI,
                cloudVoiceIdentifier = "Puck"
            )
        )

        val ELEVENLABS_VOICES = listOf(
            SpeakerProfile(
                id = "eleven_rachel",
                speakerId = 0,
                displayName = "Rachel",
                language = "American English",
                gender = "Female",
                tone = "Calm, Conversational, Realistic",
                engineType = VoiceEngineType.CLOUD_ELEVENLABS,
                isDefault = true,
                cloudVoiceIdentifier = "21m00Tcm4TlvDq8ikWAM"
            ),
            SpeakerProfile(
                id = "eleven_adam",
                speakerId = 1,
                displayName = "Adam",
                language = "American English",
                gender = "Male",
                tone = "Deep Narrative, Smooth, Rich",
                engineType = VoiceEngineType.CLOUD_ELEVENLABS,
                cloudVoiceIdentifier = "pNInz6obpgDQGcFmaJgB"
            )
        )

        val NONE_SPEAKER = SpeakerProfile(
            id = "none_default",
            speakerId = -1,
            displayName = "None (Disabled)",
            language = "None",
            gender = "None",
            tone = "Disabled",
            engineType = VoiceEngineType.NONE,
            isDefault = true
        )

        fun speakersFor(engineType: VoiceEngineType): List<SpeakerProfile> = when (engineType) {
            VoiceEngineType.CLOUD_GEMINI -> GEMINI_VOICES
            VoiceEngineType.CLOUD_ELEVENLABS -> ELEVENLABS_VOICES
            VoiceEngineType.NONE -> listOf(NONE_SPEAKER)
        }

        fun defaultFor(engineType: VoiceEngineType): SpeakerProfile =
            if (engineType == VoiceEngineType.NONE) NONE_SPEAKER
            else speakersFor(engineType).firstOrNull { it.isDefault } ?: speakersFor(engineType).firstOrNull() ?: GEMINI_VOICES.first()

        fun findById(id: String?): SpeakerProfile? {
            if (id.isNullOrBlank()) return null
            if (id == NONE_SPEAKER.id) return NONE_SPEAKER
            return (GEMINI_VOICES + ELEVENLABS_VOICES).firstOrNull { it.id == id }
        }
    }
}
