package com.jarvis.assistant.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Which AI engine runs the assistant (Gemini Live or Groq) and the Groq settings.
 * The Groq API key is stored in AES-256 encrypted preferences (Android Keystore) and is never logged.
 */
class AiEngineStore(context: Context) {
    private val appContext = context.applicationContext
    private val plain: SharedPreferences = appContext.getSharedPreferences("jarvis_ai_engine", Context.MODE_PRIVATE)

    private val secure: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "jarvis_ai_engine_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("AiEngineStore", "Encrypted storage unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** True when Groq should run the assistant instead of Gemini Live. */
    var useGroq: Boolean
        get() = plain.getBoolean("use_groq", false)
        set(value) {
            plain.edit().putBoolean("use_groq", value).apply()
        }

    var groqChatModel: String
        get() = plain.getString("groq_chat_model", DEFAULT_CHAT_MODEL)?.ifBlank { DEFAULT_CHAT_MODEL } ?: DEFAULT_CHAT_MODEL
        set(value) {
            plain.edit().putString("groq_chat_model", value.trim()).apply()
        }

    fun groqKey(): String = secure?.getString("groq_key", "").orEmpty()

    fun hasGroqKey(): Boolean = groqKey().isNotBlank()

    /** Returns false when the key could not be stored securely (nothing is saved in plain text). */
    fun setGroqKey(key: String): Boolean {
        val store = secure ?: return false
        store.edit().putString("groq_key", key.trim()).apply()
        return true
    }

    companion object {
        const val DEFAULT_CHAT_MODEL = "llama-3.3-70b-versatile"
    }
}
