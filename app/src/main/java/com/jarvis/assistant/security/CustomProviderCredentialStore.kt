package com.jarvis.assistant.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores custom provider API keys encrypted at rest (Android Keystore backed, AES-256 GCM).
 * Keys are never stored as plain text, never logged, and never exposed through the UI.
 *
 * A key exists only when the user saved it in the custom-provider flow; an existing saved key is
 * never displayed again (the UI only shows "•••• saved").
 */
class CustomProviderCredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val secure: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("ProviderCredStore", "Encrypted storage unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    fun keyFor(providerId: String): String = secure?.getString(providerId, "") ?: ""

    fun hasKeyFor(providerId: String): Boolean = keyFor(providerId).isNotBlank()

    /** Returns false when the key could not be stored securely (nothing is saved in plain text). */
    fun saveKey(providerId: String, key: String): Boolean {
        val store = secure ?: return false
        val clean = key.trim()
        store.edit().let {
            if (clean.isEmpty()) it.remove(providerId) else it.putString(providerId, clean)
            return it.commit()
        }
    }

    fun clearKey(providerId: String) {
        secure?.edit()?.remove(providerId)?.apply()
    }

    companion object {
        private const val PREF_FILE = "jarvis_custom_provider_keys"
    }
}
