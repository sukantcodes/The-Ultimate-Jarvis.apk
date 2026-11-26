package com.jarvis.assistant.study.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Narration for the Study Board. Uses the phone's own text-to-speech engine, so it works offline once the voice is
 * installed. Every utterance has a token; a callback from a stopped or replaced utterance is ignored, so interrupting
 * speech can never confuse the board's state.
 */
class StudyTts(context: Context) {

    interface Callback {
        /** [ok] is false when the utterance failed or the language has no installed voice. */
        fun onFinished(token: Long, ok: Boolean, reason: String)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failedInit = false
    private var pending: (() -> Unit)? = null
    private var callback: Callback? = null
    private var activeToken = 0L
    private var rate = 1f

    fun setCallback(cb: Callback?) { callback = cb }

    fun setRate(r: Float) {
        rate = r.coerceIn(0.7f, 1.6f)
        tts?.setSpeechRate(rate)
    }

    private fun ensure(onReady: () -> Unit) {
        if (ready) { onReady(); return }
        if (failedInit) { onReady(); return }
        pending = onReady
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setSpeechRate(rate)
                    tts?.setOnUtteranceProgressListener(listener)
                } else failedInit = true
                pending?.invoke()
                pending = null
            }
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { finish(utteranceId, true, "") }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { finish(utteranceId, false, "Speech failed.") }
        override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId, false, "Speech failed (code $errorCode).") }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { /* stop() is driven by us; the token already moved on */ }
    }

    private fun finish(utteranceId: String?, ok: Boolean, reason: String) {
        val token = utteranceId?.removePrefix("study_")?.toLongOrNull() ?: return
        main.post { if (token == activeToken) callback?.onFinished(token, ok, reason) }
    }

    /** Speaks [text] and returns its token. A new call replaces whatever is being spoken. */
    fun speak(text: String, locale: Locale): Long {
        val token = ++activeToken
        val clean = text.trim()
        if (clean.isEmpty()) { main.post { if (token == activeToken) callback?.onFinished(token, true, "") }; return token }
        ensure {
            if (token != activeToken) return@ensure
            val engine = tts
            if (!ready || engine == null) { callback?.onFinished(token, false, "Text-to-speech is not available on this phone."); return@ensure }
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                callback?.onFinished(token, false, "No ${locale.displayLanguage} voice is installed. Install one in your phone's text-to-speech settings, or use text-only mode.")
                return@ensure
            }
            val code = engine.speak(clean.take(3500), TextToSpeech.QUEUE_FLUSH, null, "study_$token")
            if (code != TextToSpeech.SUCCESS) callback?.onFinished(token, false, "Speech could not start.")
        }
        return token
    }

    /** Stops speaking. The stopped utterance never reports back. */
    fun stop() {
        activeToken++
        try { tts?.stop() } catch (_: Exception) {}
    }

    val isSpeaking: Boolean get() = try { tts?.isSpeaking == true } catch (_: Exception) { false }

    fun shutdown() {
        activeToken++
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null; ready = false; pending = null; callback = null
    }
}
