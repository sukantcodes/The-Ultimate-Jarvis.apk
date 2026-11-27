package com.jarvis.assistant.study.voice

import java.io.ByteArrayOutputStream

/** Pure helpers for the cloud voice (no Android classes), so they can be unit tested on a plain JVM. */
object StudyVoiceCodec {

    /** True when the reply is raw 16-bit PCM, which MediaPlayer cannot play until it has a WAV header. */
    fun isRawPcm(mime: String): Boolean {
        val m = mime.lowercase()
        return m.contains("l16") || m.contains("pcm")
    }

    /** The sample rate named in a mime type such as "audio/L16;codec=pcm;rate=24000". Gemini voices use 24 kHz. */
    fun sampleRate(mime: String): Int {
        val r = Regex("rate=(\\d{4,6})").find(mime.lowercase())?.groupValues?.get(1)?.toIntOrNull()
        return if (r != null && r in 8000..96000) r else 24000
    }

    /** Wraps mono 16-bit little-endian PCM in a standard 44-byte WAV header. */
    fun pcm16ToWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val byteRate = sampleRate * channels * 2
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun int32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun int16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); int32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int32(16); int16(1); int16(channels); int32(sampleRate); int32(byteRate); int16(channels * 2); int16(16)
        out.write("data".toByteArray()); int32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    private val LANGUAGE_TAGS = mapOf(
        "english" to "en-IN", "hindi" to "hi-IN", "hinglish" to "en-IN", "bhojpuri" to "hi-IN", "maithili" to "hi-IN", "sanskrit" to "hi-IN",
        "bengali" to "bn-IN", "bangla" to "bn-IN", "marathi" to "mr-IN", "gujarati" to "gu-IN", "tamil" to "ta-IN", "telugu" to "te-IN",
        "kannada" to "kn-IN", "malayalam" to "ml-IN", "punjabi" to "pa-IN", "urdu" to "ur-IN", "odia" to "or-IN", "assamese" to "as-IN",
        "nepali" to "ne-NP", "spanish" to "es-ES", "french" to "fr-FR", "german" to "de-DE", "portuguese" to "pt-BR", "arabic" to "ar-SA",
        "indonesian" to "id-ID", "japanese" to "ja-JP", "korean" to "ko-KR", "chinese" to "zh-CN", "russian" to "ru-RU", "italian" to "it-IT",
        "turkish" to "tr-TR", "vietnamese" to "vi-VN", "thai" to "th-TH"
    )

    private val TAG_TO_DISPLAY = mapOf(
        "en-IN" to "English", "hi-IN" to "Hindi", "bn-IN" to "Bengali", "mr-IN" to "Marathi", "gu-IN" to "Gujarati",
        "ta-IN" to "Tamil", "te-IN" to "Telugu", "kn-IN" to "Kannada", "ml-IN" to "Malayalam", "pa-IN" to "Punjabi",
        "ur-IN" to "Urdu", "or-IN" to "Odia", "as-IN" to "Assamese", "ne-NP" to "Nepali", "es-ES" to "Spanish",
        "fr-FR" to "French", "de-DE" to "German", "pt-BR" to "Portuguese", "ar-SA" to "Arabic", "id-ID" to "Indonesian",
        "ja-JP" to "Japanese", "ko-KR" to "Korean", "zh-CN" to "Chinese", "ru-RU" to "Russian", "it-IT" to "Italian",
        "tr-TR" to "Turkish", "vi-VN" to "Vietnamese", "th-TH" to "Thai"
    )

    /**
     * The language tag for the phone voice. A named language wins; "auto" is read from the script of the text itself, so a
     * Bengali explanation is spoken as Bengali without the student choosing anything.
     */
    fun languageTag(language: String, text: String): String {
        val norm = language.trim().lowercase()
        LANGUAGE_TAGS[norm]?.let { return it }
        for (ch in text) {
            when (ch) {
                in '\u0900'..'\u097F' -> return "hi-IN"
                in '\u0980'..'\u09FF' -> return "bn-IN"
                in '\u0A00'..'\u0A7F' -> return "pa-IN"
                in '\u0A80'..'\u0AFF' -> return "gu-IN"
                in '\u0B00'..'\u0B7F' -> return "or-IN"
                in '\u0B80'..'\u0BFF' -> return "ta-IN"
                in '\u0C00'..'\u0C7F' -> return "te-IN"
                in '\u0C80'..'\u0CFF' -> return "kn-IN"
                in '\u0D00'..'\u0D7F' -> return "ml-IN"
                in '\u0600'..'\u06FF' -> return "ar-SA"
                in '\u0400'..'\u04FF' -> return "ru-RU"
                in '\u0E00'..'\u0E7F' -> return "th-TH"
                in '\u3040'..'\u30FF' -> return "ja-JP"
                in '\uAC00'..'\uD7AF' -> return "ko-KR"
                in '\u4E00'..'\u9FFF' -> return "zh-CN"
            }
        }
        return "en-IN"
    }

    /** Human-readable spoken language name for prompting multilingual Live/TTS neural voices accurately. */
    fun resolveLanguageName(language: String, text: String): String {
        val trimmed = language.trim()
        if (trimmed.isNotEmpty() && !trimmed.equals("auto", true)) return trimmed
        return TAG_TO_DISPLAY[languageTag("auto", text)] ?: "English"
    }

    /** One entry of Google's model list. */
    data class ModelEntry(val name: String, val methods: List<String>)

    /**
     * Chooses the speech model from the list Google returns for this key, so a retired or renamed model id never has to be
     * guessed. Only models that can generate content and are named as speech (TTS) models qualify; the newest flash one wins.
     * Returns null when the key has no speech model, in which case the caller uses the phone's voice.
     */
    fun pickTtsModel(models: List<ModelEntry>): String? {
        val candidates = models.filter { m ->
            val n = m.name.lowercase()
            "tts" in n && m.methods.any { it.equals("generateContent", true) } && "live" !in n
        }.map { it.name.removePrefix("models/") }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareByDescending<String> { "flash" in it.lowercase() }
                .thenByDescending { version(it) }
                .thenBy { "preview" in it.lowercase() }
                .thenBy { it }
        ).first()
    }

    /** The first dotted number in a model id ("gemini-3.8-flash-tts" gives 3.8), as a comparable value. */
    internal fun version(id: String): Double {
        val m = Regex("(\\d+)(?:\\.(\\d+))?").find(id) ?: return 0.0
        return (m.groupValues[1].toIntOrNull() ?: 0) + (m.groupValues[2].toIntOrNull() ?: 0) / 100.0
    }

    /** Matches GeminiLiveWebSocket model normalization so Study Board uses the exact same live voice backend as Jarvis. */
    fun normalizeLiveModelId(configuredModel: String): String = when (configuredModel.trim()) {
        "models/gemini-3.8-live" -> "models/gemini-3.8-live"
        "models/gemini-3.1-flash-agent" -> "models/gemini-3.1-flash-live-preview"
        else -> {
            val trimmed = configuredModel.trim()
            if (trimmed.isNotEmpty() && !trimmed.contains("2.5")) trimmed
            else "models/gemini-3.1-flash-live-preview"
        }
    }

    /** Ensures clean terminal punctuation so consecutive spoken steps flow smoothly without run-on cadence. */
    fun ensureSentenceEnding(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        return when (t.last()) {
            '.', '!', '?', '।', '۔', '。' -> t
            else -> "$t."
        }
    }

    data class LessonSegment(
        val blockIndex: Int,
        val text: String,
        val startFrac: Float,
        val endFrac: Float,
        val anchorWords: List<String>
    )

    data class ContinuousPlan(
        val script: String,
        val segments: List<LessonSegment>,
        val estimatedDurationMs: Long
    )

    /**
     * Combines loaded block speeches into one continuous script with proportional timing fractions and anchor words,
     * allowing unbroken voice narration while advancing whiteboard blocks in smooth lockstep.
     */
    fun buildContinuousPlan(entries: List<Pair<Int, String>>): ContinuousPlan {
        val valid = entries.mapNotNull { (idx, raw) ->
            val clean = ensureSentenceEnding(raw)
            if (clean.isEmpty()) null else idx to clean
        }
        if (valid.isEmpty()) return ContinuousPlan("", emptyList(), 0L)

        val weights = valid.map { (_, t) -> t.length.coerceAtLeast(18).toFloat() }
        val totalWeight = weights.sum().coerceAtLeast(1f)
        val segments = ArrayList<LessonSegment>(valid.size)
        var acc = 0f
        valid.forEachIndexed { pos, (idx, text) ->
            val startFrac = acc / totalWeight
            acc += weights[pos]
            val endFrac = if (pos == valid.lastIndex) 1f else acc / totalWeight
            val words = text.lowercase()
                .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 3 }
                .take(4)
            segments.add(LessonSegment(idx, text, startFrac, endFrac, words))
        }
        val script = valid.joinToString("\n") { it.second }
        val estMs = (script.length * 80L).coerceIn(2_000L, 240_000L)
        return ContinuousPlan(script, segments, estMs)
    }

    /** Computes which target blockIndex corresponds to normalized spoken progress [0f..1f]. */
    fun segmentBlockForProgress(segments: List<LessonSegment>, progressFrac: Float): Int? {
        if (segments.isEmpty()) return null
        val p = progressFrac.coerceIn(0f, 1f)
        return segments.firstOrNull { p < it.endFrac }?.blockIndex ?: segments.last().blockIndex
    }

    /** Returns `(blockIndex, localFraction 0f..1f)` for normalized plan progress [0f..1f]. */
    fun segmentStateForProgress(segments: List<LessonSegment>, progressFrac: Float): Pair<Int, Float>? {
        if (segments.isEmpty()) return null
        val p = progressFrac.coerceIn(0f, 1f)
        val seg = segments.firstOrNull { p < it.endFrac } ?: segments.last()
        val span = (seg.endFrac - seg.startFrac).coerceAtLeast(0.005f)
        val local = ((p - seg.startFrac) / span).coerceIn(0f, 1f)
        return seg.blockIndex to local
    }

    /**
     * Converts actual played PCM [playedFrames] into normalized plan progress [0f..1f] using recorded
     * server-side segment transition frame offsets [segmentStartFrames] when available, giving near sample-exact
     * alignment between whiteboard drawing/underlining and spoken audio.
     */
    fun progressFromSegmentFrames(
        segments: List<LessonSegment>,
        segmentStartFrames: LongArray,
        playedFrames: Long,
        totalFrames: Long
    ): Float {
        if (segments.isEmpty() || totalFrames <= 0L) return 0f
        val n = segments.size
        val effBounds = LongArray(n + 1)
        effBounds[0] = 0L
        effBounds[n] = totalFrames

        // Seed monotonic resolved anchors from server frame boundaries
        val known = BooleanArray(n + 1)
        known[0] = true
        known[n] = true
        var lastKnownFrame = 0L
        for (i in 1 until n) {
            val raw = if (i < segmentStartFrames.size) segmentStartFrames[i] else -1L
            if (raw >= 0L) {
                val clamped = raw.coerceIn(lastKnownFrame, totalFrames)
                effBounds[i] = clamped
                known[i] = true
                lastKnownFrame = clamped
            }
        }

        // Fill any unresolved intermediate bounds using segment weight proportions between adjacent anchors
        var leftIdx = 0
        while (leftIdx < n) {
            var rightIdx = leftIdx + 1
            while (rightIdx < n && !known[rightIdx]) rightIdx++
            if (rightIdx - leftIdx > 1) {
                val leftFrame = effBounds[leftIdx]
                val rightFrame = maxOf(leftFrame, effBounds[rightIdx])
                val leftFrac = segments[leftIdx].startFrac
                val rightFrac = if (rightIdx >= n) 1f else segments[rightIdx].startFrac
                val fracSpan = (rightFrac - leftFrac).coerceAtLeast(0.001f)
                for (k in (leftIdx + 1) until rightIdx) {
                    val rel = ((segments[k].startFrac - leftFrac) / fracSpan).coerceIn(0f, 1f)
                    effBounds[k] = (leftFrame + ((rightFrame - leftFrame) * rel).toLong())
                        .coerceIn(effBounds[k - 1], rightFrame)
                }
            }
            leftIdx = rightIdx
        }

        val safePlayed = playedFrames.coerceIn(0L, totalFrames)
        var activeSeg = n - 1
        for (i in 0 until n) {
            if (safePlayed < effBounds[i + 1] || i == n - 1) {
                activeSeg = i
                break
            }
        }
        val curStart = effBounds[activeSeg]
        val nextEnd = maxOf(curStart + 1L, effBounds[activeSeg + 1])
        val inSegFrac = ((safePlayed - curStart).toFloat() / (nextEnd - curStart).toFloat()).coerceIn(0f, 1f)
        val seg = segments[activeSeg]
        return (seg.startFrac + inSegFrac * (seg.endFrac - seg.startFrac)).coerceIn(0f, 0.995f)
    }
}
