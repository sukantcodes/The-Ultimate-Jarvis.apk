package com.jarvis.assistant.study.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.offline.NetworkStateMonitor
import com.jarvis.assistant.study.model.StudyLanguage
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.tts.engine.JarvisVoiceDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Synchronizes JARVIS voice narration with live Study Board step rendering.
 *
 * Uses JARVIS's [JarvisVoiceDispatcher] (Gemini / ElevenLabs cloud voice) when online with valid
 * credentials, falls back cleanly to on-device [TextToSpeech] when offline/unconfigured, and
 * guards every callback with an epoch counter so pause, interruption, step skipping, or
 * language switching never corrupts board playback state.
 */
class StudySpeechSynchronizer(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val epoch = AtomicLong(0L)

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var paused: Boolean = false

    @Volatile
    private var voiceLanguage: StudyLanguage = StudyLanguage.AUTO

    @Volatile
    private var speechRate: Float = 1.0f

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _speakingStepIndex = MutableStateFlow<Int?>(null)
    val speakingStepIndex: StateFlow<Int?> = _speakingStepIndex.asStateFlow()

    private var localTts: TextToSpeech? = null
    private var localTtsReady: Boolean = false
    private var localTtsInitializing: Boolean = false
    private var pendingUtterance: PendingLocalUtterance? = null

    private data class PendingLocalUtterance(
        val epochId: Long,
        val stepIndex: Int,
        val text: String,
        val language: StudyLanguage,
        val rate: Float,
        val onDone: () -> Unit
    )

    fun configure(
        voiceEnabled: Boolean,
        language: StudyLanguage,
        writingSpeed: Float
    ) {
        val wasEnabled = enabled
        enabled = voiceEnabled
        voiceLanguage = language
        speechRate = writingSpeed.coerceIn(0.65f, 1.85f)
        if (!voiceEnabled && wasEnabled) {
            stop()
        } else if (voiceEnabled && !localTtsReady && !localTtsInitializing) {
            ensureLocalTts()
        }
    }

    fun isVoiceEnabled(): Boolean = enabled

    /**
     * Speaks [step] narration aligned with the animated board.
     * Always invokes [onComplete] (at most once for this epoch) even if TTS is unavailable,
     * paused, or fails, unless interrupted by a newer [speakStep], [pause], [interrupt], or [stop].
     */
    fun speakStep(
        step: StudyStep,
        onComplete: () -> Unit
    ) {
        if (!enabled || paused) {
            onComplete()
            return
        }
        val speechText = prepareSpeechText(step.narrationText())
        if (speechText.isBlank()) {
            onComplete()
            return
        }

        val currentEpoch = epoch.incrementAndGet()
        _isSpeaking.value = true
        _speakingStepIndex.value = step.stepIndex

        if (canUseCloudVoice()) {
            runCatching {
                JarvisVoiceDispatcher.getInstance(appContext).speak(
                    rawText = speechText,
                    overrideSpeaker = null,
                    onComplete = {
                        mainHandler.post {
                            if (epoch.get() == currentEpoch) {
                                _isSpeaking.value = false
                                _speakingStepIndex.value = null
                                onComplete()
                            }
                        }
                    }
                )
            }.onFailure {
                speakLocally(currentEpoch, step.stepIndex, speechText, onComplete)
            }
        } else {
            speakLocally(currentEpoch, step.stepIndex, speechText, onComplete)
        }
    }

    fun speakText(rawText: String, onComplete: (() -> Unit)? = null) {
        if (!enabled) {
            onComplete?.invoke()
            return
        }
        val speechText = prepareSpeechText(rawText)
        if (speechText.isBlank()) {
            onComplete?.invoke()
            return
        }
        val currentEpoch = epoch.incrementAndGet()
        _isSpeaking.value = true
        _speakingStepIndex.value = null

        if (canUseCloudVoice()) {
            runCatching {
                JarvisVoiceDispatcher.getInstance(appContext).speak(
                    rawText = speechText,
                    overrideSpeaker = null,
                    onComplete = {
                        mainHandler.post {
                            if (epoch.get() == currentEpoch) {
                                _isSpeaking.value = false
                                onComplete?.invoke()
                            }
                        }
                    }
                )
            }.onFailure {
                speakLocally(currentEpoch, -1, speechText) { onComplete?.invoke() }
            }
        } else {
            speakLocally(currentEpoch, -1, speechText) { onComplete?.invoke() }
        }
    }

    fun pause() {
        paused = true
        invalidateAndSilence()
    }

    fun resume() {
        paused = false
    }

    /** Stops speech immediately when user interrupts (e.g., taps voice mic for a doubt). */
    fun interrupt() {
        invalidateAndSilence()
    }

    fun stop() {
        paused = false
        invalidateAndSilence()
    }

    fun release() {
        epoch.incrementAndGet()
        pendingUtterance = null
        mainHandler.post {
            runCatching { JarvisVoiceDispatcher.getInstance(appContext).stop() }
            runCatching {
                localTts?.stop()
                localTts?.shutdown()
            }
            localTts = null
            localTtsReady = false
            localTtsInitializing = false
            _isSpeaking.value = false
            _speakingStepIndex.value = null
        }
    }

    private fun invalidateAndSilence() {
        epoch.incrementAndGet()
        pendingUtterance = null
        _isSpeaking.value = false
        _speakingStepIndex.value = null
        mainHandler.post {
            runCatching { JarvisVoiceDispatcher.getInstance(appContext).stop() }
            runCatching { localTts?.stop() }
        }
    }

    private fun canUseCloudVoice(): Boolean {
        if (!NetworkStateMonitor.isInternetAvailable(appContext)) return false
        val app = appContext as? JarvisApp ?: return false
        return app.preferences.apiKey.isNotBlank() || app.preferences.elevenLabsApiKey.isNotBlank()
    }

    private fun speakLocally(
        epochId: Long,
        stepIndex: Int,
        speechText: String,
        onComplete: () -> Unit
    ) {
        val item = PendingLocalUtterance(
            epochId = epochId,
            stepIndex = stepIndex,
            text = speechText,
            language = voiceLanguage,
            rate = speechRate,
            onDone = onComplete
        )
        mainHandler.post {
            if (epoch.get() != epochId) return@post
            val tts = localTts
            if (tts != null && localTtsReady) {
                dispatchLocalTts(tts, item)
            } else {
                pendingUtterance = item
                ensureLocalTts()
                // Timeout guard: never block board progression if TTS engine refuses to initialize.
                mainHandler.postDelayed({
                    if (epoch.get() == epochId && pendingUtterance?.epochId == epochId && !localTtsReady) {
                        pendingUtterance = null
                        _isSpeaking.value = false
                        _speakingStepIndex.value = null
                        onComplete()
                    }
                }, 1400L)
            }
        }
    }

    private fun ensureLocalTts() {
        if (localTtsInitializing || localTtsReady) return
        localTtsInitializing = true
        mainHandler.post {
            if (localTts != null) {
                localTtsInitializing = false
                return@post
            }
            runCatching {
                var created: TextToSpeech? = null
                created = TextToSpeech(appContext) { status ->
                    localTtsInitializing = false
                    localTtsReady = (status == TextToSpeech.SUCCESS)
                    val engine = created
                    if (localTtsReady && engine != null) {
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) {
                                handleUtteranceFinish(utteranceId)
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                handleUtteranceFinish(utteranceId)
                            }
                            override fun onError(utteranceId: String?, errorCode: Int) {
                                handleUtteranceFinish(utteranceId)
                            }
                        })
                        val pending = pendingUtterance
                        if (pending != null && epoch.get() == pending.epochId) {
                            pendingUtterance = null
                            dispatchLocalTts(engine, pending)
                        }
                    } else {
                        val pending = pendingUtterance
                        pendingUtterance = null
                        if (pending != null && epoch.get() == pending.epochId) {
                            _isSpeaking.value = false
                            _speakingStepIndex.value = null
                            pending.onDone()
                        }
                    }
                }
                localTts = created
            }.onFailure {
                localTtsInitializing = false
                localTtsReady = false
                val pending = pendingUtterance
                pendingUtterance = null
                if (pending != null && epoch.get() == pending.epochId) {
                    _isSpeaking.value = false
                    _speakingStepIndex.value = null
                    pending.onDone()
                }
            }
        }
    }

    private val localCompletionCallbacks = mutableMapOf<String, () -> Unit>()

    private fun dispatchLocalTts(tts: TextToSpeech, item: PendingLocalUtterance) {
        val locale = when (item.language) {
            StudyLanguage.HINDI -> Locale("hi", "IN")
            StudyLanguage.HINGLISH -> Locale("en", "IN")
            StudyLanguage.ENGLISH -> Locale("en", "IN")
            StudyLanguage.AUTO -> Locale.getDefault()
        }
        runCatching { tts.language = locale }
        runCatching { tts.setSpeechRate(item.rate.coerceIn(0.7f, 1.75f)) }

        val uttId = "study_utt_${item.epochId}"
        localCompletionCallbacks[uttId] = {
            if (epoch.get() == item.epochId) {
                _isSpeaking.value = false
                _speakingStepIndex.value = null
                item.onDone()
            }
        }
        val queued = runCatching {
            tts.speak(item.text, TextToSpeech.QUEUE_FLUSH, null, uttId)
        }.getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) {
            localCompletionCallbacks.remove(uttId)?.invoke()
        }
    }

    private fun handleUtteranceFinish(utteranceId: String?) {
        if (utteranceId == null) return
        mainHandler.post {
            localCompletionCallbacks.remove(utteranceId)?.invoke()
        }
    }

    companion object {
        /**
         * Converts mathematical notation and symbols into clear, natural spoken phrasing so voice
         * narration sounds like a human classroom teacher instead of spelling raw LaTeX tokens.
         */
        fun prepareSpeechText(raw: String): String {
            if (raw.isBlank()) return ""
            var s = raw.trim()
            // Convert \frac{a}{b} -> a over b
            s = s.replace(Regex("\\\\frac\\{([^{}]+)\\}\\{([^{}]+)\\}")) { m ->
                "${m.groupValues[1]} over ${m.groupValues[2]}"
            }
            // Convert \sqrt{a} -> square root of a
            s = s.replace(Regex("\\\\sqrt\\{([^{}]+)\\}")) { m ->
                "square root of ${m.groupValues[1]}"
            }
            s = s.replace("\\times", " times ")
                .replace("\\cdot", " times ")
                .replace("\\div", " divided by ")
                .replace("\\pi", " pi ")
                .replace("\\theta", " theta ")
                .replace("\\alpha", " alpha ")
                .replace("\\beta", " beta ")
                .replace("\\Delta", " delta ")
                .replace("Δ", " delta ")
                .replace("π", " pi ")
                .replace("θ", " theta ")
                .replace("√", " square root of ")
                .replace("±", " plus or minus ")
                .replace("×", " times ")
                .replace("÷", " divided by ")
                .replace("≠", " not equal to ")
                .replace("≤", " less than or equal to ")
                .replace("≥", " greater than or equal to ")
                .replace("→", " gives ")
                .replace("⇌", " is in equilibrium with ")
                .replace("=", " equals ")
                .replace("^2", " squared")
                .replace("^3", " cubed")
                .replace("²", " squared")
                .replace("³", " cubed")
                .replace("\\", " ")
                .replace("{", " ")
                .replace("}", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            return s
        }
    }
}
