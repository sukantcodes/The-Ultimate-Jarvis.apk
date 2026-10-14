package com.jarvis.assistant.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Dual-SIM helpers: list the active SIMs, look up a saved contact, and place a call on a chosen SIM.
 * Single-SIM phones never use this path; they keep using the existing ContactCaller.
 */
object SimCallHelper {
    private const val TAG = "SimCallHelper"

    data class SimOption(val slot: Int, val handle: PhoneAccountHandle, val label: String)

    sealed class ContactLookup {
        object NoPermission : ContactLookup()
        object NoMatch : ContactLookup()
        data class Multiple(val names: List<String>) : ContactLookup()
        data class Found(val name: String, val number: String) : ContactLookup()
    }

    fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Returns the SIMs that can place calls, sorted by slot (slot 0 = SIM 1). Empty when it cannot be read. */
    @Suppress("MissingPermission", "DEPRECATION")
    fun activeSims(context: Context): List<SimOption> {
        if (!hasPhoneStatePermission(context)) return emptyList()
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return emptyList()
            val handles = telecom.callCapablePhoneAccounts ?: return emptyList()
            val subs = try {
                (context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager)
                    ?.activeSubscriptionInfoList
            } catch (e: SecurityException) {
                null
            }
            val options = handles.mapIndexed { index, handle ->
                val sub = subs?.firstOrNull { s ->
                    handle.id == s.subscriptionId.toString() ||
                        (!s.iccId.isNullOrBlank() && handle.id.contains(s.iccId))
                }
                val slot = sub?.simSlotIndex ?: index
                val carrier = sub?.carrierName?.toString()?.trim().orEmpty()
                val accountLabel = telecom.getPhoneAccount(handle)?.label?.toString()?.trim().orEmpty()
                SimOption(slot, handle, carrier.ifBlank { accountLabel })
            }
            options.sortedBy { it.slot }
        } catch (e: SecurityException) {
            Log.w(TAG, "SIM list needs phone permission: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "SIM list failed: ${e.message}")
            emptyList()
        }
    }

    /** "1", "sim 1", "first", "ek", "2", "second", "dusra" ... -> 1 or 2, otherwise null. */
    fun parseSimNumber(raw: String): Int? {
        val tokens = raw.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val one = setOf("1", "sim1", "one", "first", "1st", "ek", "pehla", "pehle")
        val two = setOf("2", "sim2", "two", "second", "2nd", "dusra", "doosra", "dusre")
        return when {
            tokens.any { it in one } -> 1
            tokens.any { it in two } -> 2
            else -> null
        }
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]+"), " ").replace(Regex("\\s+"), " ").trim()

    fun lookupContact(context: Context, spoken: String): ContactLookup {
        val typed = spoken.trim()
        if (typed.count { it.isDigit() } >= 5 && typed.none { it.isLetter() }) {
            return ContactLookup.Found(typed, typed.filter { it.isDigit() || it == '+' })
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ContactLookup.NoPermission
        }
        val query = normalize(spoken)
        if (query.isEmpty()) return ContactLookup.NoMatch

        val scored = LinkedHashMap<String, Pair<Int, String>>()
        try {
            val phone = ContactsContract.CommonDataKinds.Phone
            context.contentResolver.query(
                phone.CONTENT_URI,
                arrayOf(phone.DISPLAY_NAME, phone.NUMBER),
                null, null, null
            )?.use { c ->
                val nameIdx = c.getColumnIndex(phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(phone.NUMBER)
                if (nameIdx < 0 || numIdx < 0) return@use
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val number = c.getString(numIdx) ?: continue
                    val n = normalize(name)
                    val score = when {
                        n == query -> 3
                        n.startsWith(query) -> 2
                        (" " + n + " ").contains(" " + query + " ") -> 1
                        else -> 0
                    }
                    if (score > 0) {
                        val prev = scored[name]
                        if (prev == null || score > prev.first) scored[name] = Pair(score, number)
                    }
                }
            }
        } catch (e: SecurityException) {
            return ContactLookup.NoPermission
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed: ${e.message}")
        }
        if (scored.isEmpty()) return ContactLookup.NoMatch
        val best = scored.values.maxOf { it.first }
        val top = scored.filter { it.value.first == best }
        return if (top.size == 1) {
            val entry = top.entries.first()
            ContactLookup.Found(entry.key, entry.value.second)
        } else {
            ContactLookup.Multiple(top.keys.take(5))
        }
    }

    @Suppress("MissingPermission")
    fun placeCall(context: Context, number: String, sim: SimOption): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim.handle)
            }
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "placeCall denied: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "placeCall failed: ${e.message}")
            false
        }
    }
}
