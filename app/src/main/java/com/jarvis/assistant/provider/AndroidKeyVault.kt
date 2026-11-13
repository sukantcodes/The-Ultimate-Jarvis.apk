package com.jarvis.assistant.provider

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Plain (non-secret) provider data: profile JSON and the active profile id. */
class SharedPrefsKeyValueStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("jarvis_providers", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/**
 * Endpoint API keys, always encrypted (never plain text).
 *
 * Every [AndroidKeyVault] in the process shares one [SecureBackend], so the app never has several copies of the
 * encrypted store fighting over the same files. The backend tries, in order:
 *  1. EncryptedSharedPreferences with an AES-256 Android Keystore master key;
 *  2. if that store keeps failing to open (for example after a restore, an OS update or a Keystore reset), it
 *     replaces the unreadable store with a fresh one, but only after several failed attempts in a row, never after
 *     a single temporary failure, and a copy of the old encrypted file is kept first;
 *  3. if the library still cannot start, a direct AES-256-GCM key held in the Android Keystore.
 * Initialisation is retried on later calls, so one bad moment does not leave Add API key broken until a restart.
 */
class AndroidKeyVault(context: Context) : KeyVault {
    private val app: Context = context.applicationContext

    override val available: Boolean get() = SecureBackend.store(app) != null

    override val lastError: String? get() = SecureBackend.lastError

    override fun load(profileId: String): ApiKeySecret? = try {
        SecureBackend.store(app)?.get(PREFIX + profileId)?.takeIf { it.isNotBlank() }?.let { ApiKeySecret.of(it) }
    } catch (e: Throwable) {
        Log.w(TAG, "Failed to read key for $profileId: ${e.javaClass.simpleName}")
        null
    }

    override fun store(profileId: String, key: String): Boolean {
        val s = SecureBackend.store(app) ?: return false
        return try {
            if (key.isBlank()) s.remove(PREFIX + profileId) else s.put(PREFIX + profileId, key)
        } catch (e: Throwable) {
            SecureBackend.note("saving", e)
            false
        }
    }

    override fun delete(profileId: String) {
        try { SecureBackend.store(app)?.remove(PREFIX + profileId) } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "AndroidKeyVault"
        private const val PREFIX = "key_"
    }
}

/** A tiny string store whose values are encrypted at rest. */
internal interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String): Boolean
    fun remove(key: String): Boolean
}

internal object SecureBackend {
    private const val TAG = "SecureBackend"
    private const val FILE = "jarvis_provider_keys_secure"
    private const val DIRECT_FILE = "jarvis_provider_keys_direct"
    private const val DIRECT_ALIAS = "jarvis_provider_key_direct_v1"
    private const val TINK_KEYSET_FILE = "__androidx_security_crypto_encrypted_prefs__"
    private const val RETRY_AFTER_MS = 3_000L
    private const val STATE_FILE = "jarvis_vault_state"
    private const val KEY_OPEN_FAILURES = "open_failures"
    private const val KEY_LAST_FAILURE_AT = "last_failure_at"
    private const val BACKUP_DIR = "vault-backup"

    private val lock = Any()
    @Volatile private var current: SecretStore? = null
    @Volatile private var lastAttemptAt = 0L
    @Volatile var lastError: String? = null
        private set

    fun note(stage: String, t: Throwable) {
        lastError = "$stage failed (${t.javaClass.simpleName}${t.message?.let { ": " + it.take(80) } ?: ""})"
        Log.w(TAG, lastError, t)
    }

    fun store(app: Context): SecretStore? {
        current?.let { return it }
        synchronized(lock) {
            current?.let { return it }
            val now = System.currentTimeMillis()
            if (lastError != null && now - lastAttemptAt < RETRY_AFTER_MS) return null
            lastAttemptAt = now
            val opened = open(app)
            if (opened != null) lastError = null
            current = opened
            return opened
        }
    }

