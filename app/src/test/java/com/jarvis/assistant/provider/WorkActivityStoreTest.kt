package com.jarvis.assistant.provider

import org.junit.Assert.*
import org.junit.Test

class WorkActivityStoreTest {
    private var now = 10_000L
    private val store = WorkActivityStore({ now })

    private fun start(tool: String, target: String, content: String = "") = LiveToolStep(tool, target, "code", content, isWriting = true, round = 1, maxRounds = 5)
    private fun end(tool: String, target: String, out: String = "", failed: Boolean = false) = LiveToolStep(tool, target, "code", out, isWriting = false, round = 1, maxRounds = 5, failed = failed)

    @Test fun unknown_turn_is_ignored() {
        store.onStep("nope", start("write_file", "a.txt"))
        store.setProgress("nope", 40)
        store.finish("nope", false)
        assertNull(store.get("nope"))
    }

    @Test fun begin_creates_a_running_turn_with_no_steps() {
        store.begin("t1")
        val a = store.get("t1")!!
        assertTrue(a.running)
        assertTrue(a.steps.isEmpty())
        assertEquals(-1, a.percent)
    }

    @Test fun a_step_runs_then_finishes_with_its_response() {
        store.begin("t1")
        store.onStep("t1", start("terminal_run", "ls -la", "$ ls -la"))
        assertEquals(StepState.RUNNING, store.get("t1")!!.steps.single().state)
        store.onStep("t1", end("terminal_run", "ls -la", "$ ls -la\nfile.txt"))
        val s = store.get("t1")!!.steps.single()
        assertEquals(StepState.DONE, s.state)
        assertEquals("$ ls -la\nfile.txt", s.response)
        assertTrue(s.request.startsWith("terminal_run ls -la"))
    }

    @Test fun a_failed_tool_marks_the_step_failed() {
        store.begin("t1")
        store.onStep("t1", start("read_file", "missing.txt"))
        store.onStep("t1", end("read_file", "missing.txt", "No such file", failed = true))
        assertEquals(StepState.FAILED, store.get("t1")!!.steps.single().state)
    }

    @Test fun file_writes_keep_the_code_in_the_request_and_have_no_response() {
        store.begin("t1")
        store.onStep("t1", start("write_file", "index.html", "<h1>Hi</h1>"))
        store.onStep("t1", end("write_file", "index.html", "<h1>Hi</h1>"))
        val s = store.get("t1")!!.steps.single()
        assertTrue(s.request.contains("<h1>Hi</h1>"))
        assertNull(s.response)
        assertEquals(StepState.DONE, s.state)
    }

    @Test fun a_new_step_closes_one_left_running() {
        store.begin("t1")
        store.onStep("t1", start("read_file", "a.txt"))
        store.onStep("t1", start("read_file", "b.txt"))
        val steps = store.get("t1")!!.steps
        assertEquals(2, steps.size)
        assertEquals(StepState.DONE, steps[0].state)
        assertEquals(StepState.RUNNING, steps[1].state)
    }

    @Test fun finish_settles_running_steps_and_records_time() {
        store.begin("t1")
        store.onStep("t1", start("terminal_run", "sleep 1"))
        store.setProgress("t1", 55)
        now += 65_000
        store.finish("t1", failed = true)
        val a = store.get("t1")!!
        assertFalse(a.running)
        assertTrue(a.failed)
        assertEquals(StepState.FAILED, a.steps.single().state)
        assertEquals(-1, a.percent)
        assertEquals(65_000L, a.elapsedMs(now + 999_000))
    }

    @Test fun nothing_changes_after_the_turn_ended() {
        store.begin("t1")
        store.finish("t1", false)
        store.onStep("t1", start("write_file", "late.txt"))
        store.setProgress("t1", 80)
        assertTrue(store.get("t1")!!.steps.isEmpty())
        assertEquals(-1, store.get("t1")!!.percent)
    }

    @Test fun only_the_latest_turns_are_kept() {
        val small = WorkActivityStore({ now }, maxTurns = 3)
        for (i in 1..5) small.begin("t$i")
        assertNull(small.get("t1"))
        assertNull(small.get("t2"))
        assertNotNull(small.get("t3"))
        assertNotNull(small.get("t5"))
    }
}
