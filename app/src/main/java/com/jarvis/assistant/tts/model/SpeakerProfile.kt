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
        val KOKORO_SPEAKERS = listOf(
            SpeakerProfile(
                id = "kokoro_bm_george",
                speakerId = 11,
                displayName = "George (Classic Jarvis)",
                language = "British English (en-GB)",
                gender = "Male",
                tone = "Authoritative, Formal, Refined",
                engineType = VoiceEngineType.LOCAL_KOKORO,
                isDefault = true
            ),
            SpeakerProfile(
                id = "kokoro_bm_lewis",
                speakerId = 12,
                displayName = "Lewis",
                language = "British English (en-GB)",
                gender = "Male",
                tone = "Professional, Crisp, Articulate",
                engineType = VoiceEngineType.LOCAL_KOKORO
            ),
            SpeakerProfile(
                id = "kokoro_af_bella",
                speakerId = 1,
                displayName = "Bella",
                language = "American English (en-US)",
                gender = "Female",
                tone = "Friendly, Conversational, Dynamic",
                engineType = VoiceEngineType.LOCAL_KOKORO
            ),
            SpeakerProfile(
                id = "kokoro_bf_emma",
                speakerId = 7,
                displayName = "Emma",
                language = "British English (en-GB)",
                gender = "Female",
                tone = "Calm, Composed, Natural",
                engineType = VoiceEngineType.LOCAL_KOKORO
            ),
            SpeakerProfile(
                id = "kokoro_am_adam",
                speakerId = 5,
                displayName = "Adam",
                language = "American English (en-US)",
                gender = "Male",
                tone = "Modern, Clear, Confident",
                engineType = VoiceEngineType.LOCAL_KOKORO
            )
        )

        val PIPER_SPEAKERS = listOf(
            SpeakerProfile(
                id = "piper_alan",
                speakerId = 0,
                displayName = "Alan (Medium VITS)",
                language = "British English (en-GB)",
                gender = "Male",
                tone = "Ultra-Fast Offline, Balanced, Direct",
                engineType = VoiceEngineType.LOCAL_PIPER,
                isDefault = true
            )
        )

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

        fun speakersFor(engineType: VoiceEngineType): List<SpeakerProfile> = when (engineType) {
            VoiceEngineType.LOCAL_KOKORO -> KOKORO_SPEAKERS
            VoiceEngineType.LOCAL_PIPER -> PIPER_SPEAKERS
            VoiceEngineType.CLOUD_GEMINI -> GEMINI_VOICES
            VoiceEngineType.CLOUD_ELEVENLABS -> ELEVENLABS_VOICES
            VoiceEngineType.NONE -> emptyList()
        }

        fun defaultFor(engineType: VoiceEngineType): SpeakerProfile =
            speakersFor(engineType).firstOrNull { it.isDefault } ?: speakersFor(engineType).firstOrNull() ?: KOKORO_SPEAKERS.first()

        fun findById(id: String?): SpeakerProfile? {
            if (id.isNullOrBlank()) return null
            return (KOKORO_SPEAKERS + PIPER_SPEAKERS + GEMINI_VOICES + ELEVENLABS_VOICES).firstOrNull { it.id == id }
        }
    }
}
