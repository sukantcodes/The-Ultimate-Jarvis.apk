package com.jarvis.assistant.tts.model

/**
 * Supported cloud Text-to-Speech engines in Jarvis.
 */
enum class VoiceEngineType(
    val id: String,
    val displayName: String
) {
    CLOUD_GEMINI(
        id = "cloud_gemini",
        displayName = "Google AI Studio / Gemini 3.8 Flash TTS"
    ),
    CLOUD_ELEVENLABS(
        id = "cloud_elevenlabs",
        displayName = "ElevenLabs Turbo v2.5"
    ),
    NONE(
        id = "none",
        displayName = "Disabled"
    );

    companion object {
        fun fromId(id: String?): VoiceEngineType =
            entries.firstOrNull { it.id == id } ?: CLOUD_GEMINI
    }
}
