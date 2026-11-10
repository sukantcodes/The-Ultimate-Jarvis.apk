package com.jarvis.assistant.provider

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClozedAgentProtocolTest {

    private fun anthropicProvider(): ResolvedProvider = Fixtures.resolved(
        base = "https://api.anthropic.com/v1",
        auth = AuthSpec(AuthMethod.X_API_KEY, secret = ApiKeySecret.of(Fixtures.KEY))
    )

    @Test fun anthropic_consecutive_tool_results_are_grouped_into_one_user_message() = runBlocking {
        val transport = FakeTransport().apply {
            stream(FakeTransport.StreamRule({ true }, listOf("data: {\"type\":\"message_stop\"}")))
        }
        val call = ClozedToolCall("c1", "read", "{\"path\":\"a.txt\"}")
        val completion = ClozedLlm(transport).complete(
            anthropicProvider(),
            "system",
            listOf(
                ClozedMessage.User("inspect"),
                ClozedMessage.Assistant("", listOf(call)),
                ClozedMessage.ToolResult("c1", "read", "one"),
                ClozedMessage.ToolResult("c2", "read", "two"),
                ClozedMessage.ToolResult("c3", "read", "three")
            ),
            emptyList(), {}, {}
        )
        assertTrue(completion.calls.isEmpty())
        val body = JsonParser.parseString(transport.bodyOf(transport.requests.single())).asJsonObject
        val messages = body.getAsJsonArray("messages")
        assertEquals(3, messages.size())
        assertEquals("user", messages[0].asJsonObject.get("role").asString)
        assertEquals("assistant", messages[1].asJsonObject.get("role").asString)
        val grouped = messages[2].asJsonObject
        assertEquals("user", grouped.get("role").asString)
        assertEquals(3, grouped.getAsJsonArray("content").size())
        assertEquals(listOf("one", "two", "three"), grouped.getAsJsonArray("content").map {
            it.asJsonObject.get("content").asString
        })
        assertEquals("https://api.anthropic.com/v1/messages", transport.requests.single().url)
        assertEquals(Fixtures.KEY, transport.requests.single().headers["x-api-key"])
    }

    @Test fun openai_blank_assistant_tool_call_content_is_json_null() {
        val llm = ClozedLlm(FakeTransport())
        val body = llm.openAiBody(
            Fixtures.resolved(),
            "system",
            listOf(
                ClozedMessage.User("inspect"),
                ClozedMessage.Assistant("", listOf(ClozedToolCall("c1", "read", "{\"path\":\"a.txt\"}")))
            ),
            emptyList(),
            null
        )
        val messages = body.getAsJsonArray("messages")
        val assistant = messages[2].asJsonObject
        assertTrue(assistant.has("content"))
        assertTrue(assistant.get("content").isJsonNull)
        assertEquals(1, assistant.getAsJsonArray("tool_calls").size())
    }

    @Test fun openai_fragmented_tool_arguments_are_appended_by_call_index() = runBlocking {
        val transport = FakeTransport().apply {
            stream(FakeTransport.StreamRule({ true }, listOf(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"read","arguments":"{\"path\":"}}]}}]}""",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"notes.txt\"}}]}}]}",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"}\"}}]}}]}",
                "data: [DONE]"
            )))
        }
        val result = ClozedLlm(transport).complete(
            Fixtures.resolved(), "system", listOf(ClozedMessage.User("read notes")),
            emptyList(), {}, {}
        )
        assertEquals(1, result.calls.size)
        assertEquals("read", result.calls.single().name)
        assertEquals("{\"path\":\"notes.txt\"}", result.calls.single().arguments)
        assertEquals("call-1", result.calls.single().id)
    }

    @Test fun malformed_anthropic_tool_arguments_fail_instead_of_becoming_empty_objects() {
        val llm = ClozedLlm(FakeTransport())
        val error = runCatching {
            llm.anthropicBody(
                anthropicProvider(), "system",
                listOf(ClozedMessage.Assistant("", listOf(ClozedToolCall("c1", "read", "not-json")))),
                emptyList()
            )
        }.exceptionOrNull()
        assertTrue(error is ClozedLlmException)
        assertEquals("Tool arguments for read are not valid JSON.", error?.message)
    }
}
