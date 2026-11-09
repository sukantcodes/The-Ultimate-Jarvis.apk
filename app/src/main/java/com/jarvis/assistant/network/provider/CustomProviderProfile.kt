package com.jarvis.assistant.network.provider

import org.json.JSONArray
import org.json.JSONObject

/**
 * Protocol a custom provider endpoint speaks. Only HTTPS endpoints are accepted; the realtime
 * protocol requires a secure WebSocket endpoint (wss).
 */
enum class ProviderProtocol(val label: String, val requiredScheme: String) {
    OPENAI_COMPATIBLE("OpenAI-compatible", "https"),
    GEMINI_COMPATIBLE("Gemini-compatible", "https"),
    GEMINI_REALTIME("Gemini Live (WebSocket)", "wss");

    companion object {
        fun fromId(id: String?): ProviderProtocol =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: OPENAI_COMPATIBLE
    }
}

/** How the API key is presented to the provider. */
enum class AuthMethod(val label: String) {
    BEARER("Authorization: Bearer"),
    X_API_KEY("x-api-key"),
    X_GOOGLE_API_KEY("x-goog-api-key"),
    QUERY_PARAM("Query parameter (api_key)");

    companion object {
        fun fromId(id: String?): AuthMethod =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: BEARER
    }
}

/** A single model returned by discovery, plus how JARVIS will use it. */
data class DiscoveredModel(
    val id: String,
    val displayName: String,
    val capabilities: ProviderModelCapabilities,
    val raw: JSONObject? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("capabilities", capabilities.toJson())

    companion object {
        fun fromJson(json: JSONObject): DiscoveredModel =
            DiscoveredModel(
                id = json.optString("id", ""),
                displayName = json.optString("displayName", json.optString("id", "")),
                capabilities = ProviderModelCapabilities.fromJson(
                    json.optJSONObject("capabilities") ?: JSONObject()
                )
            )
    }
}

/**
 * A saved custom provider profile. API keys are stored separately in encrypted preferences
 * (see [com.jarvis.assistant.security.CustomProviderCredentialStore]); this object never
 * holds a key value.
 */
data class CustomProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: ProviderProtocol,
    val authMethod: AuthMethod,
    /** Optional extra path used for model discovery when the default is unsupported. */
    val discoveryPath: String = "",
    val models: List<DiscoveredModel> = emptyList(),
    val selectedModelId: String = "",
    /** Free-form extra provider parameters (never secrets). */
    val providerSettings: Map<String, String> = emptyMap(),
    val lastRefreshStatus: String = ""
) {
    val hasKey: Boolean get() = false // presence is answered by the credential store

    fun selectedModel(): DiscoveredModel? = models.firstOrNull { it.id == selectedModelId }

    fun toJson(): JSONObject {
        val modelsJson = JSONArray()
        models.forEach { modelsJson.put(it.toJson()) }
        val settingsJson = JSONObject()
        providerSettings.forEach { (k, v) -> settingsJson.put(k, v) }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("baseUrl", baseUrl)
            .put("protocol", protocol.name)
            .put("authMethod", authMethod.name)
            .put("discoveryPath", discoveryPath)
            .put("models", modelsJson)
            .put("selectedModelId", selectedModelId)
            .put("providerSettings", settingsJson)
            .put("lastRefreshStatus", lastRefreshStatus)
    }

    fun copyWithModel(id: String): CustomProviderProfile = copy(selectedModelId = id)

    companion object {
        fun fromJson(json: JSONObject): CustomProviderProfile {
            val modelsJson = json.optJSONArray("models") ?: JSONArray()
            val models = ArrayList<DiscoveredModel>(modelsJson.length())
            for (i in 0 until modelsJson.length()) {
                val modelJson = modelsJson.optJSONObject(i) ?: continue
                models.add(DiscoveredModel.fromJson(modelJson))
            }
            val settingsJson = json.optJSONObject("providerSettings") ?: JSONObject()
            val settings = LinkedHashMap<String, String>()
            val keys = settingsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                settings[key] = settingsJson.optString(key, "")
            }
            return CustomProviderProfile(
                id = json.optString("id", ""),
                name = json.optString("name", "Custom Provider"),
                baseUrl = json.optString("baseUrl", ""),
                protocol = ProviderProtocol.fromId(json.optString("protocol", "")),
                authMethod = AuthMethod.fromId(json.optString("authMethod", "")),
                discoveryPath = json.optString("discoveryPath", ""),
                models = models,
                selectedModelId = json.optString("selectedModelId", ""),
                providerSettings = settings,
                lastRefreshStatus = json.optString("lastRefreshStatus", "")
            )
        }
    }
}
