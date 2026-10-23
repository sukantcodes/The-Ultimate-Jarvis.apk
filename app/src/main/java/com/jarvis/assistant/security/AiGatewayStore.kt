package com.jarvis.assistant.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted credentials and routing preferences for the unified AI API gateway. */
class AiGatewayStore(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("jarvis_ai_gateway", Context.MODE_PRIVATE)
    private val secure: SharedPreferences? by lazy {
        try {
            val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app, "jarvis_ai_gateway_secure", master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("AiGatewayStore", "Encrypted storage unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    var provider: String
        get() = prefs.getString("provider", AUTO)?.takeIf { it in PROVIDERS } ?: AUTO
        set(value) { prefs.edit().putString("provider", value.takeIf { it in PROVIDERS } ?: AUTO).apply() }

    var autoFastest: Boolean
        get() = prefs.getBoolean("auto_fastest", true)
        set(value) { prefs.edit().putBoolean("auto_fastest", value).apply() }

    fun key(provider: String): String = secure?.getString("key_${provider.lowercase()}", "").orEmpty()
    fun hasKey(provider: String): Boolean = key(provider).isNotBlank()

    fun setKey(provider: String, key: String): Boolean {
        val s = secure ?: return false
        val k = "key_${provider.lowercase()}"
        val value = key.trim()
        val ok = if (value.isBlank()) s.edit().remove(k).commit() else s.edit().putString(k, value).commit()
        return ok
    }

    fun model(provider: String): String =
        prefs.getString("model_${provider.lowercase()}", defaultModel(provider)).orEmpty().ifBlank { defaultModel(provider) }

    fun setModel(provider: String, model: String) {
        prefs.edit().putString("model_${provider.lowercase()}", model.trim()).apply()
    }

    fun latency(provider: String): Long = prefs.getLong("latency_${provider.lowercase()}", Long.MAX_VALUE)
    fun setLatency(provider: String, millis: Long) { prefs.edit().putLong("latency_${provider.lowercase()}", millis).apply() }

    fun candidates(): List<String> {
        val configured = listOf(OPENAI, ANTHROPIC, DEEPSEEK).filter { hasKey(it) }
        if (configured.isEmpty()) return emptyList()
        if (provider != AUTO && provider in configured) return listOf(provider) + configured.filter { it != provider }
        return configured.sortedBy { latency(it) }
    }

    companion object {
        const val AUTO = "AUTO"
        const val OPENAI = "OPENAI"
        const val ANTHROPIC = "ANTHROPIC"
        const val DEEPSEEK = "DEEPSEEK"
        val PROVIDERS = listOf(AUTO, OPENAI, ANTHROPIC, DEEPSEEK)
        fun label(provider: String) = when (provider) {
            OPENAI -> "OpenAI"
            ANTHROPIC -> "Anthropic / Claude"
            DEEPSEEK -> "DeepSeek"
            else -> "Auto • Fastest available"
        }
        fun defaultModel(provider: String) = when (provider) {
            OPENAI -> "gpt-6-luna"
            ANTHROPIC -> "claude-haiku-latest"
            DEEPSEEK -> "deepseek-chat"
            else -> ""
        }
    }
}
