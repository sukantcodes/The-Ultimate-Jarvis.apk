package com.jarvis.assistant.data.preferences

import android.content.Context
import com.jarvis.assistant.network.provider.CustomProviderProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository for custom AI provider profiles. Profiles (endpoint, protocol, auth method, model
 * list, selected model) live in plain shared preferences as JSON; API keys never live here —
 * they are kept in [com.jarvis.assistant.security.CustomProviderCredentialStore] (encrypted).
 */
class CustomProviderStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    val profiles: List<CustomProviderProfile>
        get() {
            val raw = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
            val out = ArrayList<CustomProviderProfile>()
            return try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    out.add(CustomProviderProfile.fromJson(obj))
                }
                out
            } catch (e: Exception) {
                out
            }
        }

    fun profile(id: String): CustomProviderProfile? = profiles.firstOrNull { it.id == id }

    fun save(profile: CustomProviderProfile) {
        val current = profiles
        val updated = ArrayList(current)
        val existingIndex = updated.indexOfFirst { it.id == profile.id }
        if (existingIndex >= 0) updated[existingIndex] = profile else updated.add(profile)
        persist(updated)
    }

    fun delete(id: String) {
        val current = profiles.filterNot { it.id == id }
        persist(current)
        if (activeProfileId == id) activeProfileId = ""
    }

    /** Returns the active profile, or null when no custom provider is active. */
    val activeProfile: CustomProviderProfile?
        get() {
            val id = activeProfileId
            if (id.isBlank()) return null
            return profile(id)
        }

    var activeProfileId: String
        get() = prefs.getString(KEY_ACTIVE_PROFILE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value.trim()).apply()

    fun isActive(id: String): Boolean = activeProfileId == id

    fun setActive(profile: CustomProviderProfile) {
        activeProfileId = profile.id
    }

    fun clearActive() {
        activeProfileId = ""
    }

    private fun persist(profiles: List<CustomProviderProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    companion object {
        private const val PREF_FILE = "jarvis_custom_providers"
        private const val KEY_PROFILES = "provider_profiles"
        private const val KEY_ACTIVE_PROFILE = "active_provider_id"
    }
}
