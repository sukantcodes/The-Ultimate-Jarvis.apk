package com.jarvis.assistant.automation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalTaskMapperTest {
    @Test
    fun commasInsideMessagePayloadAreNotSplitIntoTasks() {
        val mapped = UniversalTaskMapper.map("Type Hi, how are you?")
        assertEquals(1, mapped.actions.size)
        assertTrue(mapped.actions.single().title.contains("Hi, how are you?"))
    }

    @Test
    fun intentionallyRepeatedStepsArePreserved() {
        val mapped = UniversalTaskMapper.map("open Chrome then open Chrome")
        assertEquals(2, mapped.actions.size)
    }

    @Test
    fun conversationalUseOfLikeDoesNotBecomeALikeAction() {
        val mapped = UniversalTaskMapper.map("I like watching videos")
        assertFalse(mapped.actions.any { it.title == "Inspect the current like state" })
    }

    @Test
    fun explicitLikeThisVideoIsRecognized() {
        val mapped = UniversalTaskMapper.map("like this video")
        assertTrue(mapped.actions.any { it.title == "Inspect the current like state" })
    }

    @Test
    fun fullConditionalPhraseIsPreserved() {
        val mapped = UniversalTaskMapper.map("Open Spotify when I arrive home")
        assertEquals("when I arrive home", mapped.actions.single().condition)
        assertTrue(mapped.actions.single().description.contains("when I arrive home"))
    }
}
