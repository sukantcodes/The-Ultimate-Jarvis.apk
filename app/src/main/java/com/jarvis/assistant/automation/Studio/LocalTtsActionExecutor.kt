package com.jarvis.assistant.automation.studio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lifecycle-safe-enough local TTS helper for short automation acknowledgements.
 * Call close() when the owning service/application component is permanently disposed.
 */
class LocalTtsActionExecutor(
    context: Context,
    private val locale: Locale = Locale.getDefault()
) : AutoCloseable {
    private val initialized = AtomicBoolean(false)
    @Volatile private var engine: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine
            val success = status == TextToSpeech.SUCCESS && tts != null &&
                tts.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE
            initialized.set(success)
            if (!ready.isCompleted) ready.complete(success)
        }
    }

    suspend fun speak(text: String): ActionExecutionResult {
        if (text.isBlank()) return ActionExecutionResult(false, "Nothing to speak.")
        val isReady = withTimeoutOrNull(3_000L) { ready.await() } ?: false
        if (!isReady || !initialized.get()) {
            return ActionExecutionResult(false, "Local speech engine is not ready.")
        }
        val result = engine?.speak(text.take(1_000), TextToSpeech.QUEUE_FLUSH, null, "studio-${System.nanoTime()}")
        return if (result == TextToSpeech.SUCCESS) {
            ActionExecutionResult(true, text, shouldSpeak = false)
        } else {
            ActionExecutionResult(false, "Local speech could not be started.")
        }
    }

    override fun close() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        initialized.set(false)
    }
}
