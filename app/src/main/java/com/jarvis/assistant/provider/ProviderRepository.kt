package com.jarvis.assistant.provider

import java.util.UUID

/** Plain key/value storage for non-secret data (profile JSON, active profile id). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/**
 * Secret storage for endpoint API keys. The Android implementation encrypts with an Android Keystore-backed
 * master key. If encryption is unavailable, [store] returns false and nothing is saved: keys are never written
 * as plain text.
 */
interface KeyVault {
    val available: Boolean
    fun load(profileId: String): ApiKeySecret?
    fun store(profileId: String, key: String): Boolean
    fun delete(profileId: String)
}

/**
 * Saved provider profiles plus which one is active. Profiles contain no secrets; keys are kept per profile id in
 * the [KeyVault], so the Google Gemini key and the Groq key are never touched.
 */
class ProviderRepository(
    private val store: KeyValueStore,
    private val vault: KeyVault
) {
    private val lock = Any()

    val canStoreKeys: Boolean get() = vault.available

    fun newId(): String = "prov_" + UUID.randomUUID().toString().replace("-", "").take(16)

    fun list(): List<ProviderProfile> = synchronized(lock) { ProfileCodec.decode(store.getString(KEY_PROFILES)) }

    fun get(id: String): ProviderProfile? = list().firstOrNull { it.id == id }

    fun upsert(profile: ProviderProfile) = synchronized(lock) {
        val all = list().toMutableList()
        val index = all.indexOfFirst { it.id == profile.id }
        if (index >= 0) all[index] = profile else all.add(profile)
        store.putString(KEY_PROFILES, ProfileCodec.encode(all))
    }

    /** Deletes the profile and its key. Returns true when it was the active profile (so the caller can react). */
    fun delete(id: String): Boolean = synchronized(lock) {
        val all = list().filter { it.id != id }
        store.putString(KEY_PROFILES, ProfileCodec.encode(all))
        vault.delete(id)
        val wasActive = activeId() == id
        if (wasActive) store.remove(KEY_ACTIVE)
        wasActive
    }

    fun activeId(): String? = synchronized(lock) { store.getString(KEY_ACTIVE)?.takeIf { it.isNotBlank() } }

    fun setActiveId(id: String?) = synchronized(lock) {
        if (id == null) store.remove(KEY_ACTIVE) else store.putString(KEY_ACTIVE, id)
    }

    fun loadKey(id: String): ApiKeySecret? = vault.load(id)

    fun hasKey(id: String): Boolean {
        val k = vault.load(id) ?: return false
        val has = !k.isBlank
        k.wipe()
        return has
    }

    /** Masked hint such as "••••••••a1b2" for showing that a key exists. Never the whole key. */
    fun keyHint(id: String): String {
        val k = vault.load(id) ?: return ""
        val hint = ProviderAuth.hint(k)
        k.wipe()
        return hint
    }

    /** Returns false when the key could not be stored securely. */
    fun saveKey(id: String, key: String): Boolean = vault.store(id, key.trim())

    companion object {
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ACTIVE = "active_profile_id"
    }
}
