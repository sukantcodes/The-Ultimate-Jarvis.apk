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
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The teacher's voice for the Study Board using the exact JARVIS voice selected in Settings.
 *
 * Primary path for Gemini voice: real-time streaming BidiGenerateContent WebSocket audio into a low-latency
 * 24 kHz mono 16-bit PCM AudioTrack (sub-second start latency, human prosody, fluent across all world and Indian languages,
 * with live RMS amplitude emission for synchronized UI animation and dynamic speed control via PlaybackParams).
 *
 * Resilience fallback: if the live WebSocket rejects or stalls before first audio, seamlessly falls back to REST
 * Gemini cloud TTS (dynamically discovered `-tts` model with the same selected JARVIS voice) or ElevenLabs
 * Multilingual v2 when ElevenLabs is chosen.
 *
 * Wake-word safety: while narration is active (plus a short echo guard afterward), both [StudyBoardController] and
 * [JarvisWakeWordService] / [JarvisConversationService] suppress standby wake listening so JARVIS never wakes on its own voice.
 */
class StudyVoice(context: Context) {

    interface Callback {
        fun onStarted(token: Long, cloud: Boolean) {}
        fun onFinished(token: Long, ok: Boolean, reason: String)
        fun onAmplitude(amplitude: Float) {}
        /** One short, human-readable note when cloud voice could not be reached. */
        fun onNote(text: String) {}
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val prefs = app.getSharedPreferences("jarvis_study_prefs", Context.MODE_PRIVATE)

    private var callback: Callback? = null
    @Volatile private var rate = 1f
    @Volatile private var token = 0L
    private var wakePaused = false
    private var failures = 0
    private val resumeWake = Runnable { resumeWakeWord() }

    // Buffered fallback player (REST TTS / ElevenLabs)
    private var player: MediaPlayer? = null
    private val cache = LinkedHashMap<String, Deferred<ByteArray?>>()

    // Real-time WebSocket + AudioTrack streaming player
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

    /** Returns the user's selected JARVIS cloud voice name (e.g., Charon, Aoede, Puck, or ElevenLabs speaker). */
    fun activeVoiceName(): String = when (engine()) {
        VoiceEngineType.CLOUD_ELEVENLABS -> {
            runCatching { JarvisVoiceDispatcher.getInstance(app).getActiveSpeaker().displayName }
                .getOrDefault("ElevenLabs")
        }
        VoiceEngineType.CLOUD_GEMINI -> selectedGeminiVoice()
        else -> "Offline"
    }

    /** True when JARVIS cloud voice can be attempted right now. */
    fun cloudAvailable(): Boolean {
        if (System.currentTimeMillis() < cloudBlockedUntil) return false
        if (!NetworkStateMonitor.isInternetAvailable(app)) return false
        return when (engine()) {
            VoiceEngineType.CLOUD_ELEVENLABS -> elevenKey().isNotBlank()
            VoiceEngineType.CLOUD_GEMINI -> geminiKey().isNotBlank()
            else -> false
        }
    }

    /** Pre-warms the real-time voice WebSocket so the first step starts speaking with minimal latency. */
    fun warmUp(languageHint: String = "Auto") {
        if (!cloudAvailable()) return
        if (engine() == VoiceEngineType.CLOUD_GEMINI) {
            livePlayer.warmUp(selectedGeminiVoice(), languageHint)
        }
    }

    /** Prepares REST TTS audio ahead of time when using ElevenLabs or buffered fallback mode. */
    fun prefetch(text: String) = prefetch(text, "Auto")

