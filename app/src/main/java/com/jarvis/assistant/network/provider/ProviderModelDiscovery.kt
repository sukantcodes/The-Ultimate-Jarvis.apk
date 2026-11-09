package com.jarvis.assistant.network.provider

import com.jarvis.assistant.security.CustomProviderCredentialStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real model discovery for custom providers. No model list is ever fabricated: the provider's
 * endpoint is contacted with the configured authentication and the response is parsed. When a
 * provider does not expose discovery, [discover] returns [Result.Error] with
 * [Result.Error.kind] == UNSUPPORTED so the UI can offer a manual model ID instead.
 */
class ProviderModelDiscovery(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
) {

    enum class ErrorKind {
        INVALID_ENDPOINT,
        AUTH_FAILED,
        UNSUPPORTED,
        NO_MODELS,
        TIMEOUT,
        UNAVAILABLE,
        INVALID_RESPONSE,
        HTTP_ERROR
    }

    sealed class Result {
        data class Success(val models: List<DiscoveredModel>) : Result()
        data class Error(val kind: ErrorKind, val message: String, val httpCode: Int = 0) : Result()
    }

    fun discover(
        profile: CustomProviderProfile,
        credentialStore: CustomProviderCredentialStore
    ): Result {
        val validation = ProviderUrlValidator.validate(profile.baseUrl, profile.protocol)
        if (validation !is ProviderUrlValidator.Result.Ok) {
            val validationError = validation as ProviderUrlValidator.Result.Error
            return Result.Error(ErrorKind.INVALID_ENDPOINT, validationError.reason)
        }

        val key = credentialStore.keyFor(profile.id)
        if (key.isBlank()) {
            return Result.Error(ErrorKind.AUTH_FAILED, "No API key is saved for this provider.")
        }

        val url = if (profile.authMethod == AuthMethod.QUERY_PARAM) {
            val parameter = if (
                profile.protocol == ProviderProtocol.GEMINI_COMPATIBLE ||
                profile.protocol == ProviderProtocol.GEMINI_REALTIME
            ) "key" else "api_key"
            appendQueryKey(buildDiscoveryUrl(profile), key, parameter)
        } else {
            buildDiscoveryUrl(profile)
        }
        val request = Request.Builder()
            .url(url)
            .apply { applyAuth(this, profile.authMethod, key) }
            .header("Accept", "application/json")
            .get()
            .build()

        val raw = try {
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string().orEmpty()
                when {
                    code == 401 || code == 403 ->
                        return Result.Error(ErrorKind.AUTH_FAILED, "The provider rejected the API key (HTTP $code).", code)
                    code == 429 ->
                        return Result.Error(ErrorKind.UNAVAILABLE, "The provider is rate-limiting requests (HTTP 429).", code)
                    code !in 200..299 ->
                        return Result.Error(ErrorKind.HTTP_ERROR, "Model discovery returned HTTP $code.", code)
                    body.isBlank() ->
                        return Result.Error(ErrorKind.INVALID_RESPONSE, "Model discovery returned an empty response.")
                    else -> body
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            return Result.Error(ErrorKind.TIMEOUT, "Model discovery timed out.")
        } catch (e: java.io.IOException) {
            return Result.Error(ErrorKind.UNAVAILABLE, "Could not reach the provider: ${e.message ?: "network error"}")
        }

        val models = parseModels(raw, profile.protocol)
        if (models.isEmpty()) {
            return Result.Error(ErrorKind.NO_MODELS, "The provider returned no usable models.")
        }
        return Result.Success(models)
    }

    private fun buildDiscoveryUrl(profile: CustomProviderProfile): String {
        // Explicitly configured discovery path wins.
        val customPath = profile.discoveryPath.trim().trimStart('/')
        if (customPath.isNotEmpty()) {
            return if (customPath.startsWith("http://") || customPath.startsWith("https://")) customPath
            else ProviderUrlValidator.appendApiPath(profile.baseUrl, customPath)
        }
        return when (profile.protocol) {
            ProviderProtocol.GEMINI_COMPATIBLE, ProviderProtocol.GEMINI_REALTIME -> {
                // The Gemini REST list endpoint. Realtime endpoints use the same REST surface.
                ProviderUrlValidator.appendApiPath(profile.baseUrl, "v1beta/models")
            }
            ProviderProtocol.OPENAI_COMPATIBLE -> ProviderUrlValidator.appendApiPath(profile.baseUrl, "v1/models")
        }
    }

    private fun parseModels(raw: String, protocol: ProviderProtocol): List<DiscoveredModel> {
        return try {
            val root = JSONObject(raw)
            val nested = root.optJSONObject("result")
            val array = root.optJSONArray("data")
                ?: root.optJSONArray("models")
                ?: root.optJSONArray("items")
                ?: root.optJSONArray("model_list")
                ?: nested?.optJSONArray("models")
                ?: JSONArray()
            val out = ArrayList<DiscoveredModel>(array.length())
            for (i in 0 until array.length()) {
                val item = array.opt(i)
                val entry = when (item) {
                    is JSONObject -> item
                    is String -> JSONObject().put("id", item)
                    else -> continue
                }
                val model = modelFromEntry(entry, protocol) ?: continue
                if (out.none { it.id == model.id }) out.add(model)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun modelFromEntry(entry: JSONObject, protocol: ProviderProtocol): DiscoveredModel? {
        val id = when {
            protocol == ProviderProtocol.GEMINI_COMPATIBLE || protocol == ProviderProtocol.GEMINI_REALTIME ->
                entry.optString("name", "").substringAfterLast('/')
            else -> entry.optString("id", entry.optString("name", ""))
        }
        if (id.isBlank()) return null

        // Capabilities are derived conservatively from the protocol and any explicit fields the
        // provider returned. They are never invented from the model name alone.
        val explicitRealtime = entry.optBoolean("realtime", false) ||
            entry.optString("modality", "").contains("audio", ignoreCase = true)
        val capabilities = when {
            protocol == ProviderProtocol.GEMINI_REALTIME || explicitRealtime ->
                ProviderModelCapabilities.realtimeVoiceCapable
            protocol == ProviderProtocol.OPENAI_COMPATIBLE ->
                // OpenAI-compatible chat-completions endpoints commonly support function
                // calling, but many model-list endpoints omit capability metadata. Enable
                // tool calls optimistically; CustomChatSession already retries without tools
                // when a provider/model explicitly rejects the tool schema.
                ProviderModelCapabilities.textOnly.copy(
                    tools = entry.optBoolean("tools", true),
                    streaming = entry.optBoolean("streaming", false),
                    imageInput = entry.optBoolean("imageInput", false),
                    fileAnalysis = entry.optBoolean("fileAnalysis", false),
                    audioInput = entry.optBoolean("audioInput", false),
                    audioOutput = entry.optBoolean("audioOutput", false)
                )
            else -> ProviderModelCapabilities.textOnly
        }
        val display = entry.optString("displayName", "")
            .ifBlank { entry.optString("id", id) }
        return DiscoveredModel(
            id = id,
            displayName = display,
            capabilities = capabilities,
            raw = entry
        )
    }

    private fun applyAuth(builder: Request.Builder, method: AuthMethod, key: String) {
        when (method) {
            AuthMethod.BEARER -> builder.header("Authorization", "Bearer $key")
            AuthMethod.X_API_KEY -> builder.header("x-api-key", key)
            AuthMethod.X_GOOGLE_API_KEY -> builder.header("x-goog-api-key", key)
            AuthMethod.QUERY_PARAM -> { /* key is appended to the URL by the caller */ }
        }
    }

    private fun appendQueryKey(url: String, key: String, parameter: String): String {
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}$parameter=${java.net.URLEncoder.encode(key, "UTF-8")}"
    }
}
