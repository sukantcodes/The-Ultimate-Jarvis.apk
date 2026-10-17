package com.jarvis.assistant.automation.studio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Invoke this from the app's finalized-transcript path BEFORE submitting that transcript
 * to Gemini. Do not call it for partial streaming transcript chunks.
 *
 * Handled means the caller must not send the command to Gemini.
 * PassThrough means the caller should continue its existing AI flow unchanged.
 */
class PriorityInterceptorService(
    context: Context,
    private val repository: RuleRepository,
    private val dispatcher: StudioActionDispatcher
) {
    private val enabledPreference = context.applicationContext
        .getSharedPreferences("automation_studio_settings", Context.MODE_PRIVATE)
    private val running = AtomicBoolean(false)
    private val matcher = RuleMatcher()

    fun isEnabled(): Boolean = enabledPreference.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        enabledPreference.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    suspend fun interceptFinalTranscript(transcript: String): InterceptionResult =
        withContext(Dispatchers.IO) {
            if (!isEnabled()) return@withContext InterceptionResult.PassThrough("Automation Studio is disabled.")
            if (transcript.isBlank()) return@withContext InterceptionResult.PassThrough("Empty transcript.")
            if (!running.compareAndSet(false, true)) {
                return@withContext InterceptionResult.PassThrough("Another local rule is already executing.")
            }
            try {
                val match = repository.getAll()
                    .asSequence()
                    .filter { it.enabled && it.triggerType == RuleTriggerType.VOICE_COMMAND }
                    .sortedWith(compareBy<AutomationRule> { it.priority }.thenBy { it.name })
                    .mapNotNull { matcher.match(it, transcript) }
                    .firstOrNull()
                    ?: return@withContext InterceptionResult.PassThrough()

                // Execute actions outside the IO context if executors need Android main-thread APIs.
                val actionResults = kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                    dispatcher.execute(match)
                }
                val response = actionResults
                    .joinToString(" ") { it.message }
                    .ifBlank { "Done." }
                InterceptionResult.Handled(match.rule.id, response, actionResults)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                InterceptionResult.Failed(null, "I couldn't complete that local automation.", t)
            } finally {
                running.set(false)
            }
        }

    companion object {
        private const val KEY_ENABLED = "enabled"
    }
}
