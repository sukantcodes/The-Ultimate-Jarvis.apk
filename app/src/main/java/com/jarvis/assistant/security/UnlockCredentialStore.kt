package com.jarvis.assistant.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the phone's screen-lock credential (PIN, password or pattern) so Jarvis can unlock the
 * device by voice. Values are written only to AES-256 encrypted preferences backed by the Android
 * Keystore. The credential is never logged, never exposed to the AI model, and never leaves the
 * device. If encrypted storage cannot be created, nothing is saved (there is no plain-text fallback).
 */
class UnlockCredentialStore(context: Context) {

    enum class LockType { PIN, PASSWORD, PATTERN }

    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted unlock storage could not be initialized", e)
        null
    }

    val isStorageAvailable: Boolean get() = prefs != null

    fun hasCredential(): Boolean = type() != null && !secret().isNullOrEmpty()

    fun type(): LockType? = try {
        prefs?.getString(KEY_TYPE, null)?.let { LockType.valueOf(it) }
    } catch (_: Exception) {
        null
    }

    /** PIN digits, the password text, or the pattern as dot numbers 1-9 (e.g. "14753"). */
    fun secret(): String? = prefs?.getString(KEY_SECRET, null)

    /** Number of characters (digits / chars / dots) saved, for display only. */
    fun secretLength(): Int = secret()?.length ?: 0

    /**
     * Saves a credential after validating it. Returns null on success or a short error message.
     */
    fun save(type: LockType, secret: String): String? {
        val store = prefs ?: return "Secure storage is not available on this device, so nothing was saved."
        val error = validate(type, secret)
        if (error != null) return error
        val ok = store.edit()
            .putString(KEY_TYPE, type.name)
            .putString(KEY_SECRET, secret)
            .remove(KEY_FAILED_ATTEMPTS)
            .remove(KEY_BLOCKED_UNTIL)
            .commit()
        return if (ok) null else "Could not write to secure storage."
    }

    fun clear() {
        prefs?.edit()
            ?.remove(KEY_TYPE)
            ?.remove(KEY_SECRET)
            ?.remove(KEY_FAILED_ATTEMPTS)
            ?.remove(KEY_BLOCKED_UNTIL)
            ?.commit()
    }

    /** When false (default) unlocking requires the owner's enrolled voice to match. */
    var allowWithoutVoiceMatch: Boolean
        get() = prefs?.getBoolean(KEY_ALLOW_WITHOUT_VOICE, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_ALLOW_WITHOUT_VOICE, value)?.apply()
        }

    // ---- Lock-out protection: stops repeated wrong entries from locking the phone ----

    fun blockedForMillis(now: Long = System.currentTimeMillis()): Long {
        val until = prefs?.getLong(KEY_BLOCKED_UNTIL, 0L) ?: 0L
        return (until - now).coerceAtLeast(0L)
    }

    fun recordFailure(now: Long = System.currentTimeMillis()) {
        val store = prefs ?: return
        val failures = store.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = store.edit().putInt(KEY_FAILED_ATTEMPTS, failures)
        // After 2 failed automatic entries, pause automation so the phone's own lock-out is not triggered.
        if (failures >= MAX_AUTO_FAILURES) {
            editor.putLong(KEY_BLOCKED_UNTIL, now + BLOCK_MILLIS)
        }
        editor.apply()
    }

    fun recordSuccess() {
        prefs?.edit()?.remove(KEY_FAILED_ATTEMPTS)?.remove(KEY_BLOCKED_UNTIL)?.apply()
    }

    companion object {
        private const val TAG = "UnlockCredentialStore"
        private const val FILE_NAME = "jarvis_unlock_vault"
        private const val KEY_TYPE = "unlock_type"
        private const val KEY_SECRET = "unlock_secret"
        private const val KEY_ALLOW_WITHOUT_VOICE = "allow_without_voice_match"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_BLOCKED_UNTIL = "blocked_until"
        private const val MAX_AUTO_FAILURES = 2
        private const val BLOCK_MILLIS = 10 * 60 * 1000L

        fun validate(type: LockType, secret: String): String? = when (type) {
            LockType.PIN -> when {
                secret.length < 4 -> "A PIN must have at least 4 digits."
                secret.length > 16 -> "A PIN can have at most 16 digits."
                !secret.all { it in '0'..'9' } -> "A PIN can only contain digits."
                else -> null
            }
            LockType.PASSWORD -> when {
                secret.length < 4 -> "A password must have at least 4 characters."
                secret.length > 64 -> "A password can have at most 64 characters."
                else -> null
            }
            LockType.PATTERN -> when {
                secret.length < 4 -> "A pattern must connect at least 4 dots."
                secret.length > 9 -> "A pattern can connect at most 9 dots."
                !secret.all { it in '1'..'9' } -> "A pattern can only use dots 1 to 9."
                secret.toSet().size != secret.length -> "A pattern cannot use the same dot twice."
                else -> null
            }
        }
    }
}
