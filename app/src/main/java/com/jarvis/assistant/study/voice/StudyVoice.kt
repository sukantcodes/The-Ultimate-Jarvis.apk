package com.jarvis.assistant.study.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.offline.NetworkStateMonitor
import com.jarvis.assistant.tts.engine.JarvisVoiceDispatcher
import com.jarvis.assistant.tts.model.SpeakerProfile
import com.jarvis.assistant.tts.model.VoiceEngineType
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
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The teacher's voice for the Study Board: the same JARVIS voice used everywhere else in the app (Gemini cloud voice, or
 * ElevenLabs when that is the chosen engine), with the phone's own voice as a fallback.
 *
 * Latency is handled by preparing audio before it is needed. The board calls [prefetch] as soon as a step's spoken line
 * arrives from the AI, so by the time the pen reaches that step the audio is usually already in memory and starts at once.
 * If the cloud voice cannot be reached in time, the phone's voice speaks instead, so the lesson never stalls waiting for it.
 *
 * Unlike the general voice dispatcher, completion is reported when the audio has finished playing, not when it started,
 * because the board uses it to decide when the next step may begin.
 */
class StudyVoice(context: Context) {

    interface Callback {
        fun onStarted(token: Long, cloud: Boolean) {}
        fun onFinished(token: Long, ok: Boolean, reason: String)
        /** One short, human-readable note when the cloud voice could not be used and the phone voice took over. */
        fun onNote(text: String) {}
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    private val local = StudyTts(app)
    private val prefs = app.getSharedPreferences("jarvis_study_prefs", Context.MODE_PRIVATE)

    private var callback: Callback? = null
    private var rate = 1f
    private var token = 0L
    private var localToken = 0L
    private var localFor = 0L
    private var player: MediaPlayer? = null
    private val cache = LinkedHashMap<String, Deferred<ByteArray?>>()
    private var cloudBlockedUntil = 0L
    private var noteShown = false
    @Volatile private var model: String = prefs.getString(KEY_MODEL, "") ?: ""

    init {
        local.setCallback(object : StudyTts.Callback {
            override fun onStarted(token: Long) { if (token == localToken && localFor == this@StudyVoice.token) callback?.onStarted(localFor, false) }
            override fun onFinished(token: Long, ok: Boolean, reason: String) { if (token == localToken && localFor == this@StudyVoice.token) callback?.onFinished(localFor, ok, reason) }
        })
    }

    fun setCallback(cb: Callback?) { callback = cb }

    fun setRate(r: Float) {
        rate = r.coerceIn(0.7f, 1.6f)
        local.setRate(rate)
        try { player?.let { p -> if (p.isPlaying) p.playbackParams = p.playbackParams.setSpeed(rate) } } catch (_: Exception) {}
    }

    /** True when the JARVIS cloud voice can be tried right now. */
    fun cloudAvailable(): Boolean {
        if (System.currentTimeMillis() < cloudBlockedUntil) return false
        if (!NetworkStateMonitor.isInternetAvailable(app)) return false
        return when (engine()) {
            VoiceEngineType.CLOUD_ELEVENLABS -> elevenKey().isNotBlank()
            VoiceEngineType.CLOUD_GEMINI -> geminiKey().isNotBlank()
            else -> false
        }
    }

    /** Starts preparing the audio for [text] in the background so that [speak] can begin without waiting. */
    fun prefetch(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || !cloudAvailable()) return
        main.post {
            val key = keyFor(clean)
            if (cache.containsKey(key)) return@post
            cache[key] = scope.async { gate.withPermit { synthesize(clean) } }
            while (cache.size > MAX_CACHE) { val first = cache.keys.first(); cache.remove(first)?.cancel() }
        }
    }

    /** Speaks [text] and returns its token. A new call replaces whatever is being spoken. */
    fun speak(text: String, locale: Locale): Long {
        stopPlayerOnly()
        local.stop()
        val my = ++token
        val clean = text.trim()
        if (clean.isEmpty()) { main.post { if (my == token) callback?.onFinished(my, true, "") }; return my }
        if (!cloudAvailable()) { speakLocal(clean, locale, my); return my }
        val key = keyFor(clean)
        val pending = cache.remove(key) ?: scope.async { gate.withPermit { synthesize(clean) } }
        scope.launch {
            val bytes = withTimeoutOrNull(WAIT_MS) { runCatching { pending.await() }.getOrNull() }
            main.post {
                if (my != token) { return@post }
                if (bytes == null || bytes.isEmpty()) {
                    // the request is left running so its audio is ready if the student replays this step
                    if (!pending.isCompleted) cache[key] = pending else markCloudDown()
                    speakLocal(clean, locale, my)
                } else {
                    cache[key] = pending
                    play(bytes, my, clean, locale)
                }
            }
        }
        return my
    }

    fun stop() {
        token++
        stopPlayerOnly()
        local.stop()
    }

    fun shutdown() {
        token++
        stopPlayerOnly()
        local.shutdown()
        cache.values.forEach { it.cancel() }
        cache.clear()
        scope.cancel()
        callback = null
    }

    // ----- playback -----

    private fun speakLocal(text: String, locale: Locale, my: Long) {
        // the phone voice numbers its own utterances; remember which of ours it is speaking for
        localFor = my
        localToken = local.nextToken()
        local.speak(text, locale)
    }

