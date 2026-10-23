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
                val model = resolveModel(context, store, provider, key)
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

    /** Sends an image to a provider with native vision support; text-only providers are skipped. */
    suspend fun completeImage(
        context: Context,
        prompt: String,
        systemPrompt: String,
        imageBytes: ByteArray,
        mimeType: String
    ): Result = withContext(Dispatchers.IO) {
        val store = AiGatewayStore(context)
        val candidates = store.candidates().filter { it == AiGatewayStore.OPENAI || it == AiGatewayStore.ANTHROPIC }
        if (candidates.isEmpty()) throw IllegalStateException("No configured image-capable provider is available. Add an OpenAI or Claude API key in Settings → API Gateway.")
        val encoded = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
        var lastError: Exception? = null
        for (provider in candidates) {
            val key = store.key(provider)
            if (key.isBlank()) continue
            val model = store.model(provider)
            val start = System.currentTimeMillis()
            try {
                val text = when (provider) {
                    AiGatewayStore.OPENAI -> {
                        val content = JSONArray()
                            .put(JSONObject().put("type", "input_text").put("text", prompt))
                            .put(JSONObject().put("type", "input_image").put("image_url", "data:$mimeType;base64,$encoded"))
                        val payload = JSONObject()
                            .put("model", model)
                            .put("instructions", systemPrompt)
                            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                            .put("store", false)
                        val request = Request.Builder().url("https://api.openai.com/v1/responses")
                            .header("Authorization", "Bearer $key")
                            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                        http.newCall(request).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            if (!response.isSuccessful) throw IllegalStateException("OpenAI image analysis HTTP ${response.code}: ${errorMessage(body)}")
                            val json = JSONObject(body)
                            val direct = json.optString("output_text")
                            if (direct.isNotBlank()) direct.trim() else {
                                val output = json.optJSONArray("output") ?: JSONArray()
                                buildString {
                                    for (i in 0 until output.length()) {
                                        val items = output.optJSONObject(i)?.optJSONArray("content") ?: JSONArray()
                                        for (j in 0 until items.length()) {
                                            val item = items.optJSONObject(j)
                                            if (item?.optString("type") == "output_text") append(item.optString("text"))
                                        }
                                    }
                                }.trim()
                            }
                        }
                    }
                    else -> {
                        val image = JSONObject().put("type", "image").put("source",
                            JSONObject().put("type", "base64").put("media_type", mimeType).put("data", encoded))
                        val messageContent = JSONArray().put(image).put(JSONObject().put("type", "text").put("text", prompt))
                        val payload = JSONObject().put("model", model).put("max_tokens", 4096)
                            .put("system", systemPrompt)
                            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", messageContent)))
                        val request = Request.Builder().url("https://api.anthropic.com/v1/messages")
                            .header("x-api-key", key).header("anthropic-version", "2023-06-01")
                            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                        http.newCall(request).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            if (!response.isSuccessful) throw IllegalStateException("Claude image analysis HTTP ${response.code}: ${errorMessage(body)}")
                            val blocks = JSONObject(body).optJSONArray("content") ?: JSONArray()
                            buildString {
                                for (i in 0 until blocks.length()) {
                                    val block = blocks.optJSONObject(i)
                                    if (block?.optString("type") == "text") append(block.optString("text"))
                                }
                            }.trim()
                        }
                    }
                }
                if (text.isBlank()) throw IllegalStateException("The selected AI provider returned no image analysis.")
                val elapsed = System.currentTimeMillis() - start
                store.setLatency(provider, elapsed)
                return@withContext Result(text, provider, model, elapsed)
            } catch (e: Exception) { lastError = e }
        }
        throw IllegalStateException("Configured image-capable providers couldn't analyze this image. Check the model supports vision, API key, quota, and network. ${lastError?.message.orEmpty()}")
    }

    /** Sends a PDF using providers' native document-input formats. */
    suspend fun completePdf(
        context: Context,
        prompt: String,
        systemPrompt: String,
        pdfBytes: ByteArray,
        fileName: String
    ): Result = withContext(Dispatchers.IO) {
        val store = AiGatewayStore(context)
        val candidates = store.candidates().filter { it == AiGatewayStore.OPENAI || it == AiGatewayStore.ANTHROPIC }
        if (candidates.isEmpty()) throw IllegalStateException("No configured provider supports native PDF input. Add an OpenAI or Claude key, or configure Gemini.")
        val encoded = android.util.Base64.encodeToString(pdfBytes, android.util.Base64.NO_WRAP)
        var lastError: Exception? = null
        for (provider in candidates) {
            val key = store.key(provider)
            if (key.isBlank()) continue
            val model = store.model(provider)
            val start = System.currentTimeMillis()
            try {
                val text = if (provider == AiGatewayStore.OPENAI) {
                    val content = JSONArray().put(JSONObject().put("type", "input_file")
                        .put("filename", fileName).put("file_data", "data:application/pdf;base64,$encoded"))
                        .put(JSONObject().put("type", "input_text").put("text", prompt))
                    val payload = JSONObject().put("model", model).put("instructions", systemPrompt)
                        .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                        .put("store", false)
                    val request = Request.Builder().url("https://api.openai.com/v1/responses")
                        .header("Authorization", "Bearer $key")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    http.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IllegalStateException("OpenAI PDF analysis HTTP ${response.code}: ${errorMessage(body)}")
                        val json = JSONObject(body)
                        val direct = json.optString("output_text")
                        if (direct.isNotBlank()) direct.trim() else {
                            val output = json.optJSONArray("output") ?: JSONArray()
                            buildString {
                                for (i in 0 until output.length()) {
                                    val items = output.optJSONObject(i)?.optJSONArray("content") ?: JSONArray()
                                    for (j in 0 until items.length()) {
                                        val item = items.optJSONObject(j)
                                        if (item?.optString("type") == "output_text") append(item.optString("text"))
                                    }
                                }
                            }.trim()
                        }
                    }
                } else {
                    val doc = JSONObject().put("type", "document").put("source",
                        JSONObject().put("type", "base64").put("media_type", "application/pdf").put("data", encoded))
                    val content = JSONArray().put(doc).put(JSONObject().put("type", "text").put("text", prompt))
                    val payload = JSONObject().put("model", model).put("max_tokens", 4096)
                        .put("system", systemPrompt)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                    val request = Request.Builder().url("https://api.anthropic.com/v1/messages")
                        .header("x-api-key", key).header("anthropic-version", "2023-06-01")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    http.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw IllegalStateException("Claude PDF analysis HTTP ${response.code}: ${errorMessage(body)}")
                        val blocks = JSONObject(body).optJSONArray("content") ?: JSONArray()
                        buildString {
                            for (i in 0 until blocks.length()) {
                                val block = blocks.optJSONObject(i)
                                if (block?.optString("type") == "text") append(block.optString("text"))
                            }
                        }.trim()
                    }
                }
                if (text.isBlank()) throw IllegalStateException("The selected provider returned no PDF analysis.")
                val elapsed = System.currentTimeMillis() - start
                store.setLatency(provider, elapsed)
                return@withContext Result(text, provider, model, elapsed)
            } catch (e: Exception) { lastError = e }
        }
        throw IllegalStateException("Configured providers couldn't analyze this PDF. Check PDF support, model access, API quota, and network. ${lastError?.message.orEmpty()}")
    }

    private suspend fun resolveModel(context: Context, store: AiGatewayStore, provider: String, key: String): String {
        val configured = store.model(provider)
        return try {
            val available = listModels(context, provider, key)
            if (configured in available) configured else fastestSuggestedModel(provider, available)
        } catch (_: Exception) {
            configured
        }
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
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("OpenAI API HTTP ${response.code}: ${errorMessage(body)}")
            val json = JSONObject(body)
            val direct = json.optString("output_text")
            if (direct.isNotBlank()) direct.trim()
            else {
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
        return http.newCall(request).execute().use { response ->
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
