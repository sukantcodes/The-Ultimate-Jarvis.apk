package com.jarvis.assistant.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.activitylog.ActivityLog
import com.jarvis.assistant.util.SpeechAnnouncer

/**
 * Watches the battery for as long as the Jarvis service runs and speaks:
 *  - when the charger is connected or disconnected (with the battery percentage),
 *  - when the battery drops to 25% or lower (and tries to turn Battery Saver on),
 *  - every 5 minutes while the battery is full and the charger is still connected.
 */
class BatteryWatcher(
    private val context: Context,
    private val announcer: SpeechAnnouncer
) {
    private val settings = AlertSettings(context)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var lowAnnounced = false
    private var fullReminderActive = false
    private var lastPowerEventType = ""
    private var lastPowerEventAt = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> onPowerEvent(true)
                Intent.ACTION_POWER_DISCONNECTED -> onPowerEvent(false)
                Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent, false)
            }
        }
    }

    private val reminder = object : Runnable {
        override fun run() {
            if (!fullReminderActive) return
            if (settings.chargingAlerts) {
                announcer.say("Charging completed, Sir. Battery is at 100 percent. Please disconnect the charger.")
            }
            handler.postDelayed(this, FULL_REMINDER_MS)
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        try {
            val sticky = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            if (sticky != null) onBatteryChanged(sticky, true)
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the battery: ${e.message}")
        }
    }

    fun stop() {
        stopFullReminder()
        if (registered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            registered = false
        }
    }

    private fun note(text: String) {
        ActivityLog.get(context).log(ActivityLog.TYPE_BATTERY, text)
    }

    private fun currentPercent(): Int {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun onPowerEvent(connected: Boolean) {
        val type = if (connected) "connected" else "disconnected"
        val now = SystemClock.elapsedRealtime()
        if (type == lastPowerEventType && now - lastPowerEventAt < 3_000L) return
        lastPowerEventType = type
        lastPowerEventAt = now

        val percent = currentPercent()
        val percentText = if (percent >= 0) "$percent percent" else "unknown"
        note(if (connected) "Charger connected, battery $percentText" else "Charger disconnected, battery $percentText")
        if (connected) {
            lowAnnounced = false
            if (settings.chargingAlerts) {
                announcer.say("Charging connected, Sir. Current battery level is $percentText.")
            }
        } else {
            stopFullReminder()
            if (settings.chargingAlerts) {
                announcer.say("Charging disconnected. Battery is at $percentText.")
            }
        }
    }

    private fun onBatteryChanged(intent: Intent, initial: Boolean) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        if (!plugged && percent <= LOW_PERCENT) {
            if (!lowAnnounced) {
                lowAnnounced = true
                if (!initial) handleLowBattery(percent)
            }
        } else if (plugged || percent > LOW_PERCENT + 5) {
            lowAnnounced = false
        }

        val full = plugged && (percent >= 100 || status == BatteryManager.BATTERY_STATUS_FULL)
        if (full) startFullReminder() else stopFullReminder()
    }

    private fun handleLowBattery(percent: Int) {
        note("Battery low at $percent percent")
        if (!settings.chargingAlerts && !settings.autoBatterySaver) return
        var line = "Sir, your battery is at $percent percent."
        line += if (settings.autoBatterySaver) " " + enableBatterySaver() else " Please plug in the charger."
        if (settings.chargingAlerts) announcer.say(line)
    }

    /** Turns Battery Saver on when Android allows it, otherwise opens its settings page. Returns what to tell the user. */
    private fun enableBatterySaver(): String {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null && power.isPowerSaveMode) return "Battery Saver is already on."
        try {
            if (Settings.Global.putInt(context.contentResolver, "low_power", 1)) {
                return "I have turned on Battery Saver."
            }
        } catch (e: SecurityException) {
            Log.i(TAG, "WRITE_SECURE_SETTINGS is not granted, opening Battery Saver settings instead")
        } catch (e: Exception) {
            Log.w(TAG, "Could not switch Battery Saver on: ${e.message}")
        }
        return try {
            context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Please turn on Battery Saver on the screen I opened."
        } catch (e: Exception) {
            "Please turn on Battery Saver and plug in the charger."
        }
    }

    private fun startFullReminder() {
        if (fullReminderActive) return
        fullReminderActive = true
        note("Battery full at 100 percent while the charger is still connected")
        handler.post(reminder)
    }

    private fun stopFullReminder() {
        fullReminderActive = false
        handler.removeCallbacks(reminder)
    }

    companion object {
        private const val TAG = "BatteryWatcher"
        private const val LOW_PERCENT = 25
        private const val FULL_REMINDER_MS = 5 * 60 * 1000L
    }
}
