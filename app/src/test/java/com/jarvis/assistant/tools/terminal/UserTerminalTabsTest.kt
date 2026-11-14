package com.jarvis.assistant.tools.terminal

import com.jarvis.assistant.tools.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UserTerminalTabsTest {
    private val dir: File = Files.createTempDirectory("jarvis-term-user").toFile()
    private val ws = Workspace(dir)
    private val redactor = SecretRedactor()
    private val backend = ProcessBackend(inherited = { mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/root") })
    private val mgr = TerminalManager(ws, backend, redactor)

    @After fun tearDown() { mgr.shutdown(); dir.deleteRecursively() }

    private fun callTool(name: String, json: String): ToolResult = runBlocking {
        val tool = TerminalTools.all(mgr).first { it.spec.name == name }
        tool.run(args(json), object : ToolContext { override val workspace: Workspace = ws })
    }

    @Test fun user_tabs_are_numbered_and_do_not_collide_with_jarvis_sessions() {
        mgr.getOrCreate("main")
        val a = mgr.createUserSession()
        val b = mgr.createUserSession()
        assertEquals("me1", a.id)
        assertEquals("me2", b.id)
        assertTrue(mgr.isUserSession("me1"))
        assertFalse(mgr.isUserSession("main"))
        assertFalse(mgr.isUserSession("me"))
        assertFalse(mgr.isUserSession("meeting"))
    }

    @Test fun a_command_in_a_user_tab_does_not_touch_jarvis_main_session() {
        val main = mgr.getOrCreate("main")
        val mine = mgr.createUserSession()
        runBlocking { mine.run("mkdir -p only-mine && cd only-mine", 10_000) }
        assertEquals("only-mine", mine.relativeCwd())
        assertEquals(".", main.relativeCwd())
        assertFalse(main.buffer.tail(1000).contains("only-mine"))
    }

    @Test fun jarvis_can_read_and_list_what_the_user_typed() {
        val mine = mgr.createUserSession()
        mine.note("\n. $ echo visible-to-jarvis\n")
        runBlocking { mine.run("echo visible-to-jarvis", 10_000) }
        assertTrue(callTool("terminal_sessions", "{}").text.contains("me1"))
        val tail = callTool("terminal_tail", """{"session":"me1"}""")
        assertTrue(tail.text, tail.text.contains("visible-to-jarvis"))
        // terminal_tail does not use up the output
        assertTrue(callTool("terminal_tail", """{"session":"me1"}""").text.contains("visible-to-jarvis"))
        val history = callTool("terminal_history", """{"session":"me1"}""")
        assertTrue(history.text, history.text.contains("echo visible-to-jarvis"))
    }

    @Test fun jarvis_can_clear_a_tab() {
        val mine = mgr.createUserSession()
        mine.note("something\n")
        assertTrue(callTool("terminal_clear", """{"session":"me1"}""").isOk)
        assertEquals("", mine.buffer.tail(1000))
        assertFalse(callTool("terminal_clear", """{"session":"nope"}""").isOk)
    }

    @Test fun note_text_is_redacted() {
        redactor.register("super-secret-token-123")
        val s = mgr.createUserSession()
        s.note("token super-secret-token-123 here\n")
        assertFalse(s.buffer.tail(1000).contains("super-secret-token-123"))
    }
}
