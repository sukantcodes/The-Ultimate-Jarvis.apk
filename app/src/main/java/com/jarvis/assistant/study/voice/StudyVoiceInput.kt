package com.jarvis.assistant.study.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Handler
import android.os.Looper
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.background.JarvisWakeWordService
import com.jarvis.assistant.util.SpeechRecognizerFactory

/**
 * One spoken question or doubt. Uses the same recognizer selection as ChatBox, and rests the background wake-word listener
 * while the student speaks (only one recognizer can listen at a time), bringing it back afterwards.
 */
class StudyVoiceInput(context: Context) {

    interface Listener {
        fun onListening()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var localeTag = "en-IN"
    private var attempt = 0
    private var failures = 0
    private var lastPartial = ""
    private var active = false
    private var wakePaused = false

    val isListening: Boolean get() = active

    fun start(tag: String, l: Listener) {
        if (active) cancel()
        if (!SpeechRecognizerFactory.available(app)) { l.onError("Speech recognition is not available on this phone. Install or enable the Google app."); return }
        listener = l; localeTag = tag; attempt = 0; failures = 0; lastPartial = ""; active = true
        if (JarvisWakeWordService.isRunning()) {
            wakePaused = true
            JarvisWakeWordService.stopService(app)
            main.postDelayed({ if (active) begin() }, 450L)
        } else begin()
    }

    private fun begin() {
        if (!active) return
        try {
            recognizer?.destroy()
            val r = SpeechRecognizerFactory.create(app, attempt)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listener?.onListening() }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
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
                    when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                            failures++
                            if (failures <= 3 && active) { if (failures > 1) attempt++; main.postDelayed({ begin() }, 500L) }
                            else finish { it.onError("Voice input could not start. Make sure the Google app is installed and enabled.") }
                        }
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            finish { if (lastPartial.isNotEmpty()) it.onFinal(lastPartial) else it.onError("I did not hear a question. Tap the mic and try again.") }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finish { it.onError("Microphone permission is required.") }
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> finish { it.onError("Voice recognition needs a network connection.") }
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> finish { it.onError("This language is not available for voice input on this phone.") }
                        else -> finish { if (lastPartial.isNotEmpty()) it.onFinal(lastPartial) else it.onError("Could not recognise speech ($error).") }
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            }
            r.startListening(intent)
        } catch (e: Exception) {
            finish { it.onError("Voice input could not start: ${e.message ?: e.javaClass.simpleName}") }
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
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    private fun stopInternal() {
        active = false
        listener = null
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        if (wakePaused) {
            wakePaused = false
            main.postDelayed({ try { JarvisConversationService.startWakeWordMonitoring(app) } catch (_: Throwable) {} }, 900L)
        }
    }
}
