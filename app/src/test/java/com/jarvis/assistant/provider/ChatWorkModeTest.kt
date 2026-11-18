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

class ChatWorkModeTest {
    // ---- plain rules ----------------------------------------------------------------------------------------

    @Test fun develop_is_the_default_and_the_modes_come_in_the_requested_order() {
        assertEquals(ChatWorkMode.DEVELOP, ChatWorkMode.DEFAULT)
        assertEquals(ChatWorkMode.DEVELOP, ChatWorkMode.fromKey(null))
        assertEquals(ChatWorkMode.DEVELOP, ChatWorkMode.fromKey("nonsense"))
        assertEquals(listOf("ask", "debug", "code", "develop"), ChatWorkMode.values().map { it.key })
        assertEquals(ChatWorkMode.CODE, ChatWorkMode.fromKey("code"))
    }

    @Test fun only_debug_and_develop_may_use_tools_and_ask_and_code_get_none() {
        assertFalse(ChatWorkMode.ASK.usesTools); assertFalse(ChatWorkMode.CODE.usesTools)
        assertTrue(ChatWorkMode.DEBUG.usesTools); assertTrue(ChatWorkMode.DEVELOP.usesTools)
        assertNull("Develop keeps every tool", ChatWorkMode.DEVELOP.allowedTools)
        assertFalse("Debug never starts previews or servers", "preview_start" in ChatWorkMode.DEBUG.allowedTools!!)
        assertFalse("Debug cannot delete or move things", "delete_path" in ChatWorkMode.DEBUG.allowedTools!!)
        assertTrue("read_file" in ChatWorkMode.DEBUG.allowedTools!! && "list_files" in ChatWorkMode.DEBUG.allowedTools!!)
    }

    @Test fun ask_mode_never_shows_code_even_while_it_is_still_streaming() {
        val note = AskModeGuard.NOTE
        assertEquals("plain text is untouched", "Just words.", AskModeGuard.strip("Just words."))
        val done = AskModeGuard.strip("Try this:\n```python\nprint(1)\n```\nThen run it.")
        assertFalse(done.contains("print(1)")); assertTrue(done.contains(note)); assertTrue(done.contains("Then run it."))
        val streaming = AskModeGuard.strip("Try this:\n```python\nprint(")
        assertFalse(streaming.contains("print(")); assertTrue(streaming.contains(note))
        val two = AskModeGuard.strip("a\n```x\n1\n```\nb\n```y\n2\n```\nc")
        assertEquals("the note appears once", 1, Regex(Regex.escape(note)).findAll(two).count())
        assertTrue(two.contains("a") && two.contains("b") && two.contains("c") && !two.contains("\n1\n") && !two.contains("\n2\n"))
    }

    @Test fun ask_mode_hides_thinking_and_the_user_asked_narration() {
        assertEquals("Paris.", AskModeGuard.strip("The user asked what the capital of France is.\nParis."))
        assertEquals("Paris.", AskModeGuard.strip("<think>they want a city</think>Paris."))
        assertEquals("", AskModeGuard.strip("<think>still thinking"))
        assertEquals("Okay, here you go.", AskModeGuard.strip("Okay, here you go."))
        assertEquals("The user interface is fast.", AskModeGuard.strip("The user interface is fast."))
        assertEquals("Answer.", AskModeGuard.strip("Okay, the user is asking about X.\nLet me think about it.\nAnswer."))
    }

    @Test fun ask_prompt_demands_direct_answers_and_tables() {
        val i = ChatWorkMode.ASK.instruction
        assertTrue(i.contains("table") && i.contains("The user asked") && i.contains("{red:text}"))
    }

    // ---- through the engine ---------------------------------------------------------------------------------

    private val dir: File = Files.createTempDirectory("jarvis-modes").toFile()
    private val ws = Workspace(dir)
    private val redactor = SecretRedactor()
    private val registry = ToolRegistry().also { r -> FileTools.all().forEach(r::register) }
    private val exec = ToolExecutor(registry, PermissionPolicy(MapSettings()), AuditLog(File(dir, ".a/audit.jsonl"), redactor), { ScriptedConfirmer(Decision.ALLOW_ONCE) }, redactor, ws)
    private val backend = ExecutorBackend(registry, exec)
    private val transport = FakeTransport()
    private val manager = ProviderConfigManager(ProviderRepository(MemoryStore(), MemoryVault()), transport, clock = { 1000L })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    private fun engine(s: Rows, picked: () -> ChatWorkMode = { ChatWorkMode.DEFAULT }) =
        ProviderChatEngine(manager, transport, s, scope, { "SYS" }, uiThrottleMs = 0, toolBackend = { backend }, modeProvider = picked)

