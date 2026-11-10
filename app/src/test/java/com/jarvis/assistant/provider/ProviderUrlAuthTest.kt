package com.jarvis.assistant.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderUrlAuthTest {

    private fun ok(raw: String) = (ProviderUrl.validateBase(raw) as ProviderUrl.Check.Ok).url
    private fun bad(raw: String) = (ProviderUrl.validateBase(raw) as ProviderUrl.Check.Bad).reason

    @Test fun https_is_required_and_http_is_explained() {
        assertTrue(bad("http://api.example.com/v1").contains("https://"))
        assertTrue(bad("ws://x.example.com").contains("https://"))
        assertTrue(bad("ftp://x.example.com").contains("Unsupported"))
        assertEquals("https://api.example.com/v1", ok("https://API.Example.com/v1/"))
    }

    @Test fun rejects_credentials_queries_fragments_and_garbage() {
        assertTrue(bad("https://user:pw@api.example.com").contains("API key field"))
        assertTrue(bad("https://api.example.com/v1?key=abc").contains("query"))
        assertTrue(bad("https://api.example.com/v1#frag").contains("fragment"))
        assertTrue(bad("").isNotBlank())
        assertTrue(bad("   ").isNotBlank())
        assertTrue(bad("api.example.com").contains("https://"))
        assertTrue(bad("https://api example.com").contains("spaces"))
        assertTrue(bad("https://").isNotBlank())
    }

    @Test fun pasted_endpoint_paths_are_trimmed_back_to_the_base() {
        assertEquals("https://api.example.com/v1", ok("https://api.example.com/v1/chat/completions"))
        assertEquals("https://api.example.com/v1", ok("https://api.example.com/v1/models/"))
        assertEquals("https://api.example.com", ok("https://api.example.com/"))
        assertEquals("https://api.example.com:8443/openai/v1", ok("https://api.example.com:8443/openai/v1"))
    }

    @Test fun discovery_path_override_never_leaves_the_provider_host() {
        val base = "https://api.example.com/v1"
        assertEquals("https://api.example.com/v1/api/tags", ProviderUrl.resolveSameHost(base, "/api/tags"))
        assertEquals("https://api.example.com/other/list", ProviderUrl.resolveSameHost(base, "https://api.example.com/other/list"))
        assertNull(ProviderUrl.resolveSameHost(base, "https://evil.example.net/models"))
        assertNull(ProviderUrl.resolveSameHost(base, "http://api.example.com/models"))
        assertNull(ProviderUrl.resolveSameHost(base, "api tags"))
    }

    @Test fun gemini_live_url_is_derived_or_overridden() {
        assertEquals(
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            (ProviderUrl.geminiLiveUrl("https://generativelanguage.googleapis.com/v1beta", "") as ProviderUrl.Check.Ok).url
        )
        assertEquals(
            "wss://gw.example.com/prefix/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent",
            (ProviderUrl.geminiLiveUrl("https://gw.example.com/prefix/v1alpha", "") as ProviderUrl.Check.Ok).url
        )
        assertEquals(
            "wss://gw.example.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            (ProviderUrl.geminiLiveUrl("https://gw.example.com", "") as ProviderUrl.Check.Ok).url
        )
        assertEquals("wss://live.example.com/socket", (ProviderUrl.geminiLiveUrl("https://x.com", "wss://live.example.com/socket") as ProviderUrl.Check.Ok).url)
        assertTrue(ProviderUrl.geminiLiveUrl("https://x.com", "ws://live.example.com") is ProviderUrl.Check.Bad)
    }

    @Test fun gemini_model_names_are_normalised() {
        assertEquals("models/gemini-x", ProviderUrl.geminiModelName("gemini-x"))
        assertEquals("models/gemini-x", ProviderUrl.geminiModelName("models/gemini-x"))
        assertEquals("tunedModels/mine", ProviderUrl.geminiModelName("tunedModels/mine"))
    }

    @Test fun query_values_are_redacted_for_logging() {
        assertEquals("https://h/x?key=***&alt=***", ProviderUrl.redactQuery("https://h/x?key=SECRET&alt=sse"))
        val spec = HttpRequestSpec("GET", "https://h/x?key=SECRET", mapOf("Authorization" to "Bearer SECRET"))
        assertFalse(spec.toString().contains("SECRET"))
    }

    // ---- authentication -------------------------------------------------------------------------------

    private fun req() = HttpRequestSpec("GET", "https://api.example.com/v1/models")
    private fun auth(m: AuthMethod, name: String = "") = AuthSpec(m, name, ApiKeySecret.of(Fixtures.KEY))

    @Test fun each_auth_method_attaches_the_key_the_right_way() {
        assertEquals("Bearer ${Fixtures.KEY}", ProviderAuth.apply(req(), auth(AuthMethod.BEARER)).headers["Authorization"])
        assertEquals(Fixtures.KEY, ProviderAuth.apply(req(), auth(AuthMethod.X_API_KEY)).headers["x-api-key"])
        assertEquals(Fixtures.KEY, ProviderAuth.apply(req(), auth(AuthMethod.X_GOOG_API_KEY)).headers["x-goog-api-key"])
        assertEquals(Fixtures.KEY, ProviderAuth.apply(req(), auth(AuthMethod.CUSTOM_HEADER, "X-Acme-Token")).headers["X-Acme-Token"])
        val q = ProviderAuth.apply(req(), auth(AuthMethod.QUERY_PARAM, "api_key"))
        assertEquals("https://api.example.com/v1/models?api_key=${Fixtures.KEY}", q.url)
        assertTrue(q.headers.isEmpty())
        assertTrue(ProviderAuth.apply(req(), AuthSpec(AuthMethod.NONE)).headers.isEmpty())
    }

    @Test fun query_param_joins_existing_queries_and_url_encodes() {
        val r = HttpRequestSpec("GET", "https://h/x?alt=sse")
        val out = ProviderAuth.apply(r, AuthSpec(AuthMethod.QUERY_PARAM, "key", ApiKeySecret.of("a b&c")))
        assertEquals("https://h/x?alt=sse&key=a+b%26c", out.url)
    }

    @Test fun missing_key_is_a_clear_config_error_not_a_crash() {
        val e = runCatching { ProviderAuth.apply(req(), AuthSpec(AuthMethod.BEARER, "", null)) }.exceptionOrNull() as ProviderException
        assertEquals(ProviderErrorKind.INVALID_CONFIG, e.error.kind)
    }

    @Test fun key_never_appears_in_toString_or_error_text() {
        val spec = auth(AuthMethod.BEARER)
        assertFalse(spec.toString().contains(Fixtures.KEY))
        assertFalse(spec.secret.toString().contains(Fixtures.KEY))
        val err = ProviderErrors.fromHttp(401, """{"error":{"message":"Incorrect API key provided: ${Fixtures.KEY}"}}""", Fixtures.KEY, ErrorContext.CHAT)
        assertFalse(err.message.contains(Fixtures.KEY))
        assertTrue(err.message.contains("***"))
    }

    @Test fun wiping_a_secret_clears_it() {
        val s = ApiKeySecret.of("abcdef")
        s.wipe()
        assertTrue(s.isBlank)
        assertEquals("", s.reveal())
    }

    @Test fun auth_settings_are_validated() {
        assertNotNull(ProviderAuth.validateSettings(AuthMethod.CUSTOM_HEADER, ""))
        assertNotNull(ProviderAuth.validateSettings(AuthMethod.CUSTOM_HEADER, "Bad Header"))
        assertNotNull(ProviderAuth.validateSettings(AuthMethod.CUSTOM_HEADER, "Content-Type"))
        assertNull(ProviderAuth.validateSettings(AuthMethod.CUSTOM_HEADER, "X-Acme-Token"))
        assertNotNull(ProviderAuth.validateSettings(AuthMethod.QUERY_PARAM, ""))
        assertNotNull(ProviderAuth.validateSettings(AuthMethod.QUERY_PARAM, "a=b"))
        assertNull(ProviderAuth.validateSettings(AuthMethod.QUERY_PARAM, "api_key"))
        assertNull(ProviderAuth.validateSettings(AuthMethod.BEARER, ""))
    }

    @Test fun keys_with_line_breaks_or_control_characters_are_refused() {
        assertNotNull(ProviderAuth.validateKeyText("abc\ndef"))
        assertNotNull(ProviderAuth.validateKeyText("abc\u0000"))
        assertNotNull(ProviderAuth.validateKeyText("   "))
        assertNotNull(ProviderAuth.validateKeyText("clé"))
        assertNull(ProviderAuth.validateKeyText("  sk-abc_123  "))
    }

    @Test fun extra_headers_parse_and_cannot_override_credentials() {
        val (ok, problems) = ProviderAuth.parseExtraHeaders("HTTP-Referer: https://app\nX-Title: Jarvis\nAuthorization: Bearer evil\nnonsense\nHost: x")
        assertEquals(mapOf("HTTP-Referer" to "https://app", "X-Title" to "Jarvis"), ok)
        assertEquals(3, problems.size)
        val base = ProviderAuth.apply(req(), auth(AuthMethod.BEARER))
        val merged = ProviderAuth.withExtraHeaders(base, mapOf("authorization" to "Bearer evil", "X-Title" to "J"))
        assertEquals("Bearer ${Fixtures.KEY}", merged.headers["Authorization"])
        assertEquals("J", merged.headers["X-Title"])
    }

    @Test fun key_hint_shows_at_most_the_last_four_characters_of_long_keys() {
        assertEquals("••••••••7890", ProviderAuth.hint(ApiKeySecret.of(Fixtures.KEY)))
        assertEquals("••••••••", ProviderAuth.hint(ApiKeySecret.of("short")))
        assertEquals("", ProviderAuth.hint(null))
    }
}
