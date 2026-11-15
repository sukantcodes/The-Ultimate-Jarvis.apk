package com.jarvis.assistant.provider

import android.content.Context
import com.jarvis.assistant.JarvisApp

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
        // 1. Explicit Gemini route in Work Routing
        if (savedRoute != null && savedRoute.isGemini) {
            if (hasGeminiKey) {
                return TaskRouteResolution.Gemini(savedRoute.modelId)
            }
            return TaskRouteResolution.Blocked(
                title = "GEMINI API KEY REQUIRED",
                message = "Work Routing is configured for Gemini Built-in Voice, but no Gemini API key is saved. Please add your key in API Key Settings."
            )
        }

        // 2. User explicitly routed Voice to a Custom Provider
        if (savedRoute != null && savedRoute.isCustom) {
            when (val customVoice = manager.resolveVoice()) {
                is VoiceResolution.Ready -> return TaskRouteResolution.Custom(customVoice.provider)
                is VoiceResolution.Blocked -> {
                    if (hasGeminiKey) {
                        return TaskRouteResolution.Gemini()
                    }
                    return TaskRouteResolution.Blocked(customVoice.providerName, customVoice.reason)
                }
                VoiceResolution.NotActive -> Unit
            }
        }

        // 3. Default: Gemini is the primary brain whenever a Gemini key is configured
        if (hasGeminiKey) {
            return TaskRouteResolution.Gemini(savedRoute?.modelId.orEmpty())
        }

        // 4. Only when there is no Gemini key: the active custom provider, if it can handle voice
        return when (val customVoice = manager.resolveVoice()) {
            is VoiceResolution.Ready -> TaskRouteResolution.Custom(customVoice.provider)
            is VoiceResolution.Blocked -> TaskRouteResolution.Blocked(customVoice.providerName, customVoice.reason)
            VoiceResolution.NotActive -> TaskRouteResolution.Blocked(
                title = "NO AI PROVIDER CONFIGURED",
                message = "Please configure a Gemini API key (AI Studio) or add a Custom AI Provider in Settings."
            )
        }
    }
}
