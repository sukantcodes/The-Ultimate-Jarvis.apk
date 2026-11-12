package com.jarvis.assistant.tts.model

/**
 * Supported Text-to-Speech synthesis engines in Jarvis.
 */
enum class VoiceEngineType(
    val id: String,
    val displayName: String,
    val isLocal: Boolean,
    val archiveName: String,
    val downloadUrl: String,
    val ramFootprintMb: Int,
    val defaultModelFileName: String
) {
    LOCAL_KOKORO(
        id = "local_kokoro",
        displayName = "Kokoro-82M Int8 Multi-Lang (Default Local)",
        isLocal = true,
        archiveName = "kokoro-int8-multi-lang-v1_0",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
        ramFootprintMb = 135,
        defaultModelFileName = "model.int8.onnx"
    ),
    LOCAL_PIPER(
        id = "local_piper",
        displayName = "Piper VITS Alan (Lightweight Fallback)",
        isLocal = true,
        archiveName = "vits-piper-en_GB-alan-medium",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-alan-medium.tar.bz2",
        ramFootprintMb = 45,
        defaultModelFileName = "en_GB-alan-medium.onnx"
    ),
    CLOUD_GEMINI(
        id = "cloud_gemini",
        displayName = "Google AI Studio / Gemini 3.8 Flash TTS",
        isLocal = false,
        archiveName = "",
        downloadUrl = "",
        ramFootprintMb = 0,
        defaultModelFileName = ""
    ),
    CLOUD_ELEVENLABS(
        id = "cloud_elevenlabs",
        displayName = "ElevenLabs Turbo v2.5",
        isLocal = false,
        archiveName = "",
        downloadUrl = "",
        ramFootprintMb = 0,
        defaultModelFileName = ""
    );

    companion object {
        fun fromId(id: String?): VoiceEngineType =
            entries.firstOrNull { it.id == id } ?: LOCAL_KOKORO
    }
}
