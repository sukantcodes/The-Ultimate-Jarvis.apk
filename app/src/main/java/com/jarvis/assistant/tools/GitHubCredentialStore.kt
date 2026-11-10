package com.jarvis.assistant.tools

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores the optional GitHub personal access token encrypted on-device. The token is never sent to the model. */
object GitHubCredentialStore {
    private const val PREFS = "github_private_credentials"
    private const val TOKEN = "personal_access_token"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS,
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(context: Context): String? = runCatching { prefs(context).getString(TOKEN, null)?.takeIf { it.isNotBlank() } }.getOrNull()
    fun save(context: Context, token: String) { require(token.isNotBlank()); prefs(context).edit().putString(TOKEN, token.trim()).apply() }
    fun clear(context: Context) { prefs(context).edit().remove(TOKEN).apply() }
    fun isConfigured(context: Context): Boolean = get(context) != null
}
