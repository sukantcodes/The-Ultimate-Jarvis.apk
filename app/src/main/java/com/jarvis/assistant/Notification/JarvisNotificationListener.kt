package com.jarvis.assistant.notifications

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.assistant.activitylog.ActivityLog
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.util.SpeechAnnouncer

/**
 * Listens for new messages from WhatsApp, Instazen and Instagram and announces who they are from, for example
 * "Sir, Rahul se WhatsApp par message aaya hai". Only the sender and the app are used; message text is never
 * stored or read out. Needs "Notification access" for Jarvis to be turned on in Android settings.
 */
class JarvisNotificationListener : NotificationListenerService() {
    private val settings by lazy { MessageAlertSettings(applicationContext) }
    private var fallbackAnnouncer: SpeechAnnouncer? = null

    private val lastAnnounced = HashMap<String, Long>()
    private val lastLogged = HashMap<String, Long>()
    private val lastContent = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "Could not handle a notification: ${e.message}")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val message = MessageNotificationParser.parse(applicationContext, sbn) ?: return
        val now = System.currentTimeMillis()

        // The same message posted again (updates and reposts) is only handled once.
        val extras = sbn.notification?.extras
        val body = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val contentKey = sbn.packageName + "|" + message.sender + "|" + (message.group ?: "") + "|" + body
        synchronized(lastContent) {
            val seen = lastContent[contentKey]
            if (seen != null && now - seen < CONTENT_WINDOW_MS) return
            lastContent[contentKey] = now
            if (lastContent.size > 200) lastContent.entries.removeAll { now - it.value > CONTENT_WINDOW_MS }
        }

        val senderKey = message.app.name + "|" + message.sender + "|" + (message.group ?: "")

        // Remember who messaged (name and app only) for the activity memory.
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
            ActivityLog.get(applicationContext).log(
                ActivityLog.TYPE_MESSAGE,
                message.app.displayName + " message from " + message.sender + where
            )
        }

        if (!settings.enabled) return
        if (isSilentMessage(sbn)) return
        if (!canSpeakNow(sbn.packageName)) return

        // One announcement per sender every 30 seconds, so a chatty friend does not cause a flood.
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

        val text = if (message.group != null) {
            "Sir, " + message.group + " group mein " + message.sender + " se " + message.app.displayName + " par message aaya hai."
        } else {
            "Sir, " + message.sender + " se " + message.app.displayName + " par message aaya hai."
        }
        if (!JarvisConversationService.announceMessage(text)) {
            val announcer = fallbackAnnouncer ?: SpeechAnnouncer(applicationContext).also { fallbackAnnouncer = it }
            announcer.say(text)
        }
    }

    /** Muted chats and silent notification channels are not announced. */
    private fun isSilentMessage(sbn: StatusBarNotification): Boolean {
        return try {
            val ranking = NotificationListenerService.Ranking()
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
    }
}
