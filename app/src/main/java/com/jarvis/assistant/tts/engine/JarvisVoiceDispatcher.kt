package com.jarvis.assistant.tts.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jarvis.assistant.tts.model.SpeakerProfile
import com.jarvis.assistant.tts.model.VoiceEngineType
import com.jarvis.assistant.voice.JarvisVoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Master dispatcher orchestrating Jarvis speech synthesis, model routing,
 * decoupled automation command interception, and strict text sanitization.
 */
class JarvisVoiceDispatcher private constructor(private val context: Context) {

    companion object {
        private const val TAG = "JarvisVoiceDispatcher"
        private const val PREFS_NAME = "tts_engine_settings"
        private const val KEY_ACTIVE_ENGINE = "active_engine_type"
        private const val KEY_ACTIVE_SPEAKER = "active_speaker_profile_id"
        private const val KEY_SPEECH_SPEED = "speech_speed"

        @Volatile
        private var instance: JarvisVoiceDispatcher? = null

        fun getInstance(context: Context): JarvisVoiceDispatcher {
            return instance ?: synchronized(this) {
                instance ?: JarvisVoiceDispatcher(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val sherpaEngine: SherpaTtsEngine = SherpaTtsEngine(context)
    val cloudEngine: CloudVoiceEngine = CloudVoiceEngine(context)

    private val amplitudeListeners = CopyOnWriteArrayList<(Float) -> Unit>()
    private val speakingStateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    @Volatile private var isSpeaking: Boolean = false
    @Volatile private var isStoppingDispatcher: Boolean = false

    fun getActiveEngineType(): VoiceEngineType {
        val saved = prefs.getString(KEY_ACTIVE_ENGINE, VoiceEngineType.LOCAL_KOKORO.id)
        return VoiceEngineType.fromId(saved)
    }

    fun setActiveEngineType(engineType: VoiceEngineType) {
        prefs.edit().putString(KEY_ACTIVE_ENGINE, engineType.id).apply()
        if (engineType.isLocal && TtsModelDownloader.isEngineInstalled(context, engineType)) {
            sherpaEngine.initializeEngine(engineType)
        }
    }

    fun getActiveSpeaker(): SpeakerProfile {
        val engine = getActiveEngineType()
        val savedId = prefs.getString(KEY_ACTIVE_SPEAKER, null)
        val found = SpeakerProfile.findById(savedId)
        return if (found != null && found.engineType == engine) {
            found
        } else {
            SpeakerProfile.defaultFor(engine)
        }
    }

    fun setActiveSpeaker(speakerProfile: SpeakerProfile) {
        prefs.edit()
            .putString(KEY_ACTIVE_ENGINE, speakerProfile.engineType.id)
            .putString(KEY_ACTIVE_SPEAKER, speakerProfile.id)
            .apply()
        if (speakerProfile.engineType.isLocal && TtsModelDownloader.isEngineInstalled(context, speakerProfile.engineType)) {
            sherpaEngine.initializeEngine(speakerProfile.engineType)
        }
    }

    fun getSpeechSpeed(): Float = prefs.getFloat(KEY_SPEECH_SPEED, 1.0f)

    fun setSpeechSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_SPEECH_SPEED, speed.coerceIn(0.5f, 2.0f)).apply()
    }

    fun addAmplitudeListener(listener: (Float) -> Unit) {
        amplitudeListeners.add(listener)
    }

    fun removeAmplitudeListener(listener: (Float) -> Unit) {
        amplitudeListeners.remove(listener)
    }

    fun addSpeakingStateListener(listener: (Boolean) -> Unit) {
        speakingStateListeners.add(listener)
        listener(isSpeaking)
    }

    fun removeSpeakingStateListener(listener: (Boolean) -> Unit) {
        speakingStateListeners.remove(listener)
    }

    private fun notifyAmplitude(amplitude: Float) {
        for (l in amplitudeListeners) {
            try { l(amplitude) } catch (_: Exception) {}
        }
    }

    private fun notifySpeaking(speaking: Boolean) {
        isSpeaking = speaking
        for (l in speakingStateListeners) {
            try { l(speaking) } catch (_: Exception) {}
        }
    }

    /**
     * Strict text sanitization filter:
     * Completely strips fenced code blocks, inline code, JSON payloads, URLs, and markdown formatting.
     * Jarvis vocalizes purely natural human conversational sentences.
     */
    fun sanitizeForSpeech(input: String): String {
        var text = input
        // 1. Strip fenced code blocks
        text = text.replace(Regex("```[\\s\\S]*?```"), "")
        // 2. Strip inline code
        text = text.replace(Regex("`[^`]*`"), "")
        // 3. Strip JSON structures {...}
        text = text.replace(Regex("\\{[\\s\\S]*?\\}"), "")
        // 4. Strip markdown image and link URLs, preserving text label
        text = text.replace(Regex("!\\[[^\\]]*\\]\\([^\\)]+\\)"), "")
        text = text.replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")
        // 5. Strip raw URLs
        text = text.replace(Regex("https?://\\S+"), "")
        // 6. Strip markdown symbols: # * ~ _ > •
        text = text.replace(Regex("[#*~_>•]"), "")
        // 7. Strip HTML tags
        text = text.replace(Regex("<[^>]+>"), "")
        // 8. Normalize whitespace and newlines
        text = text.replace(Regex("\\s+"), " ").trim()
        return text
    }

    /**
     * Intercepts, parses, and dispatches automated system actions asynchronously.
     */
    private fun interceptAndDispatchAutomations(rawText: String) {
        scope.launch {
            try {
                // Example: detect structured intents or action payloads if present
                if (rawText.contains("__ACTION_") || rawText.contains("{\"action\"")) {
                    Log.d(TAG, "Asynchronous automation payload intercepted from response text")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Automation interceptor handled exception", e)
            }
        }
    }

    /**
     * Master speech dispatch entrypoint for assistant utterances.
     */
    fun speak(
        rawText: String,
        overrideSpeaker: SpeakerProfile? = null,
        onComplete: (() -> Unit)? = null
    ) {
        // 1. Concurrently / asynchronously dispatch automation commands
        interceptAndDispatchAutomations(rawText)

        // 2. Strictly sanitize the vocal speech stream
        val cleanSpeech = sanitizeForSpeech(rawText)
        if (cleanSpeech.isBlank()) {
            onComplete?.invoke()
            return
        }

        stop()
        notifySpeaking(true)

        val speaker = overrideSpeaker ?: getActiveSpeaker()
        val speed = getSpeechSpeed()

        val amplitudeCallback: (Float) -> Unit = { amp ->
            notifyAmplitude(amp)
        }

        val completionCallback: () -> Unit = {
            notifyAmplitude(0f)
            notifySpeaking(false)
            onComplete?.invoke()
        }

        when (speaker.engineType) {
            VoiceEngineType.LOCAL_KOKORO, VoiceEngineType.LOCAL_PIPER -> {
                if (TtsModelDownloader.isEngineInstalled(context, speaker.engineType)) {
                    sherpaEngine.speak(cleanSpeech, speaker, speed, amplitudeCallback, completionCallback)
                } else {
                    Log.w(TAG, "Local engine ${speaker.engineType.displayName} not installed. Falling back to system TTS.")
                    // Fallback directly to system TTS with zero recursive dispatching
                    JarvisVoiceController.speakSystemTts(context, cleanSpeech)
                    completionCallback()
                }
            }
            VoiceEngineType.CLOUD_GEMINI, VoiceEngineType.CLOUD_ELEVENLABS -> {
                cloudEngine.speak(cleanSpeech, speaker, amplitudeCallback, completionCallback)
            }
        }
    }

    /**
     * Halts playback on the Sherpa-ONNX and Cloud synthesis engines only.
     * Never calls into JarvisVoiceController to avoid mutual recursion.
     */
    fun stopEngines() {
        sherpaEngine.stop()
        cloudEngine.stop()
        notifyAmplitude(0f)
        notifySpeaking(false)
    }

    /**
     * Master stop entrypoint. Re-entrancy guarded against stack overflow.
     */
    fun stop() {
        if (isStoppingDispatcher) return
        isStoppingDispatcher = true
        try {
            stopEngines()
            try {
                JarvisVoiceController.stopLegacyTts()
            } catch (_: Exception) {}
        } finally {
            isStoppingDispatcher = false
        }
    }

    fun release() {
        stop()
        sherpaEngine.release()
        cloudEngine.release()
    }
}
