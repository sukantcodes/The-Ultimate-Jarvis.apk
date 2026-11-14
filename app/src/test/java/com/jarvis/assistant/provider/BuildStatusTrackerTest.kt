package com.jarvis.assistant.provider

import org.junit.Assert.*
import org.junit.Test

class BuildStatusTrackerTest {
    private var now = 1_000L
    private val tracker = BuildStatusTracker { now }

    @Test fun starts_hidden() {
        assertFalse(tracker.status.value.isVisible)
        assertEquals(BuildStage.IDLE, tracker.status.value.stage)
    }

    @Test fun checking_means_nothing_was_sent_yet() {
        tracker.begin("t1", "Custom", "model-x")
        val s = tracker.status.value
        assertEquals(BuildStage.CHECKING, s.stage)
        assertFalse(s.requestSent)
        assertTrue(s.isVisible)
    }

    @Test fun request_sent_then_generating_then_done() {
        tracker.begin("t1", "Custom", "model-x")
        tracker.requestSent()
        assertEquals(BuildStage.SENT, tracker.status.value.stage)
        assertTrue(tracker.status.value.requestSent)
        tracker.generating()
        assertEquals(BuildStage.GENERATING, tracker.status.value.stage)
        now += 4_000
        tracker.done()
        val s = tracker.status.value
        assertEquals(BuildStage.DONE, s.stage)
        assertEquals(4_000L, s.elapsedMs(now + 99_000))
    }

    @Test fun file_tools_count_files_once_and_move_to_writing() {
        tracker.begin("t1", "Custom", "m")
        tracker.requestSent()
        tracker.toolStep("write_file", "index.html", isWriting = true)
        tracker.toolStep("write_file", "index.html", isWriting = true)
        tracker.toolStep("write_file", "style.css", isWriting = true)
        val s = tracker.status.value
        assertEquals(BuildStage.WRITING, s.stage)
        assertEquals(3, s.toolSteps)
        assertEquals(listOf("index.html", "style.css"), s.filesWritten)
    }

    @Test fun a_read_step_is_generating_not_writing() {
        tracker.begin("t1", "Custom", "m")
        tracker.toolStep("read_file", "notes.txt", isWriting = false)
        assertEquals(BuildStage.GENERATING, tracker.status.value.stage)
        assertTrue(tracker.status.value.filesWritten.isEmpty())
    }

    @Test fun failure_keeps_whether_anything_was_sent() {
        tracker.begin("t1", "Custom", "m")
        tracker.failed("No key")
        val s = tracker.status.value
        assertEquals(BuildStage.FAILED, s.stage)
        assertFalse(s.requestSent)
        assertEquals("No key", s.message)
    }

    @Test fun updates_after_the_end_are_ignored() {
        tracker.begin("t1", "Custom", "m")
        tracker.requestSent()
        tracker.stopped()
        tracker.generating()
        tracker.toolStep("write_file", "a.txt", isWriting = true)
        assertEquals(BuildStage.STOPPED, tracker.status.value.stage)
        assertTrue(tracker.status.value.filesWritten.isEmpty())
    }

    @Test fun hide_clears_the_card() {
        tracker.begin("t1", "Custom", "m")
        tracker.done()
        tracker.hide()
        assertFalse(tracker.status.value.isVisible)
    }
}
