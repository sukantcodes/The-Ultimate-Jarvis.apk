package com.jarvis.assistant.automation.studio

import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant

/**
 * Bridges Automation Studio actions to the repository's existing managers.
 * Keep WhatsAppAssistant's confirmation/draft flow intact: sending a message should
 * continue to require its existing confirmAndSend() path when configured by the app.
 */
class ExistingJarvisActionBridge(
    private val phoneControlManager: PhoneControlManager,
    private val whatsAppAssistant: WhatsAppAssistant
) : StudioActionExecutor {

    override suspend fun execute(
        action: AutomationAction,
        variables: Map<String, String>
    ): ActionExecutionResult {
        fun value(key: String): String =
            resolve(action.parameters[key].orEmpty(), variables).trim()

        return try {
            when (action.type) {
                RuleActionType.PLACE_CALL -> {
                    val contact = value("contact")
                    if (contact.isBlank()) ActionExecutionResult(false, "Who should I call?")
                    else ActionExecutionResult(true, phoneControlManager.makePhoneCall(contact), true)
                }
                RuleActionType.WHATSAPP_MESSAGE -> {
                    val contact = value("contact")
                    val message = value("message")
                    if (contact.isBlank() || message.isBlank()) {
                        ActionExecutionResult(false, "Please provide both a contact and a message.")
                    } else {
                        // Creates the existing app's pending draft; does not bypass confirmation.
                        ActionExecutionResult(true, whatsAppAssistant.createDraft(contact, message), true)
                    }
                }
                RuleActionType.SET_VOLUME -> {
                    val level = value("level").removeSuffix("%").toIntOrNull()
                        ?: return ActionExecutionResult(false, "Volume level must be a number from 0 to 100.")
                    if (level !in 0..100) ActionExecutionResult(false, "Volume must be between 0 and 100.")
                    else ActionExecutionResult(true, phoneControlManager.setVolumePercent(level), true)
                }
                RuleActionType.SET_BRIGHTNESS -> {
                    val level = value("level").removeSuffix("%").toIntOrNull()
                        ?: return ActionExecutionResult(false, "Brightness must be a number from 0 to 100.")
                    if (level !in 0..100) ActionExecutionResult(false, "Brightness must be between 0 and 100.")
                    else ActionExecutionResult(true, phoneControlManager.setBrightnessPercent(level), true)
                }
                RuleActionType.SET_FLASHLIGHT -> {
                    val enabled = value("enabled").lowercase() in setOf("true", "on", "yes", "1")
                    ActionExecutionResult(true, phoneControlManager.setFlashlight(enabled), true)
                }
                else -> ActionExecutionResult(false, "Action ${action.type} is handled by a separate executor.")
            }
        } catch (e: SecurityException) {
            ActionExecutionResult(false, "Permission is required to perform this action.")
        } catch (e: Exception) {
            ActionExecutionResult(false, "The action failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun resolve(raw: String, variables: Map<String, String>): String {
        var result = raw
        variables.forEach { (key, value) -> result = result.replace("{$key}", value, ignoreCase = true) }
        return result
    }
}
