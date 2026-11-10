package com.jarvis.assistant.provider

import kotlinx.coroutines.CancellationException

enum class StepStatus { PASS, WARN, FAIL, SKIPPED }

data class TestStep(val title: String, val status: StepStatus, val detail: String)

data class TestReport(
    val steps: List<TestStep>,
    /** Models the provider reported during the test, when discovery worked. */
    val models: List<ModelInfo>?,
    val latencyMs: Long?,
    val discoveryUnsupported: Boolean
) {
    val ok: Boolean get() = steps.none { it.status == StepStatus.FAIL }

    val summary: String
        get() {
            val failed = steps.firstOrNull { it.status == StepStatus.FAIL }
            if (failed != null) return "${failed.title}: ${failed.detail}"
            val parts = ArrayList<String>()
            models?.let { parts += "${it.size} model${if (it.size == 1) "" else "s"} found" }
            latencyMs?.let { parts += "model answered in $it ms" }
            val warn = steps.firstOrNull { it.status == StepStatus.WARN }
            return (if (parts.isEmpty()) "Connected" else "Connected · " + parts.joinToString(" · ")) +
                (warn?.let { " (${it.detail})" } ?: "")
        }
}

/** Builds the immutable runtime snapshot used by tests, probes, chat and voice. */
object ProviderResolver {
    fun resolve(profile: ProviderProfile, secret: ApiKeySecret?, modelId: String): ResolvedProvider {
        val base = (ProviderUrl.validateBase(profile.baseUrl) as? ProviderUrl.Check.Ok)?.url ?: profile.baseUrl.trim()
        val model = profile.model(modelId) ?: ModelInfo(id = modelId, manual = true)
        val extra = ProviderAuth.parseExtraHeaders(profile.settings[ProviderProfile.SETTING_EXTRA_HEADERS].orEmpty()).first
        return ResolvedProvider(
            profileId = profile.id,
            name = profile.displayName,
            baseUrl = base,
            protocol = profile.protocol,
            auth = AuthSpec(profile.authMethod, profile.authName, secret),
            modelId = modelId,
            model = model,
            realtimeUrl = profile.realtimeUrl,
            sttModelId = profile.sttModelId,
            settings = profile.settings,
            extraHeaders = extra
        )
    }
}

/**
 * "Test Connection" for a custom provider. Every request goes to the provider's own endpoint with its own key.
 * Nothing here ever contacts Google or any other provider.
 */
class ProviderConnectionTester(
    private val transport: HttpTransport,
    private val discovery: ModelDiscoveryService = ModelDiscoveryService(transport)
) {

    suspend fun test(profile: ProviderProfile, secret: ApiKeySecret?, modelId: String): TestReport {
        val steps = ArrayList<TestStep>()
        fun add(title: String, status: StepStatus, detail: String) { steps += TestStep(title, status, detail) }

        // 1. Address
        val base = when (val check = ProviderUrl.validateBase(profile.baseUrl)) {
            is ProviderUrl.Check.Ok -> { add("Endpoint address", StepStatus.PASS, ProviderUrl.hostOf(check.url).orEmpty()); check.url }
            is ProviderUrl.Check.Bad -> {
                add("Endpoint address", StepStatus.FAIL, check.reason)
                return TestReport(steps, null, null, false)
            }
        }

        // 2. Credentials present and usable
        ProviderAuth.validateSettings(profile.authMethod, profile.authName)?.let {
            add("Authentication settings", StepStatus.FAIL, it)
            return TestReport(steps, null, null, false)
        }
        if (profile.authMethod == AuthMethod.NONE) {
            add("API key", StepStatus.WARN, "No authentication is configured")
        } else if (secret == null || secret.isBlank) {
            add("API key", StepStatus.FAIL, "No API key is saved for this provider.")
            return TestReport(steps, null, null, false)
        } else {
            add("API key", StepStatus.PASS, "Sent only to ${ProviderUrl.hostOf(base)} using ${profile.authMethod.label}")
        }
        val auth = AuthSpec(profile.authMethod, profile.authName, secret)
        val extra = ProviderAuth.parseExtraHeaders(profile.settings[ProviderProfile.SETTING_EXTRA_HEADERS].orEmpty()).first

        // 3. Reach the provider, authenticate and list models
        var discovered: List<ModelInfo>? = null
        var unsupported = false
        when (val outcome = discovery.discover(profile, auth, extra)) {
            is DiscoveryOutcome.Success -> {
                discovered = outcome.models
                add("Model list", StepStatus.PASS, "${outcome.models.size} models from ${ProviderUrl.hostOf(base)}")
            }
            is DiscoveryOutcome.Failure -> {
                val e = outcome.error
                when (e.kind) {
                    ProviderErrorKind.DISCOVERY_UNSUPPORTED, ProviderErrorKind.NO_MODELS -> {
                        unsupported = true
                        add("Model list", StepStatus.WARN, e.message)
                    }
                    else -> {
                        add("Model list", StepStatus.FAIL, e.message)
                        return TestReport(steps, null, null, false)
                    }
                }
            }
        }

        // 4. Is the chosen model offered?
        if (modelId.isBlank()) {
            add("Selected model", StepStatus.SKIPPED, "Choose a model to test it.")
            return TestReport(steps, discovered, null, unsupported)
        }
        val inList = discovered?.any { it.id == modelId }
        when (inList) {
            true -> add("Selected model", StepStatus.PASS, "$modelId is in the provider's list")
            false -> {
                add("Selected model", StepStatus.FAIL, "$modelId isn't in the provider's model list. Refresh the list and choose again.")
                return TestReport(steps, discovered, null, unsupported)
            }
            null -> add("Selected model", StepStatus.WARN, "$modelId was entered manually; the provider's list couldn't confirm it.")
        }

        // 5. Does the model actually answer?
        val provider = ProviderResolver.resolve(profile, secret, modelId)
        val eligibility = CapabilityPolicy.chat(discovered?.firstOrNull { it.id == modelId } ?: provider.model)
        if (!eligibility.ok) {
            add("Model responds", StepStatus.WARN, eligibility.reason)
            return TestReport(steps, discovered, null, unsupported)
        }
        val client = ChatClientFactory.create(profile.protocol, transport)
        val started = System.currentTimeMillis()
        var latency: Long? = null
        try {
            client.complete(ChatRequest(provider, "", listOf(ChatMessage(ChatRole.USER, "Reply with the single word OK.")), 16))
            latency = System.currentTimeMillis() - started
            add("Model responds", StepStatus.PASS, "$modelId answered in $latency ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            add("Model responds", StepStatus.FAIL, e.error.message)
        } catch (e: TransportException) {
            add("Model responds", StepStatus.FAIL, ProviderErrors.fromTransport(e).message)
        }
        return TestReport(steps, discovered, latency, unsupported)
    }
}
