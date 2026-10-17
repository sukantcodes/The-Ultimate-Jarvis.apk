package com.jarvis.assistant.automation.studio

import android.content.Context
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant

/**
 * Runtime assembly for Automation Studio. Construct once from the application/service owner
 * after JarvisApp's existing phone and WhatsApp managers are initialized.
 *
 * Note: this wires background trigger events to the rule engine, but the host still needs to
 * call `interceptor.interceptFinalTranscript()` from a genuine finalized-text-before-Gemini path.
 * The current Gemini Live microphone stream is not such a path.
 */
class AutomationStudioRuntime(
    context: Context,
    phoneControlManager: PhoneControlManager,
    whatsAppAssistant: WhatsAppAssistant
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val ttsExecutor = LocalTtsActionExecutor(appContext)
    val repository = RuleRepository(appContext)

    private val existingActions = ExistingJarvisActionBridge(phoneControlManager, whatsAppAssistant)
    private val systemActions = SystemActionExecutor(appContext)
    private val speakAction = object : StudioActionExecutor {
        override suspend fun execute(action: AutomationAction, variables: Map<String, String>): ActionExecutionResult {
            val raw = action.parameters["text"].orEmpty()
            val text = variables.entries.fold(raw) { current, (key, value) ->
                current.replace("{$key}", value, ignoreCase = true)
            }.trim()
            return ttsExecutor.speak(text)
        }
    }

    private val dispatcher = StudioActionDispatcher(
        mapOf(
            RuleActionType.SPEAK to speakAction,
            RuleActionType.PLACE_CALL to existingActions,
            RuleActionType.WHATSAPP_MESSAGE to existingActions,
            RuleActionType.SET_VOLUME to existingActions,
            RuleActionType.SET_BRIGHTNESS to existingActions,
            RuleActionType.SET_FLASHLIGHT to existingActions,
            RuleActionType.SET_RINGER_MODE to systemActions,
            RuleActionType.MEDIA_PLAY to systemActions,
            RuleActionType.MEDIA_PAUSE to systemActions,
            RuleActionType.MEDIA_NEXT to systemActions,
            RuleActionType.MEDIA_PREVIOUS to systemActions,
            RuleActionType.OPEN_WIFI_SETTINGS to systemActions,
            RuleActionType.OPEN_BLUETOOTH_SETTINGS to systemActions
        )
    )

    val interceptor = PriorityInterceptorService(appContext, repository, dispatcher)
    private val triggerEngine = AutomationTriggerRuleEngine(appContext, repository, dispatcher)
    private val triggerMonitor = AutomationTriggerMonitor(appContext)
    private val triggerHandler = AutomationTriggerSink.Handler { _, trigger, payload ->
        triggerEngine.onTrigger(trigger, payload)
    }

    init {
        AutomationTriggerSink.handler = triggerHandler
        triggerMonitor.start()
    }

    override fun close() {
        triggerMonitor.close()
        if (AutomationTriggerSink.handler === triggerHandler) AutomationTriggerSink.handler = null
        ttsExecutor.close()
    }
}
