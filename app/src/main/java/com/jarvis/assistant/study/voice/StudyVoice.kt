package com.jarvis.assistant.study.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.audio.AudioUtils
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.background.JarvisWakeWordService
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.offline.NetworkStateMonitor
import com.jarvis.assistant.study.StudyBoardController
import com.jarvis.assistant.tts.engine.JarvisVoiceDispatcher
import com.jarvis.assistant.tts.model.VoiceEngineType
import com.jarvis.assistant.voice.VoiceGenderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The teacher's voice for the Study Board using the exact JARVIS voice configured in Settings.
 *
 * Designed for uninterrupted, natural multi-step lectures:
 * - [speakContinuous] narrates the entire pre-loaded lesson smoothly inside ONE unbroken 24 kHz PCM
 *   Gemini Live (`BidiGenerateContent`) stream, preventing per-step stop/start glitches, rate limits,
 *   and mid-lesson voice switching while reporting real-time playback fraction + live output transcription
 *   so whiteboard drawing advances in smooth lockstep with the voice.
 * - Hardware drain awareness tracks [AudioTrack.getPlaybackHeadPosition] so speech never gets cut off
 *   mid-sentence when finishing a turn or transitioning to the next block.
 * - Automatic fallback to REST Gemini TTS or ElevenLabs uses raw text ONLY (never reading prompt instructions aloud).
 * - Suppresses standby wake detection ([StudyBoardController], [JarvisConversationService], [JarvisWakeWordService])
 *   for the entire narration duration so JARVIS never wakes up on its own speaker output.
 */
class StudyVoice(context: Context) {

    interface Callback {
        fun onStarted(token: Long, cloud: Boolean) {}
        fun onFinished(token: Long, ok: Boolean, reason: String)
        fun onAmplitude(amplitude: Float) {}
        /** Called during playback with normalized audio progress [0f..1f] and cumulative transcript text. */
        fun onProgress(token: Long, progressFrac: Float, spokenTranscript: String) {}
        /** One concise note when cloud voice genuinely cannot be reached (offline / invalid key / quota). */
        fun onNote(text: String) {}
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)

    private val restHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Dedicated WebSocket client matching GeminiLiveWebSocket (readTimeout=0 keeps live streaming alive)
    private val liveHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val prefs = app.getSharedPreferences("jarvis_study_prefs", Context.MODE_PRIVATE)

    private var callback: Callback? = null
    @Volatile private var rate = 1f
    @Volatile private var token = 0L
    private var wakePaused = false
    private var failures = 0
    private val resumeWake = Runnable { resumeWakeWord() }

    private var player: MediaPlayer? = null
    @Volatile private var playerPaused = false
    private val cache = LinkedHashMap<String, Deferred<ByteArray?>>()

    private val livePlayer = LiveStreamEngine()

    private var cloudBlockedUntil = 0L
    private var noteShown = false
    @Volatile private var model: String = prefs.getString(KEY_MODEL, "") ?: ""

    fun setCallback(cb: Callback?) { callback = cb }

    fun setRate(r: Float) {
        rate = r.coerceIn(0.5f, 2.0f)
        livePlayer.updateSpeed(rate)
        try {
            player?.let { p ->
                if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(rate)
            }
        } catch (_: Exception) {}
    }

    fun activeVoiceName(): String = when (engine()) {
        VoiceEngineType.CLOUD_ELEVENLABS -> {
            runCatching { JarvisVoiceDispatcher.getInstance(app).getActiveSpeaker().displayName }
                .getOrDefault("ElevenLabs")
        }
        VoiceEngineType.CLOUD_GEMINI -> selectedGeminiVoice()
        else -> "Offline"
    }

    fun cloudAvailable(): Boolean {
        if (System.currentTimeMillis() < cloudBlockedUntil) return false
        if (!NetworkStateMonitor.isInternetAvailable(app)) return false
        return when (engine()) {
            VoiceEngineType.CLOUD_ELEVENLABS -> elevenKey().isNotBlank()
            VoiceEngineType.CLOUD_GEMINI -> geminiKey().isNotBlank()
            else -> false
        }
    }

    fun warmUp(languageHint: String = "Auto") {
        if (!cloudAvailable()) return
        if (engine() == VoiceEngineType.CLOUD_GEMINI) {
            livePlayer.warmUp(selectedGeminiVoice(), languageHint)
        }
    }

    fun prefetch(text: String) = prefetch(text, "Auto")

    fun prefetch(text: String, languageHint: String) {
        val clean = text.trim()
        if (clean.isEmpty() || !cloudAvailable()) return
        if (engine() == VoiceEngineType.CLOUD_GEMINI && livePlayer.isHealthy()) {
            livePlayer.warmUp(selectedGeminiVoice(), languageHint)
            return
        }
        main.post {
            val key = keyFor(clean, languageHint)
            if (cache.containsKey(key)) return@post
            cache[key] = scope.async { gate.withPermit { synthesize(clean, languageHint) } }
            while (cache.size > MAX_CACHE) {
                val first = cache.keys.first()
                cache.remove(first)?.cancel()
            }
        }
    }

    /** Speaks a single step cleanly with the selected JARVIS voice and returns its token. */
    fun speak(text: String, locale: Locale): Long = speak(text, locale, "Auto")

    fun speak(text: String, @Suppress("UNUSED_PARAMETER") locale: Locale, languageHint: String): Long {
        val clean = text.trim()
        if (clean.isEmpty()) {
            stopAudioOnly(keepLiveSession = true)
            val my = ++token
            main.post { if (my == token) callback?.onFinished(my, true, "") }
            return my
        }
        return speakChunks(listOf(clean), estimatedDurationMs = (clean.length * 68L).coerceAtLeast(1_500L), languageHint = languageHint)
    }

    /**
     * Speaks an entire pre-loaded lesson continuously as one seamless JARVIS lecture, never stopping
     * or switching voices between steps while reporting playback progress for visual synchronization.
     */
    fun speakContinuous(
        plan: StudyVoiceCodec.ContinuousPlan,
        languageHint: String
    ): Long {
        if (plan.script.isBlank()) {
            stopAudioOnly(keepLiveSession = true)
            val my = ++token
            main.post { if (my == token) callback?.onFinished(my, true, "") }
            return my
        }
        val perSegment = plan.segments.map { StudyVoiceCodec.ensureSentenceEnding(it.text) }.filter { it.isNotEmpty() }
        val chunks = if (perSegment.size == plan.segments.size && perSegment.isNotEmpty()) {
            perSegment
        } else {
            splitIntoNaturalChunks(plan.segments.map { it.text }, maxChunkChars = 1_500)
        }
        return speakChunks(chunks, plan.estimatedDurationMs, languageHint, plan.segments)
    }

