package com.jarvis.assistant.power

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/** Battery Saver and Do Not Disturb switches, shared by the battery alerts and the Automation Studio. */
object PowerActions {
    private const val TAG = "PowerActions"

    /** Turns Battery Saver on or off when Android allows it, otherwise opens its settings page. Returns what to tell the user. */
    fun setBatterySaver(context: Context, on: Boolean): String {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null && power.isPowerSaveMode == on) {
            return if (on) "Battery Saver is already on." else "Battery Saver is already off."
        }
        try {
            if (Settings.Global.putInt(context.contentResolver, "low_power", if (on) 1 else 0)) {
                return if (on) "I have turned on Battery Saver." else "I have turned off Battery Saver."
            }
        } catch (e: SecurityException) {
            Log.i(TAG, "WRITE_SECURE_SETTINGS is not granted, opening Battery Saver settings instead")
        } catch (e: Exception) {
            Log.w(TAG, "Could not switch Battery Saver: ${e.message}")
        }
        return try {
            context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (on) "Please turn on Battery Saver on the screen I opened." else "Please turn off Battery Saver on the screen I opened."
        } catch (e: Exception) {
            if (on) "Please turn on Battery Saver." else "Please turn off Battery Saver."
        }
    }

    /** Turns Do Not Disturb on or off. Android needs "Do Not Disturb access" for Jarvis once; without it the settings page opens. */
    fun setDoNotDisturb(context: Context, on: Boolean): String {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "Do Not Disturb is not available on this phone."
        if (!manager.isNotificationPolicyAccessGranted) {
            return try {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "I need Do Not Disturb access first. Please allow Jarvis on the screen I opened."
            } catch (e: Exception) {
                "I need Do Not Disturb access. Please allow Jarvis in Android settings."
            }
        }
        return try {
            manager.setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            if (on) "Do Not Disturb is on." else "Do Not Disturb is off."
        } catch (e: Exception) {
            Log.w(TAG, "Could not change Do Not Disturb: ${e.message}")
            "I could not change Do Not Disturb."
        }
    }
}
