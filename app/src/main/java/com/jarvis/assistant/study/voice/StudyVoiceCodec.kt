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
        "english" to "en-IN", "hindi" to "hi-IN", "hinglish" to "en-IN", "bengali" to "bn-IN", "bangla" to "bn-IN", "marathi" to "mr-IN",
        "gujarati" to "gu-IN", "tamil" to "ta-IN", "telugu" to "te-IN", "kannada" to "kn-IN", "malayalam" to "ml-IN", "punjabi" to "pa-IN",
        "urdu" to "ur-IN", "odia" to "or-IN", "assamese" to "as-IN", "nepali" to "ne-NP", "spanish" to "es-ES", "french" to "fr-FR",
        "german" to "de-DE", "portuguese" to "pt-BR", "arabic" to "ar-SA", "indonesian" to "id-ID", "japanese" to "ja-JP", "korean" to "ko-KR",
        "chinese" to "zh-CN"
    )

    /**
     * The language tag for the phone voice. A named language wins; "auto" is read from the script of the text itself, so a
     * Bengali explanation is spoken as Bengali without the student choosing anything.
     */
    fun languageTag(language: String, text: String): String {
        LANGUAGE_TAGS[language.trim().lowercase()]?.let { return it }
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
                in '\u3040'..'\u30FF' -> return "ja-JP"
                in '\uAC00'..'\uD7AF' -> return "ko-KR"
                in '\u4E00'..'\u9FFF' -> return "zh-CN"
            }
        }
        return "en-IN"
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
}