    fun prefetch(text: String, languageHint: String) {
        val clean = text.trim()
        if (clean.isEmpty() || !cloudAvailable()) return
        if (engine() == VoiceEngineType.CLOUD_GEMINI && livePlayer.isHealthy()) {
            // Live WebSocket streams on demand with sub-second latency; keep it warm instead of paying double REST calls.
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

    /** Speaks [text] with the selected JARVIS voice and returns its token. Replaces any active utterance immediately. */
    fun speak(text: String, locale: Locale): Long = speak(text, locale, "Auto")

    fun speak(text: String, @Suppress("UNUSED_PARAMETER") locale: Locale, languageHint: String): Long {
        stopAudioOnly(keepLiveSession = true)
        val my = ++token
        val clean = text.trim()
        if (clean.isEmpty()) {
            main.post { if (my == token) callback?.onFinished(my, true, "") }
            return my
        }
        if (!cloudAvailable()) {
            main.post {
                if (my == token) {
                    markCloudDown(quiet = true)
                    callback?.onFinished(my, false, NO_VOICE)
                }
            }
            return my
        }

        pauseWakeWord()
        val activeEngine = engine()
        if (activeEngine == VoiceEngineType.CLOUD_GEMINI && livePlayer.isHealthy()) {
            val started = livePlayer.speak(
                token = my,
                text = clean,
                voiceName = selectedGeminiVoice(),
                languageName = StudyVoiceCodec.resolveLanguageName(languageHint, clean),
                onFirstAudio = { t ->
                    main.post {
                        if (t == token) {
                            failures = 0
                            callback?.onStarted(t, true)
                        }
                    }
                },
                onAmplitude = { amp ->
                    main.post {
                        if (my == token) callback?.onAmplitude(amp)
                    }
                },
                onComplete = { t, ok, shouldFallback ->
                    main.post {
                        if (t != token) return@post
                        callback?.onAmplitude(0f)
                        if (ok) {
                            failures = 0
                            callback?.onFinished(t, true, "")
                            scheduleWakeResume(WAKE_RESUME_MS)
                        } else if (shouldFallback) {
                            // Live socket unavailable or timed out before producing sound: seamless REST TTS fallback
                            speakBuffered(clean, languageHint, t)
                        } else {
                            noteFailure()
                            callback?.onFinished(t, false, NO_VOICE)
                            scheduleWakeResume(WAKE_RESUME_SHORT_MS)
                        }
                    }
                }
            )
            if (started) return my
        }

        speakBuffered(clean, languageHint, my)
        return my
    }

    private fun speakBuffered(clean: String, languageHint: String, my: Long) {
        val key = keyFor(clean, languageHint)
        val pending = cache.remove(key) ?: scope.async { gate.withPermit { synthesizeWithRetry(clean, languageHint) } }
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
                    playMediaBytes(bytes, my)
                }
            }
        }
    }

    fun stop() {
        token++
        stopAudioOnly(keepLiveSession = false)
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

    // ----- buffered playback (fallback & ElevenLabs) -----

    private fun playMediaBytes(bytes: ByteArray, my: Long) {
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(MemorySource(bytes))
            val waveRunnable = object : Runnable {
                var phase = 0f
                override fun run() {
                    val active = player
                    if (my != token || active == null) return
                    val playing = runCatching { active.isPlaying }.getOrDefault(false)
                    if (!playing) return
                    phase += 0.35f
                    val amp = ((kotlin.math.sin(phase) + 1f) * 0.42f).coerceIn(0.18f, 0.90f)
                    callback?.onAmplitude(amp)
                    main.postDelayed(this, 60L)
                }
            }
            p.setOnPreparedListener { mp ->
                if (my != token) {
                    try { mp.release() } catch (_: Exception) {}
                    return@setOnPreparedListener
                }
                try { mp.playbackParams = mp.playbackParams.setSpeed(rate) } catch (_: Exception) {}
                mp.start()
                callback?.onStarted(my, true)
                main.post(waveRunnable)
            }
            p.setOnCompletionListener { mp ->
                main.removeCallbacks(waveRunnable)
                callback?.onAmplitude(0f)
                try { mp.release() } catch (_: Exception) {}
                if (player === mp) player = null
                if (my == token) {
                    callback?.onFinished(my, true, "")
                    scheduleWakeResume(WAKE_RESUME_MS)
                }
            }
            p.setOnErrorListener { mp, _, _ ->
                main.removeCallbacks(waveRunnable)
                callback?.onAmplitude(0f)
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
        if (++failures >= 3) markCloudDown(quiet = false)
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
        try {
            JarvisConversationService.onStudyBoardNarrationChanged(app, true)
        } catch (_: Throwable) {}
        try {
            if (JarvisWakeWordService.isRunning()) JarvisWakeWordService.stopService(app)
        } catch (_: Throwable) {}
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
        try {
            JarvisConversationService.onStudyBoardNarrationChanged(app, false)
        } catch (_: Throwable) {}
    }

    // ----- engine & REST synthesis -----

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
            VoiceEngineType.CLOUD_GEMINI -> synthesizeGeminiRest(text, languageHint)
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
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            return res.body?.bytes()
        }
    }

