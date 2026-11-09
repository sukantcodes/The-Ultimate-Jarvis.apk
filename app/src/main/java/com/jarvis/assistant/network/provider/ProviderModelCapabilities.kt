package com.jarvis.assistant.network.provider

/**
 * Capabilities that a provider model may support. These are used by JARVIS to decide where a
 * model can be used and how, and are derived from the real discovery response (protocol/format),
 * not invented from the model name.
 *
 * @property realtimeVoice true when the provider exposes a bidirectional (WebSocket) audio session
 *   that can power the live voice assistant (Gemini-Live-compatible protocol).
 * @property streaming true when chat responses can arrive incrementally.
 * @property tools true when function/tool calling is supported.
 * @property imageInput true when image content can be sent to the model.
 * @property fileAnalysis true when document/file content can be analysed.
 */
data class ProviderModelCapabilities(
    val textGeneration: Boolean = true,
    val streaming: Boolean = true,
    val tools: Boolean = false,
    val imageInput: Boolean = false,
    val fileAnalysis: Boolean = false,
    val audioInput: Boolean = false,
    val audioOutput: Boolean = false,
    val realtimeVoice: Boolean = false
) {
    /** Models with no realtime session can still be used in the ChatBox. */
    fun usableForChat(): Boolean = textGeneration

    /** Only models that support the voice protocol can run the live voice assistant. */
    fun usableForVoice(): Boolean = textGeneration && realtimeVoice

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("textGeneration", textGeneration)
        .put("streaming", streaming)
        .put("tools", tools)
        .put("imageInput", imageInput)
        .put("fileAnalysis", fileAnalysis)
        .put("audioInput", audioInput)
        .put("audioOutput", audioOutput)
        .put("realtimeVoice", realtimeVoice)

    companion object {
        /** Conservative defaults for a text-capable OpenAI-compatible model. */
        val textOnly = ProviderModelCapabilities(
            streaming = true, tools = false, imageInput = false, fileAnalysis = false,
            audioInput = false, audioOutput = false, realtimeVoice = false
        )

        /** Used for Gemini-Live compatible endpoints where realtime audio is supported. */
        val realtimeVoiceCapable = ProviderModelCapabilities(
            streaming = true, tools = true, imageInput = true, fileAnalysis = true,
            audioInput = true, audioOutput = true, realtimeVoice = true
        )

        fun fromJson(json: org.json.JSONObject): ProviderModelCapabilities =
            ProviderModelCapabilities(
                textGeneration = json.optBoolean("textGeneration", true),
                streaming = json.optBoolean("streaming", true),
                tools = json.optBoolean("tools", false),
                imageInput = json.optBoolean("imageInput", false),
                fileAnalysis = json.optBoolean("fileAnalysis", false),
                audioInput = json.optBoolean("audioInput", false),
                audioOutput = json.optBoolean("audioOutput", false),
                realtimeVoice = json.optBoolean("realtimeVoice", false)
            )
    }
}
