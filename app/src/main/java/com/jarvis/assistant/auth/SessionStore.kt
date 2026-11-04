package com.jarvis.assistant.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the Supabase session in EncryptedSharedPreferences (AES-256, Android Keystore master key).
 * If secure storage cannot be created the session is kept in memory only: tokens are never written
 * to plain preferences. The guest-choice flag holds no secret and lives in ordinary preferences.
 */
internal class SessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val plain: SharedPreferences = appContext.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
    private val secure: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appContext, SECURE_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Secure auth storage unavailable; sessions will not persist", e)
        null
    }

    private var memorySession: AuthSession? = null

    fun loadSession(): AuthSession? {
        val prefs = secure ?: return memorySession
        val access = prefs.getString(K_ACCESS, null) ?: return null
        val refresh = prefs.getString(K_REFRESH, null) ?: return null
        return AuthSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSec = prefs.getLong(K_EXPIRES, 0L),
            userId = prefs.getString(K_USER_ID, "").orEmpty(),
            email = prefs.getString(K_EMAIL, "").orEmpty()
        )
    }

    fun saveSession(session: AuthSession) {
        memorySession = session
        secure?.edit()
            ?.putString(K_ACCESS, session.accessToken)
            ?.putString(K_REFRESH, session.refreshToken)
            ?.putLong(K_EXPIRES, session.expiresAtEpochSec)
            ?.putString(K_USER_ID, session.userId)
            ?.putString(K_EMAIL, session.email)
            ?.apply()
    }

    fun clearSession() {
        memorySession = null
        secure?.edit()
            ?.remove(K_ACCESS)?.remove(K_REFRESH)?.remove(K_EXPIRES)?.remove(K_USER_ID)?.remove(K_EMAIL)
            ?.apply()
    }

    /** PKCE verifier for an in-flight Google / reset / sign-up flow. Cleared as soon as it is consumed. */
    fun savePendingVerifier(flow: AuthFlow, verifier: String) {
        val prefs = secure
        if (prefs != null) prefs.edit().putString(K_VERIFIER + flow.key, verifier).apply()
        else pendingMemory[flow] = verifier
    }

    fun takePendingVerifier(flow: AuthFlow): String? {
        val prefs = secure
        if (prefs == null) return pendingMemory.remove(flow)
        val key = K_VERIFIER + flow.key
        val value = prefs.getString(key, null)
        prefs.edit().remove(key).apply()
        return value
    }

    var guestChosen: Boolean
        get() = plain.getBoolean(K_GUEST, false)
        set(value) { plain.edit().putBoolean(K_GUEST, value).apply() }

    /** One-shot message shown on the auth screen (for example, "session expired"). */
    var pendingNotice: String?
        get() = plain.getString(K_NOTICE, null)
        set(value) {
            plain.edit().apply { if (value == null) remove(K_NOTICE) else putString(K_NOTICE, value) }.apply()
        }

    private val pendingMemory = HashMap<AuthFlow, String>()

    private companion object {
        const val TAG = "SessionStore"
        const val PLAIN_FILE = "jarvis_auth_state"
        const val SECURE_FILE = "jarvis_secure_auth_prefs"
        const val K_ACCESS = "access_token"
        const val K_REFRESH = "refresh_token"
        const val K_EXPIRES = "expires_at"
        const val K_USER_ID = "user_id"
        const val K_EMAIL = "email"
        const val K_VERIFIER = "pkce_verifier_"
        const val K_GUEST = "guest_chosen"
        const val K_NOTICE = "pending_notice"
    }
}
