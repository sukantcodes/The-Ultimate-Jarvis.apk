package com.jarvis.assistant.provider

import org.junit.Assert.*
import org.junit.Test

class TokenModeTest {

    @Test
    fun fromKey_resolves_all_modes() {
        assertEquals(TokenMode.LOW, TokenMode.fromKey("low"))
        assertEquals(TokenMode.HIGH, TokenMode.fromKey("high"))
        assertEquals(TokenMode.MAX, TokenMode.fromKey("max"))
        assertEquals(TokenMode.HIGH, TokenMode.fromKey("unknown"))
        assertEquals(TokenMode.HIGH, TokenMode.fromKey(null))
    }

    @Test
    fun systemPrompt_contains_adaptive_thinking_and_work_priority() {
        val prompt = ChatContext.systemPrompt("Boss", TokenMode.HIGH)
        assertTrue(prompt.contains("ADAPTIVE THINKING & REASONING COMPLEXITY"))
        assertTrue(prompt.contains("SIMPLE & CASUAL QUESTIONS"))
        assertTrue(prompt.contains("COMPLEX TASKS & CODING"))
        assertTrue(prompt.contains("TOKEN EFFICIENCY — WORK OVER CHATTING"))
        assertTrue(prompt.contains("TOKEN BUDGET: HIGH"))
    }

    @Test
    fun systemPrompt_reflects_token_mode_tier() {
        val lowPrompt = ChatContext.systemPrompt("User", TokenMode.LOW)
        assertTrue(lowPrompt.contains("TOKEN BUDGET: LOW"))

        val maxPrompt = ChatContext.systemPrompt("User", TokenMode.MAX)
        assertTrue(maxPrompt.contains("TOKEN BUDGET: MAX"))
    }
}
