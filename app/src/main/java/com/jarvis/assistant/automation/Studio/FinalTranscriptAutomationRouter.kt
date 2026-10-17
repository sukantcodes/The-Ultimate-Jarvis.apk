package com.jarvis.assistant.automation.studio


/**
 * Additive adapter for hosts that have a complete user transcript BEFORE sending
 * that text turn to Gemini. It cannot intercept audio that has already been sent
 * through a Gemini Live streaming connection.
 *
 * A caller supplies a stable turnId and must invoke routeBeforeGemini only once
 * per completed user turn. Duplicate calls with the same active turnId pass
 * through to the existing Gemini callback only if the host explicitly retries;
 * therefore callers should treat DuplicateTurn as a no-op and avoid resubmission.
 */
class FinalTranscriptAutomationRouter(
    private val gate: AutomationStudioGate
) {
    private val turnLock = Any()
    private val activeOrCompletedTurnIds = LinkedHashSet<String>()
    private val maxRememberedTurns = 256

    private fun rememberTurn(turnId: String): Boolean = synchronized(turnLock) {
        if (!activeOrCompletedTurnIds.add(turnId)) return@synchronized false
        while (activeOrCompletedTurnIds.size > maxRememberedTurns) {
            val oldest = activeOrCompletedTurnIds.firstOrNull() ?: break
            activeOrCompletedTurnIds.remove(oldest)
        }
        true
    }

    private fun forgetTurn(turnId: String) {
        synchronized(turnLock) { activeOrCompletedTurnIds.remove(turnId) }
    }

    sealed class RouteResult {
        data class HandledLocally(val response: InterceptionResult.Handled) : RouteResult()
        data class SentToGemini(val reason: String) : RouteResult()
        data class DuplicateTurn(val turnId: String) : RouteResult()
    }

    /**
     * Must be called from a finalized-text path before that text is submitted to Gemini.
     * `sendToGemini` should be the host's existing text submission, unchanged.
     * `onLocalHandled` should render/speak the local response using the app's existing
     * response path. Do not call both for one turn.
     */
    suspend fun routeBeforeGemini(
        turnId: String,
        finalTranscript: String,
        sendToGemini: suspend (String) -> Unit,
        onLocalHandled: suspend (InterceptionResult.Handled) -> Unit
    ): RouteResult {
        if (turnId.isBlank()) {
            sendToGemini(finalTranscript)
            return RouteResult.SentToGemini("Missing stable turn id; fail-open to existing Gemini path.")
        }

        if (!rememberTurn(turnId)) {
            return RouteResult.DuplicateTurn(turnId)
        }

        return try {
            val decision = gate.tryLocalFirst(finalTranscript)
            if (decision.handled && decision.result is InterceptionResult.Handled) {
                // The gate's own onHandled callback should be configured as a no-op when
                // this callback owns rendering, to avoid announcing the same result twice.
                onLocalHandled(decision.result)
                RouteResult.HandledLocally(decision.result)
            } else {
                sendToGemini(finalTranscript)
                RouteResult.SentToGemini(
                    (decision.result as? InterceptionResult.PassThrough)?.reason
                        ?: "Local automation did not handle the turn."
                )
            }
        } catch (t: Throwable) {
            // Cancellation must propagate; other errors should retain existing AI behavior.
            if (t is kotlinx.coroutines.CancellationException) {
                forgetTurn(turnId)
                throw t
            }
            sendToGemini(finalTranscript)
            RouteResult.SentToGemini("Automation router failed; continued through Gemini.")
        }
    }

    /** Call only when the host knows a turn was abandoned and will never be submitted. */
    fun forgetAbandonedTurn(turnId: String) {
        forgetTurn(turnId)
    }
}
