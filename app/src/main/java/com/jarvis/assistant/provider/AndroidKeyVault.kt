package com.jarvis.assistant.provider

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.GeneralSecurityException

/** Plain (non-secret) provider data: profile JSON and the active profile id. */
class SharedPrefsKeyValueStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("jarvis_providers", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/**
 * Endpoint API keys, encrypted with an AES-256 master key held in the Android Keystore.
 * Safely recovers from Keystore corruption / AEADBadTagException / GeneralSecurityException.
 */
class AndroidKeyVault(context: Context) : KeyVault {
    private val prefs: SharedPreferences? = initVault(context)

    private fun initVault(context: Context): SharedPreferences? {
        val app = context.applicationContext
        return try {
            createEncryptedPrefs(app)
        } catch (e: Exception) {
            Log.w("AndroidKeyVault", "Secure storage init error (${e.javaClass.simpleName}); recovering cleanly", e)
            try {
                // Delete corrupted preferences file and re-initialize
                val file = File(app.filesDir.parentFile, "shared_prefs/$FILE.xml")
                if (file.exists()) file.delete()
                createEncryptedPrefs(app)
            } catch (fallbackError: Throwable) {
                Log.e("AndroidKeyVault", "Failed to recreate EncryptedSharedPreferences after recovery", fallbackError)
                null
            }
        }
    }

    private fun createEncryptedPrefs(app: Context): SharedPreferences {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            app, FILE, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override val available: Boolean get() = prefs != null

    override fun load(profileId: String): ApiKeySecret? = try {
        prefs?.getString(PREFIX + profileId, null)?.takeIf { it.isNotBlank() }?.let { ApiKeySecret.of(it) }
    } catch (e: Exception) {
        Log.w("AndroidKeyVault", "Failed to decrypt key for $profileId: ${e.message}")
        null
    }

    override fun store(profileId: String, key: String): Boolean {
        val p = prefs ?: return false
        return try {
            if (key.isBlank()) p.edit().remove(PREFIX + profileId).commit()
            else p.edit().putString(PREFIX + profileId, key).commit()
        } catch (e: Exception) {
            Log.e("AndroidKeyVault", "Failed to encrypt and store key for $profileId", e)
            false
        }
    }

    override fun delete(profileId: String) {
        try { prefs?.edit()?.remove(PREFIX + profileId)?.commit() } catch (_: Exception) {}
    }

    companion object {
        private const val FILE = "jarvis_provider_keys_secure"
        private const val PREFIX = "key_"
    }
}
