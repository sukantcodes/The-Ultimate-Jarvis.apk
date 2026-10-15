package com.jarvis.assistant.notifications

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification

enum class MessageApp(val displayName: String) {
    WHATSAPP("WhatsApp"),
    INSTAGRAM("Instagram"),
    INSTAZEN("Instazen")
}

data class IncomingMessage(val app: MessageApp, val sender: String, val group: String?)

/**
 * Decides whether a notification is a real incoming chat message from WhatsApp, Instagram or Instazen, and works out
 * who sent it. Ongoing calls, "2 messages from 2 chats" summaries, and app status notices are ignored.
 */
object MessageNotificationParser {
    private val whatsappPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val instagramPackages = setOf("com.instagram.android", "com.instagram.lite")
    private val labelCache = HashMap<String, MessageApp?>()

    private val summaryPatterns = listOf(
        Regex("(?i)^\\d+\\s+(new\\s+)?(messages?|chats?)\\b"),
        Regex("(?i)checking for new messages"),
        Regex("(?i)waiting for this message"),
        Regex("(?i)whatsapp web is"),
        Regex("(?i)backup in progress"),
        Regex("(?i)^new messages?$")
    )

    /** True for a normal message notification (not a summary, an ongoing item, a call or a progress notice). */
    fun looksLikeMessage(notification: Notification): Boolean {
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (notification.category == Notification.CATEGORY_MESSAGE) return true
        val template = notification.extras?.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        return template.endsWith("MessagingStyle")
    }

    private fun classify(context: Context, packageName: String): MessageApp? {
        if (packageName in whatsappPackages) return MessageApp.WHATSAPP
        if (packageName.contains("instazen", ignoreCase = true)) return MessageApp.INSTAZEN
        if (packageName in instagramPackages) return MessageApp.INSTAGRAM
        if (packageName == context.packageName) return null
        synchronized(labelCache) {
            if (labelCache.containsKey(packageName)) return labelCache[packageName]
        }
        // Instazen may use any package name, so for other message apps look at the name shown to the user.
        val result = try {
            val pm = context.packageManager
            val label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            when {
                label.contains("instazen", ignoreCase = true) -> MessageApp.INSTAZEN
                packageName.contains("instagram", ignoreCase = true) -> MessageApp.INSTAGRAM
                else -> null
            }
        } catch (e: Exception) {
            null
        }
        synchronized(labelCache) { labelCache[packageName] = result }
        return result
    }

    private fun clean(raw: String): String =
        raw.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e]"), "")
            .replace(Regex("\\s*\\(\\d+\\s+messages?\\)\\s*$"), "")
            .replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
            .trim()

    private fun isSummary(title: String, text: String): Boolean =
        summaryPatterns.any { it.containsMatchIn(text) || it.containsMatchIn(title) }

    @Suppress("DEPRECATION")
    private fun lastMessageSender(extras: Bundle): String? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = messages.lastOrNull() as? Bundle ?: return null
        return last.getCharSequence("sender")?.toString()?.let { clean(it) }
    }

    /** Returns who the message is from, or null when the notification is not an incoming message from a supported app. */
    fun parse(context: Context, sbn: StatusBarNotification): IncomingMessage? {
        val notification = sbn.notification ?: return null
        if (!looksLikeMessage(notification)) return null
        val app = classify(context, sbn.packageName) ?: return null
        val extras = notification.extras ?: return null

        val title = clean(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        if (title.isBlank()) return null
        if (title.equals(app.displayName, ignoreCase = true) || title.equals("WhatsApp Business", ignoreCase = true)) return null
        if (isSummary(title, text)) return null

        // Some versions title group messages "Sender @ Group".
        if (title.contains(" @ ")) {
            val parts = title.split(" @ ", limit = 2)
            val sender = clean(parts[0])
            val group = clean(parts[1])
            if (sender.isNotBlank() && group.isNotBlank()) return IncomingMessage(app, sender, group)
        }

        val conversationTitle = clean(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty())
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || conversationTitle.isNotBlank()
        if (isGroup) {
            val groupName = if (conversationTitle.isNotBlank()) conversationTitle else title
            val sender = lastMessageSender(extras)?.takeIf { it.isNotBlank() }
                ?: text.substringBefore(":", "").trim().takeIf { it.isNotEmpty() && it.length <= 40 }
                ?: "someone"
            return IncomingMessage(app, sender, groupName)
        }
        return IncomingMessage(app, title, null)
    }
}
