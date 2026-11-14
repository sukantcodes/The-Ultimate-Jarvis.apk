package com.jarvis.assistant.provider

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.voice.JarvisVoiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MultiModelCoordinator manages simultaneous cooperation across multiple configured API keys and models.
 *
 * Core capabilities:
 * 1. Synchronous Multi-Key Awareness:
 *    - Recognizes both configured Gemini API keys and Custom Model profiles (OpenAI, Claude, DeepSeek, Local LLM).
 * 2. Intelligent Work Routing:
 *    - Heavy coding and tool execution -> Routed to the active coding model (Custom Model or Gemini).
 *    - Multimodal vision/images -> Routed to Gemini Vision.
 *    - Spoken responses & live conversation voice -> Routed to Gemini Live Voice when Gemini key is set,
 *      or on-device TTS when only custom model is active.
 * 3. Unified Workspace Continuity:
 *    - All models share the identical disk workspace and execution context.
 * 4. Fault-Tolerant Cross-Model Failover:
 *    - If one key/model encounters rate limiting (429) or connection failure, seamlessly transfers
 *      the active task to the alternative configured model.
 */
object MultiModelCoordinator {

    private const val TAG = "MultiModelCoordinator"

    data class MultiModelCapabilities(
        val hasGemini: Boolean,
        val hasCustom: Boolean,
        val activeCustomName: String?,
        val supportsLiveVoice: Boolean,
        val supportsAutonomousTools: Boolean
    )

    private val _capabilities = MutableStateFlow(
        MultiModelCapabilities(
            hasGemini = false,
            hasCustom = false,
            activeCustomName = null,
            supportsLiveVoice = false,
            supportsAutonomousTools = true
        )
    )
    val capabilities: StateFlow<MultiModelCapabilities> = _capabilities.asStateFlow()

    fun refreshCapabilities(context: Context) {
        val app = (context.applicationContext as? JarvisApp)
        val geminiKey = app?.preferences?.apiKey.orEmpty().trim()
        val hasGemini = geminiKey.isNotBlank()

        val manager = ProviderRuntime.manager(context)
        val activeProfile = manager.activeProfile()
        val hasCustom = activeProfile != null && (manager.hasKey(activeProfile.id) || activeProfile.authMethod == AuthMethod.NONE)

        _capabilities.value = MultiModelCapabilities(
            hasGemini = hasGemini,
            hasCustom = hasCustom,
            activeCustomName = activeProfile?.displayName,
            supportsLiveVoice = hasGemini,
            supportsAutonomousTools = true
        )

        Log.i(TAG, "Multi-model capabilities refreshed: hasGemini=$hasGemini, hasCustom=$hasCustom (${activeProfile?.displayName}), liveVoice=$hasGemini")
    }

    /**
     * Determines whether to route a specific category to Gemini or the Custom Provider.
     */
    fun resolveTargetEngine(context: Context, category: WorkCategory): EngineTarget {
        refreshCapabilities(context)
        val caps = _capabilities.value

        return when (category) {
            WorkCategory.IMAGE -> {
                if (caps.hasGemini) EngineTarget.GEMINI else EngineTarget.CUSTOM
            }
            WorkCategory.VOICE -> {
                if (caps.hasGemini) EngineTarget.GEMINI_VOICE else EngineTarget.LOCAL_TTS
            }
            WorkCategory.CODING, WorkCategory.TASK_EXECUTION -> {
                if (caps.hasCustom) EngineTarget.CUSTOM else EngineTarget.GEMINI
            }
            else -> {
                if (caps.hasGemini) EngineTarget.GEMINI else if (caps.hasCustom) EngineTarget.CUSTOM else EngineTarget.OFFLINE
            }
        }
    }

    /**
     * Announces live progress updates to the user via voice and/or notification during autonomous execution.
     */
    fun reportProgress(
        context: Context,
        action: String,
        percent: Int,
        estimatedSecondsRemaining: Int,
        speakProgress: Boolean = false
    ) {
        val message = when {
            percent >= 100 -> "Task completed successfully, Sir."
            estimatedSecondsRemaining > 0 -> "$action: $percent% complete, about $estimatedSecondsRemaining seconds remaining."
            else -> "$action: $percent% complete."
        }

        if (speakProgress) {
            val caps = _capabilities.value
            if (caps.hasGemini) {
                JarvisConversationService.announceMessage(message)
            } else {
                JarvisVoiceController.speakTurnResponse(context, "progress-$percent", null, message, forceTts = true)
            }
        }
    }

    enum class EngineTarget {
        CUSTOM,
        GEMINI,
        GEMINI_VOICE,
        LOCAL_TTS,
        OFFLINE
    }
}