    private fun speakChunks(
        chunks: List<String>,
        estimatedDurationMs: Long,
        languageHint: String,
        planSegments: List<StudyVoiceCodec.LessonSegment> = emptyList()
    ): Long {
        stopAudioOnly(keepLiveSession = true)
        val my = ++token
        val joined = chunks.joinToString("\n").trim()
        if (joined.isEmpty()) {
            main.post { if (my == token) callback?.onFinished(my, true, "") }
            return my
        }
        if (!cloudAvailable()) {
            main.post {
                if (my == token) {
                    markCloudDown(quiet = false)
                    callback?.onFinished(my, false, NO_VOICE)
                }
            }
            return my
        }

        pauseWakeWord()
        val activeEngine = engine()
        if (activeEngine == VoiceEngineType.CLOUD_GEMINI && livePlayer.isHealthy()) {
            val langName = StudyVoiceCodec.resolveLanguageName(languageHint, joined)
            val started = livePlayer.speakQueue(
                token = my,
                chunks = chunks,
                planSegments = planSegments,
                estimatedDurationMs = estimatedDurationMs,
                voiceName = selectedGeminiVoice(),
                languageName = langName,
                onFirstAudio = { t ->
                    main.post {
                        if (t == token) {
                            failures = 0
                            callback?.onStarted(t, true)
                        }
                    }
                },
                onAmplitude = { amp ->
                    main.post { if (my == token) callback?.onAmplitude(amp) }
                },
                onProgress = { t, frac, transcript ->
                    main.post { if (t == token) callback?.onProgress(t, frac, transcript) }
                },
                onComplete = { t, ok, shouldFallback ->
                    main.post {
                        if (t != token) return@post
                        callback?.onAmplitude(0f)
                        if (ok) {
                            failures = 0
                            callback?.onProgress(t, 1f, "")
                            callback?.onFinished(t, true, "")
                            scheduleWakeResume(WAKE_RESUME_MS)
                        } else if (shouldFallback) {
                            speakBuffered(joined, estimatedDurationMs, languageHint, t, planSegments)
                        } else {
                            noteFailure()
                            callback?.onFinished(t, false, NO_VOICE)
                            scheduleWakeResume(WAKE_RESUME_SHORT_MS)
                        }
                    }
                },
                onPartialResume = { t, resumeSegIdx ->
                    main.post {
                        if (t != token) return@post
                        val remaining = if (resumeSegIdx in planSegments.indices) planSegments.subList(resumeSegIdx, planSegments.size) else emptyList()
                        if (remaining.isNotEmpty()) {
                            speakBufferedSegments(remaining, languageHint, t, emitStart = false)
                        } else {
                            callback?.onAmplitude(0f)
                            callback?.onProgress(t, 1f, "")
                            callback?.onFinished(t, true, "")
                            scheduleWakeResume(WAKE_RESUME_MS)
                        }
                    }
                }
            )
            if (started) return my
        }

        speakBuffered(joined, estimatedDurationMs, languageHint, my, planSegments)
        return my
    }

    /** Pauses the currently playing narration without discarding buffered audio position. */
    fun pause() {
        livePlayer.pause()
        try {
            player?.let { p ->
                if (p.isPlaying) {
                    p.pause()
                    playerPaused = true
                }
            }
        } catch (_: Exception) {}
        callback?.onAmplitude(0f)
        scheduleWakeResume(WAKE_RESUME_SHORT_MS)
    }

