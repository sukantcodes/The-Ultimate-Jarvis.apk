package com.jarvis.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisWakeWordGrammarTest {
    @Test
    fun acceptsOnlyConfiguredFinalPhrases() {
        JarvisWakeWordGrammar.phrases.forEach { phrase ->
            assertTrue("Expected wake phrase: $phrase", JarvisWakeWordGrammar.isValidFinalPhrase(phrase))
        }
    }

    @Test
    fun rejectsUnknownNoiseAndSentencesContainingWakePhrase() {
        assertFalse(JarvisWakeWordGrammar.isValidFinalPhrase("[unk]"))
        assertFalse(JarvisWakeWordGrammar.isValidFinalPhrase("music"))
        assertFalse(JarvisWakeWordGrammar.isValidFinalPhrase("please hey jarvis open music"))
    }

    @Test
    fun normalizesCaseAndWhitespace() {
        assertTrue(JarvisWakeWordGrammar.isValidFinalPhrase("  HEY   JARVIS  "))
    }

    @Test
    fun grammarAndValidationPhraseSetsStayAligned() {
        val jsonPhrases = Regex("\"([^\"]+)\"")
            .findAll(JarvisWakeWordGrammar.VOSK_JSON)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(jsonPhrases.containsAll(JarvisWakeWordGrammar.phrases))
        assertTrue(JarvisWakeWordGrammar.phrases.containsAll(jsonPhrases))
    }
}
