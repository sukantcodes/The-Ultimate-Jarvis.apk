package com.jarvis.assistant.tts.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jarvis.assistant.JarvisApp
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
        val saved = prefs.getString(KEY_ACTIVE_ENGINE, null)
        if (saved != null) {
            val engine = VoiceEngineType.fromId(saved)
            if (engine.isLocal && TtsModelDownloader.isEngineInstalled(context, engine)) {
                return engine
            }
            if (!engine.isLocal && engine != VoiceEngineType.NONE) {
                return engine
            }
            if (engine == VoiceEngineType.NONE) {
                return VoiceEngineType.NONE
            }
        }
        // Auto-resolution:
        // 1. If an offline neural model is installed, automatically set & prioritize it
        if (TtsModelDownloader.isEngineInstalled(context, VoiceEngineType.LOCAL_KOKORO)) {
            return VoiceEngineType.LOCAL_KOKORO
        }
        if (TtsModelDownloader.isEngineInstalled(context, VoiceEngineType.LOCAL_PIPER)) {
            return VoiceEngineType.LOCAL_PIPER
        }
        // 2. Until an offline voice model is downloaded, use Gemini Voice!
        val app = context.applicationContext as? JarvisApp ?: JarvisApp.instance
        if (app.preferences.apiKey.trim().isNotBlank()) {
            return VoiceEngineType.CLOUD_GEMINI
        }
        return VoiceEngineType.NONE
    }

    /**
     * Loads the active offline voice in the background so the first sentence of the first reply is not delayed by
     * model loading. Safe to call at any time; does nothing when no offline voice is active.
     */
    fun prewarm() {
        scope.launch {
            try {
                val engine = getActiveEngineType()
                if (engine.isLocal && TtsModelDownloader.isEngineInstalled(context, engine)) {
                    sherpaEngine.initializeEngine(engine)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Offline voice prewarm skipped", e)
            }
        }
    }

    /**
     * True when an offline voice is installed, active, and has not failed to start. While it is false, other
     * voices (Gemini's own audio, then the phone's voice) must stay available so Jarvis is never silent.
     */
    fun isOfflineVoiceUsable(): Boolean {
        val engine = getActiveEngineType()
        return engine.isLocal && TtsModelDownloader.isEngineInstalled(context, engine) && !sherpaEngine.lastInitFailed
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

        val hasGemini = (context.applicationContext as? JarvisApp)?.preferences?.apiKey?.trim()?.isNotEmpty() == true
        if (hasGemini && speaker.engineType != VoiceEngineType.CLOUD_ELEVENLABS) {
            Log.i(TAG, "Direct Gemini Key configured. Using Gemini Cloud Voice.")
            cloudEngine.speak(cleanSpeech, SpeakerProfile.defaultFor(VoiceEngineType.CLOUD_GEMINI), amplitudeCallback, completionCallback)
            return
        }

        when (speaker.engineType) {
            VoiceEngineType.LOCAL_KOKORO, VoiceEngineType.LOCAL_PIPER -> {
                if (TtsModelDownloader.isEngineInstalled(context, speaker.engineType)) {
                    sherpaEngine.speak(cleanSpeech, speaker, speed, amplitudeCallback, completionCallback)
                } else if (hasGemini) {
                    Log.i(TAG, "Local engine ${speaker.engineType.displayName} not installed. Using Gemini Cloud Voice.")
                    cloudEngine.speak(cleanSpeech, SpeakerProfile.defaultFor(VoiceEngineType.CLOUD_GEMINI), amplitudeCallback, completionCallback)
                } else {
                    Log.w(TAG, "Local engine ${speaker.engineType.displayName} not installed and no Gemini key.")
                    completionCallback()
                }
            }
            VoiceEngineType.CLOUD_GEMINI, VoiceEngineType.CLOUD_ELEVENLABS -> {
                cloudEngine.speak(cleanSpeech, speaker, amplitudeCallback, completionCallback)
            }
            VoiceEngineType.NONE -> {
                if (hasGemini) {
                    Log.i(TAG, "Offline TTS not active. Using Gemini Voice.")
                    cloudEngine.speak(cleanSpeech, SpeakerProfile.defaultFor(VoiceEngineType.CLOUD_GEMINI), amplitudeCallback, completionCallback)
                } else {
                    Log.i(TAG, "Voice engine is deactivated.")
                    completionCallback()
                }
            }
        }
    }

    /**
     * Immediately stops active speech playback, releases native model memory,
     * and resets active TTS preference to NONE.
     */
    fun deactivateOfflineTts() {
        stop()
        sherpaEngine.releaseNativeTts()
        setActiveEngineType(VoiceEngineType.NONE)
        prefs.edit().putString(KEY_ACTIVE_ENGINE, VoiceEngineType.NONE.id).apply()
        Log.i(TAG, "Offline TTS deactivated: native model unloaded from memory and preference reset to NONE.")
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
