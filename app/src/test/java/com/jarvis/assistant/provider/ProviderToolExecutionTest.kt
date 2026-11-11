package com.jarvis.assistant.provider

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.tools.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ProviderToolExecutionTest {
    @Test fun model_tool_call_runs_through_permission_executor_and_returns_result_to_model() = runBlocking {
        val transport = FakeTransport()
        val keyValue = MemoryStore()
        val vault = MemoryVault()
        val repository = ProviderRepository(keyValue, vault)
        val capabilities = mapOf(
            Capability.TEXT to CapabilityResult(Support.YES, Evidence.PROBE),
            Capability.TOOLS to CapabilityResult(Support.YES, Evidence.PROBE)
        )
        val profile = Fixtures.profile(models = listOf(ModelInfo("m1", capabilities = capabilities)))
        val manager = ProviderConfigManager(repository, transport)
        assertNull(manager.save(profile, Fixtures.KEY))
        repository.setActiveId(profile.id)

        val tool = object : Tool {
            override val spec = ToolSpec("echo_tool", "Return a fixed test value.", emptyList(), Risk.READ, "test")
            override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.Ok("42")
        }
        val registry = ToolRegistry().also { it.register(tool) }
        val redactor = SecretRedactor()
        val settings = object : PermissionPolicy.SettingsStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) = Unit
        }
        val dir = Files.createTempDirectory("jarvis-tool-engine").toFile()
        val executor = ToolExecutor(
            registry, PermissionPolicy(settings), AuditLog(null, redactor),
            { DenyingConfirmer }, redactor, Workspace(dir)
        )

        val toolAnswer = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"echo_tool","arguments":"{}"}}]}}]}"""
        transport.on({ !transport.bodyOf(it).contains("tool_call_id") }, 200, toolAnswer)
        transport.on({ transport.bodyOf(it).contains("tool_call_id") }, 200, Fixtures.completion("The tool returned 42."))

        val store = TestTurnStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ProviderChatEngine(
            manager, transport, store, scope, { "SYS" }, uiThrottleMs = 0,
            toolSpecs = { listOf(tool.spec) },
            toolExecutor = { call -> executor.execute(call) }
        )
        try {
            assertTrue(engine.send("Use the echo tool") is SendOutcome.Started)
            withTimeout(5000) { while (engine.isBusy) delay(5) }
            assertEquals("The tool returned 42.", store.answer)
            assertEquals(2, transport.requests.size)
            assertTrue(transport.bodyOf(transport.requests[0]).contains("\"tools\""))
            val followUp = transport.bodyOf(transport.requests[1])
            assertTrue(followUp.contains("\"role\":\"tool\""))
            assertTrue(followUp.contains("\"content\":\"42\""))
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test fun rejected_tool_schema_retries_as_plain_chat_and_displays_provider_answer() = runBlocking {
        val transport = FakeTransport()
        val repository = ProviderRepository(MemoryStore(), MemoryVault())
        val profile = Fixtures.profile()
        val manager = ProviderConfigManager(repository, transport)
        assertNull(manager.save(profile, Fixtures.KEY))
        repository.setActiveId(profile.id)
        transport.on(
            { body -> body.bodyOfTools() },
            400,
            """{"error":{"message":"This model does not support tools"}}"""
        )
        transport.on(
            { !it.bodyOfTools() },
            200,
            Fixtures.completion("Provider answer received successfully.")
        )
        val store = TestTurnStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tool = ToolSpec("echo_tool", "Test tool", emptyList(), Risk.READ, "test")
        val engine = ProviderChatEngine(
            manager, transport, store, scope, { "SYS" }, uiThrottleMs = 0,
            toolSpecs = { listOf(tool) },
            toolExecutor = { ToolResult.Ok("unused") }
        )
        try {
            assertTrue(engine.send("Please answer") is SendOutcome.Started)
            withTimeout(5000) { while (engine.isBusy) delay(5) }
            assertEquals("Provider answer received successfully.", store.answer)
            assertEquals(2, transport.requests.size)
            assertTrue(transport.bodyOf(transport.requests[0]).contains("\"tools\""))
            assertFalse(transport.bodyOf(transport.requests[1]).contains("\"tools\""))
        } finally {
            scope.cancel()
        }
    }

    private fun HttpRequestSpec.bodyOfTools(): Boolean =
        (body as? HttpBody.Text)?.content?.contains("\"tools\"") == true

    private class TestTurnStore : ChatTurnStore {
        private var id = ""
        var answer: String = ""
        override fun completedTurns(excludingId: String?) = emptyList<StoredTurn>()
        override fun begin(displayText: String, attachment: AttachmentRef?): String { id = "turn-1"; return id }
        override fun updatePartial(id: String, text: String) { answer = text }
        override fun finish(id: String, text: String) { answer = text }
        override fun fail(id: String, partial: String, errorText: String) { answer = partial + errorText }
        override fun resetForRetry(id: String) = true
        override fun userText(id: String) = "Use the echo tool"
    }
}
