package com.jarvis.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class JarvisNotificationListener : NotificationListenerService() {

    private lateinit var jarvisTTS: JarvisTTS

    override fun onCreate() {
        super.onCreate()
        jarvisTTS = JarvisTTS(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        
        // Agar user ne settings se notifications band kiye hain, toh return ho jao
        if (!JarvisPreferences.isNotificationEnabled(applicationContext)) return

        val packageName = sbn?.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: "New Message"
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""

        Log.d("JarvisNotification", "Package: $packageName | Title: $title")

        when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> {
                val lang = JarvisPreferences.getLanguage(applicationContext)
                val message = if (lang == "hi") "WhatsApp message aaya hai $title se." else "WhatsApp message from $title. $text"
                announcerVoice(message, lang)
            }
            // Baaki apps ke package names yahan add kiye ja sakte hain
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    private fun announcerVoice(message: String, langCode: String) {
        jarvisTTS.speak(message, langCode)
    }

    override fun onDestroy() {
        super.onDestroy()
        jarvisTTS.shutdown()
    }
}
