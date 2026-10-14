package com.jarvis.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class JarvisNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        
        val packageName = sbn?.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: "New Message"
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""

        // Debugging ke liye har incoming notification ka package print karega
        Log.d("JarvisNotification", "Package: $packageName | Title: $title")

        when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> {
                announcerVoice("WhatsApp message from $title. $text")
            }
            // Jab Instazen ya kisi aur app ka package name pata chale, yahan add kar dena
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    private fun announcerVoice(message: String) {
        // Yahan Jarvis ka speech/TTS function call hoga
    }
}
