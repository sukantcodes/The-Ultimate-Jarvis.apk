package com.jarvis.assistant.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android requires an assistant app to declare a recognition service. Jarvis does its own listening,
 * so this one simply reports that it cannot be used by other apps.
 */
class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: Exception) {
        }
    }

    override fun onCancel(listener: RecognitionService.Callback?) {}

    override fun onStopListening(listener: RecognitionService.Callback?) {}
}
