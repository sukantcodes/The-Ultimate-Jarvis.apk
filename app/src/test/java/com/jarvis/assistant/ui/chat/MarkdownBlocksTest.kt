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

    @Test fun a_header_without_its_separator_is_hidden_until_the_separator_arrives() {
        assertEquals("Intro", MarkdownBlocks.trimUnsettledTable("Intro\n| Name | Age |"))
        assertEquals("Intro", MarkdownBlocks.trimUnsettledTable("Intro\n| Name | Age |\n| --"))
    }

    @Test fun a_confirmed_table_shows_its_finished_rows_and_hides_a_cut_off_one() {
        val t = "Intro\n| A | B |\n|---|---|\n| 1 | 2 |\n| 3 |"
        assertEquals("Intro\n| A | B |\n|---|---|\n| 1 | 2 |", MarkdownBlocks.trimUnsettledTable(t))
        val done = "Intro\n| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"
        assertEquals(done, MarkdownBlocks.trimUnsettledTable(done))
    }

    @Test fun a_table_followed_by_anything_is_left_alone() {
        val t = "| A | B |\n|---|---|\n| 1 | 2 |\nAfter the table"
        assertEquals(t, MarkdownBlocks.trimUnsettledTable(t))
        val nl = "| A | B |\n|---|---|\n| 1 | 2 |\n"
        assertEquals(nl, MarkdownBlocks.trimUnsettledTable(nl))
    }

    @Test fun text_without_a_trailing_table_is_untouched() {
        val t = "Just words\nand a | pipe in the middle\nmore"
        assertEquals(t, MarkdownBlocks.trimUnsettledTable(t))
    }
}
