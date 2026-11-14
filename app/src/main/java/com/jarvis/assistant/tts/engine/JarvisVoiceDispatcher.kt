package com.jarvis.assistant.tts.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.tts.model.SpeakerProfile
import com.jarvis.assistant.tts.model.VoiceEngineType
import com.jarvis.assistant.voice.JarvisVoiceController

/**
 * Master dispatcher for Jarvis speech: strict text sanitization, then the cloud voice (Google AI Studio / Gemini,
 * or ElevenLabs when chosen). There is no on-device neural voice.
 */
class JarvisVoiceDispatcher private constructor(private val context: Context) {

    companion object {
        private const val TAG = "JarvisVoiceDispatcher"
        private const val PREFS_NAME = "tts_engine_settings"
        private const val KEY_ACTIVE_ENGINE = "active_engine_type"
        private const val KEY_ACTIVE_SPEAKER = "active_speaker_profile_id"

        @Volatile
        private var instance: JarvisVoiceDispatcher? = null

        fun getInstance(context: Context): JarvisVoiceDispatcher {
            return instance ?: synchronized(this) {
                instance ?: JarvisVoiceDispatcher(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val cloudEngine: CloudVoiceEngine = CloudVoiceEngine(context)

    @Volatile private var isStoppingDispatcher: Boolean = false

    private fun hasGeminiKey(): Boolean =
        (context.applicationContext as? JarvisApp)?.preferences?.apiKey?.trim()?.isNotEmpty() == true

    fun getActiveEngineType(): VoiceEngineType {
        val saved = prefs.getString(KEY_ACTIVE_ENGINE, null)
        // A voice saved by an older version for the removed offline engines is ignored.
        val engine = VoiceEngineType.entries.firstOrNull { it.id == saved }
        if (engine != null && engine != VoiceEngineType.NONE) return engine
        return if (hasGeminiKey()) VoiceEngineType.CLOUD_GEMINI else VoiceEngineType.NONE
    }

    fun getActiveSpeaker(): SpeakerProfile {
        val engine = getActiveEngineType()
        val found = SpeakerProfile.findById(prefs.getString(KEY_ACTIVE_SPEAKER, null))
        return if (found != null && found.engineType == engine) found else SpeakerProfile.defaultFor(engine)
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
     * Master speech dispatch entrypoint for assistant utterances.
     */
    fun speak(
        rawText: String,
        overrideSpeaker: SpeakerProfile? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val cleanSpeech = sanitizeForSpeech(rawText)
        if (cleanSpeech.isBlank()) {
            onComplete?.invoke()
            return
        }

        stop()

        val speaker = overrideSpeaker ?: getActiveSpeaker()
        val completionCallback: () -> Unit = { onComplete?.invoke() }

        if (hasGeminiKey() && speaker.engineType != VoiceEngineType.CLOUD_ELEVENLABS) {
            Log.i(TAG, "Gemini key configured. Using Gemini Cloud Voice.")
            cloudEngine.speak(cleanSpeech, SpeakerProfile.defaultFor(VoiceEngineType.CLOUD_GEMINI), null, completionCallback)
            return
        }

        when (speaker.engineType) {
            VoiceEngineType.CLOUD_GEMINI, VoiceEngineType.CLOUD_ELEVENLABS ->
                cloudEngine.speak(cleanSpeech, speaker, null, completionCallback)
            VoiceEngineType.NONE -> {
                Log.i(TAG, "No cloud voice is available.")
                completionCallback()
            }
        }
    }

    /**
     * Halts playback on the cloud synthesis engine only.
     * Never calls into JarvisVoiceController to avoid mutual recursion.
     */
    fun stopEngines() {
        cloudEngine.stop()
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
        cloudEngine.release()
    }
}
