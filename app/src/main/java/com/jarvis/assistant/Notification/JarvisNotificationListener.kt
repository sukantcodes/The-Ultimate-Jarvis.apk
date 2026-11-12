package com.jarvis.assistant.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.assistant.activitylog.ActivityLog
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.automation.blinkit.BlinkitLinks
import com.jarvis.assistant.automation.blinkit.BlinkitNotificationTracker
import com.jarvis.assistant.automation.tasks.ScheduledTaskMemory
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.studio.AutomationEvents
import com.jarvis.assistant.studio.TriggerType
import com.jarvis.assistant.util.SpeechAnnouncer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Autonomous Notification Triage & Voice Dispatcher Service.
 * Listens for incoming chat messages across supported messaging apps,
 * applies cybernetic priority triage (VIP whitelist, urgency heuristics, spam filtering),
 * announces message previews aloud, and tracks RemoteInput reply actions for hands-free voice responses.
 */
class JarvisNotificationListener : NotificationListenerService() {
    private val settings by lazy { MessageAlertSettings(applicationContext) }
    private var fallbackAnnouncer: SpeechAnnouncer? = null

    private val lastAnnounced = HashMap<String, Long>()
    private val lastLogged = HashMap<String, Long>()
    private val lastContent = HashMap<String, Long>()
    private val lastEventDispatch = HashMap<String, Long>()

    data class PendingReply(
        val sender: String,
        val app: MessageApp,
        val replyAction: Notification.Action,
        val timestamp: Long
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "Could not handle notification: ${e.message}", e)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notificationExtras = sbn.notification?.extras
        val notificationTitle = notificationExtras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val notificationBody = notificationExtras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        if (BlinkitLinks.isBlinkitPackage(sbn.packageName)) {
            val orderUpdate = BlinkitNotificationTracker.onNotification(
                applicationContext, sbn.packageName, notificationTitle, notificationBody
            )
            if (orderUpdate != null) {
                ActivityLog.get(applicationContext).log(ActivityLog.TYPE_MESSAGE, orderUpdate)
                if (!JarvisConversationService.announceMessage(orderUpdate)) {
                    val announcer = fallbackAnnouncer ?: SpeechAnnouncer(applicationContext).also { fallbackAnnouncer = it }
                    announcer.say(orderUpdate)
                }
            }
        }

        ScheduledTaskMemory.onAppNotification(applicationContext, sbn.packageName, notificationTitle, notificationBody).forEach { update ->
            if (!JarvisConversationService.announceMessage(update)) {
                val announcer = fallbackAnnouncer ?: SpeechAnnouncer(applicationContext).also { fallbackAnnouncer = it }
                announcer.say(update)
            }
        }

        if (!settings.enabled) {
            note("message announcements are switched off")
            return
        }

        val message = MessageNotificationParser.parse(applicationContext, sbn)
        if (message == null) {
            val lowerPackage = sbn.packageName.lowercase()
            if (lowerPackage.contains("whatsapp") || lowerPackage.contains("insta") || lowerPackage.contains("telegram")) {
                note("ignored a notification from " + sbn.packageName + " (not recognised as a chat message)")
            }
            return
        }

        val now = System.currentTimeMillis()

        // Deduplication: The same message posted again is handled only once within the content window.
        val contentKey = sbn.packageName + "|" + message.sender + "|" + (message.group ?: "") + "|" + message.body
        synchronized(lastContent) {
            val seen = lastContent[contentKey]
            if (seen != null && now - seen < CONTENT_WINDOW_MS) return
            lastContent[contentKey] = now
            if (lastContent.size > 200) lastContent.entries.removeAll { now - it.value > CONTENT_WINDOW_MS }
        }

        // Triage Filtering Logic:
        // 1. Spam & Promotion Filter
        if (settings.spamFilterEnabled && message.isSpam) {
            note("filtered promotional message from " + message.sender)
            return
        }

        val isVip = settings.isVip(message.sender) || (message.group != null && settings.isVip(message.group))