    /** Resumes narration paused via [pause], keeping audio and wake suppression synchronized. */
    fun resume(): Boolean {
        pauseWakeWord()
        if (livePlayer.resume()) return true
        return try {
            val p = player
            if (p != null && playerPaused) {
                playerPaused = false
                p.start()
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun speakBuffered(
        clean: String,
        estimatedDurationMs: Long,
        languageHint: String,
        my: Long,
        planSegments: List<StudyVoiceCodec.LessonSegment> = emptyList()
    ) {
        if (planSegments.size > 1) {
            speakBufferedSegments(planSegments, languageHint, my, emitStart = true)
            return
        }
        val singlePart = if (clean.length <= 2_400) clean else clean.take(2_400)
        val key = keyFor(singlePart, languageHint)
        val pending = cache.remove(key) ?: scope.async { gate.withPermit { synthesizeWithRetry(singlePart, languageHint) } }
        scope.launch {
            val bytes = withTimeoutOrNull(WAIT_MS) { runCatching { pending.await() }.getOrNull() }
            main.post {
                if (my != token) return@post
                if (bytes == null || bytes.isEmpty()) {
                    if (!pending.isCompleted) cache[key] = pending else noteFailure()
                    callback?.onFinished(my, false, NO_VOICE)
                    scheduleWakeResume(WAKE_RESUME_SHORT_MS)
                } else {
                    failures = 0
                    cache[key] = pending
                    val seg = planSegments.firstOrNull()
                    playMediaBytes(
                        bytes = bytes,
                        estimatedDurationMs = estimatedDurationMs,
                        my = my,
                        segStartFrac = seg?.startFrac ?: 0f,
                        segEndFrac = seg?.endFrac ?: 1f,
                        segText = seg?.text.orEmpty(),
                        emitStart = true,
                        emitFinish = true
                    )
                }
            }
        }
    }

    private fun speakBufferedSegments(
        segments: List<StudyVoiceCodec.LessonSegment>,
        languageHint: String,
        my: Long,
        emitStart: Boolean
    ) {
        val jobs = segments.map { seg ->
            val key = keyFor(seg.text, languageHint)
            cache.remove(key) ?: scope.async { gate.withPermit { synthesizeWithRetry(seg.text, languageHint) } }
        }
        fun playSeg(idx: Int, firstSegment: Boolean) {
            if (my != token) return
            if (idx !in segments.indices) {
                callback?.onAmplitude(0f)
                callback?.onProgress(my, 1f, "")
                callback?.onFinished(my, true, "")
                scheduleWakeResume(WAKE_RESUME_MS)
                return
            }
            val seg = segments[idx]
            val segEstMs = (seg.text.length * 80L).coerceAtLeast(1_500L)
            scope.launch {
                val bytes = withTimeoutOrNull(WAIT_MS) { runCatching { jobs[idx].await() }.getOrNull() }
                main.post {
                    if (my != token) return@post
                    if (bytes == null || bytes.isEmpty()) {
                        noteFailure()
                        callback?.onFinished(my, false, NO_VOICE)
                        scheduleWakeResume(WAKE_RESUME_SHORT_MS)
                        return@post
                    }
                    failures = 0
                    cache[keyFor(seg.text, languageHint)] = jobs[idx]
                    playMediaBytes(
                        bytes = bytes,
                        estimatedDurationMs = segEstMs,
                        my = my,
                        segStartFrac = seg.startFrac,
                        segEndFrac = seg.endFrac,
                        segText = seg.text,
                        emitStart = emitStart && firstSegment,
                        emitFinish = idx == segments.lastIndex,
                        onSegmentDone = { playSeg(idx + 1, firstSegment = false) }
                    )
                }
            }
        }
        playSeg(0, firstSegment = true)
    }

    fun stop() {
        token++
        stopAudioOnly(keepLiveSession = true)
        callback?.onAmplitude(0f)
        scheduleWakeResume(WAKE_RESUME_SHORT_MS)
    }

    fun shutdown() {
        token++
        stopAudioOnly(keepLiveSession = false)
        livePlayer.shutdown()
        main.removeCallbacks(resumeWake)
        resumeWakeWordImmediate()
        cache.values.forEach { it.cancel() }
        cache.clear()
        scope.cancel()
        callback = null
    }

    // ----- buffered fallback player (REST TTS / ElevenLabs) -----

    private fun playMediaBytes(
        bytes: ByteArray,
        estimatedDurationMs: Long,
        my: Long,
        segStartFrac: Float = 0f,
        segEndFrac: Float = 1f,
        segText: String = "",
        emitStart: Boolean = true,
        emitFinish: Boolean = true,
        onSegmentDone: (() -> Unit)? = null
    ) {
        try {
            val p = MediaPlayer()
            playerPaused = false
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(MemorySource(bytes))
            val tickerRunnable = object : Runnable {
                var phase = 0f
                override fun run() {
                    val active = player
                    if (my != token || active == null) return
                    val playing = runCatching { active.isPlaying }.getOrDefault(false)
                    if (playing) {
                        phase += 0.35f
                        val amp = ((kotlin.math.sin(phase) + 1f) * 0.42f).coerceIn(0.18f, 0.90f)
                        callback?.onAmplitude(amp)
                        val dur = runCatching { active.duration }.getOrDefault(0).coerceAtLeast(estimatedDurationMs.toInt())
                        val pos = runCatching { active.currentPosition }.getOrDefault(0).coerceAtLeast(0)
                        val localFrac = if (dur > 0) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                        val frac = (segStartFrac + localFrac * (segEndFrac - segStartFrac)).coerceIn(0f, 0.995f)
                        val partialText = if (segText.isNotEmpty()) {
                            val chars = (segText.length * localFrac).toInt().coerceIn(0, segText.length)
                            segText.substring(0, chars)
                        } else ""
                        callback?.onProgress(my, frac, partialText)
                    }
                    main.postDelayed(this, 35L)
                }
            }
            p.setOnPreparedListener { mp ->
                if (my != token) {
                    try { mp.release() } catch (_: Exception) {}
                    return@setOnPreparedListener
                }
                try { mp.playbackParams = mp.playbackParams.setSpeed(rate) } catch (_: Exception) {}
                mp.start()
                if (emitStart) callback?.onStarted(my, true)
                main.post(tickerRunnable)
            }
            p.setOnCompletionListener { mp ->
                main.removeCallbacks(tickerRunnable)
                callback?.onAmplitude(0f)
                playerPaused = false
                try { mp.release() } catch (_: Exception) {}
                if (player === mp) player = null
                if (my == token) {
                    if (emitFinish) {
                        callback?.onProgress(my, 1f, "")
                        callback?.onFinished(my, true, "")
                        scheduleWakeResume(WAKE_RESUME_MS)
                    } else {
                        callback?.onProgress(my, segEndFrac.coerceIn(0f, 0.995f), segText)
                        onSegmentDone?.invoke()
                    }
                }
            }
            p.setOnErrorListener { mp, _, _ ->
                main.removeCallbacks(tickerRunnable)
                callback?.onAmplitude(0f)
                playerPaused = false
                try { mp.release() } catch (_: Exception) {}
                if (player === mp) player = null
                if (my == token) {
                    noteFailure()
                    callback?.onFinished(my, false, NO_VOICE)
                    scheduleWakeResume(WAKE_RESUME_SHORT_MS)
                }
                true
            }
            player = p
            p.prepareAsync()
        } catch (_: Exception) {
            noteFailure()
            callback?.onFinished(my, false, NO_VOICE)
            scheduleWakeResume(WAKE_RESUME_SHORT_MS)
        }
    }

    private fun stopAudioOnly(keepLiveSession: Boolean) {
        livePlayer.cancelTurn(keepWarm = keepLiveSession)
        playerPaused = false
        val p = player ?: return
        player = null
        try {
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            p.setOnPreparedListener(null)
            p.stop()
        } catch (_: Exception) {}
        try { p.release() } catch (_: Exception) {}
    }

    private fun noteFailure() {
        if (++failures >= 2) markCloudDown(quiet = false)
    }

    private fun markCloudDown(quiet: Boolean) {
        cloudBlockedUntil = System.currentTimeMillis() + BLOCK_MS
        if (!quiet && !noteShown) {
            noteShown = true
            callback?.onNote(NO_VOICE)
        }
    }

    // ----- wake-word suppression while Study Board teaches -----

    private fun pauseWakeWord() {
        main.removeCallbacks(resumeWake)
        StudyBoardController.setNarrating(true)
        if (wakePaused) return
        wakePaused = true
        try { JarvisConversationService.onStudyBoardNarrationChanged(app, true) } catch (_: Throwable) {}
        try { if (JarvisWakeWordService.isRunning()) JarvisWakeWordService.stopService(app) } catch (_: Throwable) {}
    }

    private fun scheduleWakeResume(delayMs: Long) {
        main.removeCallbacks(resumeWake)
        main.postDelayed(resumeWake, delayMs)
    }

    private fun resumeWakeWord() {
        if (!wakePaused && !StudyBoardController.isNarrating.value) return
        resumeWakeWordImmediate()
    }

    private fun resumeWakeWordImmediate() {
        wakePaused = false
        StudyBoardController.setNarrating(false)
        try { JarvisConversationService.onStudyBoardNarrationChanged(app, false) } catch (_: Throwable) {}
    }

    // ----- engine & pure-text REST synthesis -----

    private fun engine(): VoiceEngineType {
        val chosen = runCatching { JarvisVoiceDispatcher.getInstance(app).getActiveEngineType() }
            .getOrDefault(VoiceEngineType.NONE)
        return when {
            chosen == VoiceEngineType.CLOUD_ELEVENLABS && elevenKey().isNotBlank() -> VoiceEngineType.CLOUD_ELEVENLABS
            geminiKey().isNotBlank() -> VoiceEngineType.CLOUD_GEMINI
            else -> VoiceEngineType.NONE
        }
    }

    private fun geminiKey() = JarvisApp.instance.preferences.apiKey.trim()
    private fun elevenKey() = JarvisApp.instance.preferences.elevenLabsApiKey.trim()

    private fun selectedGeminiVoice(): String {
        val chosen = JarvisApp.instance.preferences.voice
        return VoiceGenderStore(app).voiceFor(chosen).ifBlank { GeminiConstants.DEFAULT_VOICE }
    }

    private fun configuredLiveModel(): String =
        StudyVoiceCodec.normalizeLiveModelId(JarvisApp.instance.preferences.aiModel)

    private fun keyFor(text: String, languageHint: String): String {
        val voiceId = if (engine() == VoiceEngineType.CLOUD_GEMINI) selectedGeminiVoice() else activeVoiceName()
        return "${engine().name}|$voiceId|${StudyVoiceCodec.languageTag(languageHint, text)}|$text"
    }

    private fun synthesizeWithRetry(text: String, languageHint: String): ByteArray? {
        synthesize(text, languageHint)?.let { return it }
        try { Thread.sleep(600) } catch (_: InterruptedException) { return null }
        return synthesize(text, languageHint)
    }

    private fun synthesize(text: String, languageHint: String): ByteArray? = try {
        when (engine()) {
            VoiceEngineType.CLOUD_ELEVENLABS -> synthesizeElevenLabs(text, languageHint)
            VoiceEngineType.CLOUD_GEMINI -> synthesizeGeminiRest(text)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun synthesizeElevenLabs(text: String, languageHint: String): ByteArray? {
        val key = elevenKey()
        if (key.isBlank()) return null
        val speaker = JarvisVoiceDispatcher.getInstance(app).getActiveSpeaker()
        val voiceId = speaker.cloudVoiceIdentifier.ifBlank { "21m00Tcm4TlvDq8ikWAM" }
        val langTag = StudyVoiceCodec.languageTag(languageHint, text)
        val modelId = if (langTag.startsWith("en-")) "eleven_turbo_v2_5" else "eleven_multilingual_v2"
        val payload = JsonObject().apply {
            addProperty("text", text)
            addProperty("model_id", modelId)
            add("voice_settings", JsonObject().apply {
                addProperty("stability", 0.45f)
                addProperty("similarity_boost", 0.80f)
                addProperty("style", 0.25f)
                addProperty("use_speaker_boost", true)
            })
        }
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            .addHeader("xi-api-key", key)
            .addHeader("Accept", "audio/mpeg")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        restHttp.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            return res.body?.bytes()
        }
    }

    /**
     * Fallback REST Gemini TTS: ALWAYS passes raw [text] verbatim with zero instruction wrappers
     * (TTS models read every word inside `contents.parts.text` aloud).
     */
    private fun synthesizeGeminiRest(text: String): ByteArray? {
        val key = geminiKey()
        val name = model.ifBlank { discoverModel(key) } ?: return null
        val voice = selectedGeminiVoice()
        val body = JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", text) }) })
                })
            })
            add("generationConfig", JsonObject().apply {
                add("responseModalities", JsonArray().apply { add("AUDIO") })
                add("speechConfig", JsonObject().apply {
                    add("voiceConfig", JsonObject().apply {
                        add("prebuiltVoiceConfig", JsonObject().apply { addProperty("voiceName", voice) })
                    })
                })
            })
        }
        val req = Request.Builder()
            .url("$BASE/models/$name:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON))
            .build()
        restHttp.newCall(req).execute().use { res ->
            if (res.code == 404) {
                forgetModel()
                return null
            }
            if (!res.isSuccessful) return null
            val root = JsonParser.parseString(res.body?.string() ?: return null).asJsonObject
            val parts = root.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("content")?.getAsJsonArray("parts") ?: return null
            for (p in parts) {
                val inline = p.asJsonObject.getAsJsonObject("inlineData") ?: continue
                val data = inline.get("data")?.asString ?: continue
                val mime = inline.get("mimeType")?.asString.orEmpty()
                val raw = Base64.decode(data, Base64.DEFAULT)
                return if (StudyVoiceCodec.isRawPcm(mime)) {
                    StudyVoiceCodec.pcm16ToWav(raw, StudyVoiceCodec.sampleRate(mime))
                } else raw
            }
        }
        return null
    }

