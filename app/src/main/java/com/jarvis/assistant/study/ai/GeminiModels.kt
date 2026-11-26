package com.jarvis.assistant.study.ai

import com.google.gson.JsonParser
import com.jarvis.assistant.provider.HttpRequestSpec
import com.jarvis.assistant.provider.HttpTransport

/**
 * Which Gemini models the Study Board may use with the person's own key.
 *
 * Google retires and renames models, closes old ones to new users and overloads the newest at busy hours, so a fixed
 * model id is what made the board fail. Instead the board asks Google which models this key can really use (cached for
 * six hours), orders them newest stable Flash first, remembers the one that last worked and skips ones that answered
 * "not found". If the list cannot be fetched a short built-in list is used.
 */
internal object GeminiModels {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    val FALLBACK = listOf("gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.1-flash-lite")

    /** Models that cannot write a text answer (voice, speech, image, video, agents, embeddings). */
    private val NON_TEXT = listOf("live", "audio", "agent", "tts", "image", "imagen", "veo", "omni", "transcribe", "embedding", "robotics", "translate", "banana", "lyria", "deep-research", "learnlm", "aqa")

    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private var cached: List<String>? = null
    private var cachedAt = 0L
    private var cachedFor = 0
    private val dead = HashSet<String>()
    @Volatile private var lastGood: String? = null

    fun isTextModel(id: String): Boolean = id.isNotBlank() && NON_TEXT.none { id.contains(it, ignoreCase = true) }

    fun markGood(id: String) { lastGood = id }
    fun markDead(id: String) { synchronized(dead) { dead.add(id) } }

    /** Models to try, best first. Never empty. */
    suspend fun candidates(transport: HttpTransport, key: String): List<String> {
        val now = System.currentTimeMillis()
        val keyId = key.hashCode()
        var found = cached?.takeIf { cachedFor == keyId && now - cachedAt < TTL_MS }
        if (found == null) {
            found = discover(transport, key)
            if (!found.isNullOrEmpty()) { cached = found; cachedAt = now; cachedFor = keyId }
        }
        val base = found.orEmpty().ifEmpty { FALLBACK }
        val ordered = (listOfNotNull(lastGood) + base).distinct().filter { synchronized(dead) { it !in dead } }
        if (ordered.isNotEmpty()) return ordered
        synchronized(dead) { dead.clear() } // everything was marked missing: start over rather than give up
        return base
    }

    private suspend fun discover(transport: HttpTransport, key: String): List<String>? = try {
        val response = transport.execute(
            HttpRequestSpec("GET", "$BASE/models?pageSize=200", mapOf("Accept" to "application/json", "x-goog-api-key" to key), timeoutMs = 8_000L)
        )
        if (!response.isSuccessful) null else {
            val models = JsonParser.parseString(response.body).asJsonObject.getAsJsonArray("models")
            models.mapNotNull { el ->
                val o = el.asJsonObject
                val id = o.get("name")?.asString?.removePrefix("models/") ?: return@mapNotNull null
                val methods = o.getAsJsonArray("supportedGenerationMethods")?.map { it.asString }.orEmpty()
                id.takeIf { it.startsWith("gemini-") && "flash" in it && "generateContent" in methods && isTextModel(it) }
            }.distinct().sortedByDescending { rank(it) }
        }
    } catch (_: Throwable) { null }

    /** Newer is better; Lite and preview/experimental models come after full stable ones. */
    private fun rank(id: String): Double {
        val ver = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val lite = if ("lite" in id) 12 else 0
        val unstable = if (listOf("preview", "exp", "latest", "thinking").any { it in id }) 6 else 0
        return ver * 10 - lite - unstable
    }
}
