package com.jarvis.assistant.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientConversationContextStoreTest {
    @Test
    fun storesOnlyUsefulTextAndDoesNotExecuteIt() {
        var now = 10_000L
        val store = AmbientConversationContextStore(clock = { now })
        assertFalse(store.add("[unk]"))
        assertFalse(store.add("noise"))
        assertTrue(store.add("Battery life matters more than camera quality."))
        assertEquals(1, store.size())
        assertTrue(store.contextForPrompt().contains("UNTRUSTED REFERENCE ONLY"))
        assertTrue(store.contextForPrompt().contains("Battery life matters"))
    }

    @Test
    fun expiresOldEntries() {
        var now = 1_000L
        val store = AmbientConversationContextStore(ttlMillis = 500L, clock = { now })
        assertTrue(store.add("I prefer a phone with long battery life."))
        now += 501L
        assertEquals(0, store.size())
        assertEquals("", store.contextForPrompt())
    }

    @Test
    fun enforcesEntryAndCharacterBounds() {
        var now = 1L
        val store = AmbientConversationContextStore(
            ttlMillis = 10_000L,
            maxEntries = 2,
            maxCharacters = 100,
            clock = { now++ }
        )
        assertTrue(store.add("First conversation topic is about battery life."))
        assertTrue(store.add("Second conversation topic is about gaming."))
        assertTrue(store.add("Third conversation topic is about cameras."))
        assertEquals(2, store.size())
        val context = store.contextForPrompt()
        assertFalse(context.contains("First conversation topic"))
        assertTrue(context.contains("Second conversation topic"))
        assertTrue(context.contains("Third conversation topic"))
    }

    @Test
    fun onlyExplicitContextReferenceExportsTranscript() {
        val store = AmbientConversationContextStore()
        store.add("Battery life and gaming performance are important.")
        assertEquals("", store.contextForExplicitRequest("Jarvis, open YouTube"))
        assertTrue(store.contextForExplicitRequest("Jarvis, suggest a phone based on what we discussed").contains("Battery life"))
    }

    @Test
    fun clearRemovesAllContextImmediately() {
        val store = AmbientConversationContextStore()
        store.add("We were comparing two phone models.")
        store.clear()
        assertEquals(0, store.size())
        assertEquals("", store.contextForPrompt())
    }
}
