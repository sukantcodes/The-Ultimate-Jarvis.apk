package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.speech.tts.TextToSpeech
import java.util.Locale

class SystemEventReceiver : BroadcastReceiver(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var pendingMessage: String? = null

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // TTS initialize karna
        if (tts == null) {
            tts = TextToSpeech(context, this)
        }

        val batteryStatus = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                speakOut("Charging connected, Sir. Current battery is $batteryPct percent.")
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                speakOut("Charging disconnected. Battery is at $batteryPct percent.")
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                if (batteryPct == 25) {
                    speakOut("Warning Sir, battery is at 25 percent. Activating battery saver.")
                } else if (batteryPct == 100) {
                    speakOut("Battery is fully charged to 100 percent, Sir. Please unplug the charger.")
                }
            }
        }
    }

    private fun speakOut(message: String) {
        if (tts != null) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            pendingMessage = message
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.ENGLISH
            pendingMessage?.let {
                speakOut(it)
                pendingMessage = null
            }
        }
    }
}
