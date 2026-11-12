package com.jarvis.assistant.notifications

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification

enum class MessageApp(val displayName: String) {
    WHATSAPP("WhatsApp"),
    INSTAGRAM("Instagram"),
    INSTAZEN("Instazen"),
    TELEGRAM("Telegram"),
    SMS("Messages"),
    SLACK("Slack"),
    DISCORD("Discord")
}

data class IncomingMessage(
    val app: MessageApp,
    val sender: String,
    val group: String? = null,
    val body: String = "",
    val isUrgent: Boolean = false,
    val isSpam: Boolean = false,
    val replyAction: Notification.Action? = null,
    val sbnKey: String = ""
)

/**
 * Classifies incoming notifications from supported messaging applications,
 * extracts clean sender/group identities and preview text, detects urgency & spam,
 * and retrieves direct reply RemoteInput actions for autonomous voice dispatch.
 */
object MessageNotificationParser {
    private val whatsappPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val instagramPackages = setOf("com.instagram.android", "com.instagram.lite")
    private val telegramPackages = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.beta",
        "org.telegram.plus",
        "org.thunderdog.challegram"
    )
    private val smsPackages = setOf(
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.simplemobiletools.sms.messenger"
    )
    private val slackPackages = setOf("com.Slack")
    private val discordPackages = setOf("com.discord")

    private val labelCache = HashMap<String, MessageApp?>()

    private val summaryPatterns = listOf(
        Regex("(?i)^\\d+\\s+(new\\s+)?(messages?|chats?)\\b"),
        Regex("(?i)checking for new messages"),
        Regex("(?i)waiting for this message"),
        Regex("(?i)whatsapp web is"),
        Regex("(?i)backup in progress"),
        Regex("(?i)^new messages?$")
    )

    private val urgentPatterns = listOf(
        Regex("(?i)\\b(urgent|emergency|asap|immediately|important|alert|critical|sos)\\b"),
        Regex("(?i)\\b(call\\s+me|reach\\s+me|call\\s+back|need\\s+you\\s+now|call\\s+immediately)\\b")
    )

    private val spamPatterns = listOf(
        Regex("(?i)\\b\\d+%\\s*off\\b"),
        Regex("(?i)\\bflat\\s+\\d+%?\\s*off\\b"),
        Regex("(?i)\\b(cashback|coupon|coupons|promo\\s*code|use\\s*code|discount|discounts)\\b"),
        Regex("(?i)\\b(limited\\s*time\\s*offer|limited\\s*offer|exclusive\\s*deal|special\\s*deal)\\b"),
        Regex("(?i)\\b(congratulations\\s*you('?ve)?\\s*won|win\\s*cash|win\\s*prize|win\\s*up\\s*to)\\b"),
        Regex("(?i)\\b(free\\s*voucher|reward\\s*points|apply\\s*coupon|sale\\s*starts|mega\\s*sale)\\b"),
        Regex("(?i)\\b(unbeatable\\s*price|buy\\s*\\d+\\s*get\\s*\\d+|click\\s*here\\s*to\\s*claim)\\b")
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
        if (packageName == context.packageName) return null
        if (packageName in whatsappPackages) return MessageApp.WHATSAPP
        if (packageName.contains("instazen", ignoreCase = true)) return MessageApp.INSTAZEN
        if (packageName in instagramPackages) return MessageApp.INSTAGRAM
        if (packageName in telegramPackages || packageName.contains("telegram", ignoreCase = true)) return MessageApp.TELEGRAM
        if (packageName in smsPackages) return MessageApp.SMS
        if (packageName in slackPackages || packageName.contains("slack", ignoreCase = true)) return MessageApp.SLACK
        if (packageName in discordPackages || packageName.contains("discord", ignoreCase = true)) return MessageApp.DISCORD

        synchronized(labelCache) {
            if (labelCache.containsKey(packageName)) return labelCache[packageName]
        }

        val result = try {
            val pm = context.packageManager
            val label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().lowercase()
            when {
                label.contains("whatsapp") -> MessageApp.WHATSAPP
                label.contains("instazen") -> MessageApp.INSTAZEN
                label.contains("instagram") -> MessageApp.INSTAGRAM
                label.contains("telegram") -> MessageApp.TELEGRAM
                label.contains("slack") -> MessageApp.SLACK
                label.contains("discord") -> MessageApp.DISCORD
                label.contains("messages") || label.contains("sms") -> MessageApp.SMS
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        synchronized(labelCache) { labelCache[packageName] = result }
        return result
    }

    fun clean(raw: String): String =
        raw.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e]"), "")
            .replace(Regex("\\s*\\(\\d+\\s+messages?\\)\\s*$"), "")
            .replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
            .trim()

    fun isSummary(title: String, text: String): Boolean =
        summaryPatterns.any { it.containsMatchIn(text) || it.containsMatchIn(title) }

    fun isUrgent(title: String, body: String): Boolean {
        val combined = "$title $body"
        return urgentPatterns.any { it.containsMatchIn(combined) }
    }

    fun isSpam(title: String, body: String): Boolean {
        val combined = "$title $body"
        return spamPatterns.any { it.containsMatchIn(combined) }
    }

    @Suppress("DEPRECATION")
    private fun lastMessageSender(extras: Bundle): String? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = messages.lastOrNull() as? Bundle ?: return null
        return last.getCharSequence("sender")?.toString()?.let { clean(it) }
    }

    @Suppress("DEPRECATION")
    private fun lastMessageText(extras: Bundle): String? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = messages.lastOrNull() as? Bundle ?: return null
        return last.getCharSequence("text")?.toString()?.let { clean(it) }
    }

    fun extractBody(extras: Bundle): String {
        val msgStyleText = lastMessageText(extras)
        if (!msgStyleText.isNullOrBlank()) return msgStyleText

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        if (!bigText.isNullOrBlank()) return clean(bigText)

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        if (!text.isNullOrBlank()) return clean(text)

        return ""
    }

    fun extractReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val remoteInputs = action.remoteInputs
            if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                return action
            }
        }
        return null
    }

    /** Returns who the message is from and its triage metadata, or null when not a chat message. */
    fun parse(context: Context, sbn: StatusBarNotification): IncomingMessage? {
        val notification = sbn.notification ?: return null
        if (!looksLikeMessage(notification)) return null
        val app = classify(context, sbn.packageName) ?: return null
        val extras = notification.extras ?: return null

        val title = clean(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
        val body = extractBody(extras)
        if (title.isBlank()) return null
        if (title.equals(app.displayName, ignoreCase = true) || title.equals("WhatsApp Business", ignoreCase = true)) return null
        if (isSummary(title, body)) return null

        val replyAction = extractReplyAction(notification)
        val urgent = isUrgent(title, body)
        val spam = isSpam(title, body)
        val sbnKey = sbn.key ?: ""

        // Some versions title group messages "Sender @ Group".
        if (title.contains(" @ ")) {
            val parts = title.split(" @ ", limit = 2)
            val sender = clean(parts[0])
            val group = clean(parts[1])
            if (sender.isNotBlank() && group.isNotBlank()) {
                return IncomingMessage(
                    app = app,
                    sender = sender,
                    group = group,
                    body = body,
                    isUrgent = urgent,
                    isSpam = spam,
                    replyAction = replyAction,
                    sbnKey = sbnKey
                )
            }
        }

        val conversationTitle = clean(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty())
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || conversationTitle.isNotBlank()
        if (isGroup) {
            val groupName = if (conversationTitle.isNotBlank()) conversationTitle else title
            val sender = lastMessageSender(extras)?.takeIf { it.isNotBlank() }
                ?: body.substringBefore(":", "").trim().takeIf { it.isNotEmpty() && it.length <= 40 }
                ?: "someone"
            return IncomingMessage(
                app = app,
                sender = sender,
                group = groupName,
                body = body,
                isUrgent = urgent,
                isSpam = spam,
                replyAction = replyAction,
                sbnKey = sbnKey
            )
        }

        return IncomingMessage(
            app = app,
            sender = title,
            group = null,
            body = body,
            isUrgent = urgent,
            isSpam = spam,
            replyAction = replyAction,
            sbnKey = sbnKey
        )
    }
}
