package com.jarvis.assistant.notifications

import java.text.Normalizer

/**
 * Converts notification display names into natural speech text without changing the stored/original name.
 *
 * Social handles commonly contain separators (for example, "rahul_kumar" or "rahul-kumar").
 * Reading those symbols aloud sounds unnatural, so separators and decorative symbols become spaces.
 */
object MessageSpeechSanitizer {
    fun forAnnouncement(raw: String): String {
        if (raw.isBlank()) return ""
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")
            .replace(Regex("[_\\-./\\\\|]+"), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\s'’]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized
    }
}
