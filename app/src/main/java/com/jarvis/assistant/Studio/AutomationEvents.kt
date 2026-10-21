package com.jarvis.assistant.studio

/**
 * Carries system events (battery, charger, incoming messages) to the automation engine. The Jarvis service
 * registers the handler when it starts; whoever sees an event simply calls [dispatch].
 */
object AutomationEvents {
    @Volatile
    var handler: ((TriggerType, Map<String, String>) -> Unit)? = null

    fun dispatch(type: TriggerType, variables: Map<String, String>) {
        try {
            handler?.invoke(type, variables)
        } catch (_: Exception) {
        }
    }
}
