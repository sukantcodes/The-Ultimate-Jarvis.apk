package com.jarvis.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class JarvisTTS(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isInitialized = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Defaulting to English on startup
            val result = tts?.setLanguage(Locale.ENGLISH)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isInitialized = true
            }
        }
    }

    // Function with language support (English, Hindi, Bengali, Kannada, Urdu)
    fun speak(text: String, langCode: String = "en") {
        if (!isInitialized) return

        val locale = when (langCode.lowercase()) {
            "hi", "hindi" -> Locale("hi", "IN")
            "bn", "bengali" -> Locale("bn", "IN")
            "kn", "kannada" -> Locale("kn", "IN")
            "ur", "urdu" -> Locale("ur", "IN")
            else -> Locale.ENGLISH
        }

        val result = tts?.setLanguage(locale)
        if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
