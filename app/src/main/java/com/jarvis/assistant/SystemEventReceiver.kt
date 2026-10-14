package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val tts = JarvisTTS(context)
        val lang = JarvisPreferences.getLanguage(context) // App se selected language fetch hogi

        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                val message = if (lang == "hi") "Charging shuru ho gayi hai." else "Charging started. Power connected."
                tts.speak(message, lang)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                val message = if (lang == "hi") "Power disconnect ho gaya hai." else "Power disconnected."
                tts.speak(message, lang)
            }
            Intent.ACTION_BATTERY_LOW -> {
                val message = if (lang == "hi") "Battery low hai. Kripya charger lagayein." else "Battery is low. Please plug in the charger."
                tts.speak(message, lang)
            }
        }
    }
}