    private fun open(app: Context): SecretStore? {
        val state = app.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
        try {
            val opened = encryptedPrefs(app)
            state.edit().putInt(KEY_OPEN_FAILURES, 0).apply()
            return opened
        } catch (t: Throwable) { note("Opening secure storage", t) }

        // One failure can be temporary (the Keystore is busy right after boot or unlock). Saved keys are only
        // replaced once opening has failed several times in a row, counted across app restarts.
        val nowMs = System.currentTimeMillis()
        var failures = state.getInt(KEY_OPEN_FAILURES, 0)
        if (VaultRecoveryPolicy.countsAsNewFailure(state.getLong(KEY_LAST_FAILURE_AT, 0L), nowMs)) {
            failures += 1
            state.edit().putInt(KEY_OPEN_FAILURES, failures).putLong(KEY_LAST_FAILURE_AT, nowMs).apply()
        }
        if (!VaultRecoveryPolicy.shouldReplaceStore(failures)) return null

        // The store has kept failing, so its keys are unreadable. Keep a copy of the encrypted file, then start clean.
        backup(app)
        try {
            wipe(app)
            val fresh = encryptedPrefs(app)
            state.edit().putInt(KEY_OPEN_FAILURES, 0).apply()
            return fresh
        } catch (t: Throwable) { note("Recreating secure storage", t) }
        try { return DirectKeystoreStore(app) } catch (t: Throwable) { note("Keystore fallback", t) }
        return null
    }

    /** Copies the encrypted preferences file (ciphertext only) aside before the store is replaced. */
    private fun backup(app: Context) {
        runCatching {
            val source = java.io.File(app.applicationInfo.dataDir, "shared_prefs/$FILE.xml")
            if (!source.exists()) return@runCatching
            val dir = java.io.File(app.filesDir, BACKUP_DIR).apply { mkdirs() }
            source.copyTo(java.io.File(dir, "$FILE.${System.currentTimeMillis()}.xml"), overwrite = true)
            // Keep only the three newest copies.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
        }
    }

    private fun encryptedPrefs(app: Context): SecretStore {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(
            app, FILE, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        // Prove the store really works (read and write) before reporting it available.
        check(prefs.edit().putString("__probe__", "ok").commit()) { "probe write refused" }
        prefs.edit().remove("__probe__").commit()
        return object : SecretStore {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String): Boolean = prefs.edit().putString(key, value).commit()
            override fun remove(key: String): Boolean = prefs.edit().remove(key).commit()
        }
    }

    private fun wipe(app: Context) {
        runCatching { app.deleteSharedPreferences(FILE) }
        runCatching { app.deleteSharedPreferences(TINK_KEYSET_FILE) }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
    }

    /** AES-256-GCM with a key that never leaves the Android Keystore; ciphertext sits in ordinary preferences. */
    private class DirectKeystoreStore(app: Context) : SecretStore {
        private val prefs: SharedPreferences = app.getSharedPreferences(DIRECT_FILE, Context.MODE_PRIVATE)
        private val key: SecretKey = loadOrCreateKey()

        init {
            // Fail construction (so the caller reports "unavailable") if the Keystore cannot actually encrypt.
            decrypt(encrypt("probe")).let { check(it == "probe") { "round trip mismatch" } }
        }

        private fun loadOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(DIRECT_ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(DIRECT_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return gen.generateKey()
        }

        private fun encrypt(plain: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        }

        private fun decrypt(encoded: String): String {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
            return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }

        override fun get(key: String): String? =
            prefs.getString(key, null)?.let { runCatching { decrypt(it) }.getOrNull() }

        override fun put(key: String, value: String): Boolean = prefs.edit().putString(key, encrypt(value)).commit()

        override fun remove(key: String): Boolean = prefs.edit().remove(key).commit()
    }
}

/** Decides when an unreadable key store may be replaced. Kept free of Android code so it can be unit tested. */
internal object VaultRecoveryPolicy {
    /** Failed opens in a row (across restarts) before the unreadable store is replaced. */
    const val FAILURES_BEFORE_REPLACE = 3

    fun shouldReplaceStore(consecutiveFailures: Int): Boolean = consecutiveFailures >= FAILURES_BEFORE_REPLACE

    /** Failures closer together than this are one bad moment, not several (retries during one screen visit). */
    const val FAILURE_SPACING_MS = 30_000L

    fun countsAsNewFailure(lastFailureAtMs: Long, nowMs: Long): Boolean =
        lastFailureAtMs <= 0L || nowMs - lastFailureAtMs >= FAILURE_SPACING_MS
}