    private fun synthesizeGeminiRest(text: String, languageHint: String): ByteArray? {
        val key = geminiKey()
        val name = model.ifBlank { discoverModel(key) } ?: return null
        val voice = selectedGeminiVoice()
        val langName = StudyVoiceCodec.resolveLanguageName(languageHint, text)
        val promptText = "Speak naturally as a calm, fluent human teacher in $langName. Read ONLY this text verbatim with proper pronunciation:\n$text"
        val body = JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", promptText) }) })
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
        http.newCall(req).execute().use { res ->
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
        http.newCall(req).execute().use { res ->
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

    // ----- Real-Time BidiGenerateContent Streaming Speaker -----

    private inner class LiveStreamEngine {
        private val wsLock = Any()
        private var ws: WebSocket? = null
        @Volatile private var setupComplete = false
        @Volatile private var connectedVoice = ""
        @Volatile private var connectedModel = GeminiConstants.DEFAULT_MODEL
        @Volatile private var openedAt = 0L
        private val liveDisabledUntil = AtomicLong(0L)

        // Active utterance turn state (synchronized via turnToken)
        @Volatile private var activeTurnToken = -1L
        @Volatile private var turnFirstAudioSent = false
        @Volatile private var turnServerDone = false
        private var turnOnFirstAudio: ((Long) -> Unit)? = null
        private var turnOnAmplitude: ((Float) -> Unit)? = null
        private var turnOnComplete: ((Long, Boolean, Boolean) -> Unit)? = null
        private var turnTimeoutRunnable: Runnable? = null

        private val audioQueue = LinkedBlockingQueue<ByteArray>()
        private var track: AudioTrack? = null
        private val trackLock = Any()
        private val running = AtomicBoolean(false)
        private val playing = AtomicBoolean(false)
        private val workerStop = AtomicBoolean(false)
        private var worker: Thread? = null
        @Volatile private var activeFlushGen = 0

        fun isHealthy(): Boolean =
            System.currentTimeMillis() >= liveDisabledUntil.get()

        fun warmUp(voiceName: String, @Suppress("UNUSED_PARAMETER") languageHint: String) {
            if (!isHealthy()) return
            val key = geminiKey()
            if (key.isBlank()) return
            synchronized(wsLock) {
                val agedOut = System.currentTimeMillis() - openedAt > LIVE_MAX_AGE_MS
                if (ws != null && setupComplete && connectedVoice == voiceName && !agedOut) return
                ensureConnectedLocked(key, voiceName)
            }
        }

        fun updateSpeed(newRate: Float) {
            synchronized(trackLock) {
                applyPlaybackSpeed(track, newRate)
            }
        }

        fun speak(
            token: Long,
            text: String,
            voiceName: String,
            languageName: String,
            onFirstAudio: (Long) -> Unit,
            onAmplitude: (Float) -> Unit,
            onComplete: (Long, Boolean, Boolean) -> Unit
        ): Boolean {
            val key = geminiKey()
            if (key.isBlank() || !isHealthy()) return false

            ensureWorkerRunning()
            resetTrackAndQueue()

            activeTurnToken = token
            turnFirstAudioSent = false
            turnServerDone = false
            turnOnFirstAudio = onFirstAudio
            turnOnAmplitude = onAmplitude
            turnOnComplete = onComplete

            scheduleFirstChunkWatchdog(token)

            scope.launch {
                val sent = sendSpeakTurn(key, voiceName, languageName, text, token)
                if (!sent && activeTurnToken == token) {
                    finishTurn(token, ok = false, fallback = true)
                }
            }
            return true
        }

        fun cancelTurn(keepWarm: Boolean) {
            val prev = activeTurnToken
            activeTurnToken = -1L
            cancelWatchdog()
            turnOnFirstAudio = null
            turnOnAmplitude = null
            turnOnComplete = null
            val wasStreamingMidTurn = prev >= 0L && !turnServerDone
            resetTrackAndQueue()
            if (wasStreamingMidTurn || !keepWarm) {
                // Close mid-generation WebSocket so leftover PCM from an interrupted turn never bleeds into the next step.
                closeSocketOnly()
                if (keepWarm && geminiKey().isNotBlank() && isHealthy()) {
                    synchronized(wsLock) { ensureConnectedLocked(geminiKey(), selectedGeminiVoice()) }
                }
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

        private suspend fun sendSpeakTurn(
            key: String,
            voiceName: String,
            languageName: String,
            text: String,
            myToken: Long
        ): Boolean {
            synchronized(wsLock) {
                val agedOut = System.currentTimeMillis() - openedAt > LIVE_MAX_AGE_MS
                if (ws == null || connectedVoice != voiceName || agedOut) {
                    closeSocketOnly()
                    ensureConnectedLocked(key, voiceName)
                }
            }

            // Wait briefly for WebSocket handshake & setupComplete
            val deadline = System.currentTimeMillis() + 3_500L
            while (!setupComplete && System.currentTimeMillis() < deadline) {
                if (activeTurnToken != myToken) return false
                try { Thread.sleep(25) } catch (_: InterruptedException) { return false }
            }
            if (!setupComplete || activeTurnToken != myToken) return false

            val payload = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("text", "[Speak in $languageName] Read this board explanation aloud verbatim:\n$text")
                        }))
                    }))
                    put("turnComplete", true)
                })
            }
            val socket = synchronized(wsLock) { ws } ?: return false
            return socket.send(payload.toString())
        }

        private fun ensureConnectedLocked(key: String, voiceName: String) {
            if (key.isBlank()) return
            closeSocketOnly()
            connectedVoice = voiceName
            setupComplete = false
            openedAt = System.currentTimeMillis()
            val req = Request.Builder()
                .url("${GeminiConstants.WS_BASE_URL}?key=$key")
                .build()
            ws = http.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val setupJson = buildSpeakerSetupJson(connectedModel, voiceName)
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
            if (code == 1008 && connectedModel != GeminiConstants.MODEL_3_8_LIVE) {
                connectedModel = GeminiConstants.MODEL_3_8_LIVE
            } else if (code == 401 || code == 403 || reason.contains("API_KEY_INVALID", true)) {
                liveDisabledUntil.set(System.currentTimeMillis() + 60_000L)
            }
            val currentTurn = activeTurnToken
            if (currentTurn >= 0L && !turnServerDone) {
                finishTurn(currentTurn, ok = false, fallback = !turnFirstAudioSent)
            }
        }

        private fun closeSocketOnly() {
            synchronized(wsLock) {
                setupComplete = false
                val old = ws
                ws = null
                runCatching { old?.close(1000, "Reset") }
            }
        }

        private fun buildSpeakerSetupJson(modelName: String, voiceName: String): JSONObject =
            JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", modelName)
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", SPEAKER_SYSTEM_PROMPT)))
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
                        put("temperature", 0.35)
                        if (modelName.contains("2.5")) {
                            put("thinkingConfig", JSONObject().apply { put("thinkingBudget", 0) })
                        }
                    })
                })
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
                                audioQueue.offer(pcm)
                            }
                        }
                    }
                }

                if (server.optBoolean("turnComplete", false) ||
                    server.optBoolean("generationComplete", false) ||
                    server.optBoolean("endOfTurn", false)
                ) {
                    turnServerDone = true
                    if (!turnFirstAudioSent) {
                        // Server ended turn without sending audio -> fall back cleanly
                        finishTurn(currentTurn, ok = false, fallback = true)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Study Live parse skipped: ${e.message}")
            }
        }

        private fun scheduleFirstChunkWatchdog(turnToken: Long) {
            cancelWatchdog()
            val r = Runnable {
                if (activeTurnToken == turnToken && !turnFirstAudioSent) {
                    liveDisabledUntil.set(System.currentTimeMillis() + 12_000L)
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
            val cb = turnOnComplete
            activeTurnToken = -1L
            turnOnFirstAudio = null
            turnOnAmplitude = null
            turnOnComplete = null
            cb?.invoke(turnToken, ok, fallback)
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
                var emptyCycles = 0
                while (!workerStop.get()) {
                    try {
                        val genAtStart = activeFlushGen
                        if (!playing.get()) {
                            val first = audioQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
                            if (first.isEmpty() || genAtStart != activeFlushGen) continue
                            val batch = ArrayList<ByteArray>(4)
                            batch.add(first)
                            var buffered = first.size
                            val prefillUntil = System.nanoTime() + 140_000_000L
                            while (buffered < 8_000 && System.nanoTime() < prefillUntil && !turnServerDone) {
                                val next = audioQueue.poll(12, TimeUnit.MILLISECONDS) ?: continue
                                if (next.isNotEmpty()) { batch.add(next); buffered += next.size }
                            }
                            if (genAtStart != activeFlushGen) continue
                            playing.set(true)
                            emptyCycles = 0
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
                            }
                            turnOnAmplitude?.invoke(AudioUtils.calculateRmsNormalized(first, first.size))
                        } else {
                            val chunk = audioQueue.poll(20, TimeUnit.MILLISECONDS)
                            if (chunk != null && chunk.isNotEmpty()) {
                                if (genAtStart != activeFlushGen) continue
                                emptyCycles = 0
                                turnOnAmplitude?.invoke(AudioUtils.calculateRmsNormalized(chunk, chunk.size))
                                writeChunk(chunk)
                            } else {
                                emptyCycles++
                                val done = (turnServerDone && emptyCycles >= 6) || (emptyCycles >= 60 && turnServerDone)
                                if (done && playing.getAndSet(false)) {
                                    val finishedToken = activeTurnToken
                                    if (finishedToken >= 0L) {
                                        finishTurn(finishedToken, ok = true, fallback = false)
                                    }
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

        private fun initTrackLocked(): AudioTrack {
            val minBytes = AudioTrack.getMinBufferSize(
                PCM_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufBytes = maxOf(minBytes * 8, 96_000)
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
            synchronized(trackLock) {
                runCatching {
                    track?.let { t ->
                        if (t.state == AudioTrack.STATE_INITIALIZED) {
                            runCatching { t.pause() }
                            runCatching { t.flush() }
                            runCatching { t.stop() }
                        }
                    }
                }
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
        private const val NO_VOICE = "JARVIS voice could not be reached right now, so the board continues silently. Check your internet and Gemini key."
        private const val KEY_MODEL = "voice_model"
        private const val KEY_MODEL_AT = "voice_model_at"
        private const val PCM_SAMPLE_RATE = 24_000
        private const val MAX_CACHE = 14
        private const val WAIT_MS = 22_000L
        private const val BLOCK_MS = 15_000L
        private const val WAKE_RESUME_MS = 1_400L
        private const val WAKE_RESUME_SHORT_MS = 600L
        private const val LIVE_FIRST_AUDIO_TIMEOUT_MS = 4_800L
        private const val LIVE_MAX_AGE_MS = 8L * 60_000L
        private const val DAY_MS = 24L * 3600_000L
        private const val SPEAKER_SYSTEM_PROMPT =
            "You are JARVIS speaking aloud on the Study Board. " +
            "CRITICAL: Read ONLY the provided teaching script word-for-word as a calm, warm, ultra-realistic human tutor in the requested language. " +
            "Use natural human pacing, emphasize key concepts cleanly, pronounce math and technical terms smoothly, and NEVER add extra greetings, commentary, or follow-up questions."
    }
}
