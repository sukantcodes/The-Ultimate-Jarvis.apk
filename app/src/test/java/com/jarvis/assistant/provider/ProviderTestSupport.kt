package com.jarvis.assistant.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A scripted transport: first matching rule wins. Records every request so tests can inspect what was sent. */
class FakeTransport : HttpTransport {

    class Rule(
        val matches: (HttpRequestSpec) -> Boolean,
        val respond: (HttpRequestSpec) -> HttpResponse
    )

    class StreamRule(
        val matches: (HttpRequestSpec) -> Boolean,
        val lines: List<String>,
        val failWith: Exception? = null,
        /** Throw after emitting [lines] (a connection dropped mid-answer). */
        val failAfter: Exception? = null,
        val delayEachMs: Long = 0L
    )

    val requests = ArrayList<HttpRequestSpec>()
    private val rules = ArrayList<Rule>()
    private val streamRules = ArrayList<StreamRule>()
    var wsResult: WsProbe = WsProbe(true, "setupComplete")
    var wsRequests = ArrayList<HttpRequestSpec>()

    fun on(matches: (HttpRequestSpec) -> Boolean, code: Int, body: String) {
        rules += Rule(matches) { HttpResponse(code, body) }
    }

    fun onUrl(contains: String, code: Int, body: String) = on({ it.url.contains(contains) }, code, body)

    fun onThrow(matches: (HttpRequestSpec) -> Boolean, e: TransportException) {
        rules += Rule(matches) { throw e }
    }

    fun stream(rule: StreamRule) {
        streamRules += rule
    }

    /** Forgets scripted stream answers so a test can make a failing provider "recover". */
    fun clearStreams() {
        streamRules.clear()
    }

    override suspend fun execute(request: HttpRequestSpec): HttpResponse {
        requests += request
        val rule = rules.firstOrNull { it.matches(request) }
            ?: return HttpResponse(404, """{"error":{"message":"no rule for ${request.url}"}}""")
        return rule.respond(request)
    }

    override fun streamLines(request: HttpRequestSpec): Flow<String> = flow {
        requests += request
        val rule = streamRules.firstOrNull { it.matches(request) }
            ?: throw HttpStatusException(404, """{"error":{"message":"no stream rule"}}""")
        rule.failWith?.let { throw it }
        for (line in rule.lines) {
            if (rule.delayEachMs > 0) kotlinx.coroutines.delay(rule.delayEachMs)
            emit(line)
        }
        rule.failAfter?.let { throw it }
    }

    override suspend fun probeWebSocket(request: HttpRequestSpec, firstMessage: String, timeoutMs: Long): WsProbe {
        wsRequests += request
        lastWsFirstMessage = firstMessage
        return wsResult
    }

    var lastWsFirstMessage: String = ""

    fun bodyOf(request: HttpRequestSpec): String = (request.body as? HttpBody.Text)?.content.orEmpty()
}

class MemoryStore : KeyValueStore {
    val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

class MemoryVault(override val available: Boolean = true) : KeyVault {
    val keys = HashMap<String, String>()
    override fun load(profileId: String): ApiKeySecret? = keys[profileId]?.let { ApiKeySecret.of(it) }
    override fun store(profileId: String, key: String): Boolean {
        if (!available) return false
        keys[profileId] = key
        return true
    }
    override fun delete(profileId: String) { keys.remove(profileId) }
}

object Fixtures {
    const val KEY = "sk-test-SECRET-1234567890"

    fun profile(
        id: String = "p1",
        base: String = "https://api.example.com/v1",
        protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE,
        auth: AuthMethod = AuthMethod.BEARER,
        model: String = "m1",
        models: List<ModelInfo> = listOf(ModelInfo("m1"))
    ) = ProviderProfile(
        id = id, name = "Test", baseUrl = base, protocol = protocol, authMethod = auth,
        models = models, selectedModelId = model
    )

    fun resolved(
        protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE,
        base: String = if (protocol == ProviderProtocol.GEMINI) "https://gw.example.com/v1beta" else "https://api.example.com/v1",
        auth: AuthSpec = AuthSpec(
            if (protocol == ProviderProtocol.GEMINI) AuthMethod.X_GOOG_API_KEY else AuthMethod.BEARER,
            "", ApiKeySecret.of(KEY)
        ),
        model: String = "m1",
        modelInfo: ModelInfo = ModelInfo(model),
        stt: String = "",
        settings: Map<String, String> = emptyMap(),
        realtimeUrl: String = ""
    ) = ResolvedProvider("p1", "Test", base, protocol, auth, model, modelInfo, realtimeUrl, stt, settings)

    fun openAiModels(vararg ids: String) =
        """{"object":"list","data":[${ids.joinToString(",") { """{"id":"$it","object":"model","owned_by":"acme"}""" }}]}"""

    fun completion(text: String) = """{"choices":[{"message":{"role":"assistant","content":${com.google.gson.JsonPrimitive(text)}}}]}"""

    fun sse(vararg deltas: String): List<String> =
        deltas.flatMap { listOf("data: {\"choices\":[{\"delta\":{\"content\":${com.google.gson.JsonPrimitive(it)}}}]}", "") } +
            listOf("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", "", "data: [DONE]", "")
}
