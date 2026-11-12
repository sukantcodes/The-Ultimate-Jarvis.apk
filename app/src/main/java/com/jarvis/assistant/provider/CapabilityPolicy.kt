package com.jarvis.assistant.provider

/**
 * Decides what a model is allowed to power. Discovery only says a model exists; this says what JARVIS can
 * honestly do with it. It never answers "yes" on a guess, and it always says why when the answer is no.
 */
object CapabilityPolicy {

    fun voice(profile: ProviderProfile, model: ModelInfo?): VoiceEligibility {
        if (model == null) return VoiceEligibility(VoiceMode.NONE, "Choose a model first.")
        return when (profile.protocol) {
            ProviderProtocol.GEMINI -> when (model.support(Capability.REALTIME_VOICE)) {
                Support.YES -> VoiceEligibility(VoiceMode.REALTIME_GEMINI_LIVE, "Real-time voice over the Gemini Live protocol.")
                Support.NO -> VoiceEligibility(
                    VoiceMode.NONE,
                    "${model.id} cannot power JARVIS's real-time voice session: " +
                        model.result(Capability.REALTIME_VOICE).note.ifBlank { "the provider doesn't offer the Live API for it." } +
                        if (model.support(Capability.TEXT) != Support.NO) " It can still be used in ChatBox." else ""
                )
                Support.UNKNOWN -> VoiceEligibility(
                    VoiceMode.NONE,
                    "Real-time voice hasn't been verified for ${model.id}. Tap \"Check compatibility\" to test the Live connection."
                )
            }
            ProviderProtocol.OPENAI_COMPATIBLE -> {
                if (model.support(Capability.TEXT) == Support.NO) {
                    return VoiceEligibility(VoiceMode.NONE, "${model.id} isn't a chat model, so it can't answer in a voice session.")
                }
                val tools = if (model.support(Capability.TOOLS) == Support.NO) " Phone actions are unavailable because this model doesn't accept tools." else ""
                if (profile.sttModelId.isBlank()) {
                    return VoiceEligibility(
                        VoiceMode.TURN_BASED,
                        "Turn-based hybrid voice: on-device speech-to-text transcribes your speech, ${model.id} answers, and offline neural TTS speaks the reply.$tools"
                    )
                }
                when (profile.sttSupport) {
                    Support.YES -> {
                        VoiceEligibility(
                            VoiceMode.TURN_BASED,
                            "Turn-based voice: ${profile.sttModelId} transcribes your speech, ${model.id} answers, and your phone speaks the reply.$tools"
                        )
                    }
                    Support.NO -> VoiceEligibility(
                        VoiceMode.TURN_BASED,
                        "Turn-based hybrid voice: ${profile.sttModelId} is unavailable online; on-device speech-to-text transcribes your speech, ${model.id} answers, and offline neural TTS speaks the reply.$tools"
                    )
                    Support.UNKNOWN -> VoiceEligibility(
                        VoiceMode.TURN_BASED,
                        "Turn-based voice: ${profile.sttModelId} transcribes your speech, ${model.id} answers, and your phone speaks the reply.$tools"
                    )
                }
            }
        }
    }

    fun chat(model: ModelInfo?): ChatEligibility {
        if (model == null) return ChatEligibility(false, "Choose a model first.")
        return if (model.support(Capability.TEXT) == Support.NO) {
            ChatEligibility(false, "${model.id} can't generate text: ${model.result(Capability.TEXT).note.ifBlank { "the provider doesn't list it as a chat model." }}")
        } else ChatEligibility(true, "")
    }

    fun imageInput(model: ModelInfo?): ChatEligibility = needs(model, Capability.IMAGE_INPUT, "image input", "Run \"Check compatibility\" to find out.")

    fun pdfInput(model: ModelInfo?): ChatEligibility = needs(model, Capability.FILE_INPUT, "PDF/file input", "Run \"Check compatibility\" to find out.")

    private fun needs(model: ModelInfo?, c: Capability, what: String, hint: String): ChatEligibility {
        if (model == null) return ChatEligibility(false, "Choose a model first.")
        return when (model.support(c)) {
            Support.YES -> ChatEligibility(true, "")
            Support.NO -> ChatEligibility(false, "${model.id} doesn't support $what.")
            Support.UNKNOWN -> ChatEligibility(false, "$what hasn't been verified for ${model.id}. $hint")
        }
    }

    /** One short line per capability for the model selector. */
    fun badges(model: ModelInfo): List<Pair<Capability, Support>> =
        listOf(
            Capability.TEXT, Capability.STREAMING, Capability.TOOLS, Capability.IMAGE_INPUT,
            Capability.FILE_INPUT, Capability.REALTIME_VOICE
        ).map { it to model.support(it) }

    fun summary(profile: ProviderProfile, model: ModelInfo): String {
        val chat = chat(model)
        val voice = voice(profile, model)
        val chatText = if (chat.ok) "ChatBox ✓" else "ChatBox ✗"
        val voiceText = when (voice.mode) {
            VoiceMode.REALTIME_GEMINI_LIVE -> "Voice ✓ (real-time)"
            VoiceMode.TURN_BASED -> "Voice ✓ (turn-based)"
            VoiceMode.NONE -> "Voice ✗"
        }
        return "$chatText · $voiceText"
    }
}
