package com.jarvis.assistant.home.adapters

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Device passwords, kept in EncryptedSharedPreferences. They are never written to the registry, logs or Supabase. */
object HomeSecrets {
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var memory: MutableMap<String, String> = HashMap()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            prefs = runCatching {
                val key = MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    context.applicationContext, "jarvis_home_secrets", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrNull()
        }
    }

    fun get(deviceId: String): String? = prefs?.getString(deviceId, null) ?: synchronized(this) { memory[deviceId] }

    fun put(deviceId: String, secret: String) {
        val p = prefs
        if (p != null) p.edit().putString(deviceId, secret).apply() else synchronized(this) { memory[deviceId] = secret }
    }

    fun remove(deviceId: String) {
        prefs?.edit()?.remove(deviceId)?.apply()
        synchronized(this) { memory.remove(deviceId) }
    }
}
