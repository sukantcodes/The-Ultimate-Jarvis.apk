package com.jarvis.assistant.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The answer to "should ChatBox use a custom provider right now, and with what?" */
sealed class ChatResolution {
    /** No custom provider is active. Existing engines (Gemini, Groq, offline) behave exactly as before. */
    object NotActive : ChatResolution()

    class Ready(val provider: ResolvedProvider) : ChatResolution()

    /** A custom provider is active but can't be used. The reason is shown; nothing else is silently used instead. */
    class Blocked(val providerName: String, val reason: String) : ChatResolution()
}

sealed class VoiceResolution {
    object NotActive : VoiceResolution()
    class Ready(val provider: ResolvedProvider, val mode: VoiceMode, val explanation: String) : VoiceResolution()
    class Blocked(val providerName: String, val reason: String) : VoiceResolution()
}

sealed class ActivationResult {
    class Activated(val profile: ProviderProfile, val warning: String?) : ActivationResult()
    class Rejected(val reason: String) : ActivationResult()
}

/** Fired whenever the active provider, or the active provider's model or settings, changes. */
data class ActiveProviderState(val activeProfileId: String?, val revision: Long)

/** What ChatBox needs from the manager. Kept tiny so ChatBox logic can be tested with a fake. */
interface ChatConfigSource {
    fun resolveChat(): ChatResolution

    /** Same, for one kind of work. Sources without routing simply use the active provider. */
    fun resolveChat(category: WorkCategory): ChatResolution = resolveChat()
}

/** Decides which kind of work a ChatBox message is, so it can follow that category's route. */
object WorkClassifier {
    private val CODE_HINTS = listOf(
        "```", "stack trace", "exception", "compile error", "build error", "fix this bug", "debug", "refactor",
        "unit test", "kotlin", "python", "javascript", "typescript", "gradle", "function ", "class ", "regex",
        "clone", "repo", "github", "git ", "server", "terminal", "shell", "preview", "build", "compile", "npm ", "pip ", "bash"
    )

    fun classify(text: String, attachments: List<ChatAttachment>, hasDocument: Boolean): WorkCategory {
        if (attachments.any { it is ChatAttachment.Image }) return WorkCategory.IMAGE
        if (hasDocument || attachments.isNotEmpty()) return WorkCategory.FILE_ANALYSIS
        val lower = text.lowercase()
        return if (CODE_HINTS.any { lower.contains(it) }) WorkCategory.CODING else WorkCategory.CHAT
    }
}

/**
 * The single source of truth for the active custom provider, its model, endpoint, authentication, protocol and
 * capabilities. The voice service and ChatBox both ask this class; neither keeps its own copy.
 */
