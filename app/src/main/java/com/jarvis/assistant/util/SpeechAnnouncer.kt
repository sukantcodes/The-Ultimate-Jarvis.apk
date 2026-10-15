package com.jarvis.assistant.util

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks short system announcements (battery, charging and so on) with the phone's own voice.
 * It works in standby, without internet and without using any API quota.
 */
class SpeechAnnouncer(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val counter = AtomicInteger(0)
    private val pending = ArrayDeque<String>()
    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        mainHandler.post {
            try {
                engine = TextToSpeech(appContext) { status ->
                    mainHandler.post { onEngineReady(status == TextToSpeech.SUCCESS) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start text-to-speech: ${e.message}")
            }
        }
    }

    private fun onEngineReady(success: Boolean) {
        val tts = engine
        if (!success || tts == null) {
            Log.w(TAG, "Text-to-speech is not available on this phone")
            return
        }
        try {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val result = tts.setLanguage(Locale("en", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.US)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Text-to-speech setup failed: ${e.message}")
        }
        ready = true
        while (pending.isNotEmpty()) speakNow(pending.removeFirst())
    }

    fun say(text: String) {
        if (text.isBlank()) return
        mainHandler.post {
            if (ready) speakNow(text) else pending.addLast(text)
        }
    }

    private fun speakNow(text: String) {
        try {
            engine?.speak(text, TextToSpeech.QUEUE_ADD, null, "announce_" + counter.incrementAndGet())
        } catch (e: Exception) {
            Log.w(TAG, "Could not speak: ${e.message}")
        }
    }

    fun shutdown() {
        mainHandler.post {
            try {
                engine?.stop()
                engine?.shutdown()
            } catch (_: Exception) {
            }
            engine = null
            ready = false
            pending.clear()
        }
    }

    companion object {
        private const val TAG = "SpeechAnnouncer"
    }
}
