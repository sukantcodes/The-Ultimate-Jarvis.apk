package com.jarvis.assistant.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.jarvis.assistant.clock.ClockAssistant
import com.jarvis.assistant.clock.voice.ClockCommandParser
import com.jarvis.assistant.clock.voice.ClockIntent
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-screen assistant entry point.
 *
 * Keep this session alive while listening and speaking. Alarm commands use the same ClockAssistant/ClockEngine as the
 * in-app clock, so a confirmed voice result is immediately visible in the Alarms screen and is scheduled by AlarmManager.
 */
class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val utteranceIds = AtomicLong(0L)

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var sessionActive = false
    private var ttsReady = false
    private var waitingForClarification = false
    @Volatile private var detectedSpeechLanguage: String? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sessionActive = true
        waitingForClarification = false
        detectedSpeechLanguage = null
        releaseSpeechResources()
        initializeSpeech()
    }

    private fun initializeSpeech() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speak("To use voice alarms from the lock screen, unlock your phone once and allow microphone permission for JARVIS.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            speak("Voice recognition is not available on this phone right now.")
            return
        }

        ttsReady = false
        tts = TextToSpeech(context.applicationContext) { status ->
            if (!sessionActive) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.getDefault()
                startListening()
            } else {
                Log.w(TAG, "Text to speech initialization failed: $status")
                startListening()
            }
        }
    }

    private fun startListening() {
        if (!sessionActive) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speak("Microphone permission is required. Please unlock the phone and allow it for JARVIS.")
            return
        }
        try {
            val speech = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { created ->
                created.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onLanguageDetection(results: Bundle) {
                        if (Build.VERSION.SDK_INT >= 34) {
                            val tag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                            if (!tag.isNullOrBlank()) {
                                detectedSpeechLanguage = tag
                                Log.d(TAG, "Speech language detected: $tag")
                            }
                        }
                    }

                    override fun onError(error: Int) {
                        if (!sessionActive) return
                        val message = when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Microphone permission is missing. Unlock your phone and allow microphone access for JARVIS."
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "I didn't catch that. Please invoke JARVIS and say your alarm time again."
                            else -> "I couldn't hear the command. Please try again."
                        }
                        speak(message)
                    }

                    override fun onResults(results: Bundle?) {
                        if (!sessionActive) return
                        val spoken = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (spoken.isBlank()) {
                            speak("I didn't catch that. Please try again.")
                            return
                        }
                        handleSpokenAlarm(spoken)
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit
                })
                recognizer = created
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Android 14+ can detect and switch languages when the installed recognition service
                // has the corresponding speech models. Do not restrict the user to one locale.
                if (Build.VERSION.SDK_INT >= 34) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
                }
            }
            speech.startListening(intent)
        } catch (e: SecurityException) {
            Log.w(TAG, "Lock-screen microphone access was refused", e)
            speak("Microphone access was refused. Unlock your phone and check JARVIS microphone permission.")
        } catch (e: Exception) {
            Log.e(TAG, "Could not start lock-screen speech recognition", e)
            speak("Voice recognition could not start. Please try again.")
        }
    }

    private fun handleSpokenAlarm(spoken: String) {
        worker.execute {
            val hasPendingAnswer = runCatching { ClockAssistant.hasPendingQuestion(context) }.getOrDefault(false)
            val parsed = ClockCommandParser.parse(spoken)
            val isAlarmCreation = parsed?.intent is ClockIntent.SetAlarm
            val reply = if (hasPendingAnswer || isAlarmCreation) {
                runCatching { ClockAssistant.handleText(context, spoken, detectedSpeechLanguage) }.getOrElse {
                    Log.e(TAG, "Lock-screen alarm command failed", it)
                    null
                }
            } else null

            val finalReply = reply ?: if (isAlarmCreation || hasPendingAnswer) {
                "I couldn't complete that alarm command. Please try again."
            } else {
                "I can set alarms from the lock screen. For example, say: set an alarm for 6:30 AM."
            }
            val stillNeedsAnswer = runCatching { ClockAssistant.hasPendingQuestion(context) }.getOrDefault(false)
            main.post {
                if (!sessionActive) return@post
                waitingForClarification = stillNeedsAnswer
                speak(finalReply)
            }
        }
    }

    private fun speak(message: String) {
        if (!sessionActive) return
        val engine = tts
        if (engine == null || !ttsReady) {
            // If TTS is still starting, retry once it reports ready. A missing TTS engine is handled without a false success.
            if (engine == null) {
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (!sessionActive) return@TextToSpeech
                    if (status == TextToSpeech.SUCCESS) {
                        ttsReady = true
                        tts?.language = Locale.getDefault()
                        speak(message)
                    } else {
                        finishSession()
                    }
                }
            }
            return
        }
        val id = "jarvis-lockscreen-${utteranceIds.incrementAndGet()}"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post {
                    if (!sessionActive) return@post
                    if (waitingForClarification) startListening() else finishSession()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { if (sessionActive) finishSession() }
            }
        })
        engine.speak(message, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    private fun finishSession() {
        if (!sessionActive) return
        sessionActive = false
        releaseSpeechResources()
        hide()
    }

    private fun releaseSpeechResources() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ttsReady = false
    }

    override fun onHide() {
        sessionActive = false
        releaseSpeechResources()
        super.onHide()
    }

    companion object {
        private const val TAG = "JarvisLockScreen"
    }
}
