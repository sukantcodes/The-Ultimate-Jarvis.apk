package com.jarvis.assistant.provider

import com.google.gson.JsonParser
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClientsTest {

    private fun user(text: String, vararg a: ChatAttachment) = ChatMessage(ChatRole.USER, text, a.toList())
    private fun collect(t: FakeTransport, req: ChatRequest, gemini: Boolean = false): List<ChatEvent> = runBlocking {
        ChatClientFactory.create(if (gemini) ProviderProtocol.GEMINI else ProviderProtocol.OPENAI_COMPATIBLE, t).stream(req).toList()
    }
    private fun text(events: List<ChatEvent>) = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
    private fun errorOf(block: () -> Unit): ProviderError = (runCatching(block).exceptionOrNull() as ProviderException).error

    // ---- OpenAI-compatible -----------------------------------------------------------------------------

    @Test fun openai_request_targets_chat_completions_with_selected_model_and_multi_turn_context() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("Hel", "lo")))
        val req = ChatRequest(
            Fixtures.resolved(model = "my-model", settings = mapOf("temperature" to "0.3", "max_tokens" to "256")),
            "SYS",
            listOf(user("first"), ChatMessage(ChatRole.ASSISTANT, "answer"), user("follow-up"))
        )
        val events = collect(t, req)
        assertEquals("Hello", text(events))
        assertTrue(events.last() is ChatEvent.Done)
        val sent = t.requests.single()
        assertEquals("https://api.example.com/v1/chat/completions", sent.url)
        assertEquals("Bearer ${Fixtures.KEY}", sent.headers["Authorization"])
        assertEquals("text/event-stream", sent.headers["Accept"])
        val body = JsonParser.parseString(t.bodyOf(sent)).asJsonObject
        assertEquals("my-model", body["model"].asString)
        assertTrue(body["stream"].asBoolean)
        assertEquals(0.3, body["temperature"].asDouble, 0.0)
        assertEquals(256, body["max_tokens"].asInt)
        val roles = body["messages"].asJsonArray.map { it.asJsonObject["role"].asString }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
        assertEquals("follow-up", body["messages"].asJsonArray.last().asJsonObject["content"].asString)
    }

    @Test fun openai_omits_temperature_and_max_tokens_unless_the_user_configured_them() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("x")))
        collect(t, ChatRequest(Fixtures.resolved(), "", listOf(user("hi"))))
        val body = JsonParser.parseString(t.bodyOf(t.requests.single())).asJsonObject
        assertFalse(body.has("temperature"))
        assertFalse(body.has("max_tokens"))
        assertEquals("user", body["messages"].asJsonArray[0].asJsonObject["role"].asString)
    }

    @Test fun openai_images_and_pdfs_are_sent_in_the_standard_content_parts() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("ok")))
        collect(t, ChatRequest(Fixtures.resolved(), "", listOf(user("look", ChatAttachment.Image("image/png", "AAAA"), ChatAttachment.Pdf("a.pdf", "BBBB")))))
        val content = JsonParser.parseString(t.bodyOf(t.requests.single())).asJsonObject["messages"].asJsonArray[0].asJsonObject["content"].asJsonArray
        assertEquals(listOf("text", "image_url", "file"), content.map { it.asJsonObject["type"].asString })
        assertEquals("data:image/png;base64,AAAA", content[1].asJsonObject["image_url"].asJsonObject["url"].asString)
        assertEquals("a.pdf", content[2].asJsonObject["file"].asJsonObject["filename"].asString)
    }

    @Test fun openai_stream_ignores_comments_and_blank_lines_and_content_arrays() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, listOf(
            ": keep-alive", "", "event: message",
            """data: {"choices":[{"delta":{"role":"assistant","content":""}}]}""",
            """data: {"choices":[{"delta":{"content":[{"type":"text","text":"A"},{"type":"text","text":"B"}]}}]}""",
            "data: not json at all",
            """data: {"choices":[{"delta":{"content":"C"},"finish_reason":"stop"}]}""",
            "data: [DONE]"
        )))
        assertEquals("ABC", text(collect(t, ChatRequest(Fixtures.resolved(), "", listOf(user("hi"))))))
    }

    @Test fun openai_gateway_that_ignores_stream_true_still_yields_the_answer() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, listOf(Fixtures.completion("whole answer"))))
        assertEquals("whole answer", text(collect(t, ChatRequest(Fixtures.resolved(), "", listOf(user("hi"))))))
    }

    @Test fun openai_http_errors_during_streaming_become_specific_errors() {
        fun kind(code: Int, body: String = "{}"): ProviderErrorKind {
            val t = FakeTransport()
            t.stream(FakeTransport.StreamRule({ true }, emptyList(), failWith = HttpStatusException(code, body)))
            return errorOf { collect(t, ChatRequest(Fixtures.resolved(), "", listOf(user("hi")))) }.kind
        }
        assertEquals(ProviderErrorKind.AUTH_FAILED, kind(401))
        assertEquals(ProviderErrorKind.KEY_REJECTED, kind(403))
        assertEquals(ProviderErrorKind.RATE_LIMITED, kind(429))
        assertEquals(ProviderErrorKind.UNAVAILABLE, kind(502))
        assertEquals(ProviderErrorKind.MODEL_NOT_FOUND, kind(404))
        assertEquals(ProviderErrorKind.MODEL_NOT_FOUND, kind(400, """{"error":{"message":"The model `zzz` does not exist"}}"""))
        assertEquals(ProviderErrorKind.BAD_REQUEST, kind(400, """{"error":{"message":"messages too long"}}"""))
    }

    @Test fun openai_error_event_inside_a_200_stream_is_reported() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, listOf("""data: {"error":{"message":"overloaded"}}""")))
        val e = errorOf { collect(t, ChatRequest(Fixtures.resolved(), "", listOf(user("hi")))) }
        assertTrue(e.message.contains("overloaded"))
    }

    @Test fun dropped_connection_mid_answer_is_flagged_as_interrupted_and_keeps_what_arrived() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("par").take(2), failAfter = TransportException(TransportFailure.UNREACHABLE, "x")))
        val got = StringBuilder()
        val e = errorOf {
            runBlocking {
                OpenAiChatClient(t).stream(ChatRequest(Fixtures.resolved(), "", listOf(user("hi")))).collect {
                    if (it is ChatEvent.Delta) got.append(it.text)
                }
            }
        }
        assertEquals(ProviderErrorKind.STREAM_INTERRUPTED, e.kind)
        assertEquals("par", got.toString())
    }

    @Test fun unreachable_before_any_text_is_a_plain_network_error() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, emptyList(), failWith = TransportException(TransportFailure.TIMEOUT, "x")))
        assertEquals(ProviderErrorKind.TIMEOUT, errorOf { collect(t, ChatRequest(Fixtures.resolved(), "", listOf(user("hi")))) }.kind)
    }

    @Test fun an_empty_answer_is_an_error_not_a_blank_reply() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, listOf("data: [DONE]")))
        assertEquals(ProviderErrorKind.INVALID_RESPONSE, errorOf { collect(t, ChatRequest(Fixtures.resolved(), "", listOf(user("hi")))) }.kind)
    }

    @Test fun max_completion_tokens_fallback_is_used_once_when_the_provider_demands_it() {
        val t = FakeTransport()
        t.on({ it.url.endsWith("/chat/completions") && t.bodyOf(it).contains("\"max_tokens\"") }, 400,
            """{"error":{"message":"Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead."}}""")
        t.on({ it.url.endsWith("/chat/completions") }, 200, Fixtures.completion("OK"))
        val out = runBlocking { OpenAiChatClient(t).complete(ChatRequest(Fixtures.resolved(), "", listOf(user("hi")), maxTokens = 16)) }
        assertEquals("OK", out.text)
        assertEquals(2, t.requests.size)
        assertTrue(t.bodyOf(t.requests[1]).contains("max_completion_tokens"))
    }

    @Test fun complete_detects_tool_calls_and_rejects_garbage() {
        val t = FakeTransport()
        t.on({ true }, 200, """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"1","type":"function","function":{"name":"get_time","arguments":"{}"}}]}}]}""")
        val out = runBlocking { OpenAiChatClient(t).complete(ChatRequest(Fixtures.resolved(), "", listOf(user("hi")), includeProbeTool = true)) }
        assertTrue(out.hadToolCall)
        assertEquals("get_time", out.toolCalls.single().name)
        val body = JsonParser.parseString(t.bodyOf(t.requests.single())).asJsonObject
        assertEquals("get_time", body["tools"].asJsonArray[0].asJsonObject["function"].asJsonObject["name"].asString)

        val t2 = FakeTransport()
        t2.on({ true }, 200, "<html>nope</html>")
        assertEquals(ProviderErrorKind.INVALID_RESPONSE, errorOf { runBlocking { OpenAiChatClient(t2).complete(ChatRequest(Fixtures.resolved(), "", listOf(user("hi")))) } }.kind)
    }

    @Test fun openai_followup_encodes_assistant_tool_calls_and_tool_results() {
        val t = FakeTransport()
        t.on({ true }, 200, Fixtures.completion("Finished"))
        val call = ProviderToolCall("call-1", "read_file", JsonParser.parseString("""{"path":"notes.txt"}""").asJsonObject)
        runBlocking {
            OpenAiChatClient(t).complete(ChatRequest(
                Fixtures.resolved(), "", listOf(
                    user("Read my notes"),
                    ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(call)),
                    ChatMessage(ChatRole.TOOL, "The file says hello.", toolCallId = "call-1", toolName = "read_file")
                )
            ))
        }
        val messages = JsonParser.parseString(t.bodyOf(t.requests.single())).asJsonObject.getAsJsonArray("messages")
        assertEquals(listOf("user", "assistant", "tool"), messages.map { it.asJsonObject.get("role").asString })
        assertEquals("call-1", messages[1].asJsonObject.getAsJsonArray("tool_calls")[0].asJsonObject.get("id").asString)
        assertEquals("The file says hello.", messages[2].asJsonObject.get("content").asString)
    }

    @Test fun network_failure_during_complete_is_mapped_not_thrown_raw() {
        val t = FakeTransport()
        t.onThrow({ true }, TransportException(TransportFailure.TIMEOUT, "slow"))
        assertEquals(ProviderErrorKind.TIMEOUT, errorOf { runBlocking { OpenAiChatClient(t).complete(ChatRequest(Fixtures.resolved(), "", listOf(user("hi")))) } }.kind)
    }

    // ---- Gemini-compatible -----------------------------------------------------------------------------

    private fun gem(text: String, thought: Boolean = false, finish: String? = null) =
        """data: {"candidates":[{"content":{"role":"model","parts":[{"text":${com.google.gson.JsonPrimitive(text)}${if (thought) ""","thought":true""" else ""}}]}${if (finish != null) ""","finishReason":"$finish"""" else ""}}]}"""

    @Test fun gemini_request_uses_model_path_roles_system_instruction_and_goog_key_header() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, listOf(gem("Hi "), "", gem("there", finish = "STOP"))))
        val req = ChatRequest(
            Fixtures.resolved(protocol = ProviderProtocol.GEMINI, model = "gemini-custom", settings = mapOf("temperature" to "0.5")),
            "SYS",
            listOf(user("q"), ChatMessage(ChatRole.ASSISTANT, "a"), user("q2", ChatAttachment.Image("image/png", "ZZZ")))
        )
        assertEquals("Hi there", text(collect(t, req, gemini = true)))
        val sent = t.requests.single()
        assertEquals("https://gw.example.com/v1beta/models/gemini-custom:streamGenerateContent?alt=sse", sent.url)
        assertEquals(Fixtures.KEY, sent.headers["x-goog-api-key"])
        val body = JsonParser.parseString(t.bodyOf(sent)).asJsonObject
        assertEquals("SYS", body["systemInstruction"].asJsonObject["parts"].asJsonArray[0].asJsonObject["text"].asString)
        assertEquals(listOf("user", "model", "user"), body["contents"].asJsonArray.map { it.asJsonObject["role"].asString })
        assertEquals("ZZZ", body["contents"].asJsonArray[2].asJsonObject["parts"].asJsonArray[1].asJsonObject["inlineData"].asJsonObject["data"].asString)
        assertEquals(0.5, body["generationConfig"].asJsonObject["temperature"].asDouble, 0.0)
    }

    @Test fun gemini_models_prefix_is_not_doubled_and_query_param_auth_extends_the_sse_url() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, listOf(gem("x", finish = "STOP"))))
        val auth = AuthSpec(AuthMethod.QUERY_PARAM, "key", ApiKeySecret.of(Fixtures.KEY))
        collect(t, ChatRequest(Fixtures.resolved(protocol = ProviderProtocol.GEMINI, model = "models/g-1", auth = auth), "", listOf(user("hi"))), gemini = true)
        assertEquals("https://gw.example.com/v1beta/models/g-1:streamGenerateContent?alt=sse&key=${Fixtures.KEY}", t.requests.single().url)
        assertNull(t.requests.single().headers["x-goog-api-key"])
    }

    @Test fun gemini_thought_parts_are_hidden() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, listOf(gem("secret reasoning", thought = true), gem("Answer", finish = "STOP"))))
        assertEquals("Answer", text(collect(t, ChatRequest(Fixtures.resolved(protocol = ProviderProtocol.GEMINI), "", listOf(user("hi"))), gemini = true)))
    }

    @Test fun gemini_blocked_prompt_is_explained() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, listOf("""data: {"promptFeedback":{"blockReason":"SAFETY"}}""")))
        val e = errorOf { collect(t, ChatRequest(Fixtures.resolved(protocol = ProviderProtocol.GEMINI), "", listOf(user("hi"))), gemini = true) }
        assertTrue(e.message.contains("SAFETY"))
    }

    @Test fun gemini_complete_reads_text_and_function_calls() {
        val t = FakeTransport()
        t.on({ it.url.contains(":generateContent") }, 200, """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_time","args":{}}}]}}]}""")
        val out = runBlocking { GeminiChatClient(t).complete(ChatRequest(Fixtures.resolved(protocol = ProviderProtocol.GEMINI), "", listOf(user("hi")), includeProbeTool = true)) }
        assertTrue(out.hadToolCall)
        assertEquals("get_time", out.toolCalls.single().name)
        assertTrue(t.bodyOf(t.requests.single()).contains("functionDeclarations"))
    }

    @Test fun gemini_followup_encodes_function_call_and_response_parts() {
        val t = FakeTransport()
        t.on({ true }, 200, """{"candidates":[{"content":{"parts":[{"text":"Finished"}]}}]}""")
        val call = ProviderToolCall("g1", "read_file", JsonParser.parseString("""{"path":"notes.txt"}""").asJsonObject)
        runBlocking {
            GeminiChatClient(t).complete(ChatRequest(
                Fixtures.resolved(protocol = ProviderProtocol.GEMINI), "", listOf(
                    user("Read my notes"),
                    ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(call)),
                    ChatMessage(ChatRole.TOOL, "The file says hello.", toolCallId = "g1", toolName = "read_file")
                )
            ))
        }
        val contents = JsonParser.parseString(t.bodyOf(t.requests.single())).asJsonObject.getAsJsonArray("contents")
        assertEquals(listOf("user", "model", "user"), contents.map { it.asJsonObject.get("role").asString })
        assertTrue(contents[1].asJsonObject.getAsJsonArray("parts")[0].asJsonObject.has("functionCall"))
        assertEquals("read_file", contents[2].asJsonObject.getAsJsonArray("parts")[0].asJsonObject.getAsJsonObject("functionResponse").get("name").asString)
    }

    @Test fun gemini_stream_http_failure_is_mapped() {
        val t = FakeTransport()
        t.stream(FakeTransport.StreamRule({ true }, emptyList(), failWith = HttpStatusException(403, """{"error":{"message":"API key not valid"}}""")))
        val e = errorOf { collect(t, ChatRequest(Fixtures.resolved(protocol = ProviderProtocol.GEMINI), "", listOf(user("hi"))), gemini = true) }
        assertEquals(ProviderErrorKind.KEY_REJECTED, e.kind)
        assertTrue(e.message.contains("API key not valid"))
    }
}
