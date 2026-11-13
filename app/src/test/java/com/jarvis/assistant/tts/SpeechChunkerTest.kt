package com.jarvis.assistant.tts

import com.jarvis.assistant.tts.engine.SpeechChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {
    @Test fun blank_text_gives_no_pieces() {
        assertTrue(SpeechChunker.split("   ").isEmpty())
    }

    @Test fun a_short_line_stays_in_one_piece() {
        assertEquals(listOf("Hello."), SpeechChunker.split("Hello."))
    }

    @Test fun sentences_become_separate_pieces_so_speech_can_start_early() {
        val pieces = SpeechChunker.split("I have opened the settings page for you. Tell me which option to change. Anything else, Sir?")
        assertEquals(
            listOf("I have opened the settings page for you.", "Tell me which option to change. Anything else, Sir?"),
            pieces
        )
    }

    @Test fun very_short_sentences_are_joined_to_their_neighbour() {
        val pieces = SpeechChunker.split("Done. I have set the alarm for seven in the morning.")
        assertEquals(listOf("Done. I have set the alarm for seven in the morning."), pieces)
    }

    @Test fun decimals_and_abbreviations_are_not_cut_in_the_middle() {
        val pieces = SpeechChunker.split("The battery is at 3.5 volts which is fine for now.")
        assertEquals(1, pieces.size)
    }

    @Test fun a_very_long_sentence_is_cut_at_a_comma() {
        val long = "word ".repeat(30).trim() + ", " + "more ".repeat(40).trim() + "."
        val pieces = SpeechChunker.split(long)
        assertTrue(pieces.size >= 2)
        assertTrue(pieces.all { it.length <= SpeechChunker.MAX_CHARS })
        assertEquals(long.replace(Regex("\\s+"), " "), pieces.joinToString(" "))
    }
}
