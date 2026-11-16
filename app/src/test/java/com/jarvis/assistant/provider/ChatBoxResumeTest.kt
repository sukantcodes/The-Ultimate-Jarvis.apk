package com.jarvis.assistant.provider

import com.google.gson.JsonParser
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.builtin.FileTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The complaint: an API error mid-build, then Retry or "continue", used to start the whole job again. */
class ChatBoxResumeTest {
    private val dir: File = Files.createTempDirectory("jarvis-resume").toFile()
    private val ws = Workspace(dir)
    private val redactor = SecretRedactor()
    private val registry = ToolRegistry().also { r -> FileTools.all().forEach(r::register) }
    private val exec = ToolExecutor(registry, PermissionPolicy(MapSettings()), AuditLog(File(dir, ".a/audit.jsonl"), redactor), { ScriptedConfirmer(Decision.ALLOW_ONCE) }, redactor, ws)
    private val backend = ExecutorBackend(registry, exec)

    private val store = MemoryStore()
    private val vault = MemoryVault()
    private val transport = FakeTransport()
    private val manager = ProviderConfigManager(ProviderRepository(store, vault), transport, clock = { 1000L })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val journal = WorkJournalStore()

    @After fun tearDown() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); dir.deleteRecursively() }

    private class Rows : ChatTurnStore {
        class Row(val id: String, var user: String, var assistant: String, var state: String = "pending")
        val rows = ArrayList<Row>(); var seq = 0
        @Synchronized override fun completedTurns(excludingId: String?) = rows.filter { it.state == "done" && it.id != excludingId }.map { StoredTurn(it.id, it.user, it.assistant) }
        @Synchronized override fun begin(displayText: String, attachment: AttachmentRef?): String { val r = Row("t${++seq}", displayText, ""); rows += r; return r.id }
        @Synchronized override fun updatePartial(id: String, text: String) { rows.first { it.id == id }.assistant = text }
        @Synchronized override fun finish(id: String, text: String) { rows.first { it.id == id }.apply { assistant = text; state = "done" } }
        @Synchronized override fun fail(id: String, partial: String, errorText: String) { rows.first { it.id == id }.apply { assistant = if (partial.isBlank()) errorText else "$partial\n\n$errorText"; state = "failed" } }
        @Synchronized override fun resetForRetry(id: String): Boolean { rows.firstOrNull { it.id == id }?.apply { assistant = ""; state = "pending" } ?: return false; return true }
        @Synchronized override fun userText(id: String) = rows.firstOrNull { it.id == id }?.user
    }

    private fun toolCall(id: String, name: String, args: String) =
        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":${com.google.gson.JsonPrimitive(args)}}}]}}]}"""

    private fun engine(s: Rows) = ProviderChatEngine(manager, transport, s, scope, { "SYS" }, uiThrottleMs = 0, toolBackend = { backend }, journal = journal)

    private fun activate() {
        val textYes = mapOf(Capability.TEXT to CapabilityResult(Support.YES, Evidence.PROBE))
        assertNull(manager.save(Fixtures.profile(id = "p1", base = "https://api.example.com/v1", models = listOf(ModelInfo("m1", capabilities = textYes)), model = "m1"), Fixtures.KEY))
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1"))
        runBlocking { manager.activate("p1") }
    }

    private fun awaitEnd(e: ProviderChatEngine) = runBlocking { withTimeout(10_000) { e.state.first { it is EngineState.Idle || it is EngineState.Failed } } }

    private fun lastUserMessage(): String {
        val body = JsonParser.parseString(transport.bodyOf(transport.requests.last { it.url.endsWith("/chat/completions") })).asJsonObject
        return body["messages"].asJsonArray.last().asJsonObject["content"].asString
    }

    /** Round 1 writes a file, round 2 hits "too many requests". Afterwards the provider answers normally. */
    private fun scriptRateLimitAfterOneWrite(): () -> Unit {
        var n = 0; var recovered = false
        transport.addRule({ it.url.endsWith("chat/completions") }) {
            if (recovered) HttpResponse(200, Fixtures.completion("All finished."))
            else when (n++) {
                0 -> HttpResponse(200, toolCall("c1", "write_file", """{"path":"game.js","content":"line1\nline2\nline3"}"""))
                else -> HttpResponse(429, """{"error":{"message":"Too many requests"}}""")
            }
        }
        return { recovered = true }
    }

    @Test fun retry_after_a_rate_limit_continues_the_same_job_with_what_is_already_done() {
        activate(); val recover = scriptRateLimitAfterOneWrite()
        val s = Rows(); val e = engine(s)
        val id = (e.send("Make a snake game in game.js") as SendOutcome.Started).turnId
        assertTrue(awaitEnd(e) is EngineState.Failed)
        assertTrue("the file written before the error is still shown", s.rows[0].assistant.contains("line3"))
        assertTrue(File(dir, "game.js").exists())

        recover()
        assertTrue(e.retry(id) is SendOutcome.Started)
        assertTrue(awaitEnd(e) is EngineState.Idle)
        val brief = lastUserMessage()
        assertTrue(brief, brief.startsWith("CONTINUE AN UNFINISHED TASK"))
        assertTrue(brief.contains("Make a snake game in game.js"))
        assertTrue(brief.contains("game.js (3 lines)"))
        assertTrue(brief.contains("    3| line3"))
        assertEquals("same chat row, no duplicate message", 1, s.rows.size)
        assertEquals("done", s.rows[0].state)
        assertFalse(e.hasResumableWork())
    }

    @Test fun typing_continue_carries_on_the_interrupted_job_instead_of_starting_a_new_one() {
        activate(); val recover = scriptRateLimitAfterOneWrite()
        val s = Rows(); val e = engine(s)
        e.send("Make a snake game in game.js")
        assertTrue(awaitEnd(e) is EngineState.Failed)
        assertTrue(e.hasResumableWork())

        recover()
        assertTrue(e.send("continue") is SendOutcome.Started)
        assertTrue(awaitEnd(e) is EngineState.Idle)
        val brief = lastUserMessage()
        assertTrue(brief.startsWith("CONTINUE AN UNFINISHED TASK"))
        assertTrue(brief.contains("Make a snake game in game.js"))
        assertEquals("the user's word stays what the chat shows", "continue", s.rows.last().user)
    }

    @Test fun the_spoken_continue_resumes_the_job_and_says_so_plainly_when_there_is_nothing_to_continue() {
        activate()
        val e0 = engine(Rows())
        assertTrue(e0.resumeLast() is SendOutcome.Blocked)

        val recover = scriptRateLimitAfterOneWrite()
        val s = Rows(); val e = engine(s)
        e.send("Make a snake game in game.js")
        assertTrue(awaitEnd(e) is EngineState.Failed)
        recover()
        assertTrue(e.resumeLast() is SendOutcome.Started)
        assertTrue(awaitEnd(e) is EngineState.Idle)
        assertTrue(lastUserMessage().contains("game.js (3 lines)"))
    }

    @Test fun a_new_request_that_merely_mentions_continue_is_not_mistaken_for_a_resume() {
        activate(); scriptRateLimitAfterOneWrite()
        val s = Rows(); val e = engine(s)
        e.send("Make a snake game in game.js")
        assertTrue(awaitEnd(e) is EngineState.Failed)
        e.send("make a continent map"); awaitEnd(e)
        assertFalse(lastUserMessage().startsWith("CONTINUE"))
    }
}
