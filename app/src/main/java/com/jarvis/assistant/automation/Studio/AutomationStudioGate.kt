package com.jarvis.assistant.automation.studio

import android.util.Log

/**
 * Thin fail-open wrapper around the Round 2 local-first interceptor.
 *
 * Call this ONLY after the host has assembled a complete user transcript.
 * Do not call from each streaming transcription chunk.
 */
class AutomationStudioGate(
    private val interceptor: PriorityInterceptorService,
    private val onHandled: suspend (InterceptionResult.Handled) -> Unit,
    private val onLocalFailure: suspend (InterceptionResult.Failed) -> Unit = {}
) {
    data class Decision(
        val handled: Boolean,
        val result: InterceptionResult
    )

    suspend fun tryLocalFirst(finalTranscript: String): Decision {
        if (finalTranscript.isBlank()) {
            return Decision(false, InterceptionResult.PassThrough("Empty final transcript."))
        }

        return when (val result = interceptor.interceptFinalTranscript(finalTranscript)) {
            is InterceptionResult.Handled -> {
                onHandled(result)
                Decision(true, result)
            }
            is InterceptionResult.PassThrough -> Decision(false, result)
            is InterceptionResult.Failed -> {
                Log.w(TAG, "Local automation failed; allowing existing Gemini path to continue.", result.cause)
                try {
                    onLocalFailure(result)
                } catch (callbackError: Exception) {
                    Log.w(TAG, "Local-failure callback failed.", callbackError)
                }
                // Fail open: don't swallow user intent if local execution failed.
                Decision(false, result)
            }
        }
    }

    companion object {
        private const val TAG = "AutomationStudioGate"
    }
}
