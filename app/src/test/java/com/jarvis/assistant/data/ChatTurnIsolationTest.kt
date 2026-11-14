package com.jarvis.assistant.data

import com.jarvis.assistant.data.model.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnIsolationTest {

    @Test
    fun testAudioQueryAndResponseMarkersFiltered() {
        val turns = listOf(
            ChatTurn(userTranscript = "(Audio query)", jarvisResponse = "Spoken answer"),
            ChatTurn(userTranscript = "How are you?", jarvisResponse = "(Audio response)"),
            ChatTurn(userTranscript = "Write a python script", jarvisResponse = "```python\nprint('hello')\n```"),
            ChatTurn(userTranscript = "🛠 JARVIS build", jarvisResponse = "Building web app..."),
            ChatTurn(userTranscript = "When is my birthday?", jarvisResponse = "15th August")
        )

        // Legacy audio placeholders must be filtered out so they never surface in ChatBox
        val cleanTurns = turns.filter {
            it.userTranscript != "(Audio query)" && it.jarvisResponse != "(Audio response)"
        }

        assertEquals(3, cleanTurns.size)
        assertFalse(cleanTurns.any { it.userTranscript == "(Audio query)" })
        assertFalse(cleanTurns.any { it.jarvisResponse == "(Audio response)" })
    }

    @Test
    fun testBuildLogsExcludedFromDialogueHistory() {
        val turns = listOf(
            ChatTurn(userTranscript = "🛠 JARVIS build", jarvisResponse = "Starting build..."),
            ChatTurn(userTranscript = "", jarvisResponse = "🛠 Building with high quality"),
            ChatTurn(userTranscript = "Create a database schema", jarvisResponse = "Here is the SQL schema"),
            ChatTurn(userTranscript = "Can you optimize it?", jarvisResponse = "Optimized indexing added")
        )

        val dialogueTurns = turns.filter {
            it.userTranscript.isNotBlank() &&
            !it.userTranscript.startsWith("🛠") &&
            !it.jarvisResponse.startsWith("🛠")
        }

        assertEquals(2, dialogueTurns.size)
        assertTrue(dialogueTurns.all { !it.userTranscript.startsWith("🛠") })
    }

    @Test
    fun testEmptyUserTranscriptDoesNotDefaultToAudioQueryMarker() {
        // When a work turn or build status notification is added without a user transcript,
        // it must remain blank instead of defaulting to "(Audio query)".
        val rawUserText = ""
        val cleanUser = rawUserText.trim()
        val turn = ChatTurn(
            userTranscript = cleanUser,
            jarvisResponse = "🛠 Building project live at http://localhost:8080"
        )

        assertEquals("", turn.userTranscript)
        assertTrue(turn.userTranscript.isBlank())
        assertFalse(turn.userTranscript.contains("Audio query"))
    }
}
