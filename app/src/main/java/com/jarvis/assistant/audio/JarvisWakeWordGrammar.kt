package com.jarvis.assistant.audio

/**
 * Constrained offline wake phrases used with the bundled Vosk acoustic model.
 *
 * This is grammar-based wake detection, not a separately trained neural wake-word model.
 * Keeping the grammar and final-result validation in one place prevents them drifting apart.
 */
object JarvisWakeWordGrammar {
    val phrases: Set<String> = linkedSetOf(
        "jarvis",
        "hey jarvis",
        "hi jarvis",
        "hello jarvis",
        "ok jarvis",
        "okay jarvis",
        "wake up jarvis",
        "sun jarvis",
        "suno jarvis",
        "jarvis jarvis"
    )

    /** JSON grammar consumed by Vosk's Recognizer constructor. */
    const val VOSK_JSON = """["jarvis","hey jarvis","hi jarvis","hello jarvis","ok jarvis","okay jarvis","wake up jarvis","sun jarvis","suno jarvis","jarvis jarvis"]"""

    fun normalize(words: Iterable<String>): String =
        words.map { it.trim().lowercase(java.util.Locale.ROOT) }
            .filter { it.isNotEmpty() && it != "[unk]" }
            .joinToString(" ")

    /** Strict final phrase validation: do not wake on a sentence merely containing a phrase. */
    fun isValidFinalPhrase(phrase: String): Boolean =
        normalize(phrase.split(Regex("\\s+"))) in phrases
}
