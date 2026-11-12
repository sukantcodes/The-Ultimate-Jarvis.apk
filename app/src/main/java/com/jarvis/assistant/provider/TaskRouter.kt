package com.jarvis.assistant.provider

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.offline.OfflineModelDownloader
import com.jarvis.assistant.tts.engine.JarvisVoiceDispatcher
import com.jarvis.assistant.tts.engine.TtsModelDownloader
import com.jarvis.assistant.tts.model.SpeakerProfile
import com.jarvis.assistant.tts.model.VoiceEngineType

/**
 * Task types requiring independent execution route resolution.
 */
enum class TaskType(val id: String, val displayName: String, val category: WorkCategory) {
    CHAT_ANALYSIS("chat_analysis", "Chat & Analysis", WorkCategory.CHAT),
    IMAGE_UNDERSTANDING("image_understanding", "Image Understanding", WorkCategory.IMAGE),
    CODING_DEBUGGING("coding_debugging", "Coding & Debugging", WorkCategory.CODING),
    VOICE_CONVERSATION("voice_conversation", "Voice Conversations", WorkCategory.VOICE);

    companion object {
        fun fromId(id: String?): TaskType =
            entries.firstOrNull { it.id == id } ?: CHAT_ANALYSIS
    }
}

/**
 * Result of task route resolution determining provider and execution pipeline.
 */
sealed class TaskRouteResolution {
    data class Gemini(val modelId: String = "") : TaskRouteResolution()
    data class Custom(val provider: ResolvedProvider) : TaskRouteResolution()
    data class HybridVoice(
        val customProvider: ResolvedProvider?,
        val voiceEngineType: VoiceEngineType,
        val speakerProfile: SpeakerProfile
    ) : TaskRouteResolution()
    data class Offline(val reason: String = "") : TaskRouteResolution()
    data class Blocked(val title: String, val message: String) : TaskRouteResolution()
}

/**
 * Unified TaskRouter enforcing dynamic per-task work routing across all features.
 */
object TaskRouter {

    fun getRouteForTask(context: Context, taskType: TaskType): TaskRouteResolution {
        val app = context.applicationContext as? JarvisApp ?: JarvisApp.instance
        val routes = ProviderRuntime.routing(app)
        val manager = ProviderRuntime.manager(app)
        val geminiKey = app.preferences.apiKey.trim()
        val hasGeminiKey = geminiKey.isNotBlank()

        val savedRoute = routes.get(taskType.category)

        if (taskType == TaskType.VOICE_CONVERSATION) {
            return resolveVoiceTaskRoute(app, routes, manager, savedRoute, hasGeminiKey)
        }

        // Chat, Image, Coding task routing
        val resolution = manager.resolveChat(taskType.category)
        return when (resolution) {
            is ChatResolution.Ready -> TaskRouteResolution.Custom(resolution.provider)
            is ChatResolution.Blocked -> TaskRouteResolution.Blocked(
                title = resolution.providerName,
                message = resolution.reason
            )
            is ChatResolution.NotActive -> {
                if (hasGeminiKey) {
                    TaskRouteResolution.Gemini(savedRoute?.modelId.orEmpty())
                } else if (OfflineModelDownloader.isModelValid(OfflineModelDownloader.modelFile(app))) {
                    TaskRouteResolution.Offline("Offline Brain active")
                } else {
                    TaskRouteResolution.Blocked(
                        title = "NO AI PROVIDER CONFIGURED",
                        message = "Please configure a Gemini API key or add a Custom AI Provider in Settings."
                    )
                }
            }
        }
    }

