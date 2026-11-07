package com.jarvis.assistant.automation.typing

import com.jarvis.assistant.automation.accessibility.TypingText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** States of one typing operation. Every state is bounded by a timeout; none can wait forever. */
enum class TypingState {
    IDLE, NAVIGATING, LOCATING_FIELD, REQUESTING_FOCUS, WAITING_FOR_READY_FIELD, TYPING, VERIFYING,
    COMPLETED, RECOVERY,
    /** Terminal: could not finish for a reason that retrying will not fix. */
    FAILED,
    /** Terminal: a real blocker (protected field, no editable field) needs the user. */
    BLOCKED,
    CANCELLED
}

/** Why a typing task ended without typing. Used so the spoken reason is the real one. */
enum class TypingFailure {
    NONE, APP_NOT_FOREGROUND, NO_EDITABLE_FIELD, PROTECTED_FIELD, FOCUS_NOT_ACQUIRED, FIELD_NOT_READY,
    TYPE_REJECTED, VERIFY_MISMATCH, TIMEOUT, CANCELLED, BLANK_TEXT
}

/** A located input field. [handle] is opaque to the session; the host maps it back to a live node. */
data class FieldInfo(
    val handle: Long,
    val packageName: String = "",
    val label: String = "",
    val viewId: String? = null,
    val editable: Boolean = true,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val canSetText: Boolean = true,
    val isAddressBar: Boolean = false
)

sealed class FieldProbe {
    data class Found(val info: FieldInfo) : FieldProbe()
    /** No editable field on screen (yet). May appear after a screen transition. */
    object NotFound : FieldProbe()
    /** A field exists but must not be typed into (password, secure, disabled by policy). Never retried. */
    data class Protected(val reason: String) : FieldProbe()
}

enum class Readiness { READY, NOT_READY, STALE }

data class TypeAttempt(val verified: Boolean, val detail: String)

/**
 * Everything the session needs from the device. Production code backs this with JarvisAccessibilityService;
 * unit tests back it with a fake. All functions may block briefly and are called off the main thread.
 */
interface TypingHost {
    fun foregroundPackage(): String?
    /** Suspends until the UI reports a change (accessibility event) or [timeoutMs] passes. True if a change arrived. */
    suspend fun awaitUiChange(timeoutMs: Long): Boolean
    fun probeField(preferAddressBar: Boolean): FieldProbe
    fun requestFocus(handle: Long): Boolean
    fun readiness(handle: Long): Readiness
    /** Text currently in the field, "" when it only shows a hint, null when unreadable/stale. */
    fun readField(handle: Long): String?
    /** Writes [text] as the field's entire value and reports whether it was read back. */
    suspend fun typeInto(handle: Long, text: String, allowAddressBar: Boolean): TypeAttempt
}

data class TypingConfig(
    val navigationTimeoutMs: Long = 12_000,
    val fieldTimeoutMs: Long = 6_000,
    val focusTimeoutMs: Long = 3_000,
    val readyTimeoutMs: Long = 4_000,
    val typeTimeoutMs: Long = 10_000,
    val maxAttempts: Int = 3,
    val overallDeadlineMs: Long = 60_000,
    val eventWaitMs: Long = 250
)

data class TypingResult(
    val success: Boolean,
    val state: TypingState,
    val failure: TypingFailure,
    val message: String,
    val attempts: Int,
    /** What the field actually contained at the end, when it could be read. Never logged. */
    val finalText: String? = null
)

/**
 * Drives navigation-then-typing as ONE continuous operation:
 * NAVIGATING → LOCATING_FIELD → REQUESTING_FOCUS → WAITING_FOR_READY_FIELD → TYPING → VERIFYING → COMPLETED,
 * with RECOVERY (re-inspect, relocate, refocus, retry) on any recoverable failure.
 *
 * The exact [text] is a constructor value: it cannot be lost, rewritten or emptied between states. Readiness is
 * decided from accessibility events plus bounded polling, not fixed sleeps. Success is only ever reported after
 * the text was read back from the field.
 */