    private fun activate() {
        val textYes = mapOf(Capability.TEXT to CapabilityResult(Support.YES, Evidence.PROBE))
        assertNull(manager.save(Fixtures.profile(id = "p1", base = "https://api.example.com/v1", models = listOf(ModelInfo("m1", capabilities = textYes)), model = "m1"), Fixtures.KEY))
        transport.onUrl("/models", 200, Fixtures.openAiModels("m1"))
        runBlocking { manager.activate("p1") }
    }

    private fun awaitEnd(e: ProviderChatEngine) = runBlocking { withTimeout(10_000) { e.state.first { it is EngineState.Idle || it is EngineState.Failed } } }

    private fun lastBody() = JsonParser.parseString(transport.bodyOf(transport.requests.last { it.url.endsWith("/chat/completions") })).asJsonObject
    private fun systemText() = lastBody()["messages"].asJsonArray.first().asJsonObject["content"].asString
    private fun offeredTools() = lastBody()["tools"]?.asJsonArray?.map { it.asJsonObject["function"].asJsonObject["name"].asString }.orEmpty()

    @Test fun ask_mode_offers_no_tools_tells_the_model_to_answer_only_and_hides_any_code_it_writes() {
        activate()
        transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("Sure.\n```py\nprint(1)\n", "```\nDone.")))
        val s = Rows(); val e = engine(s)
        e.send("how do I print in python", mode = ChatWorkMode.ASK); awaitEnd(e)
        assertTrue(systemText().contains("MODE: ASK"))
        assertTrue("no tools in Ask mode", offeredTools().isEmpty() && !lastBody().has("tools"))
        assertFalse(s.rows[0].assistant.contains("print(1)")); assertTrue(s.rows[0].assistant.contains("Done."))
    }

    @Test fun code_mode_writes_code_into_the_chat_only_with_no_tools_and_no_files() {
        activate()
        transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("```js\nconsole.log(1)\n```")))
        val s = Rows(); val e = engine(s)
        e.send("a hello script", mode = ChatWorkMode.CODE); awaitEnd(e)
        assertTrue(systemText().contains("MODE: CODE") && systemText().contains("never create files"))
        assertFalse(lastBody().has("tools"))
        assertTrue("the code is delivered in the chat", s.rows[0].assistant.contains("console.log(1)"))
        assertTrue(dir.listFiles { f -> !f.name.startsWith(".") }.isNullOrEmpty())
    }

    @Test fun debug_mode_offers_only_reading_and_fixing_tools() {
        activate()
        transport.on({ it.url.endsWith("chat/completions") }, 200, Fixtures.completion("No bugs found."))
        val s = Rows(); val e = engine(s)
        e.send("debug notes.txt", mode = ChatWorkMode.DEBUG); awaitEnd(e)
        assertTrue(systemText().contains("MODE: DEBUG"))
        val tools = offeredTools()
        assertTrue(tools.toString(), "read_file" in tools && "list_files" in tools && "search_files" in tools)
        assertTrue(tools.toString(), "delete_path" !in tools && "move_path" !in tools && "make_directory" !in tools)
    }

    @Test fun develop_is_the_default_and_keeps_every_tool() {
        activate()
        transport.on({ it.url.endsWith("chat/completions") }, 200, Fixtures.completion("Built."))
        val s = Rows(); val e = engine(s)
        e.send("build a site"); awaitEnd(e)
        assertTrue(systemText().contains("MODE: DEVELOP"))
        assertTrue(offeredTools().toString(), "delete_path" in offeredTools() && "write_file" in offeredTools())
    }

    @Test fun the_mode_picked_in_the_chatbox_is_what_the_next_message_uses_and_a_retry_keeps_it() {
        activate()
        var picked = ChatWorkMode.ASK
        transport.stream(FakeTransport.StreamRule({ true }, emptyList(), failWith = TransportException(TransportFailure.TIMEOUT, "t")))
        val s = Rows(); val e = engine(s) { picked }
        val id = (e.send("what is a monad") as SendOutcome.Started).turnId
        assertTrue(awaitEnd(e) is EngineState.Failed)
        picked = ChatWorkMode.DEVELOP   // the user flips the switch before pressing Retry
        transport.clearStreams(); transport.stream(FakeTransport.StreamRule({ true }, Fixtures.sse("A monad is…")))
        assertTrue(e.retry(id) is SendOutcome.Started); awaitEnd(e)
        assertTrue("the retry stays in the mode the question was asked in", systemText().contains("MODE: ASK"))
    }
}
