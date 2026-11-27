package com.jarvis.assistant.study.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.background.JarvisWakeWordService
import com.jarvis.assistant.study.StudyBoardController
import com.jarvis.assistant.util.SpeechRecognizerFactory
import java.util.Locale

/**
 * One spoken question or doubt. Cycles through installed speech recognition services plus system default fallback,
 * handles locale availability fallback cleanly, rests the background wake-word listener while listening, and falls back
 * to the OS speech prompt dialog if in-process binding is unavailable on the device.
 */
class StudyVoiceInput(context: Context) {

    interface Listener {
        fun onListening()
        fun onPartial(text: String)
        fun onRms(rmsdB: Float) {}
        fun onFinal(text: String)
        fun onError(message: String)
        /** Invoked when in-process SpeechRecognizer cannot bind so the Activity can launch the system speech UI. */
        fun onFallbackPrompt(intent: Intent) {}
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var localeTag = "en-IN"
    private var triedDefaultLocale = false
    private var attempt = 0
    private var failures = 0
    private var lastPartial = ""
    private var active = false
    private var wakePaused = false
    private var stoppingEarly = false

    private val beginRunnable = Runnable { if (active) begin() }
    private val earlyStopTimeout = Runnable {
        if (!active || !stoppingEarly) return@Runnable
        val t = lastPartial.trim()
        finish { if (t.isNotEmpty()) it.onFinal(t) else it.onError("I did not hear a question. Tap the mic and try again.") }
    }

    val isListening: Boolean get() = active

    fun start(tag: String, l: Listener) {
        if (active) cancel()
        listener = l
        localeTag = tag
        triedDefaultLocale = false
        attempt = 0
        failures = 0
        lastPartial = ""
        stoppingEarly = false
        active = true

        if (!SpeechRecognizerFactory.available(app)) {
            val promptIntent = buildPromptIntent(tag)
            if (promptIntent.resolveActivity(app.packageManager) != null) {
                val cb = listener
                stopInternal()
                cb?.onFallbackPrompt(promptIntent)
            } else {
                finish { it.onError("Speech recognition is not available on this phone. Install or enable the Google app.") }
            }
            return
        }

        StudyBoardController.setMicInputActive(true)
        try { JarvisConversationService.onStudyBoardNarrationChanged(app, true) } catch (_: Throwable) {}
        if (JarvisWakeWordService.isRunning()) {
            wakePaused = true
            JarvisWakeWordService.stopService(app)
            main.postDelayed(beginRunnable, 550L)
        } else if (JarvisConversationService.isStandby.value) {
            wakePaused = true
            main.postDelayed(beginRunnable, 280L)
        } else {
            begin()
        }
    }

    private fun buildRecognizeIntent(tag: String, strictPreference: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            if (strictPreference) putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }

    private fun buildPromptIntent(tag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask your question on the Study Board")
        }

    private fun begin() {
        if (!active) return
        try {
            recognizer?.destroy()
            val candidates = SpeechRecognizerFactory.candidates(app)
            val r = if (attempt < candidates.size) {
                SpeechRecognizerFactory.create(app, attempt)
            } else {
                SpeechRecognizer.createSpeechRecognizer(app)
            }
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (active) listener?.onListening()
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    if (active) listener?.onRms(rmsdB)
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (t.isNotEmpty()) { lastPartial = t; listener?.onPartial(t) }
                }
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty().ifEmpty { lastPartial }
                    finish { if (t.isNotEmpty()) it.onFinal(t) else it.onError("I did not hear a question. Tap the mic and try again.") }
                }
                override fun onError(error: Int) {
                    if (stoppingEarly && lastPartial.isNotEmpty()) {
                        finish { it.onFinal(lastPartial) }
                        return
                    }
                    when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                            failures++
                            val maxRetries = (candidates.size + 2).coerceAtLeast(3)
                            if (failures <= maxRetries && active) {
                                if (failures > 1) attempt++
                                main.postDelayed(beginRunnable, 550L)
                            } else {
                                val fallback = buildPromptIntent(localeTag)
                                if (fallback.resolveActivity(app.packageManager) != null) {
                                    val cb = listener
                                    stopInternal()
                                    cb?.onFallbackPrompt(fallback)
                                } else {
                                    finish { it.onError("Voice input could not start. Check that Google Speech Services is enabled.") }
                                }
                            }
                        }
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                            if (!triedDefaultLocale && active) {
                                triedDefaultLocale = true
                                localeTag = Locale.getDefault().toLanguageTag()
                                main.postDelayed(beginRunnable, 200L)
                            } else {
                                finish { it.onError("This language is not available for voice input on this phone.") }
                            }
                        }
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            finish { if (lastPartial.isNotEmpty()) it.onFinal(lastPartial) else it.onError("I did not hear a question. Tap the mic and try again.") }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finish { it.onError("Microphone permission is required.") }
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> finish { it.onError("Voice recognition needs a network connection.") }
                        else -> finish { if (lastPartial.isNotEmpty()) it.onFinal(lastPartial) else it.onError("Could not recognise speech ($error).") }
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(buildRecognizeIntent(localeTag, strictPreference = !triedDefaultLocale))
        } catch (e: Exception) {
            val fallback = buildPromptIntent(localeTag)
            if (fallback.resolveActivity(app.packageManager) != null) {
                val cb = listener
                stopInternal()
                cb?.onFallbackPrompt(fallback)
            } else {
                finish { it.onError("Voice input could not start: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private fun finish(report: (Listener) -> Unit) {
        val l = listener
        stopInternal()
        if (l != null) report(l)
    }

    /** Stops listening and discards what was heard. */
    fun cancel() { stopInternal() }

    /** Ends the current phrase now, using what has been heard so far. */
    fun stopAndUse() {
        if (!active) return
        stoppingEarly = true
        main.removeCallbacks(beginRunnable)
        val r = recognizer
        if (r == null) {
            val t = lastPartial.trim()
            finish { if (t.isNotEmpty()) it.onFinal(t) else it.onError("Listening stopped.") }
            return
        }
        try {
            r.stopListening()
            main.postDelayed(earlyStopTimeout, 900L)
        } catch (_: Exception) {
            val t = lastPartial.trim()
            finish { if (t.isNotEmpty()) it.onFinal(t) else it.onError("Listening stopped.") }
        }
    }

    private fun stopInternal() {
        active = false
        stoppingEarly = false
        listener = null
        main.removeCallbacks(beginRunnable)
        main.removeCallbacks(earlyStopTimeout)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        StudyBoardController.setMicInputActive(false)
        try {
            JarvisConversationService.onStudyBoardNarrationChanged(
                app,
                StudyBoardController.isAudioSuppressionActive()
            )
        } catch (_: Throwable) {}
        if (wakePaused) {
            wakePaused = false
            main.postDelayed({ try { JarvisConversationService.startWakeWordMonitoring(app) } catch (_: Throwable) {} }, 900L)
        }
    }
}
