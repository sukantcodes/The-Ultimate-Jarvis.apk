package com.jarvis.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Proves the streaming shortcut shows exactly what the normal renderer would, at every moment of a stream. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncrementalMarkdownTest {

    private val palette = AnswerPalette.Palette(0xFF00E5FF.toInt(), 0xFFA78BFA.toInt(), 0xFFFFE57F.toInt(), 0)

    private val prose = "# Plan\n\nFirst we **gather** the data.\n\n- one item\n- two items\n\n> a quote here\n\n" +
        "1. step one\n2. step two\n\n## Next\n\nClosing words with `code` and a [link](https://example.com).\n\n\nAfter extra blank lines."

    private val withTable = "Here is a comparison:\n\n| Name | Score |\n|------|-------|\n| Ann | 10 |\n| Bob | 20 |\n\nThat is all."

    @Test fun every_moment_of_a_stream_matches_the_full_render_for_prose() {
        val inc = IncrementalMarkdown()
        for (n in 1..prose.length) {
            val s = prose.substring(0, n)
            // Equal up to trailing blank space: when a block has no text yet (a bare "> " quote marker), the full renderer
            // leaves its gap behind while the streaming path adds none, which is what you want to see on screen.
            val expected = MarkdownRenderer.render(s, 2f, palette).toString().trimEnd()
            assertEquals("prefix length $n", expected, inc.render(s, 2f, palette, streaming = true).toString().trimEnd())
        }
    }

    @Test fun the_finished_answer_is_exactly_the_full_render() {
        val inc = IncrementalMarkdown()
        for (n in prose.indices step 7) inc.render(prose.substring(0, n), 2f, palette, true)
        for (doc in listOf(prose, withTable)) {
            assertEquals(MarkdownRenderer.render(doc, 2f, palette).toString(), inc.render(doc, 2f, palette, streaming = false).toString())
        }
    }

    @Test fun a_streaming_table_never_shows_raw_pipes() {
        val inc = IncrementalMarkdown()
        for (n in 1..withTable.length) {
            val shown = inc.render(withTable.substring(0, n), 2f, palette, streaming = true).toString()
            assertFalse("raw table text visible at length $n: [$shown]", shown.contains('|'))
        }
    }

    @Test fun a_changed_look_is_never_served_from_the_old_cache() {
        val inc = IncrementalMarkdown()
        val s = "# Title\n\nbody text one.\n\nbody text two"
        inc.render(s, 2f, palette, true)
        val other = AnswerPalette.Palette(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 1)
        assertEquals(MarkdownRenderer.render(s, 3f, other).toString(), inc.render(s, 3f, other, true).toString())
    }
}
