package com.jarvis.assistant.notifications

import android.content.Context
import org.json.JSONArray

/**
 * Manages configuration and VIP preferences for Autonomous Notification Triage,
 * voice dispatch, spam filtering, and incoming chat message announcements.
 */
class MessageAlertSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_message_alerts", Context.MODE_PRIVATE)

    companion object {
        const val TRIAGE_ALL_MESSAGES = 0
        const val TRIAGE_SMART_URGENT = 1
        const val TRIAGE_VIP_ONLY = 2

        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_USE_JARVIS_VOICE = "use_jarvis_voice"
        private const val KEY_TRIAGE_MODE = "triage_mode"
        private const val KEY_READ_MESSAGE_BODY = "read_message_body"
        private const val KEY_ENABLE_VOICE_REPLY = "enable_voice_reply"
        private const val KEY_SPAM_FILTER_ENABLED = "spam_filter_enabled"
        private const val KEY_OWNER_VOICE_REPLY_GATED = "owner_voice_reply_gated"
        private const val KEY_VIP_CONTACTS_JSON = "vip_contacts_json"
        private const val KEY_VIP_CONTACTS_LEGACY = "vip_contacts"
    }

    /** Announce incoming chat messages. Defaults to on. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** Status string describing what the notification triage engine did last. */
    var lastStatus: String
        get() = prefs.getString(KEY_LAST_STATUS, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_STATUS, value).apply()
        }

    /** Use Jarvis's own neural voice when an active conversation session is open. */
    var useJarvisVoice: Boolean
        get() = prefs.getBoolean(KEY_USE_JARVIS_VOICE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_JARVIS_VOICE, value).apply()
        }

    /**
     * Triage mode:
     * 0 = [TRIAGE_ALL_MESSAGES] (Announce all non-spam messages)
     * 1 = [TRIAGE_SMART_URGENT] (Only announce VIPs or urgent keywords) - Default
     * 2 = [TRIAGE_VIP_ONLY] (Only announce messages from VIP whitelist)
     */
    var triageMode: Int
        get() = prefs.getInt(KEY_TRIAGE_MODE, TRIAGE_SMART_URGENT)
        set(value) {
            prefs.edit().putInt(KEY_TRIAGE_MODE, value).apply()
        }

    /** Whether to speak the message text preview or only announce the sender. */
    var readMessageBody: Boolean
        get() = prefs.getBoolean(KEY_READ_MESSAGE_BODY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_READ_MESSAGE_BODY, value).apply()
        }

    /** Whether Jarvis prompts 'Say reply to respond' and registers pending voice replies. */
    var enableVoiceReply: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_VOICE_REPLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLE_VOICE_REPLY, value).apply()
        }

    /** Whether promotional and marketing messages are automatically filtered out. */
    var spamFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPAM_FILTER_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SPAM_FILTER_ENABLED, value).apply()
        }

    /** Whether voice reply execution requires verified owner voice biometrics. */
    var ownerVoiceReplyGated: Boolean
        get() = prefs.getBoolean(KEY_OWNER_VOICE_REPLY_GATED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_OWNER_VOICE_REPLY_GATED, value).apply()
        }

    /** Set of VIP contact names or handles. */
    var vipContacts: Set<String>
        get() {
            val rawJson = prefs.getString(KEY_VIP_CONTACTS_JSON, null)
            if (!rawJson.isNullOrBlank()) {
                return try {
                    val array = JSONArray(rawJson)
                    val set = linkedSetOf<String>()
                    for (i in 0 until array.length()) {
                        val item = array.optString(i, "").trim()
                        if (item.isNotEmpty()) set.add(item)
                    }
                    set
                } catch (_: Exception) {
                    emptySet()
                }
            }
            return prefs.getStringSet(KEY_VIP_CONTACTS_LEGACY, emptySet())?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        }
        set(value) {
            val cleanList = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val array = JSONArray()
            cleanList.forEach { array.put(it) }
            prefs.edit()
                .putString(KEY_VIP_CONTACTS_JSON, array.toString())
                .putStringSet(KEY_VIP_CONTACTS_LEGACY, cleanList.toSet())
                .apply()
        }

    /** Returns all VIP contact names as a sorted list. */
    fun getVipList(): List<String> = vipContacts.toList().sorted()

    /** Adds a VIP contact name. Returns true if newly added. */
    fun addVip(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val current = vipContacts.toMutableSet()
        val alreadyExists = current.any { it.equals(clean, ignoreCase = true) }
        if (!alreadyExists) {
            current.add(clean)
            vipContacts = current
            return true
        }
        return false
    }

    /** Removes a VIP contact name. Returns true if successfully removed. */
    fun removeVip(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val current = vipContacts.toMutableSet()
        val removed = current.removeAll { it.equals(clean, ignoreCase = true) }
        if (removed) {
            vipContacts = current
            return true
        }
        return false
    }

    /**
     * Checks if a message sender or group name matches any VIP contact whitelist entry.
     * Matches case-insensitively, or if the sender contains the VIP name / VIP name contains sender.
     */
    fun isVip(sender: String): Boolean {
        val cleanSender = sender.trim()
        if (cleanSender.isEmpty()) return false
        val vips = vipContacts
        return vips.any { vip ->
            val cleanVip = vip.trim()
            cleanVip.isNotEmpty() && (
                cleanSender.equals(cleanVip, ignoreCase = true) ||
                cleanSender.contains(cleanVip, ignoreCase = true) ||
                cleanVip.contains(cleanSender, ignoreCase = true)
            )
        }
    }
}
