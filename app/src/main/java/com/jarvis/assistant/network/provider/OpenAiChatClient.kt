package com.jarvis.assistant.network.provider

import com.jarvis.assistant.security.CustomProviderCredentialStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for OpenAI-compatible custom providers. Supports:
 *  - chat completions (non-streaming, tools) at <base>/v1/chat/completions
 *  - speech-to-text at <base>/v1/audio/transcriptions (multipart, like Groq/OpenAI)
 *
 * The endpoint base URL and the API key come from the active custom provider profile
 * (profile in plain preferences, key in encrypted storage). Keys are never logged.
 */
class OpenAiChatClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {

    class ApiError(val code: Int, val detail: String, val retryAfter: String?) : Exception(detail)

    data class ChatResult(val content: String, val raw: JSONObject)

    /** The full OpenAI-style path for a given provider base URL. */
    fun chatCompletionsUrl(baseUrl: String): String =
        trimBase(baseUrl) + "/v1/chat/completions"

    fun transcriptionsUrl(baseUrl: String): String =
        trimBase(baseUrl) + "/v1/audio/transcriptions"

    fun modelsUrl(baseUrl: String): String =
        trimBase(baseUrl) + "/v1/models"

    /** Common URL for discovery, shared with [ProviderModelDiscovery]. */
    private fun trimBase(baseUrl: String): String =
        ProviderUrlValidator.httpBase(baseUrl).trimEnd('/')

    /**
     * Runs one chat-completions call against the provider. `messages` are OpenAI-style
     * role/content/tool_calls/tool_call_id objects. Returns the assistant message content,
     * or throws [ApiError] when the provider replies with an error.
     */
    suspend fun chat(
        profile: CustomProviderProfile,
        credentialStore: CustomProviderCredentialStore,
        messages: List<JSONObject>,
        tools: JSONArray = JSONArray(),
        maxTokens: Int = 700,
        temperature: Double = 0.5
    ): ChatResult {
        val key = credentialStore.keyFor(profile.id)
        require(key.isNotBlank()) { "No API key saved for provider ${profile.name}" }

        val body = JSONObject().apply {
            put("model", profile.selectedModelId.ifBlank { profile.models.firstOrNull()?.id ?: "" })
            put("messages", JSONArray().apply { messages.forEach { put(it) } })
            if (tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            put("max_tokens", maxTokens)
            put("temperature", temperature)
        }

        val request = Request.Builder()
            .url(chatCompletionsUrl(profile.baseUrl))
            .apply {
                when (profile.authMethod) {
                    AuthMethod.BEARER -> header("Authorization", "Bearer $key")
                    AuthMethod.X_API_KEY -> header("x-api-key", key)
                    AuthMethod.X_GOOGLE_API_KEY -> header("x-goog-api-key", key)
                    AuthMethod.QUERY_PARAM -> {}
                }
            }
            .header("Accept", "application/json")
            .header("User-Agent", "JarvisAndroid/1.0")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val (code, raw, retryAfter) = try {
            httpClient.newCall(request).execute().use { response ->
                Triple(
                    response.code,
                    response.body?.string().orEmpty(),
                    response.header("retry-after")
                )
            }
        } catch (e: IOException) {
            throw ApiError(0, "Could not reach the provider: ${e.message ?: "network error"}", null)
        }

        if (code !in 200..299) {
            val detail = try {
                JSONObject(raw).optJSONObject("error")?.optString("message", "")
                    ?: raw.take(200)
            } catch (e: Exception) {
                raw.take(200)
            }
            throw ApiError(code, detail.ifBlank { "HTTP $code" }, retryAfter)
        }

        return try {
            val message = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
            val content = message.optString("content", "")
            ChatResult(content, message)
        } catch (e: Exception) {
            throw ApiError(500, "The provider returned an unreadable chat response.", null)
        }
    }

    /**
     * Transcribes a 16 kHz PCM utterance through the provider's /v1/audio/transcriptions
     * endpoint. Returns the transcript, or null when transcription failed.
     */
    suspend fun transcribe(
        profile: CustomProviderProfile,
        credentialStore: CustomProviderCredentialStore,
        pcm16k: ByteArray
    ): String? {
        val key = credentialStore.keyFor(profile.id)
        if (key.isBlank()) return null
        val wav = pcmToWav(pcm16k, 16_000)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", DEFAULT_STT_MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV_MEDIA))
            .build()

        val request = Request.Builder()
            .url(transcriptionsUrl(profile.baseUrl))
            .apply {
                when (profile.authMethod) {
                    AuthMethod.BEARER -> header("Authorization", "Bearer $key")
                    AuthMethod.X_API_KEY -> header("x-api-key", key)
                    AuthMethod.X_GOOGLE_API_KEY -> header("x-goog-api-key", key)
                    AuthMethod.QUERY_PARAM -> {}
                }
            }
            .header("User-Agent", "JarvisAndroid/1.0")
            .post(body)
            .build()

        val (code, raw) = try {
            httpClient.newCall(request).execute().use { response ->
                Pair(response.code, response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            return null
        }
        if (code !in 200..299) return null
        return try {
            JSONObject(raw).optString("text", "").trim().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val DEFAULT_STT_MODEL = "whisper-large-v3-turbo"
        private val JSON_MEDIA = "application/json".toMediaType()
        private val WAV_MEDIA = "audio/wav".toMediaType()

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
    }
}
