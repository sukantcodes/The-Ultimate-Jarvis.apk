package com.jarvis.assistant.provider

import android.content.Context

enum class TokenMode(
    val key: String,
    val label: String,
    val tierLabel: String,
    val progressPercent: Int,
    val maxTokens: Int,
    val budgetChars: Int,
    val summary: String,
    val promptInstruction: String
) {
    LOW(
        key = "low",
        label = "Low",
        tierLabel = "✦ Efficiency Tier",
        progressPercent = 33,
        maxTokens = 1500,
        budgetChars = 24_000,
        summary = "Ultra-efficient: minimal token consumption, fast direct answers.",
        promptInstruction = "TOKEN BUDGET: LOW (Ultra-efficient). Keep output concise and punchy. Avoid any unnecessary words or lengthy preamble."
    ),
    HIGH(
        key = "high",
        label = "High",
        tierLabel = "✦ Balanced Tier (Default)",
        progressPercent = 66,
        maxTokens = 4096,
        budgetChars = 48_000,
        summary = "Balanced: fast on simple queries, deep thinking on complex tasks.",
        promptInstruction = "TOKEN BUDGET: HIGH (Balanced work). Answer simple queries in 1-2 direct sentences. Use deep thinking and complete code for complex tasks."
    ),
    MAX(
        key = "max",
        label = "Max",
        tierLabel = "★ PREMIUM UNLIMITED",
        progressPercent = 100,
        maxTokens = 16384,
        budgetChars = 96_000,
        summary = "Premium depth: maximum reasoning capacity, full context & zero shortcuts.",
        promptInstruction = "TOKEN BUDGET: MAX (Premium). Deepest reasoning, full analysis, exhaustive code implementations and edge-case coverage."
    );

    companion object {
        fun fromKey(key: String?): TokenMode = values().firstOrNull { it.key == key } ?: HIGH
    }
}

object TokenModeStore {
    private const val PREFS = "jarvis_token_mode_preferences"
    private const val KEY = "token_mode"

    fun get(context: Context): TokenMode {
        val k = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "high")
        return TokenMode.fromKey(k)
    }

    fun set(context: Context, mode: TokenMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.key).apply()
    }
}
