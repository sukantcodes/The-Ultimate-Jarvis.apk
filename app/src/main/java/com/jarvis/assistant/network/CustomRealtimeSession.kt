package com.jarvis.assistant.network

import android.content.Context
import com.jarvis.assistant.network.provider.CustomProviderProfile
import com.jarvis.assistant.network.provider.ProviderUrlValidator
import com.jarvis.assistant.security.CustomProviderCredentialStore

/**
 * Realtime (bidirectional) voice session for a custom provider that speaks the Gemini-Live
 * BidiGenerateContent protocol over WebSocket. It reuses the entire proven Gemini-Live session
 * (setup handshake, audio frames, tool calls, turn lifecycle) and only swaps the endpoint:
 *
 *   URL  = <profile base, converted to wss> ?key=<encrypted provider key>
 *
 * The key is passed to the base class as its apiKey, so all key-missing / invalid-key error
 * paths work unchanged while [providerLabel] makes the messages name the custom provider.
 */
class CustomRealtimeSession(
    context: Context,
    private val profile: CustomProviderProfile,
    voiceName: String,
    systemPrompt: String,
    sessionListener: Listener
) : GeminiLiveWebSocket(
    apiKey = CustomProviderCredentialStore(context).keyFor(profile.id),
    model = profile.selectedModelId,
    voiceName = voiceName,
    systemPrompt = systemPrompt,
    listener = sessionListener
) {

    override val providerLabel: String = profile.name

    /** Point the Gemini-Live protocol at the custom provider's WebSocket endpoint. */
    override fun connectionUrl(apiKey: String): String {
        val base = ProviderUrlValidator.wsBase(profile.baseUrl).trimEnd('/')
        val encodedKey = java.net.URLEncoder.encode(apiKey, Charsets.UTF_8.name())
        return "$base?key=$encodedKey"
    }
}