class ProviderConfigManager(
    private val repository: ProviderRepository,
    private val transport: HttpTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Multi-key pool. When keys are saved for the active provider, the chat engine leases one per request. */
    private val keyRotation: KeyRotationSource? = null,
    /** Per-category routes. When null every kind of work follows the active provider. */
    private val routes: RoutingStore? = null
) : ChatConfigSource {

    val discovery = ModelDiscoveryService(transport)
    val prober = CapabilityProber(transport)
    val tester = ProviderConnectionTester(transport, discovery)

    private val _state = MutableStateFlow(ActiveProviderState(repository.activeId(), 0L))
    val state: StateFlow<ActiveProviderState> = _state.asStateFlow()

    val canStoreKeys: Boolean get() = repository.canStoreKeys

    private fun bump() {
        _state.value = ActiveProviderState(repository.activeId(), _state.value.revision + 1)
    }

    // ------------------------------------------------------------------------------------------------------
    // Profiles
    // ------------------------------------------------------------------------------------------------------

    fun profiles(): List<ProviderProfile> = repository.list()

    fun profile(id: String): ProviderProfile? = repository.get(id)

    fun activeProfile(): ProviderProfile? = repository.activeId()?.let { repository.get(it) }

    fun newId(): String = repository.newId()

    fun keyHint(id: String): String = repository.keyHint(id)

    fun hasKey(id: String): Boolean = repository.hasKey(id)

    fun loadKey(id: String): ApiKeySecret? = repository.loadKey(id)

    /** Checks a profile before it is saved. Returns a plain-language problem, or null when it is fine. */
    fun validate(profile: ProviderProfile): String? {
        (ProviderUrl.validateBase(profile.baseUrl) as? ProviderUrl.Check.Bad)?.let { return it.reason }
        ProviderAuth.validateSettings(profile.authMethod, profile.authName)?.let { return it }
        if (profile.discoveryPath.isNotBlank()) {
            val base = (ProviderUrl.validateBase(profile.baseUrl) as ProviderUrl.Check.Ok).url
            if (ProviderUrl.resolveSameHost(base, profile.discoveryPath) == null) {
                return "The model-discovery path must be a path on this provider or an https address on the same host."
            }
        }
        if (profile.realtimeUrl.isNotBlank()) {
            (ProviderUrl.validateRealtime(profile.realtimeUrl) as? ProviderUrl.Check.Bad)?.let { return it.reason }
        }
        val headers = profile.settings[ProviderProfile.SETTING_EXTRA_HEADERS].orEmpty()
        if (headers.isNotBlank()) {
            ProviderAuth.parseExtraHeaders(headers).second.firstOrNull()?.let { return "Extra headers: $it" }
        }
        profile.settings[ProviderProfile.SETTING_TEMPERATURE]?.takeIf { it.isNotBlank() }?.let {
            val t = it.toDoubleOrNull()
            if (t == null || t < 0.0 || t > 2.0) return "Temperature must be a number between 0 and 2."
        }
        profile.settings[ProviderProfile.SETTING_MAX_TOKENS]?.takeIf { it.isNotBlank() }?.let {
            val n = it.toIntOrNull()
            if (n == null || n <= 0) return "Max tokens must be a positive whole number."
        }
        return null
    }

    /**
     * Saves a profile. [newKey] is stored encrypted only when the user typed one; a blank value keeps the saved
     * key. Returns a problem description, or null on success. Saving never changes which provider is active.
     */
    fun save(profile: ProviderProfile, newKey: String?): String? {
        validate(profile)?.let { return it }
        if (!newKey.isNullOrBlank()) {
            ProviderAuth.validateKeyText(newKey)?.let { return it }
            if (!repository.saveKey(profile.id, newKey)) {
                return "Secure storage is unavailable on this device, so the key was not saved. Keys are never stored as plain text."
            }
        }
        repository.upsert(profile)
        if (repository.activeId() == profile.id) bump()
        return null
    }

    /** Deletes a provider and its key. If it was active, JARVIS returns to its built-in engines (no silent switch to another custom one). */
    fun delete(id: String): Boolean {
        val wasActive = repository.delete(id)
        if (wasActive) bump()
        return wasActive
    }

    /** Stops using the custom provider; the built-in Gemini / Groq / offline behaviour applies again. */
    fun deactivate() {
        if (repository.activeId() != null) {
            repository.setActiveId(null)
            bump()
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Discovery, testing and probing (all against the provider being configured, never Google)
    // ------------------------------------------------------------------------------------------------------

    suspend fun fetchModels(draft: ProviderProfile, typedKey: String?): DiscoveryOutcome {
        val secret = secretFor(draft.id, typedKey)
        try {
            val extra = ProviderAuth.parseExtraHeaders(draft.settings[ProviderProfile.SETTING_EXTRA_HEADERS].orEmpty()).first
            val outcome = discovery.discover(draft, AuthSpec(draft.authMethod, draft.authName, secret), extra)
            return when (outcome) {
                is DiscoveryOutcome.Success -> outcome.copy(models = ModelDiscoveryService.mergeKnown(draft.models, outcome.models))
                else -> outcome
            }
        } finally {
            secret?.wipe()
        }
    }

    suspend fun testConnection(draft: ProviderProfile, typedKey: String?, modelId: String): TestReport {
        val secret = secretFor(draft.id, typedKey)
        try {
            return tester.test(draft, secret, modelId)
        } finally {
            secret?.wipe()
        }
    }

    suspend fun checkCompatibility(draft: ProviderProfile, typedKey: String?, modelId: String, force: Boolean = false): ProbeReport {
        val secret = secretFor(draft.id, typedKey)
        try {
            val provider = ProviderResolver.resolve(draft, secret, modelId)
            return prober.probe(provider, provider.model, draft.sttModelId, force)
        } finally {
            secret?.wipe()
        }
    }

    /** The key the user just typed wins; otherwise the saved one. */
    private fun secretFor(profileId: String, typedKey: String?): ApiKeySecret? =
        if (!typedKey.isNullOrBlank()) ApiKeySecret.of(typedKey) else repository.loadKey(profileId)

    // ------------------------------------------------------------------------------------------------------
    // Activation
    // ------------------------------------------------------------------------------------------------------

    /**
     * Makes a saved provider the active one after checking it: valid address, key present, a chosen model, and
     * (when the provider offers a model list and is reachable) that the model is still offered.
     * The previous provider stays active if this fails.
     */
    suspend fun activate(id: String): ActivationResult {
        val profile = repository.get(id) ?: return ActivationResult.Rejected("That provider no longer exists.")
        validate(profile)?.let { return ActivationResult.Rejected(it) }
        if (profile.selectedModelId.isBlank()) {
            return ActivationResult.Rejected("Choose a model for this provider before activating it.")
        }
        val secret = repository.loadKey(id)
        try {
            if (profile.authMethod != AuthMethod.NONE && (secret == null || secret.isBlank)) {
                return ActivationResult.Rejected("No API key is saved for this provider. Edit it and enter the key.")
            }
            var warning: String? = null
            var updated = profile
            val extra = ProviderAuth.parseExtraHeaders(profile.settings[ProviderProfile.SETTING_EXTRA_HEADERS].orEmpty()).first
            when (val outcome = discovery.discover(profile, AuthSpec(profile.authMethod, profile.authName, secret), extra)) {
                is DiscoveryOutcome.Success -> {
                    val merged = ModelDiscoveryService.mergeKnown(profile.models, outcome.models)
                    updated = profile.copy(models = merged, lastRefreshAt = clock(), lastRefreshStatus = "${merged.size} models")
                    repository.upsert(updated)
                    val needed = setOf(profile.selectedModelId, profile.chatModelId).filter { it.isNotBlank() }
                    val missing = needed.firstOrNull { wanted -> merged.none { it.id == wanted } }
                    if (missing != null) {
                        return ActivationResult.Rejected("\"$missing\" is no longer offered by ${profile.displayName}. Refresh the model list and choose another model.")
                    }
                }
                is DiscoveryOutcome.Failure -> {
                    val e = outcome.error
                    when {
                        e.isAuth -> return ActivationResult.Rejected(e.message)
                        e.kind == ProviderErrorKind.INVALID_ENDPOINT || e.kind == ProviderErrorKind.INVALID_CONFIG ||
                            e.kind == ProviderErrorKind.INVALID_RESPONSE || e.kind == ProviderErrorKind.REDIRECT_BLOCKED ->
                            return ActivationResult.Rejected(e.message)
                        e.kind == ProviderErrorKind.DISCOVERY_UNSUPPORTED || e.kind == ProviderErrorKind.NO_MODELS ->
                            warning = "This provider doesn't list its models, so the model ID couldn't be confirmed."
                        else -> warning = "Couldn't reach ${profile.displayName} to confirm the model (${e.message}). It was activated; the connection will report any problem."
                    }
                }
            }
            val chat = CapabilityPolicy.chat(updated.chatModel)
            if (!chat.ok) return ActivationResult.Rejected(chat.reason)
            repository.setActiveId(id)
            bump()
            return ActivationResult.Activated(updated, warning)
        } finally {
            secret?.wipe()
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Resolution used by the runtimes
    // ------------------------------------------------------------------------------------------------------

    override fun resolveChat(): ChatResolution = resolveChatFor(activeProfile(), null)

    override fun resolveChat(category: WorkCategory): ChatResolution =
        routes?.let { resolveForCategory(category, it) } ?: resolveChat()

    /**
     * Resolves the provider for one category of work. A saved route wins; a category without one follows the
     * active custom provider. A route to Gemini yields [ChatResolution.NotActive] so the built-in Gemini path
     * handles it, and a route to a provider that no longer exists is reported as blocked, never silently replaced.
     */
    fun resolveForCategory(category: WorkCategory, routes: RoutingStore): ChatResolution {
        val route = routes.get(category) ?: return resolveChat()
        if (route.isGemini) return ChatResolution.NotActive
        if (route.providerId.isBlank()) return resolveChatFor(activeProfile(), route.modelId.ifBlank { null })
        val profile = repository.get(route.providerId)
            ?: return ChatResolution.Blocked("Selected provider", "The provider chosen for ${category.label} was deleted. Choose another in Work Routing.")
        return resolveChatFor(profile, route.modelId.ifBlank { null })
    }

    private fun resolveChatFor(profile: ProviderProfile?, modelOverride: String?): ChatResolution {
        profile ?: return ChatResolution.NotActive
        val name = profile.displayName
        validate(profile)?.let { return ChatResolution.Blocked(name, it) }
        val model = (modelOverride?.let { profile.model(it) ?: ModelInfo(id = it, manual = true) } ?: profile.chatModel)
            ?: return ChatResolution.Blocked(name, "No model is selected for $name.")
        CapabilityPolicy.chat(model).let { if (!it.ok) return ChatResolution.Blocked(name, it.reason) }
        val secret = keyFor(profile) ?: run {
            // No single key, but the key manager holds a pool: the engine fills in a real key for each request.
            if (keyRotation?.hasKeys(profile.id) == true) {
                return ChatResolution.Ready(ProviderResolver.resolve(profile, ApiKeySecret(CharArray(0)), model.id))
            }
            return ChatResolution.Blocked(name, "No API key is saved for $name.")
        }
        return ChatResolution.Ready(ProviderResolver.resolve(profile, secret.takeIf { profile.authMethod != AuthMethod.NONE }, model.id))
    }

    fun resolveVoice(): VoiceResolution {
        val profile = activeProfile() ?: return VoiceResolution.NotActive
        val name = profile.displayName
        validate(profile)?.let { return VoiceResolution.Blocked(name, it) }
        val model = profile.selectedModel ?: return VoiceResolution.Blocked(name, "No model is selected for $name.")
        val eligibility = CapabilityPolicy.voice(profile, model)
        if (!eligibility.canRunVoice) return VoiceResolution.Blocked(name, eligibility.reason)
        val secret = keyFor(profile) ?: return VoiceResolution.Blocked(name, "No API key is saved for $name.")
        return VoiceResolution.Ready(
            ProviderResolver.resolve(profile, secret.takeIf { profile.authMethod != AuthMethod.NONE }, model.id),
            eligibility.mode,
            eligibility.reason
        )
    }

    /** Returns the saved key, or an empty secret for providers that need none. Null means "key required but missing". */
    private fun keyFor(profile: ProviderProfile): ApiKeySecret? {
        if (profile.authMethod == AuthMethod.NONE) return ApiKeySecret(CharArray(0))
        return repository.loadKey(profile.id)?.takeIf { !it.isBlank }
    }

    /** Persists capability results learned by a probe so the selector keeps showing them. */
    fun saveProbeResult(profileId: String, report: ProbeReport) {
        val profile = repository.get(profileId) ?: return
        val models = profile.models.toMutableList()
        val index = models.indexOfFirst { it.id == report.model.id }
        if (index >= 0) models[index] = report.model else models.add(report.model.copy(manual = true))
        var updated = profile.copy(models = models)
        if (report.sttSupport != null) updated = updated.copy(sttSupport = report.sttSupport)
        repository.upsert(updated)
        if (repository.activeId() == profileId) bump()
    }
}
