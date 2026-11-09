package com.jarvis.assistant.automation.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.contacts.ContactInfo
import com.jarvis.assistant.automation.contacts.ContactResolutionResult
import com.jarvis.assistant.automation.contacts.ContactResolver
import com.jarvis.assistant.util.ActivityLauncherHelper
import java.net.URLEncoder
import java.util.Locale

data class PendingWhatsAppDraft(
    val contactName: String,
    val phoneNumber: String?,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

class WhatsAppAssistant(
    private val context: Context,
    private val contactResolver: ContactResolver
) {
    companion object {
        private const val TAG = "WhatsAppAssistant"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
    }

    private var pendingDraft: PendingWhatsAppDraft? = null
    private var disambiguationCandidates: List<ContactInfo>? = null
    private var pendingDisambiguationMessage: String? = null

    fun hasPendingDraft(): Boolean = pendingDraft != null

    fun isAwaitingDisambiguation(): Boolean = !disambiguationCandidates.isNullOrEmpty()

    fun createDraft(targetContact: String, messageText: String): String {
        val cleanContact = targetContact.trim()
        val cleanMessage = messageText.trim()

        if (cleanContact.isBlank()) {
            return "Who would you like to message on WhatsApp, sir?"
        }
        if (cleanMessage.isBlank()) {
            return "What message would you like to send to $cleanContact?"
        }

        val resolution = contactResolver.resolveContact(cleanContact)
        when (resolution) {
            is ContactResolutionResult.SingleMatch -> {
                disambiguationCandidates = null
                pendingDisambiguationMessage = null
                val contact = resolution.contact
                pendingDraft = PendingWhatsAppDraft(
                    contactName = contact.name,
                    phoneNumber = contact.phoneNumber,
                    message = cleanMessage
                )
                return "Draft ready for ${contact.name}: \"$cleanMessage\". Should I send it?"
            }
            is ContactResolutionResult.MultipleMatches -> {
                disambiguationCandidates = resolution.candidates
                pendingDisambiguationMessage = cleanMessage
                val names = resolution.candidates.take(3).map { it.name }.joinToString(" and ")
                return "Which $cleanContact? I found $names."
            }
            is ContactResolutionResult.NoMatch -> {
                // If contact was entered as a raw phone number or not found in contacts
                disambiguationCandidates = null
                pendingDisambiguationMessage = null
                val isPhoneNumber = cleanContact.replace(Regex("[^0-9+]"), "").length >= 7
                val phone = if (isPhoneNumber) cleanContact.replace(Regex("[^0-9+]"), "") else null
                pendingDraft = PendingWhatsAppDraft(
                    contactName = cleanContact,
                    phoneNumber = phone,
                    message = cleanMessage
                )
                return "Draft ready for $cleanContact: \"$cleanMessage\". Should I send it?"
            }
            is ContactResolutionResult.PermissionDenied -> {
                disambiguationCandidates = null
                pendingDisambiguationMessage = null
                pendingDraft = PendingWhatsAppDraft(
                    contactName = cleanContact,
                    phoneNumber = null,
                    message = cleanMessage
                )
                return "Draft ready for $cleanContact. Note: Contacts permission is needed for automatic phone resolution. Should I send it?"
            }
        }
    }

    fun handleDisambiguationChoice(userChoice: String, pendingMessage: String = ""): String {
        val candidates = disambiguationCandidates ?: return "No contact disambiguation in progress."
        val choice = userChoice.trim()
        if (choice.isBlank()) {
            return "Please specify the contact's name. Options: " +
                candidates.take(5).joinToString(", ") { it.name }
        }

        val exactMatches = candidates.filter { it.name.equals(choice, ignoreCase = true) }
        val partialMatches = if (exactMatches.isEmpty()) candidates.filter {
            it.name.contains(choice, ignoreCase = true) || choice.contains(it.name, ignoreCase = true)
        } else emptyList()
        val matching = if (exactMatches.isNotEmpty()) exactMatches else partialMatches
        val chosen = matching.singleOrNull()
        if (chosen == null) {
            return "I couldn't uniquely identify that contact. Please specify the full name. Options: " +
                candidates.take(5).joinToString(", ") { it.name }
        }

        disambiguationCandidates = null
        val msg = pendingMessage.takeIf { it.isNotBlank() } ?: pendingDisambiguationMessage.orEmpty()
        if (msg.isBlank()) return "Which message would you like to send to ${chosen.name}?"
        pendingDisambiguationMessage = null
        pendingDraft = PendingWhatsAppDraft(
            contactName = chosen.name,
            phoneNumber = chosen.phoneNumber,
            message = msg
        )
        return "Draft ready for ${chosen.name}. Please review it. Should I send it?"
    }

    fun sendMessageDirectly(targetContact: String, messageText: String): String {
        val cleanContact = targetContact.trim()
        val cleanMessage = messageText.trim()
        if (cleanContact.isBlank()) return "Who would you like to message on WhatsApp, sir?"
        if (cleanMessage.isBlank()) return "What message would you like to send to $cleanContact?"

        // Creating a draft must never dispatch an external message. The separate
        // ConfirmAction command is the only path that may launch the send workflow.
        return createDraft(cleanContact, cleanMessage)
    }

    fun confirmAndSend(): String {
        val draft = pendingDraft ?: return "There is no draft waiting to be sent."
        val message = draft.message
        val phone = draft.phoneNumber

        return try {
            val rawPhone = phone?.takeIf { it.isNotBlank() }
                ?: return "I couldn't send this message because the contact has no saved phone number."
            val cleanPhone = normalizeWhatsAppNumber(rawPhone)
                ?: return "I couldn't determine a valid international phone number for ${draft.contactName}. Please check the contact's country code."
            val targetPkg = when {
                isPackageInstalled(WHATSAPP_PACKAGE) -> WHATSAPP_PACKAGE
                isPackageInstalled("com.whatsapp.w4b") -> "com.whatsapp.w4b"
                else -> return "WhatsApp is not installed. Your draft is still saved."
            }
            val encodedMessage = URLEncoder.encode(message, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMessage"
            )).apply {
                setPackage(targetPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val launched = ActivityLauncherHelper.startActivitySafely(context, intent)
            if (!launched) {
                return "I couldn't open WhatsApp. Your draft is still saved; nothing was confirmed as sent."
            }
            // Arm the accessibility send only after WhatsApp actually opened.
            JarvisAccessibilityService.triggerWhatsAppSend(message)

            val recipient = draft.contactName
            // Clear the in-flight draft after handoff to prevent duplicate confirmations.
            pendingDraft = null
            "Opened WhatsApp for $recipient with the approved message. I cannot verify from here whether WhatsApp completed sending it."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open WhatsApp for the approved draft", e)
            "Failed to open WhatsApp. Your draft is still saved and no send was verified."
        }
    }

    private fun normalizeWhatsAppNumber(rawNumber: String): String? {
        val cleaned = rawNumber.trim()
        if (cleaned.isBlank()) return null
        val region = if (android.os.Build.VERSION.SDK_INT >= 24) {
            context.resources.configuration.locales[0]?.country.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale?.country.orEmpty()
        }.ifBlank { Locale.getDefault().country }
        val e164 = region.takeIf { it.isNotBlank() }?.let {
            PhoneNumberUtils.formatNumberToE164(cleaned, it)
        }
        if (!e164.isNullOrBlank()) return e164.filter(Char::isDigit).takeIf { it.length in 8..15 }
        if (cleaned.startsWith("+")) {
            return cleaned.filter(Char::isDigit).takeIf { it.length in 8..15 }
        }
        return null
    }

    private fun isPackageInstalled(pkgName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkgName, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    fun cancelDraft(): String {
        val draft = pendingDraft
        val wasResolvingContact = !disambiguationCandidates.isNullOrEmpty()
        if (draft == null && !wasResolvingContact) return "No active draft to cancel."

        val name = draft?.contactName
        pendingDraft = null
        disambiguationCandidates = null
        pendingDisambiguationMessage = null
        return if (name != null) {
            "Draft cancelled for $name. I did not send it."
        } else {
            "Contact selection cancelled. I did not send a message."
        }
    }

    fun makeCall(targetContact: String, isVideo: Boolean): String {
        val cleanContact = targetContact.trim()
        if (cleanContact.isBlank()) {
            return "Who would you like to call on WhatsApp?"
        }

        val resolution = contactResolver.resolveContact(cleanContact)
        val contact = when (resolution) {
            is ContactResolutionResult.SingleMatch -> resolution.contact
            is ContactResolutionResult.MultipleMatches -> {
                disambiguationCandidates = resolution.candidates
                val names = resolution.candidates.take(3).map { it.name }.joinToString(" and ")
                return "Which $cleanContact? I found $names."
            }
            else -> null
        }

        val callType = if (isVideo) "video call" else "voice call"

        if (contact == null || contact.phoneNumber.isBlank()) {
            return "I couldn't find a phone number for $cleanContact to initiate a WhatsApp $callType."
        }

        val rawDigits = contact.phoneNumber.replace(Regex("[^0-9]"), "")
        val cleanPhone = when {
            rawDigits.length == 10 -> "91$rawDigits"
            rawDigits.length == 11 && rawDigits.startsWith("0") -> "91${rawDigits.substring(1)}"
            else -> rawDigits
        }

        return try {
            // Arm accessibility service to click top action bar call icon & confirm prompt
            JarvisAccessibilityService.triggerWhatsAppCall(isVideo)

            val mimeType = if (isVideo) {
                "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
            } else {
                "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
            }

            val callDataUri = getWhatsAppCallDataUri(contact.id, mimeType)
            val intent = if (callDataUri != null) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(callDataUri, mimeType)
                    setPackage(WHATSAPP_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone")).apply {
                    setPackage(WHATSAPP_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            ActivityLauncherHelper.startActivitySafely(context, intent)
            "Starting WhatsApp $callType to ${contact.name}."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WhatsApp call: ${e.message}", e)
            "Could not initiate WhatsApp call."
        }
    }

    /**
     * Deletes the most recent message in a WhatsApp chat.
     * - If [targetContact] is given, opens that contact's chat first (via the WhatsApp
     *   deep link, same as sending a message) then arms the accessibility service to
     *   long-press the last bubble and pick the delete option.
     * - If [targetContact] is blank/null, it operates on whichever WhatsApp chat is
     *   already open on screen (e.g. right after sending a message and saying
     *   "delete that message").
     */
    fun deleteLastMessage(targetContact: String?, forEveryone: Boolean): String {
        val cleanContact = targetContact?.trim().orEmpty()
        val callType = if (forEveryone) "for everyone" else "for me"

        if (cleanContact.isBlank()) {
            return try {
                // Arm accessibility BEFORE bringing WhatsApp to the foreground so it's
                // ready to act on whatever chat is currently visible.
                JarvisAccessibilityService.triggerWhatsAppDelete(forEveryone)
                val launchIntent = context.packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                if (launchIntent != null) {
                    ActivityLauncherHelper.startActivitySafely(context, launchIntent)
                }
                "Deleting the last message $callType."
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete WhatsApp message: ${e.message}", e)
                "Could not open WhatsApp to delete the message."
            }
        }

        val resolution = contactResolver.resolveContact(cleanContact)
        val phoneCandidate = when (resolution) {
            is ContactResolutionResult.SingleMatch -> resolution.contact.phoneNumber
            is ContactResolutionResult.MultipleMatches -> {
                disambiguationCandidates = resolution.candidates
                val names = resolution.candidates.take(3).map { it.name }.joinToString(" and ")
                return "Which $cleanContact? I found $names."
            }
            else -> null
        }

        val rawDigits = (phoneCandidate ?: cleanContact).replace(Regex("[^0-9]"), "")
        if (rawDigits.isBlank()) {
            return "I couldn't find a phone number for $cleanContact to open that chat."
        }
        val cleanPhone = when {
            rawDigits.length == 10 -> "91$rawDigits"
            rawDigits.length == 11 && rawDigits.startsWith("0") -> "91${rawDigits.substring(1)}"
            else -> rawDigits
        }

        return try {
            JarvisAccessibilityService.triggerWhatsAppDelete(forEveryone)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone")).apply {
                setPackage(WHATSAPP_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ActivityLauncherHelper.startActivitySafely(context, intent)
            "Deleting the last message $callType in the chat with $cleanContact."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open chat to delete WhatsApp message: ${e.message}", e)
            "Could not open the chat with $cleanContact to delete the message."
        }
    }

    private fun getWhatsAppCallDataUri(contactId: String, mimeType: String): Uri? {
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, mimeType),
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val idIdx = it.getColumnIndex(ContactsContract.Data._ID)
                    if (idIdx >= 0) {
                        val rowId = it.getLong(idIdx)
                        return Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, rowId.toString())
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
