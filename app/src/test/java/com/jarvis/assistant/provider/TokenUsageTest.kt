package com.jarvis.assistant.provider

import com.google.gson.JsonParser
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageTest {

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun user(text: String) = ChatMessage(ChatRole.USER, text)

    private fun collect(t: FakeTransport, gemini: Boolean = false): List<ChatEvent> = runBlocking {
        val protocol = if (gemini) ProviderProtocol.GEMINI else ProviderProtocol.OPENAI_COMPATIBLE
        ChatClientFactory.create(protocol, t).stream(ChatRequest(Fixtures.resolved(protocol = protocol), "", listOf(user("hi")))).toList()
    }

    private fun usages(events: List<ChatEvent>) = events.filterIsInstance<ChatEvent.Usage>().map { it.usage }

    private class MemoryTotal : ChatTokenLedger.Store {
        override var total: Int = 0
        override var approximate: Boolean = false
    }

    // ---- parsing ---------------------------------------------------------------------------------------

    @Test fun openai_usage_uses_the_providers_total_and_falls_back_to_the_sum() {
        assertEquals(
            TokenUsage(120, 30, 150),
            TokenUsageParser.openAi(json("""{"usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}}"""))
        )
        assertEquals(
            TokenUsage(120, 30, 150),
            TokenUsageParser.openAi(json("""{"usage":{"prompt_tokens":120,"completion_tokens":30}}"""))
        )
        // A provider total that is larger than the sum (hidden reasoning, say) is what gets billed, so it wins.
        assertEquals(
            TokenUsage(120, 30, 160),
            TokenUsageParser.openAi(json("""{"usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":160}}"""))
        )
    }

    @Test fun openai_usage_understands_groq_and_input_output_naming() {
        assertEquals(
            TokenUsage(10, 5, 15),
            TokenUsageParser.openAi(json("""{"x_groq":{"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}}"""))
        )
        assertEquals(
            TokenUsage(7, 3, 10),
            TokenUsageParser.openAi(json("""{"usage":{"input_tokens":7,"output_tokens":3}}"""))
        )
        assertEquals(40L, TokenUsageParser.openAiFromText("""{"usage":{"total_tokens":40}}""")?.total)
    }

    @Test fun missing_or_null_usage_is_not_invented() {
        assertNull(TokenUsageParser.openAi(null))
        assertNull(TokenUsageParser.openAi(json("""{"choices":[]}""")))
        assertNull(TokenUsageParser.openAi(json("""{"usage":null}""")))
        assertNull(TokenUsageParser.openAi(json("""{"usage":{}}""")))
        assertNull(TokenUsageParser.openAiFromText("not json"))
        assertNull(TokenUsageParser.gemini(json("""{"candidates":[]}""")))
        assertNull(TokenUsageParser.gemini(json("""{"usageMetadata":{}}""")))
    }

    @Test fun gemini_total_includes_thinking_and_tool_use_tokens() {
        assertEquals(
            TokenUsage(100, 70, 170),
            TokenUsageParser.gemini(json("""{"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":50,"totalTokenCount":170}}"""))
        )
        assertEquals(
            TokenUsage(110, 70, 180),
            TokenUsageParser.gemini(json("""{"usageMetadata":{"promptTokenCount":100,"toolUsePromptTokenCount":10,"candidatesTokenCount":20,"thoughtsTokenCount":50}}"""))
        )
    }

    // ---- clients ---------------------------------------------------------------------------------------

    @Test fun openai_stream_asks_for_usage_and_reports_it_before_done() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, listOf(
            """data: {"choices":[{"delta":{"content":"Hi"}}],"usage":null}""", "",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":null}""", "",
            """data: {"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":2,"total_tokens":13}}""", "",
            "data: [DONE]", ""
        )))
        val events = collect(t)
        val body = JsonParser.parseString(t.bodyOf(t.requests.single())).asJsonObject
        assertTrue(body["stream_options"].asJsonObject["include_usage"].asBoolean)
        assertEquals(listOf(TokenUsage(11, 2, 13)), usages(events))
        assertTrue(events.indexOfFirst { it is ChatEvent.Usage } < events.indexOfFirst { it === ChatEvent.Done })
        assertEquals("Hi", events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text })
    }

    @Test fun openai_stream_asks_again_without_stream_options_when_the_gateway_rejects_them() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule(
            { t.bodyOf(it).contains("stream_options") }, emptyList(),
            failWith = HttpStatusException(400, """{"error":{"message":"Unrecognized request argument supplied: stream_options"}}""")
        ))
        t.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("ok")))
        val events = collect(t)
        assertEquals("ok", events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text })
        assertTrue(usages(events).isEmpty())
        assertEquals(2, t.requests.size)
        assertFalse(t.bodyOf(t.requests[1]).contains("stream_options"))
    }

    @Test fun openai_stream_without_a_usage_block_reports_nothing() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("x")))
        assertTrue(usages(collect(t)).isEmpty())
    }

    @Test fun openai_complete_carries_the_providers_usage() {
        val t = FakeTransport()
        t.on({ it.url.endsWith("chat/completions") }, 200,
            """{"choices":[{"message":{"role":"assistant","content":"hi"}}],"usage":{"prompt_tokens":9,"completion_tokens":1,"total_tokens":10}}""")
        val out = runBlocking { OpenAiChatClient(t).complete(ChatRequest(Fixtures.resolved(), "", listOf(user("hi")))) }
        assertEquals(TokenUsage(9, 1, 10), out.usage)
    }

    @Test fun gemini_stream_reports_the_last_running_total() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, listOf(
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Hi "}]}}],"usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":1,"totalTokenCount":41}}""", "",
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"there"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":5,"thoughtsTokenCount":12,"totalTokenCount":57}}"""
        )))
        assertEquals(listOf(TokenUsage(40, 17, 57)), usages(collect(t, gemini = true)))
    }

    // ---- ledger ----------------------------------------------------------------------------------------

    @Test fun the_ledger_sums_every_request_and_keeps_the_total_in_its_store() {
        val s = MemoryTotal()
        ChatTokenLedger.attach(s)
        ChatTokenLedger.reset()
        ChatTokenLedger.record(TokenUsage(100, 20, 120))
        ChatTokenLedger.record(TokenUsage(150, 30, 180))
        assertEquals(300, s.total)
        assertFalse(s.approximate)
        assertEquals(ChatTokenLedger.Snapshot(300, false), ChatTokenLedger.state.value)
    }

    @Test fun an_estimate_marks_the_total_approximate_until_the_chat_is_cleared() {
        val s = MemoryTotal()
        ChatTokenLedger.attach(s)
        ChatTokenLedger.reset()
        ChatTokenLedger.record(TokenUsage(10, 5, 15))
        ChatTokenLedger.recordEstimate(40)
        assertEquals(55, s.total)
        assertTrue(s.approximate)
        ChatTokenLedger.reset()
        assertEquals(0, s.total)
        assertFalse(s.approximate)
        assertEquals(ChatTokenLedger.Snapshot(0, false), ChatTokenLedger.state.value)
    }

    @Test fun clearing_the_chat_elsewhere_starts_the_next_count_from_zero() {
        val s = MemoryTotal()
        ChatTokenLedger.attach(s)
        ChatTokenLedger.reset()
        ChatTokenLedger.record(TokenUsage(10, 5, 15))
        s.total = 0
        s.approximate = false
        ChatTokenLedger.record(TokenUsage(2, 1, 3))
        assertEquals(3, s.total)
    }

    @Test fun a_zero_or_negative_count_changes_nothing() {
        val s = MemoryTotal()
        ChatTokenLedger.attach(s)
        ChatTokenLedger.reset()
        ChatTokenLedger.record(TokenUsage(0, 0, 0))
        ChatTokenLedger.recordEstimate(0)
        assertEquals(0, s.total)
        assertFalse(s.approximate)
    }
}
