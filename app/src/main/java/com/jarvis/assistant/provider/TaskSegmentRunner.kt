package com.jarvis.assistant.provider

import com.jarvis.assistant.tools.ToolBackend
import com.jarvis.assistant.tools.tasks.SegmentRunner

/**
 * One stretch of autonomous work for [com.jarvis.assistant.tools.tasks.TaskEngine]: asks the provider routed for
 * "Task execution" (never silently another one), with key rotation, and lets it use the shared tools.
 */
class TaskSegmentRunner(
    private val config: ChatConfigSource,
    private val transport: HttpTransport,
    private val backend: ToolBackend,
    private val keyRotation: KeyRotationSource?,
    private val systemPrompt: () -> String
) : SegmentRunner {

    override suspend fun run(prompt: String, onActivity: (String) -> Unit): String {
        val provider = when (val r = config.resolveChat(WorkCategory.TASK_EXECUTION)) {
            ChatResolution.NotActive -> throw ProviderException(ProviderError(ProviderErrorKind.INVALID_CONFIG, "Long tasks need a custom provider. Activate one in API settings (or route \"Task execution\" to one)."))
            is ChatResolution.Blocked -> throw ProviderException(ProviderError(ProviderErrorKind.INVALID_CONFIG, "${r.providerName}: ${r.reason}"))
            is ChatResolution.Ready -> r.provider
        }
        val pool = keyRotation?.takeIf { it.hasKeys(provider.profileId) }
        val tried = HashSet<String>()
        val system = systemPrompt() + ProviderChatEngine.TOOL_PROMPT
        val messages = listOf(ChatMessage(ChatRole.USER, prompt))
        while (true) {
            var lease: KeyLease? = null
            val active = if (pool == null) provider else {
                lease = pool.lease(provider.profileId, tried) ?: throw ProviderException(ProviderError(ProviderErrorKind.RATE_LIMITED, pool.unavailableSummary(provider.profileId)))
                provider.copy(auth = AuthSpec(provider.auth.method, provider.auth.name, lease.secret),
                    modelId = lease.modelId.ifBlank { provider.modelId },
                    model = if (lease.modelId.isBlank()) provider.model else (provider.model.takeIf { it.id == lease.modelId } ?: ModelInfo(id = lease.modelId, manual = true)))
            }
            try {
                val loop = if (active.protocol == ProviderProtocol.GEMINI) GeminiToolLoop(transport, backend) else OpenAiToolLoop(transport, backend)
                val res = loop.run(ChatRequest(active, system, messages), "task", onActivity)
                lease?.let { pool?.reportSuccess(it.keyId) }
                return res.text + (res.stoppedEarly?.let { "\n$it" } ?: "")
            } catch (e: ProviderException) {
                val used = lease
                if (used != null && pool != null) {
                    val f = pool.reportFailure(used.keyId, e.error); tried += used.keyId
                    if (f.rotates && tried.size < ProviderChatEngine.MAX_KEY_ATTEMPTS) continue
                }
                throw e
            } finally { lease?.secret?.wipe() }
        }
    }
}
