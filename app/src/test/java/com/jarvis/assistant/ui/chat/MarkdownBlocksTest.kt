package com.jarvis.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownBlocksTest {

    @Test fun no_blank_line_means_no_split() = assertEquals(-1, MarkdownBlocks.stableSplit("one line\nsecond line"))

    @Test fun splits_at_the_last_blank_line() {
        val s = "First para.\n\nSecond para.\n\nThird being written"
        val i = MarkdownBlocks.stableSplit(s)
        assertEquals("First para.\n\nSecond para.", s.substring(0, i))
        assertEquals("Third being written", s.substring(i).trimStart('\n'))
    }

    @Test fun trailing_blank_line_without_text_after_it_is_not_a_split() =
        assertEquals(-1, MarkdownBlocks.stableSplit("Only one block\n\n"))

    @Test fun a_leading_blank_line_is_not_a_split() = assertEquals(-1, MarkdownBlocks.stableSplit("\n\nhello"))

    @Test fun split_prefix_only_grows_as_text_streams_in() {
        val full = "# Title\n\nIntro text here.\n\n- a\n- b\n\nClosing words"
        var last = ""
        for (n in 1..full.length) {
            val s = full.substring(0, n)
            val i = MarkdownBlocks.stableSplit(s)
            val prefix = if (i < 0) "" else s.substring(0, i)
            assertEquals("prefix must never change retroactively at n=$n", true, prefix.startsWith(last))
            last = prefix
        }
    }
}
