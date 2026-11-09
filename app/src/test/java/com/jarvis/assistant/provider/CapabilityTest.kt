package com.jarvis.assistant.provider

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class CapabilityTest {

    private fun probe(t: FakeTransport, p: ResolvedProvider, known: ModelInfo = ModelInfo(p.modelId), stt: String = "", force: Boolean = false) =
        runBlocking { CapabilityProber(t).probe(p, known, stt, force) }

    /** A transport that behaves like an OpenAI-compatible provider whose model has the listed features. */
    private fun openAiProvider(tools: Boolean, image: Boolean, pdf: Boolean = false, streams: Boolean = true): FakeTransport {
        val t = FakeTransport()
        fun body(r: HttpRequestSpec) = t.bodyOf(r)
        t.on({ it.url.endsWith("/chat/completions") && body(it).contains("\"tools\"") && !tools }, 400,
            """{"error":{"message":"tools are not supported for this model"}}""")
        t.on({ it.url.endsWith("/chat/completions") && body(it).contains("image_url") && !image }, 400,
            """{"error":{"message":"this model does not support image input"}}""")
        t.on({ it.url.endsWith("/chat/completions") && body(it).contains("\"file\"") && !pdf }, 400,
            """{"error":{"message":"file content parts are not supported"}}""")
        t.on({ it.url.endsWith("/chat/completions") }, 200, Fixtures.completion("OK"))
        if (streams) t.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("1, 2, 3")))
        return t
    }

    @Test fun probing_sets_each_capability_from_what_the_provider_actually_did() {
        val t = openAiProvider(tools = true, image = false)
        val report = probe(t, Fixtures.resolved())
        val m = report.model
        assertEquals(Support.YES, m.support(Capability.TEXT))
        assertEquals(Support.YES, m.support(Capability.STREAMING))
        assertEquals(Support.YES, m.support(Capability.TOOLS))
        assertEquals(Support.NO, m.support(Capability.IMAGE_INPUT))
        assertEquals(Support.NO, m.support(Capability.FILE_INPUT))
        assertTrue(m.capabilities.values.all { it.evidence == Evidence.PROBE })
        assertTrue(m.result(Capability.IMAGE_INPUT).note.contains("image"))
        // OpenAI-compatible providers have no real-time protocol in JARVIS: never claimed.
        assertEquals(Support.UNKNOWN, m.support(Capability.REALTIME_VOICE))
    }

    @Test fun a_model_that_rejects_tools_is_marked_unable_to_use_tools() {
        val report = probe(openAiProvider(tools = false, image = true, pdf = true), Fixtures.resolved())
        assertEquals(Support.NO, report.model.support(Capability.TOOLS))
        assertEquals(Support.YES, report.model.support(Capability.IMAGE_INPUT))
        assertEquals(Support.YES, report.model.support(Capability.FILE_INPUT))
    }

    @Test fun the_probe_sends_a_real_valid_image_and_pdf() {
        val t = openAiProvider(tools = true, image = true, pdf = true)
        probe(t, Fixtures.resolved())
        val image = t.requests.map { t.bodyOf(it) }.first { it.contains("image_url") }
        val dataUrl = JsonParser.parseString(image).asJsonObject["messages"].asJsonArray[0].asJsonObject["content"].asJsonArray[1]
            .asJsonObject["image_url"].asJsonObject["url"].asString
        assertTrue(dataUrl.startsWith("data:image/png;base64,"))
        val png = java.util.Base64.getDecoder().decode(dataUrl.removePrefix("data:image/png;base64,"))
        val decoded = ImageIO.read(ByteArrayInputStream(png))
        assertNotNull("probe image must be a decodable PNG", decoded)
        assertEquals(64, decoded.width)
    }

    @Test fun rejected_credentials_stop_probing_and_claim_nothing() {
        val t = FakeTransport()
        t.on({ true }, 401, """{"error":{"message":"bad key"}}""")
        val report = probe(t, Fixtures.resolved())
        assertEquals(ProviderErrorKind.AUTH_FAILED, report.abort?.kind)
        assertTrue(report.steps.isEmpty())
        assertEquals(1, t.requests.size)
    }

    @Test fun a_model_the_provider_cannot_find_is_not_a_text_model_and_nothing_else_is_probed() {
        val t = FakeTransport()
        t.on({ true }, 404, """{"error":{"message":"The model `m1` does not exist"}}""")
        val report = probe(t, Fixtures.resolved())
        assertEquals(Support.NO, report.model.support(Capability.TEXT))
        assertEquals(1, t.requests.size)
    }

    @Test fun a_busy_provider_proves_nothing_either_way() {
        val t = FakeTransport()
        t.on({ true }, 429, "{}")
        val report = probe(t, Fixtures.resolved())
        assertEquals(Support.UNKNOWN, report.model.support(Capability.TEXT))
        assertEquals(Evidence.UNVERIFIED, report.model.result(Capability.TEXT).evidence)
    }

    @Test fun gemini_metadata_is_trusted_and_not_reprobed_but_live_is_verified_for_real() {
        val t = FakeTransport()
        t.on({ it.url.contains(":generateContent") }, 200, """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}""")
        t.stream(FakeTransport.StreamRule({ true }, listOf("""data: {"candidates":[{"content":{"parts":[{"text":"1"}]}}]}""")))
        val known = ModelInfo("g1", supportedMethods = listOf("generateContent", "bidiGenerateContent"),
            capabilities = CapabilityDeriver.fromGemini(listOf("generateContent", "bidiGenerateContent")))
        val report = probe(t, Fixtures.resolved(protocol = ProviderProtocol.GEMINI, model = "g1", modelInfo = known), known)
        assertEquals(Evidence.PROBE, report.model.result(Capability.REALTIME_VOICE).evidence)
        assertEquals(Support.YES, report.model.support(Capability.REALTIME_VOICE))
        // Streaming came from metadata, so no streaming request was spent on it.
        assertTrue(t.requests.none { it.url.contains("streamGenerateContent") })
        assertEquals(1, t.wsRequests.size)
        val ws = t.wsRequests.single()
        assertEquals("wss://gw.example.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent", ws.url)
        assertEquals(Fixtures.KEY, ws.headers["x-goog-api-key"])
        val setup = JsonParser.parseString(t.lastWsFirstMessage).asJsonObject["setup"].asJsonObject
        assertEquals("models/g1", setup["model"].asString)
        assertEquals("AUDIO", setup["generationConfig"].asJsonObject["responseModalities"].asJsonArray[0].asString)
    }

    @Test fun a_refused_live_connection_marks_real_time_voice_unsupported_with_the_reason() {
        val t = FakeTransport()
        t.on({ it.url.contains(":generateContent") }, 200, """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}""")
        t.stream(FakeTransport.StreamRule({ true }, listOf("""data: {"candidates":[{"content":{"parts":[{"text":"1"}]}}]}""")))
        t.wsResult = WsProbe(false, "model not found for bidiGenerateContent", 1008)
        val report = probe(t, Fixtures.resolved(protocol = ProviderProtocol.GEMINI, model = "manual-x"))
        assertEquals(Support.NO, report.model.support(Capability.REALTIME_VOICE))
        assertTrue(report.model.result(Capability.REALTIME_VOICE).note.contains("model not found"))
    }

    @Test fun live_models_that_do_not_list_the_live_api_are_never_probed_for_it() {
        val t = FakeTransport()
        t.on({ it.url.contains(":generateContent") }, 200, """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}""")
        t.stream(FakeTransport.StreamRule({ true }, listOf("""data: {"candidates":[{"content":{"parts":[{"text":"1"}]}}]}""")))
        val known = ModelInfo("txt", supportedMethods = listOf("generateContent"), capabilities = CapabilityDeriver.fromGemini(listOf("generateContent")))
        val report = probe(t, Fixtures.resolved(protocol = ProviderProtocol.GEMINI, model = "txt", modelInfo = known), known)
        assertTrue(t.wsRequests.isEmpty())
        assertEquals(Support.NO, report.model.support(Capability.REALTIME_VOICE))
    }

    @Test fun speech_to_text_is_checked_against_the_providers_own_transcription_endpoint() {
        fun sttSupport(code: Int, body: String = "{}"): Support {
            val t = openAiProvider(tools = true, image = true)
            t.on({ it.url.endsWith("/audio/transcriptions") }, code, body)
            val report = probe(t, Fixtures.resolved(stt = "whisper-x"), stt = "whisper-x")
            val stt = t.requests.first { it.url.endsWith("/audio/transcriptions") }
            val parts = (stt.body as HttpBody.Multipart).parts
            assertEquals("whisper-x", parts.first { it.name == "model" }.value)
            assertEquals("audio/wav", parts.first { it.name == "file" }.contentType)
            assertEquals("Bearer ${Fixtures.KEY}", stt.headers["Authorization"])
            return report.sttSupport!!
        }
        assertEquals(Support.YES, sttSupport(200, """{"text":""}"""))
        assertEquals(Support.NO, sttSupport(404))
        assertEquals(Support.NO, sttSupport(400, """{"error":{"message":"model whisper-x not found"}}"""))
        assertEquals(Support.UNKNOWN, sttSupport(503))
    }

    // ---- policy ----------------------------------------------------------------------------------------

    private fun profile(protocol: ProviderProtocol, stt: String = "", sttSupport: Support = Support.UNKNOWN) =
        Fixtures.profile(protocol = protocol).copy(sttModelId = stt, sttSupport = sttSupport)

    @Test fun text_only_gemini_model_is_chat_only_and_the_voice_refusal_says_so() {
        val m = ModelInfo("txt", capabilities = CapabilityDeriver.fromGemini(listOf("generateContent")))
        assertTrue(CapabilityPolicy.chat(m).ok)
        val v = CapabilityPolicy.voice(profile(ProviderProtocol.GEMINI), m)
        assertEquals(VoiceMode.NONE, v.mode)
        assertTrue(v.reason.contains("cannot power"))
        assertTrue(v.reason.contains("ChatBox"))
    }

    @Test fun live_capable_gemini_model_runs_realtime_voice() {
        val m = ModelInfo("live", capabilities = CapabilityDeriver.fromGemini(listOf("generateContent", "bidiGenerateContent")))
        assertEquals(VoiceMode.REALTIME_GEMINI_LIVE, CapabilityPolicy.voice(profile(ProviderProtocol.GEMINI), m).mode)
    }

    @Test fun live_only_model_cannot_be_used_in_chatbox() {
        val m = ModelInfo("liveonly", capabilities = CapabilityDeriver.fromGemini(listOf("bidiGenerateContent")))
        assertFalse(CapabilityPolicy.chat(m).ok)
        assertEquals(VoiceMode.REALTIME_GEMINI_LIVE, CapabilityPolicy.voice(profile(ProviderProtocol.GEMINI), m).mode)
    }

    @Test fun unverified_realtime_support_is_not_assumed() {
        val v = CapabilityPolicy.voice(profile(ProviderProtocol.GEMINI), ModelInfo("manual", manual = true))
        assertEquals(VoiceMode.NONE, v.mode)
        assertTrue(v.reason.contains("Check compatibility"))
    }

    @Test fun openai_compatible_voice_needs_a_verified_speech_to_text_model_and_is_turn_based() {
        val chatModel = ModelInfo("chat", capabilities = mapOf(Capability.TEXT to CapabilityResult(Support.YES, Evidence.PROBE)))
        assertEquals(VoiceMode.NONE, CapabilityPolicy.voice(profile(ProviderProtocol.OPENAI_COMPATIBLE), chatModel).mode)
        assertEquals(VoiceMode.NONE, CapabilityPolicy.voice(profile(ProviderProtocol.OPENAI_COMPATIBLE, "w"), chatModel).mode)
        assertEquals(VoiceMode.NONE, CapabilityPolicy.voice(profile(ProviderProtocol.OPENAI_COMPATIBLE, "w", Support.NO), chatModel).mode)
        val ok = CapabilityPolicy.voice(profile(ProviderProtocol.OPENAI_COMPATIBLE, "w", Support.YES), chatModel)
        assertEquals(VoiceMode.TURN_BASED, ok.mode)
        assertFalse(ok.reason.contains("unavailable"))
        val noTools = chatModel.copy(capabilities = chatModel.capabilities + (Capability.TOOLS to CapabilityResult(Support.NO, Evidence.PROBE)))
        assertTrue(CapabilityPolicy.voice(profile(ProviderProtocol.OPENAI_COMPATIBLE, "w", Support.YES), noTools).reason.contains("Phone actions are unavailable"))
    }

    @Test fun embedding_models_cannot_chat_or_talk() {
        val m = ModelInfo("embed", capabilities = mapOf(Capability.TEXT to CapabilityResult(Support.NO, Evidence.METADATA, "embedding")))
        assertFalse(CapabilityPolicy.chat(m).ok)
        assertEquals(VoiceMode.NONE, CapabilityPolicy.voice(profile(ProviderProtocol.OPENAI_COMPATIBLE, "w", Support.YES), m).mode)
    }

    @Test fun images_and_files_are_allowed_only_when_the_model_is_known_to_support_them() {
        val unknown = ModelInfo("m")
        assertFalse(CapabilityPolicy.imageInput(unknown).ok)
        assertTrue(CapabilityPolicy.imageInput(unknown).reason.contains("hasn't been verified"))
        val yes = ModelInfo("m", capabilities = mapOf(Capability.IMAGE_INPUT to CapabilityResult(Support.YES, Evidence.PROBE), Capability.FILE_INPUT to CapabilityResult(Support.NO, Evidence.PROBE)))
        assertTrue(CapabilityPolicy.imageInput(yes).ok)
        assertFalse(CapabilityPolicy.pdfInput(yes).ok)
        assertTrue(CapabilityPolicy.pdfInput(yes).reason.contains("doesn't support"))
    }

    // ---- generated probe files -------------------------------------------------------------------------

    @Test fun generated_pdf_has_correct_xref_offsets() {
        val text = String(ProbeAssets.pdf(), Charsets.US_ASCII)
        assertTrue(text.startsWith("%PDF-1.4"))
        val xref = Regex("startxref\\n(\\d+)").find(text)!!.groupValues[1].toInt()
        assertTrue(text.substring(xref).startsWith("xref"))
        val offsets = Regex("(\\d{10}) 00000 n").findAll(text).map { it.groupValues[1].toInt() }.toList()
        assertEquals(5, offsets.size)
        offsets.forEachIndexed { i, off -> assertTrue(text.substring(off).startsWith("${i + 1} 0 obj")) }
    }

    @Test fun generated_wav_header_is_valid() {
        val wav = ProbeAssets.silentWav(500, 16000)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(44 + 16000 / 2 * 2, wav.size)
    }
}