    private fun play(bytes: ByteArray, my: Long, text: String, locale: Locale) {
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(MemorySource(bytes))
            p.setOnPreparedListener { mp ->
                if (my != token) { try { mp.release() } catch (_: Exception) {}; return@setOnPreparedListener }
                try { mp.playbackParams = mp.playbackParams.setSpeed(rate) } catch (_: Exception) {}
                mp.start()
                callback?.onStarted(my, true)
            }
            p.setOnCompletionListener { mp ->
                try { mp.release() } catch (_: Exception) {}
                if (player === mp) player = null
                if (my == token) callback?.onFinished(my, true, "")
            }
            p.setOnErrorListener { mp, _, _ ->
                try { mp.release() } catch (_: Exception) {}
                if (player === mp) player = null
                if (my == token) { markCloudDown(); speakLocal(text, locale, my) }
                true
            }
            player = p
            p.prepareAsync()
        } catch (_: Exception) {
            markCloudDown()
            speakLocal(text, locale, my)
        }
    }

    private fun stopPlayerOnly() {
        val p = player ?: return
        player = null
        try { p.setOnCompletionListener(null); p.setOnErrorListener(null); p.setOnPreparedListener(null); p.stop() } catch (_: Exception) {}
        try { p.release() } catch (_: Exception) {}
    }

    private fun markCloudDown() {
        cloudBlockedUntil = System.currentTimeMillis() + BLOCK_MS
        if (!noteShown) { noteShown = true; callback?.onNote("JARVIS voice could not be reached, so your phone's voice is reading the lesson.") }
    }

    // ----- synthesis -----

    private fun engine(): VoiceEngineType {
        // mirror JarvisVoiceDispatcher: a Gemini key means the Gemini voice unless ElevenLabs was chosen
        val chosen = runCatching { JarvisVoiceDispatcher.getInstance(app).getActiveEngineType() }.getOrDefault(VoiceEngineType.NONE)
        return when {
            chosen == VoiceEngineType.CLOUD_ELEVENLABS && elevenKey().isNotBlank() -> VoiceEngineType.CLOUD_ELEVENLABS
            geminiKey().isNotBlank() -> VoiceEngineType.CLOUD_GEMINI
            else -> VoiceEngineType.NONE
        }
    }

    private fun geminiKey() = JarvisApp.instance.preferences.apiKey.trim()
    private fun elevenKey() = JarvisApp.instance.preferences.elevenLabsApiKey.trim()

    private fun keyFor(text: String) = engine().name + "|" + text

    private fun synthesize(text: String): ByteArray? = try {
        when (engine()) {
            VoiceEngineType.CLOUD_ELEVENLABS ->
                JarvisVoiceDispatcher.getInstance(app).cloudEngine.fetchElevenLabsSpeech(text, JarvisVoiceDispatcher.getInstance(app).getActiveSpeaker())
            VoiceEngineType.CLOUD_GEMINI -> synthesizeGemini(text)
            else -> null
        }
    } catch (_: Exception) { null }

    private fun synthesizeGemini(text: String): ByteArray? {
        val key = geminiKey()
        val name = model.ifBlank { discoverModel(key) } ?: return null
        val voice = SpeakerProfile.defaultFor(VoiceEngineType.CLOUD_GEMINI).cloudVoiceIdentifier.ifBlank { "Charon" }
        val body = JsonObject().apply {
            add("contents", com.google.gson.JsonArray().apply {
                add(JsonObject().apply { add("parts", com.google.gson.JsonArray().apply { add(JsonObject().apply { addProperty("text", STYLE + text) }) }) })
            })
            add("generationConfig", JsonObject().apply {
                add("responseModalities", com.google.gson.JsonArray().apply { add("AUDIO") })
                add("speechConfig", JsonObject().apply {
                    add("voiceConfig", JsonObject().apply { add("prebuiltVoiceConfig", JsonObject().apply { addProperty("voiceName", voice) }) })
                })
            })
        }
        val req = Request.Builder()
            .url("$BASE/models/$name:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { res ->
            if (res.code == 404) { forgetModel(); return null }
            if (!res.isSuccessful) return null
            val root = JsonParser.parseString(res.body?.string() ?: return null).asJsonObject
            val parts = root.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject?.getAsJsonObject("content")?.getAsJsonArray("parts") ?: return null
            for (p in parts) {
                val inline = p.asJsonObject.getAsJsonObject("inlineData") ?: continue
                val data = inline.get("data")?.asString ?: continue
                val mime = inline.get("mimeType")?.asString.orEmpty()
                val raw = Base64.decode(data, Base64.DEFAULT)
                return if (StudyVoiceCodec.isRawPcm(mime)) StudyVoiceCodec.pcm16ToWav(raw, StudyVoiceCodec.sampleRate(mime)) else raw
            }
        }
        return null
    }

    /** Asks Google which speech model this key can use, once a day, and remembers the answer. */
    private fun discoverModel(key: String): String? {
        val savedAt = prefs.getLong(KEY_MODEL_AT, 0L)
        val saved = prefs.getString(KEY_MODEL, "").orEmpty()
        if (saved.isNotBlank() && System.currentTimeMillis() - savedAt < DAY_MS) { model = saved; return saved }
        val req = Request.Builder().url("$BASE/models?pageSize=200").header("x-goog-api-key", key).build()
        val entries = ArrayList<StudyVoiceCodec.ModelEntry>()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val arr = JsonParser.parseString(res.body?.string() ?: return null).asJsonObject.getAsJsonArray("models") ?: return null
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

    private fun forgetModel() { model = ""; prefs.edit().remove(KEY_MODEL).remove(KEY_MODEL_AT).apply() }

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
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val STYLE = "Say this in a warm, clear, unhurried voice, like a patient teacher explaining to one student: "
        private const val KEY_MODEL = "voice_model"
        private const val KEY_MODEL_AT = "voice_model_at"
        private const val MAX_CACHE = 14
        private const val WAIT_MS = 6500L
        private const val BLOCK_MS = 60_000L
        private const val DAY_MS = 24L * 3600_000L
    }
}
