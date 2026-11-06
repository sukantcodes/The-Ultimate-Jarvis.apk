package com.jarvis.assistant.automation.contacts

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.util.Patterns

/** Android-native contact editor and platform-authorized blocked-number integration. */
object ContactManagementManager {
    data class Outcome(val success: Boolean, val message: String, val requiresUserAction: Boolean = false)

    fun openContactEditor(context: Context, name: String, phoneNumber: String, email: String? = null): Outcome {
        val cleanName = name.trim()
        val cleanPhone = PhoneNumberInputValidator.normalize(phoneNumber)
            ?: return Outcome(false, "That phone number is invalid. Please provide a valid number.")
        if (cleanName.isBlank() || cleanName.length > 100) return Outcome(false, "Please provide a contact name between 1 and 100 characters.")
        val cleanEmail = email?.trim()?.takeIf { it.isNotBlank() }
        if (cleanEmail != null && !Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) return Outcome(false, "That email address is invalid.")
        return try {
            val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
                putExtra(ContactsContract.Intents.Insert.NAME, cleanName)
                putExtra(ContactsContract.Intents.Insert.PHONE, cleanPhone)
                putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                if (cleanEmail != null) {
                    putExtra(ContactsContract.Intents.Insert.EMAIL, cleanEmail)
                    putExtra(ContactsContract.Intents.Insert.EMAIL_TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) Outcome(false, "No compatible Contacts app is available.")
            else {
                context.startActivity(intent)
                Outcome(true, "Opened Android's contact editor for $cleanName. Review the details and tap Save to finish.", true)
            }
        } catch (e: Exception) {
            Outcome(false, "Could not open the contact editor: ${e.message ?: "unknown Android error"}.")
        }
    }

    fun manageBlockedContact(context: Context, action: String, contactName: String, phoneNumber: String): Outcome {
        val op = action.trim().lowercase()
        if (op != "block" && op != "unblock") return Outcome(false, "Choose either block or unblock.")
        val number = resolveNumber(context, contactName, phoneNumber) ?: return resolveFailure(context, contactName, phoneNumber)
        if (PhoneNumberUtils.isEmergencyNumber(number)) return Outcome(false, "Emergency service numbers cannot be blocked.")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return Outcome(false, "Blocked-number management requires Android 7.0 or newer.")
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return Outcome(false, "Android's phone service is unavailable.")
        val supported = try { BlockedNumberContract.canCurrentUserBlockNumbers(context) } catch (_: Exception) { false }
        if (!supported) return Outcome(false, "Android does not expose blocked-number management for this user profile. No change was made.")
        if (telecom.defaultDialerPackage == context.packageName) return applyAndVerify(context, op, number)

        // Android restricts direct provider writes to privileged/default phone,
        // SMS and carrier apps. Use the official system manager; never bypass it.
        return try {
            val intent = telecom.createManageBlockedNumbersIntent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (intent.resolveActivity(context.packageManager) == null) Outcome(false, "Android did not provide a blocked-number manager.")
            else {
                context.startActivity(intent)
                Outcome(true, "Opened Android's blocked-number manager. Add or remove $number there to finish; Jarvis cannot apply the change directly because it is not the default Phone app.", true)
            }
        } catch (e: Exception) {
            Outcome(false, "Could not open Android's blocked-number manager: ${e.message ?: "unknown Android error"}.")
        }
    }

    private fun resolveNumber(context: Context, name: String, raw: String): String? {
        if (raw.isNotBlank()) return PhoneNumberInputValidator.normalize(raw)
        if (name.isBlank()) return null
        return when (val r = ContactResolver(context).resolveContact(name)) {
            is ContactResolutionResult.SingleMatch -> PhoneNumberInputValidator.normalize(r.contact.phoneNumber)
            else -> null
        }
    }

    private fun resolveFailure(context: Context, name: String, raw: String): Outcome {
        if (raw.isNotBlank() && PhoneNumberInputValidator.normalize(raw) == null) return Outcome(false, "That phone number is invalid. No change was made.")
        if (name.isBlank()) return Outcome(false, "Provide a saved contact name or exact phone number.")
        return when (val r = ContactResolver(context).resolveContact(name)) {
            ContactResolutionResult.PermissionDenied -> Outcome(false, "Contacts permission is required to find that saved contact.")
            ContactResolutionResult.NoMatch -> Outcome(false, "I could not find a saved contact named $name.")
            is ContactResolutionResult.MultipleMatches -> Outcome(false, "More than one contact matches $name: ${r.candidates.joinToString { it.name }}. Please specify the exact contact.")
            is ContactResolutionResult.SingleMatch -> Outcome(false, "That contact does not have a valid phone number.")
        }
    }

    private fun applyAndVerify(context: Context, action: String, number: String): Outcome = try {
        val before = BlockedNumberContract.isBlocked(context, number)
        if (action == "block" && before) return Outcome(true, "That number is already blocked.")
        if (action == "unblock" && !before) return Outcome(true, "That number is not currently blocked.")
        if (action == "block") {
            val values = ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
            if (context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values) == null)
                return Outcome(false, "Android did not accept the block request.")
        } else {
            BlockedNumberContract.unblock(context, number)
        }
        val after = BlockedNumberContract.isBlocked(context, number)
        val verified = if (action == "block") after else !after
        if (verified) Outcome(true, if (action == "block") "Blocked $number and verified the system blocked list." else "Unblocked $number and verified the system blocked list.")
        else Outcome(false, "Android did not confirm the $action operation. Check the system blocked list.")
    } catch (_: SecurityException) {
        Outcome(false, "Android denied direct blocked-list access. No change was confirmed; use the Phone app's blocked-number settings.")
    } catch (e: Exception) {
        Outcome(false, "Could not $action $number: ${e.message ?: "unknown Android error"}.")
    }
}

/** Pure validation shared with JVM unit tests. */
internal object PhoneNumberInputValidator {
    fun normalize(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > 32) return null
        if (value.any { !it.isDigit() && it !in "+-(). /" }) return null
        if (value.count { it == '+' } > 1 || ('+' in value && !value.startsWith("+"))) return null
        if (value.count { it.isDigit() } !in 3..20) return null
        return value
    }
}
