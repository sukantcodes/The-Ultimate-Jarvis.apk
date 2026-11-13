package com.jarvis.assistant.background

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BuildNarratorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(dir: File, name: String, lines: Int, prefix: String = "line"): File =
        File(dir, name).also { f -> f.writeText((1..lines).joinToString("\n") { "$prefix $it" }) }

    private fun narrator(reply: () -> String?, prompts: MutableList<String> = mutableListOf()) = BuildNarrator(
        request = { "snake game" },
        language = { "Hindi" },
        ask = { _, prompt -> prompts += prompt; reply() },
        fallback = { file, role, _, isNew -> "fallback $file $role $isNew" }
    )

    private fun files(dir: File) = dir.walkTopDown().filter { it.isFile }.toList()

    @Test fun says_what_the_new_code_is_for_and_includes_the_real_code() = runBlocking {
        val dir = tmp.newFolder("p")
        val f = File(dir, "game.js")
        f.writeText("function moveSnake() {}\nfunction checkCollision() {}\n" + "x();\n".repeat(8))
        val prompts = mutableListOf<String>()
        val n = narrator({ "Ab snake ki movement aur collision check likh raha hoon." }, prompts)
        val line = n.tick(dir, files(dir), 30, 90, false, 0, 600_000)
        assertEquals("Ab snake ki movement aur collision check likh raha hoon.", line)
        assertTrue(prompts[0].contains("moveSnake"))
        assertTrue(prompts[0].contains("checkCollision"))
        assertTrue(prompts[0].contains("Do not mention percentages"))
    }

    @Test fun stays_quiet_when_nothing_changed_and_never_repeats() = runBlocking {
        val dir = tmp.newFolder("q")
        write(dir, "index.html", 12)
        var calls = 0
        val n = narrator({ calls++; "Building the page." })
        assertNotNull(n.tick(dir, files(dir), 20, 60, false, 0, 600_000))
        // No change and the nudge window has not passed: no model call, no speech.
        assertNull(n.tick(dir, files(dir), 25, 55, false, 0, 600_000))
        assertEquals(1, calls)
        // New code, but the model words the same idea again: dropped.
        write(dir, "index.html", 30)
        assertNull(n.tick(dir, files(dir), 35, 50, false, 0, 600_000))
    }

    @Test fun the_model_can_choose_to_say_nothing() = runBlocking {
        val dir = tmp.newFolder("s")
        write(dir, "a.css", 9)
        val n = narrator({ "SKIP" })
        assertNull(n.tick(dir, files(dir), 20, 60, false, 0, 600_000))
    }

    @Test fun progress_is_only_offered_after_a_quiet_stretch() = runBlocking {
        val dir = tmp.newFolder("n")
        write(dir, "a.js", 9)
        val prompts = mutableListOf<String>()
        var reply = "Writing the first script."
        val n = narrator({ reply }, prompts)
        n.tick(dir, files(dir), 20, 60, false, 0, 0)
        reply = "About half way, two minutes to go."
        val nudge = n.tick(dir, files(dir), 50, 120, false, 0, 0)
        assertEquals("About half way, two minutes to go.", nudge)
        assertTrue(prompts.last().contains("50% done"))
    }

    @Test fun a_plain_words_line_is_used_when_no_model_is_available() = runBlocking {
        val dir = tmp.newFolder("f")
        write(dir, "style.css", 10)
        val n = narrator({ null })
        assertEquals("fallback style.css styling true", n.tick(dir, files(dir), 20, 60, false, 0, 600_000))
    }

    @Test fun declarations_are_read_from_each_file_type() {
        assertTrue(CodeInsights.symbols("a.js", "function jump(){}\nconst fire = (x) => x\nclass Player {}").containsAll(listOf("jump", "fire", "Player")))
        assertTrue(CodeInsights.symbols("a.css", ".hero {\n}\n#nav {\n}").containsAll(listOf(".hero", "#nav")))
        assertTrue(CodeInsights.symbols("a.html", "<nav></nav><canvas id=\"board\"></canvas>").containsAll(listOf("nav", "canvas", "board")))
        assertEquals("styling", CodeInsights.role("x.css"))
    }
}
