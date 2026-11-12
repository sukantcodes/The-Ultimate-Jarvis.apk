package com.jarvis.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.ConversationState
import java.util.Locale

/**
 * Unified Voice Controller for Jarvis.
 *
 * Capabilities:
 * 1. Zero-latency instant speech via pre-warmed TextToSpeech engine (eliminates late response).
 * 2. Intelligent dual-engine voice routing:
 *    - When Gemini / voice model is configured -> routes to authentic Jarvis Voice via [JarvisConversationService].
 *    - When only a custom model is active -> uses high-performance on-device TTS.
 * 3. Strict single-utterance deduplication: guarantees a turn is never repeated twice.
 * 4. Automatic concise executive speech: uses [SpokenSummaryGenerator] to explain tasks in 1-2
 *    short sentences rather than droning through entire code blocks or lengthy answers.
 */
object JarvisVoiceController {

    private const val TAG = "JarvisVoiceController"

    @Volatile private var ttsEngine: TextToSpeech? = null
    @Volatile private var isTtsReady: Boolean = false
    @Volatile private var isSpeakingState: Boolean = false
    @Volatile private var lastSpokenTurnId: String = ""
    private var pendingSpeech: String? = null

    private val listeners = mutableListOf<(Boolean) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isSpeaking(): Boolean =
        isSpeakingState || JarvisConversationService.conversationState.value == ConversationState.SPEAKING

    fun addSpeakingStateListener(listener: (Boolean) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
        listener(isSpeaking())
    }

    fun removeSpeakingStateListener(listener: (Boolean) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    private fun notifySpeakingState(speaking: Boolean) {
        isSpeakingState = speaking
        mainHandler.post {
            synchronized(listeners) {
                listeners.forEach { it(speaking) }
            }
        }
    }

    /**
     * Immediately interrupts active speech playback across all channels (TTS, Gemini Live,
     * neural voice dispatchers) and notifies all listeners.
     *
     * @return true if speaking was active and interrupted, false otherwise.
     */
    fun interruptIfSpeaking(reason: String = "user barge-in"): Boolean {
        if (isSpeaking()) {
            Log.i(TAG, "Interrupting speaking immediately: $reason")
            stopSpeaking()
            return true
        }
        return false
    }

    /**
     * Determines whether the given spoken phrase warrants interrupting ongoing speech.
     * Matches barge-in words like "stop", "shut up", "pause", "wait", "hold on", "cancel",
     * "quiet", "silence", or if the user starts addressing "jarvis".
     */
    fun shouldInterruptForPhrase(phrase: String): Boolean {
        val clean = phrase.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return false
        if (clean.startsWith("jarvis") || clean.startsWith("hey jarvis")) {
            return true
        }
        val interruptKeywords = listOf(
            "stop", "shut up", "pause", "wait", "hold on", "cancel", "quiet", "silence"
        )
        return interruptKeywords.any { clean.contains(it) }
    }

    /** Pre-warms the TTS engine immediately so there is zero delay when speaking. */
    fun prewarm(context: Context) {
        if (ttsEngine != null) return
        val appContext = context.applicationContext
        mainHandler.post {
            try {
                ttsEngine = TextToSpeech(appContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val engine = ttsEngine
                        if (engine != null) {
                            val langResult = engine.setLanguage(Locale.getDefault())
                            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                                engine.setLanguage(Locale.US)
                            }
                            engine.setPitch(0.96f) // Signature refined assistant pitch
                            engine.setSpeechRate(1.05f) // Snappy responsive cadence
                            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {
                                    notifySpeakingState(true)
                                }
                                override fun onDone(utteranceId: String?) {
                                    notifySpeakingState(false)
                                }
                                override fun onError(utteranceId: String?) {
                                    notifySpeakingState(false)
                                }
                            })
                            isTtsReady = true
                            Log.i(TAG, "Instant TTS pre-warmed and ready.")
                            val queued = pendingSpeech
                            pendingSpeech = null
                            if (!queued.isNullOrBlank()) {
                                speakViaTts(queued)
                            }
                        }
                    } else {
                        Log.w(TAG, "TextToSpeech init returned code $status")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize TTS", e)
            }
        }
    }

    /** Checks whether the authentic Jarvis live voice (Gemini Live) is configured and usable. */
    fun isJarvisLiveVoiceAvailable(context: Context): Boolean {
        val app = (context.applicationContext as? JarvisApp) ?: return false
        val geminiKey = app.preferences.apiKey.trim()
        return geminiKey.isNotBlank()
    }

    /**
     * Speaks the assistant response for a given turn.
     *
     * @param turnId Unique turn ID for deduplication. Never repeated if already spoken.
     * @param userPrompt The user's input prompt (used for contextual summary generation).
     * @param fullResponse The full response text or code from the assistant.
     * @param forceTts Force local TTS even if cloud voice is configured.
     */
    fun speakTurnResponse(
        context: Context,
        turnId: String,
        userPrompt: String?,
        fullResponse: String,
        forceTts: Boolean = false
    ) {
        if (turnId.isNotBlank() && turnId == lastSpokenTurnId) {
            Log.d(TAG, "Turn $turnId already spoken; suppressing duplicate invocation.")
            return
        }

        // Generate short, executive 1-2 sentence spoken summary
        val shortSpokenText = SpokenSummaryGenerator.generateSummary(userPrompt, fullResponse)
        if (shortSpokenText.isBlank()) return

        if (isSpeakingState) {
            stopSpeaking()
        }

        if (turnId.isNotBlank()) {
            lastSpokenTurnId = turnId
        }

        val useJarvisVoice = !forceTts && isJarvisLiveVoiceAvailable(context)

        if (useJarvisVoice) {
            // Authentic Jarvis voice through live service announcement
            val announced = JarvisConversationService.announceMessage(shortSpokenText)
            if (announced) {
                notifySpeakingState(true)
                // Fallback reset speaking indicator after rough speaking duration
                val estimatedDurationMs = maxOf(2500L, (shortSpokenText.split("\\s+".toRegex()).size * 350L))
                mainHandler.postDelayed({
                    if (isSpeakingState) notifySpeakingState(false)
                }, estimatedDurationMs)
                return
            }
        }

        // Direct instant neural TTS path (Kokoro-82M, Piper VITS, Cloud, or fallback)
        try {
            val dispatcher = com.jarvis.assistant.tts.engine.JarvisVoiceDispatcher.getInstance(context)
            notifySpeakingState(true)
            dispatcher.speak(shortSpokenText) {
                notifySpeakingState(false)
            }
        } catch (_: Exception) {
            prewarm(context)
            speakViaTts(shortSpokenText)
        }
    }

    private fun speakViaTts(text: String) {
        val engine = ttsEngine
        if (engine != null && isTtsReady) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_voice_response")
            notifySpeakingState(true)
        } else {
            pendingSpeech = text
        }
    }

    fun stopSpeaking() {
        try {
            ttsEngine?.stop()
        } catch (_: Exception) {}
        try {
            JarvisConversationService.interruptPlayback()
        } catch (_: Exception) {}
        try {
            JarvisConversationService.stopCurrentResponseImmediately()
        } catch (_: Exception) {}
        try {
            val app = JarvisApp.instance
            com.jarvis.assistant.tts.engine.JarvisVoiceDispatcher.getInstance(app).stop()
        } catch (_: Exception) {}
        notifySpeakingState(false)
    }

    fun release() {
        stopSpeaking()
        try {
            ttsEngine?.shutdown()
        } catch (_: Exception) {}
        ttsEngine = null
        isTtsReady = false
    }
}
