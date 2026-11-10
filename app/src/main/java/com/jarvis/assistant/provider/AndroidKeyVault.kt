package com.jarvis.assistant.provider

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Plain (non-secret) provider data: profile JSON and the active profile id. */
class SharedPrefsKeyValueStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("jarvis_providers", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/**
 * Endpoint API keys, encrypted with an AES-256 master key held in the Android Keystore. If the secure store cannot
 * be opened, [available] is false and nothing is saved: keys are never written as plain text.
 */
class AndroidKeyVault(context: Context) : KeyVault {
    private val prefs: SharedPreferences? = try {
        val app = context.applicationContext
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app, FILE, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    override val available: Boolean get() = prefs != null

    override fun load(profileId: String): ApiKeySecret? = try {
        prefs?.getString(PREFIX + profileId, null)?.takeIf { it.isNotBlank() }?.let { ApiKeySecret.of(it) }
    } catch (e: Exception) { null }

    override fun store(profileId: String, key: String): Boolean {
        val p = prefs ?: return false
        return try {
            if (key.isBlank()) p.edit().remove(PREFIX + profileId).commit()
            else p.edit().putString(PREFIX + profileId, key).commit()
        } catch (e: Exception) { false }
    }

    override fun delete(profileId: String) {
        try { prefs?.edit()?.remove(PREFIX + profileId)?.commit() } catch (_: Exception) {}
    }

    companion object {
        private const val FILE = "jarvis_provider_keys_secure"
        private const val PREFIX = "key_"
    }
}
