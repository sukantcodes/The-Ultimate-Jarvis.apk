package com.jarvis.assistant.network.provider

import android.content.Context
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.preferences.CustomProviderStore
import com.jarvis.assistant.security.AiEngineStore
import com.jarvis.assistant.security.CustomProviderCredentialStore

/**
 * Single source of truth for the AI engine configuration. Decides, based on persisted settings,
 * which engine is active:
 *
 *  1. a custom provider profile (when the user activated one),
 *  2. Groq (legacy toggle),
 *  3. Gemini Live (the default).
 *
 * The active custom profile always wins over the Groq toggle so the user can switch between
 * "Gemini Live", "Groq" and any saved custom provider without losing any configuration.
 */
class ProviderConfigManager(context: Context) {

    private val appContext = context.applicationContext
    private val providerStore = CustomProviderStore(appContext)
    private val aiEngineStore = AiEngineStore(appContext)
    private val preferences = AppPreferences(appContext)
    private val credentials = CustomProviderCredentialStore(appContext)

    enum class ActiveEngine { CUSTOM, GROQ, GEMINI }

    val activeEngine: ActiveEngine
        get() = when {
            activeCustomProfile != null -> ActiveEngine.CUSTOM
            aiEngineStore.useGroq -> ActiveEngine.GROQ
            else -> ActiveEngine.GEMINI
        }

    /** The active custom provider profile, or null when a custom provider is not active. */
    val activeCustomProfile: CustomProviderProfile?
        get() = providerStore.activeProfile

    /** The model that should be used for the active custom provider (selected by the user). */
    fun activeCustomModel(): DiscoveredModel? {
        val profile = activeCustomProfile ?: return null
        return profile.selectedModel() ?: profile.models.firstOrNull()
    }

    /** Whether the user has chosen a model for the active custom provider. */
    val hasActiveCustomModel: Boolean
        get() {
            val profile = activeCustomProfile ?: return false
            return profile.selectedModelId.isNotBlank() && profile.selectedModel() != null
        }

    /** True when the active custom provider has a saved API key. */
    fun hasActiveCustomKey(): Boolean {
        val profile = activeCustomProfile ?: return false
        return credentials.hasKeyFor(profile.id)
    }

    /** True when the active custom provider can power the live voice assistant. */
    fun customSupportsRealtimeVoice(): Boolean {
        val profile = activeCustomProfile ?: return false
        return profile.protocol == ProviderProtocol.GEMINI_REALTIME &&
            profile.selectedModel()?.capabilities?.realtimeVoice == true
    }

    /** Capabilities of the selected custom model, or the profile protocol default. */
    fun activeCustomCapabilities(): ProviderModelCapabilities {
        val profile = activeCustomProfile ?: return ProviderModelCapabilities.textOnly
        return profile.selectedModel()?.capabilities ?: when (profile.protocol) {
            ProviderProtocol.GEMINI_REALTIME -> ProviderModelCapabilities.realtimeVoiceCapable
            else -> ProviderModelCapabilities.textOnly
        }
    }

    /** A friendly summary used in the Settings subtitle. */
    fun activeSummary(): String = when (activeEngine) {
        ActiveEngine.GROQ -> "Active: Groq (${aiEngineStore.groqChatModel})"
        ActiveEngine.CUSTOM -> {
            val profile = activeCustomProfile
            if (profile == null) "Active: Custom provider"
            else {
                val model = profile.selectedModel()?.id ?: profile.selectedModelId
                "Active: ${profile.name}" + if (model.isNotBlank()) " ($model)" else " (no model selected)"
            }
        }
        ActiveEngine.GEMINI -> "Active: Gemini Live"
    }

    /** Whether any engine is configured and ready (has a key). */
    fun isAnyEngineReady(): Boolean = when (activeEngine) {
        ActiveEngine.GEMINI -> preferences.apiKey.isNotBlank()
        ActiveEngine.GROQ -> aiEngineStore.useGroq && aiEngineStore.hasGroqKey()
        ActiveEngine.CUSTOM -> hasActiveCustomKey() && hasActiveCustomModel
    }

    /** Whether the active custom model supports image/file input (for ChatBox attachments). */
    fun supportsImageInput(): Boolean {
        if (activeEngine != ActiveEngine.CUSTOM) return false
        return activeCustomCapabilities().imageInput
    }

    /** Deactivate the custom provider, keeping its profile saved. */
    fun deactivateCustom() {
        providerStore.clearActive()
    }

    fun activateCustom(profile: CustomProviderProfile) {
        providerStore.setActive(profile)
    }
}
