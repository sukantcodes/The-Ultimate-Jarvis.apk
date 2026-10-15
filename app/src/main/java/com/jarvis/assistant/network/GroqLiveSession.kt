package com.jarvis.assistant.network

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A Groq-powered replacement for the Gemini Live session. It plugs into the same service by offering the
 * same methods and callbacks, but works turn by turn:
 *   microphone audio -> Groq Whisper (speech to text) -> Groq chat model with the same phone tools
 *   -> Android text-to-speech -> audio back to Jarvis.
 */
class GroqLiveSession(
    private val context: Context,
    private val groqKey: String,
    private val chatModel: String,
    voiceName: String,
    private val systemPromptText: String,
    private val groqListener: GeminiLiveWebSocket.Listener
) : GeminiLiveWebSocket(groqKey, "", voiceName, systemPromptText, groqListener) {

    private val genderStore = com.jarvis.assistant.voice.VoiceGenderStore(context)
    private val ready = AtomicBoolean(false)
    private val activeTurns = AtomicInteger(0)
    private val turnMutex = Mutex()
    private var sessionJob: Job? = null
    private var sessionScope: CoroutineScope? = null

    private val history = ArrayList<JSONObject>()
    private var toolList: JSONArray = JSONArray()
    private var systemMessage: JSONObject = JSONObject()
    private val pendingTools = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    // --- voice activity detection ---
    private val vadLock = Any()
    private val utterance = ByteArrayOutputStream()
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollBytes = 0
    private var inSpeech = false
    private var voicedRunMs = 0
    private var speechMs = 0
    private var silenceMs = 0
    private var noiseFloor = 300.0

    // --- text to speech ---
    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()
    private val ttsWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val ttsCounter = AtomicInteger(0)
    private var ttsLocale: Locale? = null

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            if (utteranceId != null) ttsWaiters.remove(utteranceId)?.complete(true)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            if (utteranceId != null) ttsWaiters.remove(utteranceId)?.complete(false)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceId != null) ttsWaiters.remove(utteranceId)?.complete(false)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------------------------------

    override fun isSessionReady(): Boolean = ready.get()

    override fun connect(scope: CoroutineScope) {
        val job = SupervisorJob(scope.coroutineContext[Job])
        sessionJob = job
        sessionScope = CoroutineScope(scope.coroutineContext + job)

        if (groqKey.isBlank()) {
            groqListener.onError(
                "No Groq API key saved. Open Settings > AI Engine and enter your Groq key.",
                true,
                "GROQ_KEY_MISSING"
            )
            groqListener.onConnectionStateChanged("OFFLINE")
            return
        }
        groqListener.onConnectionStateChanged("CONNECTING...")

        Handler(Looper.getMainLooper()).post {
            try {
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.setOnUtteranceProgressListener(progressListener)
                        ttsReady.complete(true)
                    } else {
                        ttsReady.complete(false)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Text-to-speech could not start: ${e.message}")
                ttsReady.complete(false)
            }
        }

        sessionScope?.launch(Dispatchers.Default) {
            toolList = buildTools()
            systemMessage = JSONObject()
                .put("role", "system")
                .put("content", compactPrompt())
            synchronized(history) { history.clear() }
            ready.set(true)
            groqListener.onConnectionStateChanged("LIVE")
        }
    }

    override fun disconnect() {
        ready.set(false)
        for (waiter in pendingTools.values) {
            waiter.complete(JSONObject().put("error", "Session closed"))
        }
        pendingTools.clear()
        sessionJob?.cancel()
        sessionJob = null
        sessionScope = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        groqListener.onConnectionStateChanged("OFFLINE")
    }

    override fun sendToolResponse(callId: String, functionName: String, result: JSONObject) {
        pendingTools[callId]?.complete(result)
    }

    override fun sendMediaChunk(jpegBytes: ByteArray) {
        // Groq's chat models used here do not take images.
    }

    override fun sendAudioStreamEnd() {
        var finished: ByteArray? = null
        synchronized(vadLock) {
            if (inSpeech && speechMs >= MIN_SPEECH_MS) finished = utterance.toByteArray()
            resetVad()
        }
        finished?.let { startVoiceTurn(it) }
    }

    override fun sendClientContent(text: String) {
        if (!ready.get() || text.isBlank()) return
        val scope = sessionScope ?: return
        activeTurns.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                turnMutex.withLock { respond(text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
            } finally {
                activeTurns.decrementAndGet()
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Listening: simple energy-based voice detection
    // ------------------------------------------------------------------------------------------

    override fun sendAudioChunk(pcm16k: ByteArray) {
        if (!ready.get() || activeTurns.get() > 0 || pcm16k.isEmpty()) return
        var finished: ByteArray? = null
        synchronized(vadLock) {
            val ms = pcm16k.size / 32
            val level = rms(pcm16k)
            val threshold = max(MIN_SPEECH_RMS, noiseFloor * 2.8)
            val voiced = level > threshold

            if (!inSpeech) {
                addPreRoll(pcm16k)
                if (voiced) {
                    voicedRunMs += ms
                    if (voicedRunMs >= START_MS) {
                        inSpeech = true
                        utterance.reset()
                        for (piece in preRoll) utterance.write(piece)
                        preRoll.clear()
                        preRollBytes = 0
                        speechMs = voicedRunMs
                        silenceMs = 0
                    }
                } else {
                    voicedRunMs = 0
                    noiseFloor = noiseFloor * 0.95 + level * 0.05
                }
            } else {
                utterance.write(pcm16k)
                if (voiced) {
                    speechMs += ms
                    silenceMs = 0
                } else {
                    silenceMs += ms
                }
                val totalMs = utterance.size() / 32
                if (silenceMs >= END_SILENCE_MS || totalMs >= MAX_UTTERANCE_MS) {
                    if (speechMs >= MIN_SPEECH_MS) finished = utterance.toByteArray()
                    resetVad()
                }
            }
        }
        finished?.let { startVoiceTurn(it) }
    }

    private fun resetVad() {
        inSpeech = false
        voicedRunMs = 0
        speechMs = 0
        silenceMs = 0
        utterance.reset()
        preRoll.clear()
        preRollBytes = 0
    }

    private fun addPreRoll(chunk: ByteArray) {
        preRoll.addLast(chunk.copyOf())
        preRollBytes += chunk.size
        while (preRollBytes > PRE_ROLL_BYTES && preRoll.isNotEmpty()) {
            preRollBytes -= preRoll.removeFirst().size
        }
    }

    private fun rms(pcm: ByteArray): Double {
        val samples = pcm.size / 2
        if (samples == 0) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val s = v.toShort().toInt()
            sum += s.toDouble() * s.toDouble()
            i += 2
        }
        return sqrt(sum / samples)
    }

    private fun startVoiceTurn(pcm: ByteArray) {
        if (activeTurns.get() > 0) return
        val scope = sessionScope ?: return
        activeTurns.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                turnMutex.withLock {
                    val text = transcribe(pcm)
                    if (!text.isNullOrBlank() && !isNoiseText(text)) {
                        groqListener.onUserTextReceived(text)
                        respond(text)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
            } finally {
                activeTurns.decrementAndGet()
            }
        }
    }

    private fun isNoiseText(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]+"), " ").trim()
        if (t.length < 2) return true
        return t in setOf("thank you", "thanks for watching", "you", "bye", "thanks")
    }

    // ------------------------------------------------------------------------------------------
    // Groq: speech to text, chat with tools
    // ------------------------------------------------------------------------------------------

    private suspend fun transcribe(pcm: ByteArray): String? {
        val wav = pcmToWav(pcm, 16_000)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", STT_MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV_TYPE))
            .build()
        val request = Request.Builder()
            .url("$BASE/audio/transcriptions")
            .header("Authorization", "Bearer $groqKey")
            .header("User-Agent", "JarvisAndroid/1.0")
            .post(body)
            .build()
        val (code, raw, retryAfter) = http.newCall(request).execute().use {
            Triple(it.code, it.body?.string().orEmpty(), it.header("retry-after"))
        }
        if (code !in 200..299) {
            reportHttpError(code, raw, retryAfter)
            return null
        }
        return try {
            JSONObject(raw).optString("text", "").trim()
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the assistant message, or null after the error was reported. */
    private suspend fun chat(): JSONObject? {
        var sendTools = toolList.length() > 0
        var retriedToolFailure = false
        for (attempt in 0 until 3) {
            val body = JSONObject().apply {
                put("model", chatModel)
                put("messages", JSONArray().apply {
                    put(systemMessage)
                    synchronized(history) { history.forEach { put(it) } }
                })
                if (sendTools) {
                    put("tools", toolList)
                    put("tool_choice", "auto")
                }
                put("temperature", 0.5)
                put("max_tokens", 500)
            }
            val request = Request.Builder()
                .url("$BASE/chat/completions")
                .header("Authorization", "Bearer $groqKey")
                .header("User-Agent", "JarvisAndroid/1.0")
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            val (code, raw, retryAfter) = http.newCall(request).execute().use {
                Triple(it.code, it.body?.string().orEmpty(), it.header("retry-after"))
            }
            if (code in 200..299) {
                return try {
                    JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                } catch (e: Exception) {
                    reportHttpError(500, raw, null)
                    null
                }
            }
            if (code == 400 && raw.contains("tool_use_failed") && !retriedToolFailure) {
                retriedToolFailure = true
                continue
            }
            if (sendTools && (code == 400 || code == 413 || code == 422)) {
                Log.w(TAG, "Groq rejected the tool list (HTTP $code). Retrying without tools.")
                sendTools = false
                continue
            }
            reportHttpError(code, raw, retryAfter)
            return null
        }
        reportHttpError(400, "", null)
        return null
    }

    private suspend fun reportException(e: Exception) {
        Log.e(TAG, "Groq turn failed: " + e.javaClass.simpleName + " " + e.message)
        groqListener.onError(
            "Groq network error: " + e.javaClass.simpleName + (e.message?.let { " - " + it.take(100) } ?: ""),
            false,
            "GROQ_NETWORK"
        )
        speak("I could not reach Groq. Please check your internet connection and try again.")
        groqListener.onTurnCompleted()
    }

    private suspend fun reportHttpError(code: Int, raw: String, retryAfter: String?) {
        val detail = try {
            JSONObject(raw).optJSONObject("error")?.optString("message", "").orEmpty()
        } catch (e: Exception) {
            ""
        }
        Log.w(TAG, "Groq HTTP $code ${detail.take(160)}")
        when (code) {
            401, 403 -> {
                groqListener.onError(
                    "Groq rejected the API key. Check it in Settings > AI Engine.",
                    true,
                    "GROQ_KEY_INVALID"
                )
                groqListener.onConnectionStateChanged("ERROR \u2022 INVALID KEY")
            }
            429 -> {
                val wait = retryAfter?.trim()?.toIntOrNull()
                val tail = if (wait != null && wait in 1..120) " Try again in about $wait seconds." else " Please wait a moment."
                speak("Groq's limit has been reached.$tail You can switch to Gemini in Settings.")
                groqListener.onTurnCompleted()
            }
            else -> {
                groqListener.onError("Groq error " + code + ": " + detail.take(140), false, "GROQ_HTTP_ERROR")
                speak("Sorry, Groq returned an error. Please try again.")
                groqListener.onTurnCompleted()
            }
        }
    }

    private suspend fun respond(userText: String) {
        addHistory(JSONObject().put("role", "user").put("content", userText))
        var rounds = 0
        while (rounds < 5) {
            rounds++
            val message = chat() ?: return
            val calls = message.optJSONArray("tool_calls")
            if (calls != null && calls.length() > 0) {
                addHistory(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", contentOf(message))
                        .put("tool_calls", calls)
                )
                val waiting = ArrayList<Pair<String, CompletableDeferred<JSONObject>>>()
                for (i in 0 until calls.length()) {
                    val call = calls.optJSONObject(i) ?: continue
                    val id = call.optString("id", "call_" + System.nanoTime() + "_" + i)
                    val function = call.optJSONObject("function")
                    val name = function?.optString("name", "").orEmpty()
                    val args = try {
                        val rawArgs = function?.optString("arguments", "{}").orEmpty()
                        JSONObject(if (rawArgs.isBlank()) "{}" else rawArgs)
                    } catch (e: Exception) {
                        JSONObject()
                    }
                    val waiter = CompletableDeferred<JSONObject>()
                    pendingTools[id] = waiter
                    waiting.add(Pair(id, waiter))
                    groqListener.onToolCall(id, name, args)
                }
                for ((id, waiter) in waiting) {
                    val result = withTimeoutOrNull(60_000L) { waiter.await() }
                        ?: JSONObject().put("error", "The tool took too long to answer.")
                    pendingTools.remove(id)
                    addHistory(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", id)
                            .put("content", result.toString().take(3000))
                    )
                }
                continue
            }

            val text = contentOf(message).trim()
            addHistory(JSONObject().put("role", "assistant").put("content", text))
            if (text.isNotBlank()) {
                groqListener.onAssistantTextReceived(text)
                speak(text)
            }
            groqListener.onTurnCompleted()
            return
        }
        groqListener.onTurnCompleted()
    }

    private fun contentOf(message: JSONObject): String =
        if (message.isNull("content")) "" else message.optString("content", "")

    private fun addHistory(message: JSONObject) {
        synchronized(history) {
            history.add(message)
            while (history.size > MAX_HISTORY) history.removeAt(0)
            while (history.isNotEmpty() && history[0].optString("role") != "user") history.removeAt(0)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Prompt and tools (kept short so Groq's per-minute token limits are not used up)
    // ------------------------------------------------------------------------------------------

    private fun compactPrompt(): String {
        val marker = "=== ACTIVITY LOG"
        val markerIndex = systemPromptText.indexOf(marker)
        val base = if (markerIndex >= 0) systemPromptText.substring(0, markerIndex) else systemPromptText
        val log = if (markerIndex >= 0) systemPromptText.substring(markerIndex).take(3_500) else ""
        return base.take(3_500) + genderStore.promptSection() + "\n\n" + log + "\n\n" +
            "ENGINE NOTES: You are running on the Groq engine. Reply in one to three short, natural spoken sentences in the " +
            "user's language (English, Hindi or Hinglish). Never use markdown, lists, emojis or asterisks. When the user asks " +
            "you to do something on the phone, call the matching tool instead of describing it. Say a task is done only after " +
            "the tool result says success. If a tool result starts with REFUSED, follow its instructions exactly."
    }

    private fun buildTools(): JSONArray {
        val out = JSONArray()
        val declarations = try {
            toolDeclarations()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read tool list: ${e.message}")
            JSONArray()
        }
        for (i in 0 until declarations.length()) {
            val d = declarations.optJSONObject(i) ?: continue
            val name = d.optString("name", "")
            if (name.isBlank()) continue
            val params = d.optJSONObject("parameters")
                ?: JSONObject().put("type", "OBJECT").put("properties", JSONObject())
            val function = JSONObject()
                .put("name", name)
                .put("description", firstSentence(d.optString("description", ""), 140))
                .put("parameters", convertSchema(params, 70))
            out.put(JSONObject().put("type", "function").put("function", function))
        }
        return out
    }

    private fun convertSchema(src: JSONObject, descMax: Int): JSONObject {
        val out = JSONObject()
        val keys = src.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = src.get(key)
            when {
                key == "type" && value is String -> out.put(key, value.lowercase(Locale.ROOT))
                key == "description" && value is String -> out.put(key, firstSentence(value, descMax))
                key == "items" && value is JSONObject -> out.put(key, convertSchema(value, descMax))
                key == "properties" && value is JSONObject -> {
                    val props = JSONObject()
                    val names = value.keys()
                    while (names.hasNext()) {
                        val propName = names.next()
                        val child = value.optJSONObject(propName)
                        props.put(propName, if (child != null) convertSchema(child, descMax) else value.get(propName))
                    }
                    out.put(key, props)
                }
                else -> out.put(key, value)
            }
        }
        if (out.optString("type") == "array" && !out.has("items")) {
            out.put("items", JSONObject().put("type", "string"))
        }
        return out
    }

    private fun firstSentence(text: String, maxChars: Int): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        val cut = t.indexOf(". ")
        val base = if (cut in 20..maxChars) t.substring(0, cut + 1) else t
        return if (base.length <= maxChars) base else base.substring(0, maxChars - 1).trimEnd() + "\u2026"
    }

    // ------------------------------------------------------------------------------------------
    // Speaking: Android text-to-speech turned into the PCM the player expects (24 kHz, 16-bit, mono)
    // ------------------------------------------------------------------------------------------

    private suspend fun speak(rawText: String) {
        val clean = sanitizeForSpeech(rawText)
        if (clean.isBlank()) return
        for (part in splitForSpeech(clean)) {
            val pcm = synthesize(part) ?: continue
            var offset = 0
            while (offset < pcm.size) {
                val end = min(offset + OUT_CHUNK_BYTES, pcm.size)
                groqListener.onAudioDataReceived(pcm.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    private fun sanitizeForSpeech(text: String): String =
        text.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "\$1")
            .replace(Regex("[*_#`~>|]"), "")
            .replace(Regex("[\\p{So}\\p{Sk}]"), "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()

    private fun splitForSpeech(text: String): List<String> {
        val pieces = Regex("(?<=[.!?\u0964])\\s+|\\n+").split(text).map { it.trim() }.filter { it.isNotEmpty() }
        val merged = ArrayList<String>()
        var current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
            if (current.length >= 60) {
                merged.add(current.toString())
                current = StringBuilder()
            }
        }
        if (current.isNotEmpty()) merged.add(current.toString())
        return merged.map { if (it.length > 600) it.take(600) else it }
    }

    private suspend fun synthesize(text: String): ByteArray? {
        val ok = withTimeoutOrNull(4_000L) { ttsReady.await() } ?: false
        val engine = tts
        if (!ok || engine == null) return null

        val wanted = if (text.any { it in '\u0900'..'\u097F' }) Locale("hi", "IN") else Locale("en", "IN")
        if (ttsLocale != wanted) {
            val result = engine.setLanguage(wanted)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
            ttsLocale = wanted
        }

        try {
            engine.setPitch(genderStore.pitch())
        } catch (_: Exception) {
        }

        val id = "groq_tts_" + ttsCounter.incrementAndGet()
        val done = CompletableDeferred<Boolean>()
        ttsWaiters[id] = done
        val file = File(context.cacheDir, "$id.wav")
        try {
            val rc = engine.synthesizeToFile(text, Bundle(), file, id)
            if (rc != TextToSpeech.SUCCESS) {
                ttsWaiters.remove(id)
                return null
            }
            val finished = withTimeoutOrNull(15_000L) { done.await() } ?: false
            ttsWaiters.remove(id)
            if (!finished || !file.exists()) return null
            return wavToPcm24k(file.readBytes())
        } catch (e: Exception) {
            Log.w(TAG, "Speech synthesis failed: ${e.message}")
            return null
        } finally {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // WAV helpers
    // ------------------------------------------------------------------------------------------

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArray(44 + pcm.size)
        fun ascii(offset: Int, s: String) {
            for (i in s.indices) out[offset + i] = s[i].code.toByte()
        }
        fun int32(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v shr 8) and 0xFF).toByte()
            out[offset + 2] = ((v shr 16) and 0xFF).toByte()
            out[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun int16(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF")
        int32(4, 36 + pcm.size)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int32(16, 16)
        int16(20, 1)
        int16(22, 1)
        int32(24, sampleRate)
        int32(28, sampleRate * 2)
        int16(32, 2)
        int16(34, 16)
        ascii(36, "data")
        int32(40, pcm.size)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** Reads a 16-bit WAV file and returns mono 24 kHz little-endian PCM, or null when it cannot be read. */
    private fun wavToPcm24k(bytes: ByteArray): ByteArray? {
        if (bytes.size < 44) return null
        val channels = leShort(bytes, 22)
        val sampleRate = leInt(bytes, 24)
        val bits = leShort(bytes, 34)
        if (bits != 16 || channels < 1 || sampleRate < 4_000) return null

        var pos = 12
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = leInt(bytes, pos + 4)
            if (id == "data") {
                dataStart = pos + 8
                dataLen = if (len <= 0 || dataStart + len > bytes.size) bytes.size - dataStart else len
                break
            }
            if (len < 0) break
            pos += 8 + len
        }
        if (dataStart < 0 || dataLen <= 0) return null

        val frames = dataLen / (2 * channels)
        if (frames <= 0) return null
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val o = dataStart + (f * channels + c) * 2
                sum += (bytes[o].toInt() and 0xFF or (bytes[o + 1].toInt() shl 8)).toShort().toInt()
            }
            mono[f] = (sum / channels).toShort()
        }

        if (sampleRate == OUTPUT_RATE) {
            val out = ByteArray(frames * 2)
            for (i in 0 until frames) {
                val v = mono[i].toInt()
                out[2 * i] = (v and 0xFF).toByte()
                out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }

        val ratio = sampleRate.toDouble() / OUTPUT_RATE.toDouble()
        val outLen = (frames / ratio).toInt()
        val out = ByteArray(outLen * 2)
        for (i in 0 until outLen) {
            val position = i * ratio
            val index = position.toInt()
            val frac = position - index
            val s0 = mono[min(index, frames - 1)].toInt()
            val s1 = mono[min(index + 1, frames - 1)].toInt()
            val v = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        private const val TAG = "GroqLiveSession"
        private const val BASE = "https://api.groq.com/openai/v1"
        private const val STT_MODEL = "whisper-large-v3-turbo"
        private const val OUTPUT_RATE = 24_000
        private const val OUT_CHUNK_BYTES = 4_800
        private const val MAX_HISTORY = 14

        private const val MIN_SPEECH_RMS = 700.0
        private const val START_MS = 100
        private const val MIN_SPEECH_MS = 300
        private const val END_SILENCE_MS = 900
        private const val MAX_UTTERANCE_MS = 15_000
        private const val PRE_ROLL_BYTES = 12_800

        private val JSON_TYPE = "application/json".toMediaType()
        private val WAV_TYPE = "audio/wav".toMediaType()
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