        // 2. VIP Only Mode
        if (settings.triageMode == MessageAlertSettings.TRIAGE_VIP_ONLY && !isVip) {
            ActivityLog.get(applicationContext).log(
                ActivityLog.TYPE_MESSAGE,
                "Triage skipped non-VIP " + message.app.displayName + " message from " + message.sender
            )
            note("skipped non-VIP message from " + message.sender)
            return
        }

        // 3. Smart Urgent Mode (Announce VIPs or Urgent Messages)
        if (settings.triageMode == MessageAlertSettings.TRIAGE_SMART_URGENT && !isVip && !message.isUrgent) {
            ActivityLog.get(applicationContext).log(
                ActivityLog.TYPE_MESSAGE,
                "Triage skipped routine " + message.app.displayName + " message from " + message.sender
            )
            note("skipped routine message from " + message.sender)
            return
        }

        val senderKey = message.app.name + "|" + message.sender + "|" + (message.group ?: "")

        // Automations that start when a message arrives (one event per sender every 10 seconds).
        val shouldDispatch = synchronized(lastEventDispatch) {
            val seen = lastEventDispatch[senderKey]
            if (seen != null && now - seen < 10_000L) {
                false
            } else {
                lastEventDispatch[senderKey] = now
                true
            }
        }
        if (shouldDispatch) {
            AutomationEvents.dispatch(
                TriggerType.MESSAGE_RECEIVED,
                mapOf(
                    "sender" to message.sender,
                    "app" to message.app.displayName.lowercase(),
                    "app_name" to message.app.displayName,
                    "group" to (message.group ?: ""),
                    "body" to message.body,
                    "is_urgent" to message.isUrgent.toString()
                )
            )
        }

        // Remember who messaged for activity memory.
        val shouldLog = synchronized(lastLogged) {
            val seen = lastLogged[senderKey]
            if (seen != null && now - seen < LOG_WINDOW_MS) {
                false
            } else {
                lastLogged[senderKey] = now
                true
            }
        }
        if (shouldLog) {
            val where = if (message.group != null) " in the group " + message.group else ""
            val urgencyTag = if (message.isUrgent) " [URGENT]" else if (isVip) " [VIP]" else ""
            ActivityLog.get(applicationContext).log(
                ActivityLog.TYPE_MESSAGE,
                message.app.displayName + " message from " + message.sender + where + urgencyTag
            )
        }

        if (isSilentMessage(sbn)) {
            note("skipped a silent or muted " + message.app.displayName + " message")
            return
        }
        if (!canSpeakNow(sbn.packageName)) {
            note("skipped: you were on a call, in silent mode, or " + message.app.displayName + " was open")
            return
        }

        // One announcement per sender every 30 seconds to prevent flooding.
        val allowed = synchronized(lastAnnounced) {
            val seen = lastAnnounced[senderKey]
            if (seen != null && now - seen < ANNOUNCE_WINDOW_MS) {
                false
            } else {
                lastAnnounced[senderKey] = now
                true
            }
        }
        if (!allowed) return

        // Register pending voice reply if remote reply action is present and enabled.
        if (message.replyAction != null && settings.enableVoiceReply) {
            currentPendingReply = PendingReply(
                sender = message.sender,
                app = message.app,
                replyAction = message.replyAction,
                timestamp = now
            )
        }

        val spokenSender = MessageSpeechSanitizer.forAnnouncement(message.sender).ifBlank { "someone" }
        val spokenGroup = message.group?.let { MessageSpeechSanitizer.forAnnouncement(it) }

        val prefLang = try {
            com.jarvis.assistant.JarvisApp.instance.preferences.language
        } catch (_: Throwable) {
            "Auto"
        }
        val detectedLang = com.jarvis.assistant.util.MultilingualCommandHelper.resolveLanguage(applicationContext, message.body.ifBlank { message.sender })
        val effectiveLang = if (prefLang.isNotBlank() && !prefLang.equals("Auto", ignoreCase = true)) prefLang else detectedLang