class TypingSession(
    private val host: TypingHost,
    private val text: String,
    private val mode: TypeMode = TypeMode.APPEND,
    private val expectedPackage: String? = null,
    private val allowAddressBar: Boolean = false,
    private val config: TypingConfig = TypingConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val isCancelled: () -> Boolean = { false },
    private val onState: (TypingState) -> Unit = {}
) {
    @Volatile var state: TypingState = TypingState.IDLE
        private set

    private var baseline: String = ""
    private var baselineKnown = false
    private var deadline = 0L

    private fun enter(next: TypingState) {
        if (state != next) { state = next; onState(next) }
    }

    private fun result(
        success: Boolean, st: TypingState, failure: TypingFailure, msg: String, attempts: Int, finalText: String? = null
    ): TypingResult { enter(st); return TypingResult(success, st, failure, msg, attempts, finalText) }

    suspend fun run(): TypingResult {
        if (text.isBlank() && mode != TypeMode.CLEAR) {
            return result(false, TypingState.FAILED, TypingFailure.BLANK_TEXT, "There is no text to type.", 0)
        }
        deadline = clock() + config.overallDeadlineMs
        var attempts = 0
        var lastFailure = TypingFailure.NONE
        var lastMessage = ""
        try {
            // 1) NAVIGATING: wait for the target app, bounded, event driven.
            if (!expectedPackage.isNullOrBlank()) {
                enter(TypingState.NAVIGATING)
                if (!waitFor(config.navigationTimeoutMs) { packageMatches() }) {
                    return result(false, TypingState.FAILED, TypingFailure.APP_NOT_FOREGROUND,
                        "The app did not come to the foreground.", 0)
                }
            }

            while (attempts < config.maxAttempts) {
                checkStop()?.let { return it.copy(attempts = attempts) }
                attempts++
                if (attempts > 1) {
                    enter(TypingState.RECOVERY)   // re-inspect window, relocate field, refocus, retry
                    host.awaitUiChange(config.eventWaitMs)
                }

                // 2) LOCATING_FIELD
                enter(TypingState.LOCATING_FIELD)
                var info: FieldInfo? = null
                var protectedReason: String? = null
                val located = waitFor(config.fieldTimeoutMs) {
                    when (val p = host.probeField(allowAddressBar)) {
                        is FieldProbe.Found -> { info = p.info; true }
                        is FieldProbe.Protected -> { protectedReason = p.reason; true }
                        FieldProbe.NotFound -> false
                    }
                }
                protectedReason?.let {
                    return result(false, TypingState.BLOCKED, TypingFailure.PROTECTED_FIELD,
                        "I did not type because this field is protected: $it.", attempts)
                }
                val firstField = info
                if (!located || firstField == null) {
                    lastFailure = TypingFailure.NO_EDITABLE_FIELD
                    lastMessage = "I could not find a text field on the screen."
                    continue
                }
                var field: FieldInfo = firstField
                if (!field.enabled) {
                    lastFailure = TypingFailure.FIELD_NOT_READY
                    lastMessage = "The text field is disabled."
                    continue
                }

                // 3) REQUESTING_FOCUS: only when the field does not already own input focus.
                if (!field.focused || !field.editable) {
                    enter(TypingState.REQUESTING_FOCUS)
                    host.requestFocus(field.handle)
                    var refocused: FieldInfo? = null
                    val gotFocus = waitFor(config.focusTimeoutMs) {
                        val p = host.probeField(allowAddressBar)
                        if (p is FieldProbe.Found && p.info.focused && p.info.editable && p.info.enabled) {
                            refocused = p.info; true
                        } else false
                    }
                    val rf = refocused
                    if (!gotFocus || rf == null) {
                        lastFailure = TypingFailure.FOCUS_NOT_ACQUIRED
                        lastMessage = "I could not put the cursor in the text field."
                        continue
                    }
                    field = rf
                }

                // 4) WAITING_FOR_READY_FIELD: keyboard visible is NOT readiness; the live node must say so.
                enter(TypingState.WAITING_FOR_READY_FIELD)
                var stale = false
                val ready = waitFor(config.readyTimeoutMs) {
                    when (host.readiness(field.handle)) {
                        Readiness.READY -> true
                        Readiness.STALE -> { stale = true; true }
                        Readiness.NOT_READY -> false
                    }
                }
                if (stale || !ready) {
                    lastFailure = TypingFailure.FIELD_NOT_READY
                    lastMessage = if (stale) "The field changed while I was waiting." else "The text field was not ready for input."
                    continue
                }

                // Remember what was there before the first write so APPEND stays idempotent across retries.
                if (!baselineKnown) {
                    baseline = host.readField(field.handle).orEmpty()
                    baselineKnown = true
                }
                val target = targetText()

                // Already holds the requested content (e.g. a previous attempt landed but readback lagged).
                // Only an EXACT match counts here: a loose match (text merely contained in other text) must still be typed.
                if (mode != TypeMode.CLEAR && TypingText.match(host.readField(field.handle), target) == TypingText.Match.EXACT) {
                    return result(true, TypingState.COMPLETED, TypingFailure.NONE, "Text entered and verified.",
                        attempts, host.readField(field.handle))
                }

                // 5) TYPING
                enter(TypingState.TYPING)
                val attempt = withTimeoutOrNull(config.typeTimeoutMs) {
                    host.typeInto(field.handle, target, allowAddressBar)
                }
                if (attempt == null) {
                    lastFailure = TypingFailure.TIMEOUT
                    lastMessage = "Typing took too long."
                    continue
                }

                // 6) VERIFYING: re-read the live field; never trust the write call alone.
                enter(TypingState.VERIFYING)
                var finalText: String? = null
                val verified = waitFor(config.eventWaitMs * 4) {
                    finalText = host.readField(field.handle)
                    matches(finalText, target)
                }
                if (verified) {
                    return result(true, TypingState.COMPLETED, TypingFailure.NONE,
                        if (mode == TypeMode.CLEAR) "Field cleared." else "Text entered and verified.",
                        attempts, finalText)
                }
                lastFailure = if (attempt.verified) TypingFailure.VERIFY_MISMATCH else TypingFailure.TYPE_REJECTED
                lastMessage = "The text was not confirmed in the field (${attempt.detail})."
            }
            return result(false, TypingState.FAILED, lastFailure.takeIf { it != TypingFailure.NONE } ?: TypingFailure.TIMEOUT,
                lastMessage.ifBlank { "I could not finish typing." }, attempts)
        } catch (c: CancellationException) {
            enter(TypingState.CANCELLED)
            throw c
        }
    }

    /** The complete field value to write. The requested text is used verbatim. */
    private fun targetText(): String = when (mode) {
        TypeMode.CLEAR -> ""
        TypeMode.REPLACE -> text
        TypeMode.APPEND, TypeMode.CONTINUE -> baseline + text
    }

    private fun matches(current: String?, target: String): Boolean {
        if (current == null) return false
        if (target.isEmpty()) return current.isEmpty()
        return TypingText.isVerified(current, target)
    }

    private fun packageMatches(): Boolean {
        val fg = host.foregroundPackage().orEmpty()
        val expected = expectedPackage.orEmpty()
        return fg.isNotBlank() && fg.contains(expected, ignoreCase = true)
    }

    /** Cancellation / overall deadline, checked between steps. */
    private suspend fun checkStop(): TypingResult? {
        currentCoroutineContext().ensureActive()
        if (isCancelled()) return result(false, TypingState.CANCELLED, TypingFailure.CANCELLED, "Typing was cancelled.", 0)
        if (clock() >= deadline) return result(false, TypingState.FAILED, TypingFailure.TIMEOUT, "Typing ran out of time.", 0)
        return null
    }

    /**
     * Polls [condition] until true or [timeoutMs] passes. Between polls it waits for a real UI event (bounded),
     * so it reacts immediately to transitions without busy loops or fixed sleeps.
     */
    private suspend fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = minOf(clock() + timeoutMs, deadline)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (isCancelled()) return false
            if (condition()) return true
            val remaining = end - clock()
            if (remaining <= 0) return false
            if (!host.awaitUiChange(minOf(config.eventWaitMs, remaining))) delay(minOf(20L, remaining))
        }
    }
}
