package com.jarvis.assistant.provider

/**
 * Core data model for user-defined AI providers ("custom providers").
 *
 * Nothing in this file touches Android, the network or secrets. API keys are never part of a
 * [ProviderProfile]; they live in the encrypted vault and travel only inside [AuthSpec].
 */

/** Wire protocol a provider speaks. Each protocol has its own client and (where possible) voice session. */
enum class ProviderProtocol(val id: String, val label: String) {
    /** `GET {base}/models`, `POST {base}/chat/completions` (SSE streaming). Most gateways and clouds. */
    OPENAI_COMPATIBLE("openai", "OpenAI-compatible"),

    /** `GET {base}/models`, `:generateContent` / `:streamGenerateContent`, and the Live (WebSocket) API. */
    GEMINI("gemini", "Gemini-compatible");

    companion object {
        fun fromId(id: String?): ProviderProtocol = values().firstOrNull { it.id == id } ?: OPENAI_COMPATIBLE
    }
}

/** How the endpoint API key is attached to a request. */
enum class AuthMethod(val id: String, val label: String) {
    NONE("none", "No authentication"),
    BEARER("bearer", "Authorization: Bearer"),
    X_API_KEY("x-api-key", "x-api-key header"),
    X_GOOG_API_KEY("x-goog-api-key", "x-goog-api-key header"),
    CUSTOM_HEADER("custom-header", "Custom header"),
    QUERY_PARAM("query", "Query parameter (only if required)");

    val needsName: Boolean get() = this == CUSTOM_HEADER || this == QUERY_PARAM

    companion object {
        fun fromId(id: String?): AuthMethod = values().firstOrNull { it.id == id } ?: BEARER
    }
}

enum class Capability(val label: String) {
    TEXT("Text"),
    STREAMING("Streaming"),
    TOOLS("Tools"),
    IMAGE_INPUT("Image input"),
    FILE_INPUT("Files / PDF"),
    AUDIO_INPUT("Audio input"),
    AUDIO_OUTPUT("Audio output"),
    REALTIME_VOICE("Real-time voice")
}

enum class Support { YES, NO, UNKNOWN }

/** Where a capability verdict came from. Names alone are never used as evidence. */
enum class Evidence {
    /** The provider's own model list said so (for example Gemini `supportedGenerationMethods`). */
    METADATA,

    /** JARVIS sent a real request and looked at the answer. */
    PROBE,

    /** Not established. */
    UNVERIFIED
}

data class CapabilityResult(
    val support: Support,
    val evidence: Evidence,
    val note: String = ""
) {
    companion object {
        val UNKNOWN = CapabilityResult(Support.UNKNOWN, Evidence.UNVERIFIED)
    }
}

/** A model as reported by the provider (or typed in manually). */
data class ModelInfo(
    val id: String,
    val displayName: String = "",
    val ownedBy: String = "",
    val contextWindow: Long = 0L,
    /** Raw Gemini `supportedGenerationMethods`, kept so capability can be re-derived. */
    val supportedMethods: List<String> = emptyList(),
    val capabilities: Map<Capability, CapabilityResult> = emptyMap(),
    /** True when the user typed this id because discovery was unavailable. Never shown as a provider result. */
    val manual: Boolean = false
) {
    fun result(capability: Capability): CapabilityResult = capabilities[capability] ?: CapabilityResult.UNKNOWN
    fun support(capability: Capability): Support = result(capability).support
    val label: String get() = displayName.ifBlank { id }
}