        val displaySender = if (!spokenGroup.isNullOrBlank()) "$spokenSender in $spokenGroup" else spokenSender
        val text = com.jarvis.assistant.util.MultilingualCommandHelper.getLocalizedNotificationAnnouncement(
            sender = displaySender,
            appName = message.app.displayName,
            body = if (settings.readMessageBody) message.body else "",
            hasReplyAction = message.replyAction != null && settings.enableVoiceReply,
            language = effectiveLang
        )

        note("announced a " + message.app.displayName + " message from " + message.sender)
        if (!JarvisConversationService.announceMessage(text)) {
            val announcer = fallbackAnnouncer ?: SpeechAnnouncer(applicationContext).also { fallbackAnnouncer = it }
            announcer.say(text)
        }
    }

    private fun note(text: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        settings.lastStatus = "$time $text"
    }

    /** Muted chats and silent notification channels are not announced. */
    private fun isSilentMessage(sbn: StatusBarNotification): Boolean {
        return try {
            val ranking = Ranking()
            currentRanking.getRanking(sbn.key, ranking) && ranking.importance < NotificationManager.IMPORTANCE_DEFAULT
        } catch (e: Exception) {
            false
        }
    }

    /** No announcements during a phone call, in silent mode, or while that chat app is already on screen. */
    private fun canSpeakNow(messagePackage: String): Boolean {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio != null) {
            if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return false
            if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return false
        }
        val foreground = JarvisAccessibilityService.instance?.getActiveRootNode()?.packageName?.toString()
        return foreground != messagePackage
    }

    override fun onDestroy() {
        fallbackAnnouncer?.shutdown()
        fallbackAnnouncer = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JarvisNotifListener"
        private const val CONTENT_WINDOW_MS = 2 * 60 * 1000L
        private const val ANNOUNCE_WINDOW_MS = 30 * 1000L
        private const val LOG_WINDOW_MS = 5 * 60 * 1000L
        private const val REPLY_EXPIRATION_MS = 60_000L

        @Volatile
        var currentPendingReply: PendingReply? = null

        /** Checks if there is an active, unexpired pending reply waiting for user voice input. */
        fun hasPendingReply(): Boolean {
            val pending = currentPendingReply ?: return false
            if (System.currentTimeMillis() - pending.timestamp < REPLY_EXPIRATION_MS) {
                return true
            }
            currentPendingReply = null
            return false
        }

        /** Returns the sender of the active pending reply, or null if none/expired. */
        fun getPendingSender(): String? {
            val pending = currentPendingReply ?: return null
            if (System.currentTimeMillis() - pending.timestamp < REPLY_EXPIRATION_MS) {
                return pending.sender
            }
            currentPendingReply = null
            return null
        }

        /** Clears any stored pending voice reply. */
        fun clearPendingReply() {
            currentPendingReply = null
        }

        /**
         * Dispatches a voice reply back to the chat application via its RemoteInput pending action.
         */
        fun sendVoiceReply(context: Context, replyText: String): Boolean {
            val pending = currentPendingReply
            if (pending == null) {
                Log.w(TAG, "sendVoiceReply called with no pending reply")
                return false
            }
            if (System.currentTimeMillis() - pending.timestamp >= REPLY_EXPIRATION_MS) {
                Log.w(TAG, "sendVoiceReply called for expired pending reply")
                currentPendingReply = null
                return false
            }
            val remoteInputs = pending.replyAction.remoteInputs
            if (remoteInputs.isNullOrEmpty()) {
                Log.w(TAG, "sendVoiceReply: no RemoteInputs found on replyAction")
                currentPendingReply = null
                return false
            }
            return try {
                val intent = Intent()
                val bundle = Bundle()
                for (input in remoteInputs) {
                    bundle.putCharSequence(input.resultKey, replyText)
                }
                RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
                pending.replyAction.actionIntent.send(context, 0, intent)
                Log.i(TAG, "Successfully dispatched voice reply to ${pending.sender} on ${pending.app.displayName}")
                currentPendingReply = null
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error executing sendVoiceReply: ${e.message}", e)
                currentPendingReply = null
                false
            }
        }
    }
}