    private fun resolveVoiceTaskRoute(
        context: Context,
        routes: RoutingStore,
        manager: ProviderConfigManager,
        savedRoute: Route?,
        hasGeminiKey: Boolean
    ): TaskRouteResolution {
        val dispatcher = JarvisVoiceDispatcher.getInstance(context)
        val activeEngineType = dispatcher.getActiveEngineType()
        val isOfflineTtsActive = activeEngineType.isLocal && TtsModelDownloader.isEngineInstalled(context, activeEngineType)
        val activeSpeaker = dispatcher.getActiveSpeaker()

        // 1. Explicit Gemini route in Work Routing
        if (savedRoute != null && savedRoute.isGemini) {
            if (hasGeminiKey) {
                return TaskRouteResolution.Gemini(savedRoute.modelId)
            }
            if (isOfflineTtsActive) {
                return TaskRouteResolution.HybridVoice(null, activeEngineType, activeSpeaker)
            }
            return TaskRouteResolution.Blocked(
                title = "GEMINI API KEY REQUIRED",
                message = "Work Routing is configured for Gemini Built-in Voice, but no Gemini API key is saved. Please add your key in API Key Settings."
            )
        }

        // 2. Explicit Offline TTS route in Work Routing
        if (savedRoute != null && savedRoute.isOfflineTts) {
            val engine = VoiceEngineType.fromId(savedRoute.modelId.ifBlank { activeEngineType.id })
            val installed = TtsModelDownloader.isEngineInstalled(context, engine)
            if (installed) {
                // If Gemini key is available, use Gemini as the parent brain while offline TTS handles synthesis
                if (hasGeminiKey) {
                    return TaskRouteResolution.Gemini(savedRoute.modelId)
                }
                val customProvider = (manager.resolveChat(WorkCategory.CHAT) as? ChatResolution.Ready)?.provider
                return TaskRouteResolution.HybridVoice(customProvider, engine, activeSpeaker)
            }
        }

        // 3. User explicitly routed Voice to a Custom Provider
        if (savedRoute != null && savedRoute.isCustom) {
            val customVoice = manager.resolveVoice()
            when (customVoice) {
                is VoiceResolution.Ready -> {
                    if (customVoice.mode == VoiceMode.REALTIME_GEMINI_LIVE || customVoice.mode == VoiceMode.TURN_BASED) {
                        return TaskRouteResolution.Custom(customVoice.provider)
                    }
                    if (isOfflineTtsActive) {
                        return TaskRouteResolution.HybridVoice(customVoice.provider, activeEngineType, activeSpeaker)
                    }
                    return TaskRouteResolution.Custom(customVoice.provider)
                }
                is VoiceResolution.Blocked -> {
                    if (isOfflineTtsActive) {
                        val chatRes = manager.resolveChat(WorkCategory.CHAT)
                        val customChatProvider = (chatRes as? ChatResolution.Ready)?.provider
                        return TaskRouteResolution.HybridVoice(customChatProvider, activeEngineType, activeSpeaker)
                    }
                    if (hasGeminiKey) {
                        return TaskRouteResolution.Gemini()
                    }
                    return TaskRouteResolution.Blocked(customVoice.providerName, customVoice.reason)
                }
                VoiceResolution.NotActive -> Unit
            }
        }

        // 4. Default Master Orchestration: Gemini is ALWAYS the primary brain when Gemini key is configured
        if (hasGeminiKey) {
            return TaskRouteResolution.Gemini(savedRoute?.modelId.orEmpty())
        }

        // 5. Fallback paths only when no Gemini key is configured
        val customVoice = manager.resolveVoice()
        when (customVoice) {
            is VoiceResolution.Ready -> {
                // If it can run real-time or turn-based voice natively
                if (customVoice.mode == VoiceMode.REALTIME_GEMINI_LIVE || customVoice.mode == VoiceMode.TURN_BASED) {
                    return TaskRouteResolution.Custom(customVoice.provider)
                }
                // Hybrid turn-based voice with Offline TTS
                if (isOfflineTtsActive) {
                    return TaskRouteResolution.HybridVoice(customVoice.provider, activeEngineType, activeSpeaker)
                }
                return TaskRouteResolution.Custom(customVoice.provider)
            }
            is VoiceResolution.Blocked -> {
                // If text-only custom provider (e.g. DeepSeek v4.1 Flash) but Offline TTS is active:
                // Enable the Custom AI + Offline Voice Hybrid Pipeline!
                if (isOfflineTtsActive) {
                    val chatRes = manager.resolveChat(WorkCategory.CHAT)
                    val customChatProvider = (chatRes as? ChatResolution.Ready)?.provider
                    return TaskRouteResolution.HybridVoice(customChatProvider, activeEngineType, activeSpeaker)
                }
                return TaskRouteResolution.Blocked(customVoice.providerName, customVoice.reason)
            }
            VoiceResolution.NotActive -> {
                // Follow active provider is not set, or no custom provider active
                if (isOfflineTtsActive) {
                    val chatRes = manager.resolveChat(WorkCategory.CHAT)
                    val customChatProvider = (chatRes as? ChatResolution.Ready)?.provider
                    return TaskRouteResolution.HybridVoice(customChatProvider, activeEngineType, activeSpeaker)
                }
                if (OfflineModelDownloader.isModelValid(OfflineModelDownloader.modelFile(context))) {
                    return TaskRouteResolution.Offline("Offline Brain active")
                }
                return TaskRouteResolution.Blocked(
                    title = "VOICE ENGINE UNAVAILABLE",
                    message = "Please configure a Gemini API key or download an Offline Neural Voice model in Settings."
                )
            }
        }
    }
}