data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE,
    val authMethod: AuthMethod = AuthMethod.BEARER,
    /** Header name for [AuthMethod.CUSTOM_HEADER] or parameter name for [AuthMethod.QUERY_PARAM]. */
    val authName: String = "",
    /** Optional model-list path override: a path relative to [baseUrl], or an absolute https URL on the same host. */
    val discoveryPath: String = "",
    /** Optional wss:// URL for the Gemini Live protocol when it cannot be derived from [baseUrl]. */
    val realtimeUrl: String = "",
    val models: List<ModelInfo> = emptyList(),
    val selectedModelId: String = "",
    /** Optional different model for ChatBox. Blank means ChatBox uses [selectedModelId]. */
    val chatModelId: String = "",
    /** Speech-to-text model on this provider, used by the turn-based voice session. */
    val sttModelId: String = "",
    /** Whether the provider's speech-to-text endpoint was verified with this STT model. */
    val sttSupport: Support = Support.UNKNOWN,
    val lastRefreshAt: Long = 0L,
    val lastRefreshStatus: String = "",
    /** Provider-specific parameters: temperature, max_tokens, extra_headers. */
    val settings: Map<String, String> = emptyMap()
) {
    val displayName: String get() = name.ifBlank { ProviderUrl.hostOf(baseUrl) ?: "Custom provider" }

    fun model(id: String): ModelInfo? = models.firstOrNull { it.id == id }

    val selectedModel: ModelInfo?
        get() = selectedModelId.takeIf { it.isNotBlank() }?.let { model(it) ?: ModelInfo(id = it, manual = true) }

    val chatModel: ModelInfo?
        get() {
            val id = chatModelId.ifBlank { selectedModelId }
            return id.takeIf { it.isNotBlank() }?.let { model(it) ?: ModelInfo(id = it, manual = true) }
        }

    companion object {
        const val SETTING_TEMPERATURE = "temperature"
        const val SETTING_MAX_TOKENS = "max_tokens"
        const val SETTING_EXTRA_HEADERS = "extra_headers"
    }
}

/**
 * A key held as characters so it can be wiped. [reveal] produces a short-lived String only at the moment a
 * request is built. It never appears in [toString], logs, or error text.
 */
class ApiKeySecret(private var chars: CharArray) {
    val isBlank: Boolean get() = chars.isEmpty()

    fun reveal(): String = String(chars)

    fun wipe() {
        chars.fill('\u0000')
        chars = CharArray(0)
    }

    override fun toString(): String = "ApiKeySecret(***)"

    companion object {
        fun of(value: String): ApiKeySecret = ApiKeySecret(value.trim().toCharArray())
    }
}

data class AuthSpec(
    val method: AuthMethod,
    val name: String = "",
    val secret: ApiKeySecret? = null
) {
    override fun toString(): String = "AuthSpec($method, name=$name, secret=***)"
}

/**
 * An immutable snapshot of everything a runtime needs to talk to the active provider. A conversation takes one
 * snapshot when it starts, so switching providers can never change the model in the middle of a response.
 */
data class ResolvedProvider(
    val profileId: String,
    val name: String,
    val baseUrl: String,
    val protocol: ProviderProtocol,
    val auth: AuthSpec,
    val modelId: String,
    val model: ModelInfo,
    val realtimeUrl: String,
    val sttModelId: String,
    val settings: Map<String, String>,
    /** Static extra headers parsed from the `extra_headers` setting. Never contains the API key. */
    val extraHeaders: Map<String, String> = emptyMap()
) {
    val temperature: Double? get() = settings[ProviderProfile.SETTING_TEMPERATURE]?.toDoubleOrNull()
    val maxTokens: Int? get() = settings[ProviderProfile.SETTING_MAX_TOKENS]?.toIntOrNull()?.takeIf { it > 0 }
    val label: String get() = "$name · $modelId"
}

enum class VoiceMode {
    /** Provider speaks the Gemini Live WebSocket protocol; audio in and out in real time. */
    REALTIME_GEMINI_LIVE,

    /** Provider speech-to-text -> chat model with tools -> on-device speech. */
    TURN_BASED,

    NONE
}

data class VoiceEligibility(val mode: VoiceMode, val reason: String) {
    val canRunVoice: Boolean get() = mode != VoiceMode.NONE
}

data class ChatEligibility(val ok: Boolean, val reason: String)
