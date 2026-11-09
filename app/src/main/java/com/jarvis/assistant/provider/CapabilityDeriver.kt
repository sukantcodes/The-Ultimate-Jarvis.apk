package com.jarvis.assistant.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Reads capability facts that a provider volunteers in its own model list. It never guesses from a model's
 * name: anything the list does not state stays [Support.UNKNOWN] until a real probe settles it.
 */
object CapabilityDeriver {

    private fun yes(note: String = "") = CapabilityResult(Support.YES, Evidence.METADATA, note)
    private fun no(note: String = "") = CapabilityResult(Support.NO, Evidence.METADATA, note)

    /** Gemini lists `supportedGenerationMethods` per model. `bidiGenerateContent` is the Live (real-time) API. */
    fun fromGemini(methods: List<String>): Map<Capability, CapabilityResult> {
        if (methods.isEmpty()) return emptyMap()
        val set = methods.map { it.trim() }.toSet()
        val out = LinkedHashMap<Capability, CapabilityResult>()
        val live = "bidiGenerateContent" in set
        if ("generateContent" in set) {
            out[Capability.TEXT] = yes("generateContent")
            out[Capability.STREAMING] = yes("streamGenerateContent is offered for every generateContent model")
        } else {
            val why = if (live) "Live-only model: it can run voice but not ChatBox text."
            else "Not a text-generation model (supports: ${set.joinToString().take(80)})."
            out[Capability.TEXT] = no(why)
            out[Capability.STREAMING] = no(why)
        }
        if (live) {
            out[Capability.REALTIME_VOICE] = yes("bidiGenerateContent")
            out[Capability.AUDIO_INPUT] = yes("Live API accepts audio")
            out[Capability.AUDIO_OUTPUT] = yes("Live API returns audio")
        } else {
            out[Capability.REALTIME_VOICE] = no("This model does not list bidiGenerateContent (the Live API).")
        }
        return out
    }

    /**
     * OpenAI-style model entries usually carry no capability data. Gateways such as OpenRouter, Mistral and
     * Together add some; read exactly what is present and nothing more.
     */
    fun fromOpenAi(entry: JsonObject): Map<Capability, CapabilityResult> {
        val out = LinkedHashMap<Capability, CapabilityResult>()

        // OpenRouter style
        val architecture = entry.getAsJsonObjectOrNull("architecture")
        if (architecture != null) {
            val inputs = architecture.stringList("input_modalities")
            val outputs = architecture.stringList("output_modalities")
            if (outputs.isNotEmpty()) {
                if ("text" in outputs) out[Capability.TEXT] = yes("Provider lists text output")
                else out[Capability.TEXT] = no("Provider lists no text output (${outputs.joinToString()}).")
                if ("audio" in outputs) out[Capability.AUDIO_OUTPUT] = yes("Provider lists audio output")
            }
            if (inputs.isNotEmpty()) {
                out[Capability.IMAGE_INPUT] = if ("image" in inputs) yes("Provider lists image input") else no("Provider lists no image input.")
                out[Capability.FILE_INPUT] = if ("file" in inputs) yes("Provider lists file input") else no("Provider lists no file input.")
                out[Capability.AUDIO_INPUT] = if ("audio" in inputs) yes("Provider lists audio input") else no("Provider lists no audio input.")
            }
        }
        val params = entry.stringList("supported_parameters")
        if (params.isNotEmpty()) {
            out[Capability.TOOLS] = if ("tools" in params) yes("Provider lists tools") else no("Provider does not list tools.")
        }

        // Mistral style
        val caps = entry.getAsJsonObjectOrNull("capabilities")
        if (caps != null) {
            caps.boolOrNull("completion_chat")?.let { out[Capability.TEXT] = if (it) yes("completion_chat") else no("Not a chat model.") }
            caps.boolOrNull("function_calling")?.let { out[Capability.TOOLS] = if (it) yes("function_calling") else no("No function calling.") }
            caps.boolOrNull("vision")?.let { out[Capability.IMAGE_INPUT] = if (it) yes("vision") else no("No vision.") }
        }

        // Together / Fireworks style "type"
        val type = entry.stringOrNull("type")?.lowercase()
        if (type != null && Capability.TEXT !in out) {
            when (type) {
                "chat", "language", "text", "llm" -> out[Capability.TEXT] = yes("type=$type")
                "embedding", "embeddings", "image", "rerank", "moderation", "audio", "transcribe", "tts", "video" ->
                    out[Capability.TEXT] = no("Provider lists this as a \"$type\" model, not a chat model.")
            }
        }
        return out
    }

    fun contextWindowOf(entry: JsonObject): Long {
        for (key in listOf("inputTokenLimit", "context_length", "context_window", "max_context_length", "max_model_len")) {
            val v = entry.get(key)
            if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isNumber) return v.asLong
        }
        return 0L
    }
}

internal fun JsonObject.stringOrNull(key: String): String? {
    val v = get(key) ?: return null
    return if (v.isJsonPrimitive) v.asString else null
}

internal fun JsonObject.boolOrNull(key: String): Boolean? {
    val v = get(key) ?: return null
    return if (v.isJsonPrimitive && v.asJsonPrimitive.isBoolean) v.asBoolean else null
}

internal fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? {
    val v = get(key) ?: return null
    return if (v.isJsonObject) v.asJsonObject else null
}

internal fun JsonObject.getAsJsonArrayOrNull(key: String): JsonArray? {
    val v = get(key) ?: return null
    return if (v.isJsonArray) v.asJsonArray else null
}

internal fun JsonObject.stringList(key: String): List<String> {
    val arr = getAsJsonArrayOrNull(key) ?: return emptyList()
    return arr.mapNotNull { e: JsonElement -> if (e.isJsonPrimitive) e.asString.lowercase() else null }
}
