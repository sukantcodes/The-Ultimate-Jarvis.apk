package com.jarvis.assistant.provider

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WorkJournalTest {
    private fun step(tool: String, target: String, content: String, failed: Boolean = false) =
        LiveToolStep(tool, target, "code", content, isWriting = false, round = 1, maxRounds = 400, failed = failed)

    @Test fun a_job_with_a_finished_step_that_was_interrupted_can_be_resumed_and_a_finished_job_cannot() {
        val s = WorkJournalStore()
        s.begin("t1", "build a site")
        assertNull("nothing done yet, so there is nothing to resume", s.resumable("t1"))
        s.recordStep("t1", step("write_file", "index.html", "<html>\n</html>"))
        s.interrupted("t1", "Too many requests")
        assertNotNull(s.resumable("t1")); assertEquals("t1", s.latestResumable()?.turnId)
        s.finished("t1")
        assertNull(s.resumable("t1")); assertNull(s.latestResumable())
    }

    @Test fun failed_steps_alone_do_not_make_a_job_resumable() {
        val s = WorkJournalStore()
        s.begin("t1", "x")
        s.recordStep("t1", step("write_file", "a.txt", "", failed = true))
        s.interrupted("t1", "boom")
        assertNull(s.latestResumable())
    }

    @Test fun the_brief_names_the_file_and_shows_the_last_lines_with_real_line_numbers() {
        val s = WorkJournalStore()
        val j = s.begin("t1", "Make a snake game")
        val body = (1..40).joinToString("\n") { "line $it" }
        s.recordStep("t1", step("write_file", "game.js", body))
        s.interrupted("t1", "The provider did not answer in time.")
        val brief = WorkJournalStore.brief(j)
        assertTrue(brief.contains("CONTINUE AN UNFINISHED TASK"))
        assertTrue(brief.contains("Make a snake game"))
        assertTrue(brief.contains("game.js (40 lines)"))
        assertTrue(brief.contains("   40| line 40"))
        assertTrue(brief.contains("   16| line 16"))
        assertFalse("only the last lines are shown", brief.contains("line 15\n"))
        assertTrue(brief.contains("append_file"))
        assertTrue(brief.contains("did not answer in time"))
    }

    @Test fun appended_pieces_add_up_to_the_files_real_line_count() {
        val s = WorkJournalStore()
        val j = s.begin("t1", "x")
        s.recordStep("t1", step("write_file", "app.js", "a\nb\nc"))
        s.recordStep("t1", step("append_file", "app.js", "d\ne"))
        val last = j.steps.last()
        assertEquals(5, last.lines)
        assertEquals(listOf("a", "b", "c", "d", "e"), last.tail)
    }

    @Test fun continuing_under_a_new_turn_carries_the_progress_over() {
        val s = WorkJournalStore()
        s.begin("t1", "x")
        s.recordStep("t1", step("write_file", "a.txt", "1\n2"))
        s.recordBlocks("t1", linkedMapOf("a.txt" to "```txt:a.txt\n1\n2\n```"))
        s.interrupted("t1", "stopped")
        val carried = s.adopt("t1", "t2")!!
        assertEquals(1, carried.steps.size); assertEquals(1, carried.blocks.size)
        assertNull(s.get("t1")); assertNotNull(s.get("t2"))
    }

    @Test fun progress_survives_the_app_being_closed() {
        val dir = Files.createTempDirectory("journal").toFile()
        try {
            val f = java.io.File(dir, "j.json")
            val a = WorkJournalStore(f)
            a.begin("t1", "build")
            a.recordStep("t1", step("write_file", "a.txt", "x\ny"))
            // the app dies here with the job still marked as running
            val b = WorkJournalStore(f)
            val r = b.latestResumable()
            assertNotNull(r); assertEquals("build", r!!.prompt); assertEquals(1, r.steps.size)
            assertTrue(r.interruptedBy!!.contains("closed"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun continue_in_english_hindi_and_bengali_is_recognised_but_new_requests_are_not() {
        for (t in listOf("continue", "Continue it", "please continue", "Jarvis, continue", "resume", "carry on", "keep going", "aage badho", "jari rakho", "continue karo", "जारी रखो", "आगे बढ़ो", "চালিয়ে যাও")) {
            assertEquals(t, "", ContinueIntent.match(t))
        }
        assertEquals("make the header blue", ContinueIntent.match("continue and make the header blue")?.removePrefix("and ")?.trim())
        for (t in listOf("make a continent map", "build a website", "what is continuous integration", "", "write a game", "go online and search", "proceeding with the plan")) assertNull(t, ContinueIntent.match(t))
    }
}
