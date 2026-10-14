package com.jarvis.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class JarvisNotificationListener : NotificationListenerService() {

    private lateinit var jarvisTTS: JarvisTTS

    override fun onCreate() {
        super.onCreate()
        // Service start hote hi TTS engine initialize ho jayega
        jarvisTTS = JarvisTTS(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        
        val packageName = sbn?.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: "New Message"
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""

        // Debugging ke liye log print hoga
        Log.d("JarvisNotification", "Package: $packageName | Title: $title")

        when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> {
                announcerVoice("WhatsApp message from $title. $text", "en")
            }
            // Baaki apps ke package names yahan aage add kiye ja sakte hain
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    private fun announcerVoice(message: String, langCode: String) {
        // JarvisTTS ka speak function call hoga jo aawaz mein bolega
        jarvisTTS.speak(message, langCode)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Service destroy hone par TTS resources release ho jayengi
        jarvisTTS.shutdown()
    }
}