    private fun discoverModel(key: String): String? {
        val savedAt = prefs.getLong(KEY_MODEL_AT, 0L)
        val saved = prefs.getString(KEY_MODEL, "").orEmpty()
        if (saved.isNotBlank() && System.currentTimeMillis() - savedAt < DAY_MS) {
            model = saved
            return saved
        }
        val req = Request.Builder().url("$BASE/models?pageSize=200").header("x-goog-api-key", key).build()
        val entries = ArrayList<StudyVoiceCodec.ModelEntry>()
        restHttp.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val arr = JsonParser.parseString(res.body?.string() ?: return null).asJsonObject
                .getAsJsonArray("models") ?: return null
            for (m in arr) {
                val o = m.asJsonObject
                val methods = o.getAsJsonArray("supportedGenerationMethods")?.map { it.asString }.orEmpty()
                entries.add(StudyVoiceCodec.ModelEntry(o.get("name")?.asString.orEmpty(), methods))
            }
        }
        val picked = StudyVoiceCodec.pickTtsModel(entries) ?: return null
        model = picked
        prefs.edit().putString(KEY_MODEL, picked).putLong(KEY_MODEL_AT, System.currentTimeMillis()).apply()
        return picked
    }

    private fun forgetModel() {
        model = ""
        prefs.edit().remove(KEY_MODEL).remove(KEY_MODEL_AT).apply()
    }

    private fun splitIntoNaturalChunks(segments: List<String>, maxChunkChars: Int): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (seg in segments) {
            val s = StudyVoiceCodec.ensureSentenceEnding(seg)
            if (s.isEmpty()) continue
            if (cur.isNotEmpty() && cur.length + s.length + 1 > maxChunkChars) {
                out.add(cur.toString().trim())
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        return out
    }

    // ----- Real-Time Unbroken BidiGenerateContent Streaming Engine -----

    private inner class LiveStreamEngine {
        private val wsLock = Any()
        private var ws: WebSocket? = null
        @Volatile private var setupComplete = false
        @Volatile private var connectedVoice = ""
        @Volatile private var connectedLang = ""
        @Volatile private var connectedModel = configuredLiveModel()
        @Volatile private var openedAt = 0L
        @Volatile private var sessionUsed = false
        private val liveDisabledUntil = AtomicLong(0L)

        // Active lecture/utterance state
        @Volatile private var activeTurnToken = -1L
        @Volatile private var turnFirstAudioSent = false
        @Volatile private var turnAllServerDone = false
        @Volatile private var turnModelFallbackAttempted = false
        private var activeTurnChunks: List<String> = emptyList()
        private var activeTurnVoice: String = ""
        private var activeTurnLang: String = ""
        private val pendingTextChunks = ArrayDeque<String>()
        private val accumulatedTranscript = StringBuilder()
        @Volatile private var expectedTotalMs = 4_000L

        private var turnOnFirstAudio: ((Long) -> Unit)? = null
        private var turnOnAmplitude: ((Float) -> Unit)? = null
        private var turnOnProgress: ((Long, Float, String) -> Unit)? = null
        private var turnOnComplete: ((Long, Boolean, Boolean) -> Unit)? = null
        private var turnOnPartialResume: ((Long, Int) -> Unit)? = null
        private var turnTimeoutRunnable: Runnable? = null

        private val audioQueue = LinkedBlockingQueue<ByteArray>()
        private var track: AudioTrack? = null
        private val trackLock = Any()
        private val running = AtomicBoolean(false)
        private val playing = AtomicBoolean(false)
        private val userPaused = AtomicBoolean(false)
        private val workerStop = AtomicBoolean(false)
        private var worker: Thread? = null
        @Volatile private var activeFlushGen = 0
        @Volatile private var writtenFramesInTurn = 0L
        @Volatile private var serverFramesReceived = 0L
        @Volatile private var activePlayWallStartMs = 0L
        @Volatile private var accumulatedActivePlayMs = 0L
        @Volatile private var lastHalPosFrames = 0L
        @Volatile private var lastHalChangedMs = 0L
        @Volatile private var lastEmittedPlayedFrames = 0L
        private var activePlanSegments: List<StudyVoiceCodec.LessonSegment> = emptyList()
        private var segmentStartFrames: LongArray = LongArray(0)
        private var segmentCompleted: BooleanArray = BooleanArray(0)
        private var segmentTranscripts: Array<StringBuilder> = emptyArray()
        private var perSegmentTurnMode = false
        private var currentRecvSegIdx = 0
        private var nextSegCursor = 1
        @Volatile private var pendingFallbackStartSeg = -1

        fun isHealthy(): Boolean = System.currentTimeMillis() >= liveDisabledUntil.get()

        fun warmUp(voiceName: String, languageHint: String) {
            if (!isHealthy()) return
            val key = geminiKey()
            if (key.isBlank()) return
            val langName = StudyVoiceCodec.resolveLanguageName(languageHint, "")
            synchronized(wsLock) {
                val agedOut = System.currentTimeMillis() - openedAt > LIVE_MAX_AGE_MS
                val sameConfig = ws != null && !sessionUsed && connectedVoice == voiceName && connectedLang == langName && !agedOut
                if (sameConfig) return
                if (activeTurnToken >= 0L) return
                ensureConnectedLocked(key, voiceName, langName)
            }
        }

        fun updateSpeed(newRate: Float) {
            synchronized(trackLock) {
                applyPlaybackSpeed(track, newRate)
            }
        }

        fun pause() {
            if (activeTurnToken < 0L) return
            if (!userPaused.getAndSet(true) && activePlayWallStartMs > 0L) {
                accumulatedActivePlayMs += (SystemClock.elapsedRealtime() - activePlayWallStartMs).coerceAtLeast(0L)
                activePlayWallStartMs = 0L
            }
            synchronized(trackLock) {
                runCatching {
                    track?.let { if (it.state == AudioTrack.STATE_INITIALIZED && it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause() }
                }
            }
        }

        fun resume(): Boolean {
            if (activeTurnToken < 0L || !userPaused.getAndSet(false)) return false
            if (playing.get() && activePlayWallStartMs == 0L) {
                activePlayWallStartMs = SystemClock.elapsedRealtime()
            }
            synchronized(trackLock) {
                runCatching {
                    track?.let {
                        applyPlaybackSpeed(it, rate)
                        if (it.state == AudioTrack.STATE_INITIALIZED && it.playState != AudioTrack.PLAYSTATE_PLAYING) it.play()
                    }
                }
            }
            return true
        }

        fun speakQueue(
            token: Long,
            chunks: List<String>,
            planSegments: List<StudyVoiceCodec.LessonSegment> = emptyList(),
            estimatedDurationMs: Long,
            voiceName: String,
            languageName: String,
            onFirstAudio: (Long) -> Unit,
            onAmplitude: (Float) -> Unit,
            onProgress: (Long, Float, String) -> Unit,
            onComplete: (Long, Boolean, Boolean) -> Unit,
            onPartialResume: (Long, Int) -> Unit = { _, _ -> }
        ): Boolean {
            val key = geminiKey()
            if (key.isBlank() || chunks.isEmpty() || !isHealthy()) return false

            ensureWorkerRunning()
            resetTrackAndQueue()
            userPaused.set(false)
            turnModelFallbackAttempted = false

            synchronized(wsLock) {
                activeTurnChunks = chunks.toList()
                activePlanSegments = planSegments
                perSegmentTurnMode = planSegments.isNotEmpty() && chunks.size == planSegments.size
                currentRecvSegIdx = 0
                segmentStartFrames = LongArray(planSegments.size) { idx -> if (idx == 0) 0L else -1L }
                segmentCompleted = BooleanArray(planSegments.size)
                segmentTranscripts = Array(planSegments.size) { StringBuilder() }
                nextSegCursor = 1
                pendingFallbackStartSeg = -1
                serverFramesReceived = 0L
                activeTurnVoice = voiceName
                activeTurnLang = languageName
                pendingTextChunks.clear()
                chunks.forEach { pendingTextChunks.addLast(it) }
                accumulatedTranscript.clear()
            }

            activeTurnToken = token
            turnFirstAudioSent = false
            turnAllServerDone = false
            expectedTotalMs = estimatedDurationMs.coerceAtLeast(1_500L)
            turnOnFirstAudio = onFirstAudio
            turnOnAmplitude = onAmplitude
            turnOnProgress = onProgress
            turnOnComplete = onComplete
            turnOnPartialResume = onPartialResume

            scheduleFirstChunkWatchdog(token)

            scope.launch {
                val firstText = synchronized(wsLock) { pendingTextChunks.pollFirst() } ?: return@launch
                val sent = connectAndSendTurn(key, voiceName, languageName, firstText, token)
                if (!sent && activeTurnToken == token) {
                    finishTurn(token, ok = false, fallback = true)
                }
            }
            return true
        }

        fun cancelTurn(keepWarm: Boolean) {
            activeTurnToken = -1L
            userPaused.set(false)
            cancelWatchdog()
            synchronized(wsLock) { pendingTextChunks.clear() }
            pendingFallbackStartSeg = -1
            turnOnFirstAudio = null
            turnOnAmplitude = null
            turnOnProgress = null
            turnOnComplete = null
            turnOnPartialResume = null
            resetTrackAndQueue()
            closeSocketOnly()
            if (keepWarm && geminiKey().isNotBlank() && isHealthy()) {
                val lang = StudyVoiceCodec.resolveLanguageName("Auto", "")
                synchronized(wsLock) { ensureConnectedLocked(geminiKey(), selectedGeminiVoice(), lang) }
            }
        }

        fun shutdown() {
            cancelTurn(keepWarm = false)
            workerStop.set(true)
            running.set(false)
            worker?.interrupt()
            worker = null
            synchronized(trackLock) {
                runCatching {
                    track?.apply {
                        if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                        release()
                    }
                }
                track = null
            }
        }

        private suspend fun connectAndSendTurn(
            key: String,
            voiceName: String,
            languageName: String,
            text: String,
            myToken: Long
        ): Boolean {
            synchronized(wsLock) {
                val agedOut = System.currentTimeMillis() - openedAt > LIVE_MAX_AGE_MS
                // Always start a clean Live session when the previous warm socket was already used for a turn
                if (ws == null || sessionUsed || connectedVoice != voiceName || connectedLang != languageName || agedOut) {
                    closeSocketOnly()
                    ensureConnectedLocked(key, voiceName, languageName)
                }
            }

            val deadline = System.currentTimeMillis() + 5_000L
            while (!setupComplete && System.currentTimeMillis() < deadline) {
                if (activeTurnToken != myToken) return false
                try { Thread.sleep(25) } catch (_: InterruptedException) { return false }
            }
            if (!setupComplete || activeTurnToken != myToken) return false

            return sendClientTurnText(text)
        }

        private fun sendClientTurnText(cleanText: String): Boolean {
            sessionUsed = true
            val payload = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", cleanText)
                        }))
                    }))
                    put("turnComplete", true)
                })
            }
            val socket = synchronized(wsLock) { ws } ?: return false
            return socket.send(payload.toString())
        }

        private fun ensureConnectedLocked(key: String, voiceName: String, languageName: String) {
            if (key.isBlank()) return
            closeSocketOnly()
            connectedVoice = voiceName
            connectedLang = languageName
            setupComplete = false
            sessionUsed = false
            openedAt = System.currentTimeMillis()
            val req = Request.Builder()
                .url("${GeminiConstants.WS_BASE_URL}?key=$key")
                .build()
            ws = liveHttp.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val setupJson = buildSpeakerSetupJson(connectedModel, voiceName, languageName)
                    webSocket.send(setupJson.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleLiveMessage(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleLiveMessage(webSocket, bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleSocketDrop(webSocket, code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    handleSocketDrop(webSocket, response?.code ?: -1, t.message.orEmpty())
                }
            })
        }

        private fun handleSocketDrop(socket: WebSocket, code: Int, reason: String) {
            synchronized(wsLock) {
                if (ws !== socket) return
                ws = null
                setupComplete = false
            }
            val modelRejected = code == 1008 || reason.contains("not found", true) || reason.contains("not supported", true)
            if (modelRejected && !turnModelFallbackAttempted && !turnFirstAudioSent) {
                val alt = if (connectedModel == GeminiConstants.MODEL_3_8_LIVE) {
                    GeminiConstants.MODEL_3_1_FLASH
                } else {
                    GeminiConstants.MODEL_3_8_LIVE
                }
                val currentTurn = activeTurnToken
                val retryChunks = synchronized(wsLock) { activeTurnChunks }
                if (currentTurn >= 0L && retryChunks.isNotEmpty()) {
                    turnModelFallbackAttempted = true
                    connectedModel = alt
                    synchronized(wsLock) {
                        pendingTextChunks.clear()
                        retryChunks.forEach { pendingTextChunks.addLast(it) }
                    }
                    scope.launch {
                        val firstText = synchronized(wsLock) { pendingTextChunks.pollFirst() } ?: return@launch
                        val sent = connectAndSendTurn(geminiKey(), activeTurnVoice, activeTurnLang, firstText, currentTurn)
                        if (!sent && activeTurnToken == currentTurn) {
                            finishTurn(currentTurn, ok = false, fallback = true)
                        }
                    }
                    return
                }
            } else if (code == 401 || code == 403 || reason.contains("API_KEY_INVALID", true)) {
                liveDisabledUntil.set(System.currentTimeMillis() + 60_000L)
            }
            val currentTurn = activeTurnToken
            if (currentTurn >= 0L && !turnAllServerDone) {
                if (!turnFirstAudioSent) {
                    finishTurn(currentTurn, ok = false, fallback = true)
                } else {
                    val resumeFrom = synchronized(wsLock) {
                        if (!perSegmentTurnMode || pendingTextChunks.isEmpty()) -1
                        else {
                            val curStart = segmentStartFrames.getOrElse(currentRecvSegIdx) { 0L }.coerceAtLeast(0L)
                            val receivedSubstantial = serverFramesReceived > curStart + 4_800L
                            val nextIdx = if (receivedSubstantial) currentRecvSegIdx + 1 else currentRecvSegIdx
                            if (nextIdx in activePlanSegments.indices) nextIdx else -1
                        }
                    }
                    pendingFallbackStartSeg = resumeFrom
                    turnAllServerDone = true
                }
            }
        }

        private fun closeSocketOnly() {
            synchronized(wsLock) {
                setupComplete = false
                sessionUsed = false
                val old = ws
                ws = null
                runCatching { old?.close(1000, "Reset") }
            }
        }

        private fun buildSpeakerSetupJson(modelName: String, voiceName: String, languageName: String): JSONObject {
            val sysPrompt =
                "You are JARVIS speaking aloud on the Study Board in warm, expressive, fluent human $languageName.\n" +
                    "STRICT CONTINUOUS LECTURE MODE:\n" +
                    "- Each user message is the next uninterrupted segment of one continuous classroom lecture.\n" +
                    "- Speak ONLY the exact text in the latest user message verbatim from start to finish in the exact same voice, rhythm, and $languageName pronunciation.\n" +
                    "- Express natural teacher enthusiasm, warmth, emphasis, and thoughtful micro-pauses.\n" +
                    "- NEVER add greetings ('Sure', 'Okay', 'Got it'), acknowledgements, meta-commentary, or repeat these rules."
            return JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", modelName)
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", sysPrompt)))
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", voiceName)
                                })
                            })
                        })
                        put("temperature", 0.50)
                        if (modelName.contains("2.5")) {
                            put("thinkingConfig", JSONObject().apply { put("thinkingBudget", 0) })
                        }
                    })
                    put("outputAudioTranscription", JSONObject())
                })
            }
        }

        private fun handleLiveMessage(socket: WebSocket, raw: String) {
            if (socket !== ws) return
            try {
                val root = JSONObject(raw)
                if (root.has("setupComplete")) {
                    setupComplete = true
                    openedAt = System.currentTimeMillis()
                    return
                }
                val server = root.optJSONObject("serverContent") ?: return
                val currentTurn = activeTurnToken
                if (currentTurn < 0L) return

                val outTrans = server.optJSONObject("outputTranscription")
                if (outTrans != null) {
                    var transChunk = outTrans.optString("text", "")
                    if (transChunk.isEmpty()) {
                        val parts = outTrans.optJSONArray("parts")
                        if (parts != null) {
                            val sb = StringBuilder()
                            for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
                            transChunk = sb.toString()
                        }
                    }
                    if (transChunk.isNotEmpty()) {
                        synchronized(wsLock) {
                            accumulatedTranscript.append(transChunk)
                            if (perSegmentTurnMode && currentRecvSegIdx in segmentTranscripts.indices) {
                                segmentTranscripts[currentRecvSegIdx].append(transChunk)
                            } else {
                                updateSegmentFrameBoundaryLocked()
                            }
                        }
                    }
                }

                val modelTurn = server.optJSONObject("modelTurn")
                if (modelTurn != null) {
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val p = parts.optJSONObject(i) ?: continue
                            if (p.optBoolean("thought", false)) continue
                            val inline = p.optJSONObject("inlineData") ?: continue
                            val b64 = inline.optString("data", "")
                            if (b64.length <= 8) continue
                            val decoded = Base64.decode(b64, Base64.DEFAULT)
                            val alignedLen = decoded.size and 1.inv()
                            if (alignedLen >= 16) {
                                val pcm = if (alignedLen == decoded.size) decoded else decoded.copyOf(alignedLen)
                                cancelWatchdog()
                                if (!turnFirstAudioSent) {
                                    turnFirstAudioSent = true
                                    turnOnFirstAudio?.invoke(currentTurn)
                                }
                                serverFramesReceived += (pcm.size / 2).toLong()
                                audioQueue.offer(pcm)
                            }
                        }
                    }
                }

                if (server.optBoolean("turnComplete", false) ||
                    server.optBoolean("generationComplete", false) ||
                    server.optBoolean("endOfTurn", false)
                ) {
                    val nextChunk = synchronized(wsLock) {
                        if (perSegmentTurnMode && currentRecvSegIdx in segmentCompleted.indices) {
                            segmentCompleted[currentRecvSegIdx] = true
                            currentRecvSegIdx++
                            if (currentRecvSegIdx < segmentStartFrames.size) {
                                val prevStart = segmentStartFrames[currentRecvSegIdx - 1].coerceAtLeast(0L)
                                segmentStartFrames[currentRecvSegIdx] = maxOf(prevStart, serverFramesReceived)
                            }
                        }
                        pendingTextChunks.pollFirst()
                    }
                    if (nextChunk != null) {
                        // Seamlessly continue long lectures inside the same open AudioTrack & session
                        val ok = sendClientTurnText(nextChunk)
                        if (!ok) {
                            val resumeFrom = synchronized(wsLock) {
                                if (perSegmentTurnMode && currentRecvSegIdx in activePlanSegments.indices) currentRecvSegIdx else -1
                            }
                            pendingFallbackStartSeg = resumeFrom
                            turnAllServerDone = true
                            if (!turnFirstAudioSent) finishTurn(currentTurn, ok = false, fallback = true)
                        }
                    } else {
                        turnAllServerDone = true
                        if (!turnFirstAudioSent) {
                            finishTurn(currentTurn, ok = false, fallback = true)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Study Live parse error: ${e.message}")
            }
        }

        private fun scheduleFirstChunkWatchdog(turnToken: Long) {
            cancelWatchdog()
            val r = Runnable {
                if (activeTurnToken == turnToken && !turnFirstAudioSent) {
                    closeSocketOnly()
                    finishTurn(turnToken, ok = false, fallback = true)
                }
            }
            turnTimeoutRunnable = r
            main.postDelayed(r, LIVE_FIRST_AUDIO_TIMEOUT_MS)
        }

        private fun cancelWatchdog() {
            turnTimeoutRunnable?.let { main.removeCallbacks(it) }
            turnTimeoutRunnable = null
        }

        private fun finishTurn(turnToken: Long, ok: Boolean, fallback: Boolean) {
            if (activeTurnToken != turnToken) return
            cancelWatchdog()
            synchronized(wsLock) { pendingTextChunks.clear() }
            val resumeSeg = pendingFallbackStartSeg
            pendingFallbackStartSeg = -1
            val cb = turnOnComplete
            val resumeCb = turnOnPartialResume
            activeTurnToken = -1L
            userPaused.set(false)
            turnOnFirstAudio = null
            turnOnAmplitude = null
            turnOnProgress = null
            turnOnComplete = null
            turnOnPartialResume = null
            if (ok && resumeSeg >= 0) {
                resumeCb?.invoke(turnToken, resumeSeg)
            } else {
                cb?.invoke(turnToken, ok, fallback)
            }
        }

        private fun ensureWorkerRunning() {
            if (running.get()) return
            synchronized(trackLock) {
                if (track == null) initTrackLocked()
            }
            workerStop.set(false)
            running.set(true)
            worker = Thread({
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
                var lastProgressAt = 0L
                while (!workerStop.get()) {
                    try {
                        val genAtStart = activeFlushGen
                        if (userPaused.get()) {
                            Thread.sleep(40)
                            continue
                        }
                        if (!playing.get()) {
                            val first = audioQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
                            if (first.isEmpty() || genAtStart != activeFlushGen) continue
                            val batch = ArrayList<ByteArray>(4)
                            batch.add(first)
                            var buffered = first.size
                            val prefillUntil = System.nanoTime() + 140_000_000L
                            while (buffered < 8_000 && System.nanoTime() < prefillUntil && !turnAllServerDone) {
                                val next = audioQueue.poll(12, TimeUnit.MILLISECONDS) ?: continue
                                if (next.isNotEmpty()) { batch.add(next); buffered += next.size }
                            }
                            if (genAtStart != activeFlushGen) continue
                            playing.set(true)
                            writtenFramesInTurn = 0L
                            accumulatedActivePlayMs = 0L
                            activePlayWallStartMs = SystemClock.elapsedRealtime()
                            synchronized(trackLock) {
                                val t = track ?: initTrackLocked()
                                applyPlaybackSpeed(t, rate)
                                if (t.state == AudioTrack.STATE_INITIALIZED && t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    runCatching { t.play() }
                                }
                            }
                            for (piece in batch) {
                                if (genAtStart != activeFlushGen) break
                                writeChunk(piece)
                                writtenFramesInTurn += piece.size / 2
                            }
                            turnOnAmplitude?.invoke(AudioUtils.calculateRmsNormalized(first, first.size))
                            lastProgressAt = System.currentTimeMillis()
                            emitPlaybackProgress()
                        } else {
                            val chunk = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                            if (chunk != null && chunk.isNotEmpty()) {
                                if (genAtStart != activeFlushGen) continue
                                turnOnAmplitude?.invoke(AudioUtils.calculateRmsNormalized(chunk, chunk.size))
                                writeChunk(chunk)
                                writtenFramesInTurn += chunk.size / 2
                                val now = System.currentTimeMillis()
                                if (now - lastProgressAt >= 35L) {
                                    lastProgressAt = now
                                    emitPlaybackProgress()
                                }
                            } else if (turnAllServerDone && audioQueue.isEmpty()) {
                                // CRITICAL: wait until Android's AudioTrack hardware DMA buffer actually finishes playing
                                // the last frame out of the speaker so speech is never chopped mid-sentence!
                                emitPlaybackProgress()
                                val drained = waitForHardwareAudioDrain(genAtStart)
                                if (drained && genAtStart == activeFlushGen && playing.getAndSet(false)) {
                                    val finishedToken = activeTurnToken
                                    if (finishedToken >= 0L) {
                                        finishTurn(finishedToken, ok = true, fallback = false)
                                    }
                                }
                            } else {
                                val now = System.currentTimeMillis()
                                if (now - lastProgressAt >= 35L) {
                                    lastProgressAt = now
                                    emitPlaybackProgress()
                                }
                            }
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Study audio worker error: ${e.message}")
                    }
                }
            }, "StudyVoiceAudioTrack").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }

        private fun updateSegmentFrameBoundaryLocked() {
            val segments = activePlanSegments
            if (segments.size <= 1 || nextSegCursor >= segments.size) return
            val fullText = accumulatedTranscript.toString()
            val window = fullText.takeLast(140).lowercase()
            val expectedFrames = ((expectedTotalMs * PCM_SAMPLE_RATE) / 1000L).coerceAtLeast(12_000L)
            val seg = segments[nextSegCursor]
            val minExpectedFrames = (seg.startFrac * 0.40f * expectedFrames).toLong()
            if (serverFramesReceived < minExpectedFrames) return

            val hitCount = seg.anchorWords.count { w -> w.length >= 3 && window.contains(w) }
            val matched = (seg.anchorWords.size >= 2 && hitCount >= 2) ||
                (seg.anchorWords.size == 1 && hitCount == 1 && seg.anchorWords[0].length >= 4)
            if (matched) {
                val prevBound = if (nextSegCursor > 0) segmentStartFrames[nextSegCursor - 1].coerceAtLeast(0L) else 0L
                segmentStartFrames[nextSegCursor] = maxOf(prevBound, serverFramesReceived)
                nextSegCursor++
            }
        }

        private fun emitPlaybackProgress() {
            val currentTurn = activeTurnToken
            if (currentTurn < 0L) return
            val halPos = synchronized(trackLock) {
                val t = track ?: return@synchronized 0L
                if (t.state != AudioTrack.STATE_INITIALIZED) 0L
                else (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
            }
            val nowMs = SystemClock.elapsedRealtime()
            val wallElapsedMs = accumulatedActivePlayMs + if (!userPaused.get() && activePlayWallStartMs > 0L) {
                (nowMs - activePlayWallStartMs).coerceAtLeast(0L)
            } else 0L
            val spd = rate.coerceIn(0.5f, 2.0f)
            val wallFrames = ((wallElapsedMs * PCM_SAMPLE_RATE * spd) / 1000f).toLong()
            val rawPlayedFrames = if (halPos in 1L..(writtenFramesInTurn + 4_800L)) {
                if (halPos > lastHalPosFrames) {
                    lastHalPosFrames = halPos
                    lastHalChangedMs = nowMs
                }
                val interp = if (!userPaused.get() && lastHalChangedMs > 0L) {
                    val sinceHal = (nowMs - lastHalChangedMs).coerceIn(0L, 180L)
                    halPos + ((sinceHal * PCM_SAMPLE_RATE * spd) / 1000f).toLong()
                } else halPos
                interp.coerceIn(lastEmittedPlayedFrames, writtenFramesInTurn.coerceAtLeast(1L))
            } else {
                wallFrames.coerceIn(lastEmittedPlayedFrames, writtenFramesInTurn.coerceAtLeast(1L))
            }
            lastEmittedPlayedFrames = rawPlayedFrames
            val playedFrames = rawPlayedFrames
            val segments = activePlanSegments
            val frac: Float
            val spokenWindow: String
            if (perSegmentTurnMode && segments.isNotEmpty()) {
                val boundsSnap: LongArray
                val doneSnap: BooleanArray
                val segTransSnap: Array<String>
                synchronized(wsLock) {
                    boundsSnap = segmentStartFrames.copyOf()
                    doneSnap = segmentCompleted.copyOf()
                    segTransSnap = Array(segmentTranscripts.size) { idx -> segmentTranscripts[idx].toString() }
                }
                var activeIdx = 0
                for (i in 1 until minOf(segments.size, boundsSnap.size)) {
                    val sf = boundsSnap[i]
                    if (sf >= 0L && playedFrames >= sf) activeIdx = i else break
                }
                val seg = segments[activeIdx]
                val segStart = boundsSnap[activeIdx].coerceAtLeast(0L)
                val nextStart = if (activeIdx + 1 < boundsSnap.size) boundsSnap[activeIdx + 1] else -1L
                val segEnd: Long = when {
                    activeIdx < doneSnap.size && doneSnap[activeIdx] && nextStart > segStart -> nextStart
                    activeIdx < doneSnap.size && doneSnap[activeIdx] && turnAllServerDone ->
                        maxOf(segStart + 1L, writtenFramesInTurn, serverFramesReceived)
                    else -> {
                        val estSegFrames = ((seg.text.length.coerceAtLeast(18) * 80L * PCM_SAMPLE_RATE) / 1000L).coerceAtLeast(18_000L)
                        val receivedInSeg = (serverFramesReceived - segStart).coerceAtLeast(0L)
                        segStart + maxOf(receivedInSeg + 12_000L, estSegFrames)
                    }
                }
                val inSegFrac = ((playedFrames - segStart).toFloat() / (segEnd - segStart).coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                frac = (seg.startFrac + inSegFrac * (seg.endFrac - seg.startFrac)).coerceIn(0f, 0.995f)
                val rawSegTrans = segTransSnap.getOrNull(activeIdx).orEmpty().ifEmpty { seg.text }
                val chars = (rawSegTrans.length * inSegFrac).toInt().coerceIn(0, rawSegTrans.length)
                spokenWindow = rawSegTrans.substring(0, chars)
            } else {
                val expectedFrames = ((expectedTotalMs * PCM_SAMPLE_RATE) / 1000L).coerceAtLeast(12_000L)
                val totalFrames = if (turnAllServerDone) {
                    maxOf(writtenFramesInTurn, playedFrames, 12_000L)
                } else {
                    maxOf(expectedFrames, writtenFramesInTurn + 24_000L, playedFrames + 12_000L)
                }
                frac = if (segments.isNotEmpty()) {
                    val snap = synchronized(wsLock) { segmentStartFrames.copyOf() }
                    StudyVoiceCodec.progressFromSegmentFrames(segments, snap, playedFrames, totalFrames)
                } else {
                    (playedFrames.toFloat() / totalFrames.toFloat()).coerceIn(0f, 0.995f)
                }
                spokenWindow = synchronized(wsLock) {
                    val full = accumulatedTranscript.toString()
                    val capLen = (full.length * frac).toInt().coerceIn(0, full.length)
                    full.substring(0, capLen)
                }
            }
            turnOnProgress?.invoke(currentTurn, frac, spokenWindow)
        }

        private fun waitForHardwareAudioDrain(genAtStart: Int): Boolean {
            val remainingStart = synchronized(trackLock) {
                val t = track ?: return true
                if (t.state != AudioTrack.STATE_INITIALIZED) return true
                val head = (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                (writtenFramesInTurn - head.coerceAtLeast(0L)).coerceAtLeast(0L)
            }
            if (remainingStart <= 480L) return true
            val maxWaitMs = ((remainingStart * 1000L) / (PCM_SAMPLE_RATE * rate.coerceAtLeast(0.5f)).toLong() + 320L).coerceAtMost(2_800L)
            val startWall = System.currentTimeMillis()
            while (!workerStop.get() && genAtStart == activeFlushGen && audioQueue.isEmpty()) {
                if (userPaused.get()) {
                    Thread.sleep(45)
                    continue
                }
                emitPlaybackProgress()
                val rem = synchronized(trackLock) {
                    val t = track ?: return true
                    if (t.state != AudioTrack.STATE_INITIALIZED) return true
                    val head = (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
                    (writtenFramesInTurn - head.coerceAtLeast(0L)).coerceAtLeast(0L)
                }
                if (rem <= 360L || System.currentTimeMillis() - startWall >= maxWaitMs) {
                    return true
                }
                Thread.sleep(35)
            }
            return genAtStart == activeFlushGen
        }

        private fun initTrackLocked(): AudioTrack {
            val minBytes = AudioTrack.getMinBufferSize(
                PCM_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufBytes = maxOf(minBytes * 4, 48_000)
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PCM_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = created
            applyPlaybackSpeed(created, rate)
            return created
        }

        private fun applyPlaybackSpeed(target: AudioTrack?, speed: Float) {
            val t = target ?: return
            if (t.state != AudioTrack.STATE_INITIALIZED) return
            runCatching {
                val p = t.playbackParams ?: PlaybackParams()
                p.speed = speed.coerceIn(0.5f, 2.0f)
                t.playbackParams = p
            }
        }

        private fun writeChunk(pcm: ByteArray) {
            synchronized(trackLock) {
                val t = track ?: return
                if (t.state != AudioTrack.STATE_INITIALIZED) return
                var offset = 0
                while (offset < pcm.size && !workerStop.get()) {
                    if (userPaused.get()) {
                        try { Thread.sleep(30) } catch (_: InterruptedException) { break }
                        continue
                    }
                    val wrote = t.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (wrote <= 0) break
                    offset += wrote
                }
            }
        }

        private fun resetTrackAndQueue() {
            activeFlushGen++
            audioQueue.clear()
            playing.set(false)
            writtenFramesInTurn = 0L
            serverFramesReceived = 0L
            accumulatedActivePlayMs = 0L
            activePlayWallStartMs = 0L
            lastHalPosFrames = 0L
            lastHalChangedMs = 0L
            lastEmittedPlayedFrames = 0L
            synchronized(trackLock) {
                runCatching {
                    track?.let { t ->
                        if (t.state == AudioTrack.STATE_INITIALIZED) {
                            runCatching { t.pause() }
                            runCatching { t.flush() }
                            runCatching { t.stop() }
                        }
                        runCatching { t.release() }
                    }
                }
                track = null
            }
        }
    }

    private class MemorySource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size, data.size - position.toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    companion object {
        private const val TAG = "StudyVoice"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val NO_VOICE = "JARVIS voice could not be reached, so the lesson continues silently. Check your internet and Gemini key."
        private const val KEY_MODEL = "voice_model"
        private const val KEY_MODEL_AT = "voice_model_at"
        private const val PCM_SAMPLE_RATE = 24_000
        private const val MAX_CACHE = 14
        private const val WAIT_MS = 28_000L
        private const val BLOCK_MS = 12_000L
        private const val WAKE_RESUME_MS = 1_400L
        private const val WAKE_RESUME_SHORT_MS = 600L
        private const val LIVE_FIRST_AUDIO_TIMEOUT_MS = 11_000L
        private const val LIVE_MAX_AGE_MS = 8L * 60_000L
        private const val DAY_MS = 24L * 3600_000L
    }
}
