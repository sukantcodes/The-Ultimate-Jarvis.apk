package com.jarvis.assistant.provider

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDiscoveryTest {

    private val auth = AuthSpec(AuthMethod.BEARER, "", ApiKeySecret.of(Fixtures.KEY))
    private fun run(t: FakeTransport, p: ProviderProfile, a: AuthSpec = auth) =
        runBlocking { ModelDiscoveryService(t).discover(p, a) }

    private fun success(o: DiscoveryOutcome) = (o as DiscoveryOutcome.Success)
    private fun failure(o: DiscoveryOutcome) = (o as DiscoveryOutcome.Failure).error

    @Test fun openai_model_list_is_parsed_and_sent_with_bearer_auth_to_the_configured_host_only() {
        val t = FakeTransport()
        t.onUrl("/v1/models", 200, Fixtures.openAiModels("zeta", "alpha", "Mid"))
        val out = success(run(t, Fixtures.profile()))
        assertEquals(listOf("alpha", "Mid", "zeta"), out.models.map { it.id })
        assertEquals("acme", out.models[0].ownedBy)
        assertEquals(1, t.requests.size)
        assertEquals("https://api.example.com/v1/models", t.requests[0].url)
        assertEquals("Bearer ${Fixtures.KEY}", t.requests[0].headers["Authorization"])
    }

    @Test fun no_hardcoded_models_are_ever_invented() {
        val t = FakeTransport()
        t.onUrl("/models", 200, """{"data":[]}""")
        assertEquals(ProviderErrorKind.NO_MODELS, failure(run(t, Fixtures.profile())).kind)
    }

    @Test fun capability_unknown_when_the_provider_states_nothing_and_names_are_not_trusted() {
        val t = FakeTransport()
        t.onUrl("/models", 200, Fixtures.openAiModels("gpt-4o-realtime-preview", "whisper-1", "text-embedding-3-small"))
        val out = success(run(t, Fixtures.profile()))
        for (m in out.models) {
            assertEquals("${m.id} must stay unknown until probed", Support.UNKNOWN, m.support(Capability.TEXT))
            assertEquals(Support.UNKNOWN, m.support(Capability.REALTIME_VOICE))
        }
    }

    @Test fun openrouter_style_metadata_is_read_exactly_as_stated() {
        val t = FakeTransport()
        t.onUrl("/models", 200, """{"data":[
          {"id":"a/vision","context_length":128000,"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"supported_parameters":["tools","temperature"]},
          {"id":"a/plain","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"supported_parameters":["temperature"]},
          {"id":"a/embed","type":"embedding"}]}""")
        val models = success(run(t, Fixtures.profile())).models.associateBy { it.id }
        val vision = models.getValue("a/vision")
        assertEquals(128000L, vision.contextWindow)
        assertEquals(Support.YES, vision.support(Capability.IMAGE_INPUT))
        assertEquals(Support.YES, vision.support(Capability.TOOLS))
        assertEquals(Evidence.METADATA, vision.result(Capability.TOOLS).evidence)
        val plain = models.getValue("a/plain")
        assertEquals(Support.NO, plain.support(Capability.IMAGE_INPUT))
        assertEquals(Support.NO, plain.support(Capability.TOOLS))
        assertEquals(Support.NO, models.getValue("a/embed").support(Capability.TEXT))
    }

    @Test fun gemini_list_reads_supported_methods_and_follows_page_tokens() {
        val t = FakeTransport()
        t.on({ it.url.contains("pageToken=TOK2") }, 200, """{"models":[
            {"name":"models/gemini-live-x","displayName":"Live X","inputTokenLimit":32000,"supportedGenerationMethods":["bidiGenerateContent"]}]}""")
        t.on({ it.url.contains("/models") }, 200, """{"models":[
            {"name":"models/gemini-chat","displayName":"Chat","supportedGenerationMethods":["generateContent","countTokens","bidiGenerateContent"]},
            {"name":"models/embed-1","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"TOK2"}""")
        val p = Fixtures.profile(base = "https://gw.example.com/v1beta", protocol = ProviderProtocol.GEMINI, auth = AuthMethod.X_GOOG_API_KEY)
        val out = success(run(t, p, AuthSpec(AuthMethod.X_GOOG_API_KEY, "", ApiKeySecret.of(Fixtures.KEY))))
        assertEquals(2, out.pages)
        val m = out.models.associateBy { it.id }
        assertEquals(setOf("gemini-chat", "embed-1", "gemini-live-x"), m.keys)
        assertEquals("Chat", m.getValue("gemini-chat").displayName)
        assertEquals(Support.YES, m.getValue("gemini-chat").support(Capability.TEXT))
        assertEquals(Support.YES, m.getValue("gemini-chat").support(Capability.REALTIME_VOICE))
        assertEquals(Support.NO, m.getValue("embed-1").support(Capability.TEXT))
        assertEquals(Support.NO, m.getValue("embed-1").support(Capability.REALTIME_VOICE))
        assertEquals(Support.NO, m.getValue("gemini-live-x").support(Capability.TEXT))
        assertEquals(Support.YES, m.getValue("gemini-live-x").support(Capability.REALTIME_VOICE))
        assertTrue(t.requests[0].url.contains("pageSize=1000"))
        assertEquals(Fixtures.KEY, t.requests[0].headers["x-goog-api-key"])
        assertTrue(t.requests.all { it.url.startsWith("https://gw.example.com/") })
    }

    @Test fun openai_has_more_pagination_uses_after_cursor_and_stops() {
        val t = FakeTransport()
        t.on({ it.url.contains("after=b") }, 200, """{"data":[{"id":"c"}],"has_more":false}""")
        t.on({ it.url.contains("/models") }, 200, """{"data":[{"id":"a"},{"id":"b"}],"has_more":true,"last_id":"b"}""")
        val out = success(run(t, Fixtures.profile()))
        assertEquals(listOf("a", "b", "c"), out.models.map { it.id })
        assertEquals(2, out.pages)
    }

    @Test fun a_provider_that_repeats_the_same_page_cannot_loop_forever() {
        val t = FakeTransport()
        t.on({ it.url.contains("/models") }, 200, """{"data":[{"id":"a"}],"has_more":true,"last_id":"a"}""")
        val out = success(run(t, Fixtures.profile()))
        assertEquals(listOf("a"), out.models.map { it.id })
        assertTrue(t.requests.size <= 2)
    }

    @Test fun next_links_to_other_hosts_are_never_followed_so_the_key_cannot_leak() {
        val t = FakeTransport()
        t.on({ it.url.contains("api.example.com") }, 200, """{"data":[{"id":"a"}],"next":"https://evil.example.net/steal"}""")
        success(run(t, Fixtures.profile()))
        assertTrue(t.requests.none { it.url.contains("evil") })
    }

    @Test fun ollama_style_and_bare_array_shapes_are_understood() {
        val t = FakeTransport()
        t.onUrl("/api/tags", 200, """{"models":[{"name":"llama3:8b","model":"llama3:8b"},{"name":"qwen"}]}""")
        val p = Fixtures.profile().copy(discoveryPath = "/api/tags")
        assertEquals(listOf("llama3:8b", "qwen"), success(run(t, p)).models.map { it.id })
        assertEquals("https://api.example.com/v1/api/tags", t.requests[0].url)

        val t2 = FakeTransport()
        t2.onUrl("/models", 200, """["x","y"]""")
        assertEquals(listOf("x", "y"), success(run(t2, Fixtures.profile())).models.map { it.id })
    }

    @Test fun every_failure_has_a_specific_useful_message() {
        fun errorFor(code: Int, body: String = "{}"): ProviderError {
            val t = FakeTransport(); t.onUrl("/models", code, body); return failure(run(t, Fixtures.profile()))
        }
        assertEquals(ProviderErrorKind.AUTH_FAILED, errorFor(401).kind)
        assertEquals(ProviderErrorKind.KEY_REJECTED, errorFor(403).kind)
        assertEquals(ProviderErrorKind.DISCOVERY_UNSUPPORTED, errorFor(404).kind)
        assertEquals(ProviderErrorKind.DISCOVERY_UNSUPPORTED, errorFor(405).kind)
        assertEquals(ProviderErrorKind.RATE_LIMITED, errorFor(429).kind)
        assertEquals(ProviderErrorKind.UNAVAILABLE, errorFor(503).kind)
        assertEquals(ProviderErrorKind.REDIRECT_BLOCKED, errorFor(301).kind)
        assertEquals(ProviderErrorKind.INVALID_RESPONSE, errorFor(200, "<html>Login</html>").kind)
        assertEquals(ProviderErrorKind.INVALID_RESPONSE, errorFor(200, "not json {").kind)
        assertEquals(ProviderErrorKind.INVALID_RESPONSE, errorFor(200, """{"hello":"world"}""").kind)
        assertTrue(errorFor(200, "<html>Login</html>").message.contains("web page"))
    }

    @Test fun network_failures_map_to_timeout_and_unavailable() {
        val t = FakeTransport()
        t.onThrow({ true }, TransportException(TransportFailure.TIMEOUT, "t"))
        assertEquals(ProviderErrorKind.TIMEOUT, failure(run(t, Fixtures.profile())).kind)
        val t2 = FakeTransport()
        t2.onThrow({ true }, TransportException(TransportFailure.UNREACHABLE, "u"))
        assertEquals(ProviderErrorKind.UNAVAILABLE, failure(run(t2, Fixtures.profile())).kind)
        val t3 = FakeTransport()
        t3.onThrow({ true }, TransportException(TransportFailure.TLS, "tls"))
        assertEquals(ProviderErrorKind.TLS, failure(run(t3, Fixtures.profile())).kind)
    }

    @Test fun invalid_endpoint_never_touches_the_network() {
        val t = FakeTransport()
        val e = failure(run(t, Fixtures.profile(base = "http://insecure.example.com")))
        assertEquals(ProviderErrorKind.INVALID_ENDPOINT, e.kind)
        assertTrue(t.requests.isEmpty())
    }

    @Test fun a_key_echoed_in_an_error_body_is_redacted() {
        val t = FakeTransport()
        t.onUrl("/models", 401, """{"error":{"message":"Bad key ${Fixtures.KEY}"}}""")
        val msg = failure(run(t, Fixtures.profile())).message
        assertFalse(msg.contains(Fixtures.KEY))
    }

    @Test fun query_param_authentication_is_used_only_when_configured() {
        val t = FakeTransport()
        t.onUrl("/models", 200, Fixtures.openAiModels("a"))
        run(t, Fixtures.profile(auth = AuthMethod.QUERY_PARAM), AuthSpec(AuthMethod.QUERY_PARAM, "api_key", ApiKeySecret.of(Fixtures.KEY)))
        assertTrue(t.requests[0].url.endsWith("?api_key=${Fixtures.KEY}"))
        assertNull(t.requests[0].headers["Authorization"])
    }

    @Test fun refresh_keeps_earlier_probe_results_unless_the_provider_now_states_them() {
        val probed = ModelInfo("a", capabilities = mapOf(
            Capability.TOOLS to CapabilityResult(Support.YES, Evidence.PROBE, "ok"),
            Capability.IMAGE_INPUT to CapabilityResult(Support.NO, Evidence.PROBE, "no")
        ))
        val fresh = ModelInfo("a", capabilities = mapOf(Capability.IMAGE_INPUT to CapabilityResult(Support.YES, Evidence.METADATA)))
        val merged = ModelDiscoveryService.mergeKnown(listOf(probed), listOf(fresh, ModelInfo("b"))).associateBy { it.id }
        assertEquals(Support.YES, merged.getValue("a").support(Capability.TOOLS))
        assertEquals(Support.YES, merged.getValue("a").support(Capability.IMAGE_INPUT))
        assertEquals(Support.UNKNOWN, merged.getValue("b").support(Capability.TOOLS))
    }
}
