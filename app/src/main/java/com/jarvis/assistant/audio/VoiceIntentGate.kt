package com.jarvis.assistant.audio

import java.util.Locale

/**
 * Explicit voice-intent boundary.
 *
 * A transcript is actionable only when the user addresses Jarvis at the start
 * of the utterance. Mentioning "Jarvis" later in a sentence is not activation.
 * A separately detected standby wake phrase may authorize exactly one next
 * utterance; callers must consume that one-shot authorization.
 */
object VoiceIntentGate {
    private val wakePrefix = Regex(
        "^(?:(?:hey|hi|hello|ok|okay)\\s+)?jarvis\\b(?:[,.!?;:]|\\s|$)",
        RegexOption.IGNORE_CASE
    )

    fun normalize(text: String): String = text
        .replace('’', '\'')
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    fun hasWakePrefix(text: String): Boolean = wakePrefix.containsMatchIn(normalize(text))

    fun stripWakePrefix(text: String): String =
        normalize(text).replaceFirst(wakePrefix, "").trim().trimStart(',', ':', ';', '-', ' ')

    fun isWakeOnly(text: String): Boolean = hasWakePrefix(text) && stripWakePrefix(text).isBlank()

    /** True for a direct wake-prefixed request, or a caller-supplied one-shot wake token. */
    fun isAuthorized(text: String, oneShotWakeToken: Boolean = false): Boolean =
        hasWakePrefix(text) || oneShotWakeToken
}
