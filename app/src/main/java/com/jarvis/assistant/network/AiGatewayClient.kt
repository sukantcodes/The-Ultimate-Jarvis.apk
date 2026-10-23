package com.jarvis.assistant.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.jarvis.assistant.security.AiGatewayStore
import java.util.concurrent.TimeUnit

/** Unified text API gateway. Uses provider-specific wire formats and falls back on transient failures. */
object AiGatewayClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(55, TimeUnit.SECONDS)
        .build()

    data class Result(val text: String, val provider: String, val model: String, val latencyMs: Long)

    suspend fun complete(context: Context, prompt: String, systemPrompt: String = "You are Jarvis, a helpful Android AI assistant. Be concise, accurate, and clear."): Result =
        withContext(Dispatchers.IO) {
            val store = AiGatewayStore(context)
            val candidates = store.candidates()
            if (candidates.isEmpty()) throw IllegalStateException("No external AI provider key is configured. Add an OpenAI, Claude, or DeepSeek key in Settings → API Gateway.")
            var lastError: Exception? = null
            for (provider in candidates) {
                val key = store.key(provider)
                if (key.isBlank()) continue
                val model = store.model(provider)
                val start = System.currentTimeMillis()
                try {
                    val text = when (provider) {
                        AiGatewayStore.OPENAI -> openAi(key, model, prompt, systemPrompt)
                        AiGatewayStore.ANTHROPIC -> anthropic(key, model, prompt, systemPrompt)
                        AiGatewayStore.DEEPSEEK -> deepSeek(key, model, prompt, systemPrompt)
                        else -> continue
                    }
                    val elapsed = System.currentTimeMillis() - start
                    store.setLatency(provider, elapsed)
                    if (text.isBlank()) throw IllegalStateException("$provider returned an empty response.")
                    return@withContext Result(text, provider, model, elapsed)
                } catch (e: Exception) {
                    lastError = e
                    // Try the next configured provider. Never log API keys or request bodies.
                }
            }
            throw IllegalStateException("All configured AI providers failed. Check API keys, model access, quota, and network. ${lastError?.message.orEmpty()}")
        }

    suspend fun listModels(context: Context, provider: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val request = when (provider) {
            AiGatewayStore.OPENAI -> Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $apiKey").get().build()
            AiGatewayStore.ANTHROPIC -> Request.Builder().url("https://api.anthropic.com/v1/models?limit=1000").header("x-api-key", apiKey).header("anthropic-version", "2026-10-21").get().build()
            AiGatewayStore.DEEPSEEK -> Request.Builder().url("https://api.deepseek.com/models").header("Authorization", "Bearer $apiKey").get().build()
            else -> return@withContext emptyList()
        }
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Model list request failed: HTTP ${response.code}")
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
            ids.sortedWith(compareBy<String>({ speedScore(it) }, { it })).take(250)
        }
    }

    fun fastestSuggestedModel(provider: String, ids: List<String>): String {
        val preferred = when (provider) {
            AiGatewayStore.OPENAI -> listOf("luna", "mini", "nano", "flash", "fast")
            AiGatewayStore.ANTHROPIC -> listOf("haiku", "sonnet")
            AiGatewayStore.DEEPSEEK -> listOf("flash", "chat", "fast")
            else -> emptyList()
        }
        return ids.sortedWith(compareBy<String>({ speedScore(it, preferred) }, { it })).firstOrNull()
            ?: AiGatewayStore.defaultModel(provider)
    }

    private fun speedScore(id: String, preference: List<String> = listOf("haiku", "luna", "mini", "nano", "flash", "fast", "chat")): Int {
        val lower = id.lowercase()
        var score = 100
        preference.forEachIndexed { i, term -> if (lower.contains(term)) score = minOf(score, i * 10) }
        if (lower.contains("preview")) score += 3
        if (lower.contains("audio") || lower.contains("embedding") || lower.contains("image")) score += 100
        return score
    }

    private fun openAi(key: String, model: String, prompt: String, system: String): String {
        val payload = JSONObject()
            .put("model", model)
            .put("instructions", system)
            .put("input", prompt)
            .put("store", false)
        val request = Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("OpenAI API HTTP ${response.code}: ${errorMessage(body)}")
            val json = JSONObject(body)
            val direct = json.optString("output_text")
            if (direct.isNotBlank()) return direct.trim()
            val output = json.optJSONArray("output") ?: JSONArray()
            buildString {
                for (i in 0 until output.length()) {
                    val content = output.optJSONObject(i)?.optJSONArray("content") ?: JSONArray()
                    for (j in 0 until content.length()) {
                        val item = content.optJSONObject(j)
                        if (item?.optString("type") == "output_text") append(item.optString("text"))
                    }
                }
            }.trim()
        }
    }

    private fun deepSeek(key: String, model: String, prompt: String, system: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val payload = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        return post("https://api.deepseek.com/chat/completions", key, payload, "openai")
    }

    private fun anthropic(key: String, model: String, prompt: String, system: String): String {
        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val request = Request.Builder().url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key).header("anthropic-version", "2026-10-21")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Claude API HTTP ${response.code}: ${errorMessage(body)}")
            val content = JSONObject(body).optJSONArray("content") ?: JSONArray()
            buildString { for (i in 0 until content.length()) append(content.optJSONObject(i)?.optString("text").orEmpty()) }.trim()
        }
    }

    private fun post(url: String, key: String, payload: JSONObject, format: String): String {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("AI API HTTP ${response.code}: ${errorMessage(body)}")
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: JSONArray()
            return choices.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
        }
    }

    private fun errorMessage(body: String): String = try {
        val json = JSONObject(body)
        json.optJSONObject("error")?.optString("message")?.take(240)
            ?: json.optString("message").take(240).ifBlank { "Request rejected" }
    } catch (_: Exception) { "Request rejected" }
}
