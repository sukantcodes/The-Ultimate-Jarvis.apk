package com.jarvis.assistant.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiThinkingTest {
    @Test fun gemini_3_uses_a_thinking_level_and_pro_never_gets_minimal() {
        assertEquals("low", GeminiThinking.config("models/gemini-3.1-flash-agent", Thinking.LOW)!!.get("thinkingLevel").asString)
        assertEquals("minimal", GeminiThinking.config("models/gemini-3.1-flash-agent", Thinking.MINIMAL)!!.get("thinkingLevel").asString)
        assertEquals("low", GeminiThinking.config("models/gemini-3-pro", Thinking.MINIMAL)!!.get("thinkingLevel").asString)
    }

    @Test fun gemini_2_5_flash_turns_thinking_off_and_pro_is_left_alone() {
        assertEquals(0, GeminiThinking.config("models/gemini-2.5-flash", Thinking.LOW)!!.get("thinkingBudget").asInt)
        assertNull(GeminiThinking.config("models/gemini-2.5-pro", Thinking.LOW))
    }

    @Test fun default_and_unknown_models_send_nothing() {
        assertNull(GeminiThinking.config("models/gemini-3.1-flash-agent", Thinking.DEFAULT))
        assertNull(GeminiThinking.config("models/some-other-model", Thinking.LOW))
    }

    @Test fun only_a_400_that_mentions_thinking_counts_as_a_rejection() {
        assertTrue(GeminiThinking.isRejection(400, "Unknown field thinkingLevel"))
        assertFalse(GeminiThinking.isRejection(400, "API key not valid"))
        assertFalse(GeminiThinking.isRejection(500, "thinking"))
    }
}
