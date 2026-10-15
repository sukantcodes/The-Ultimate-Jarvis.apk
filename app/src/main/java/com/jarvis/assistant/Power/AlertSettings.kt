package com.jarvis.assistant.power

import android.content.Context

/** On/off switches for the battery and charging alerts. Both default to on. */
class AlertSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_alert_settings", Context.MODE_PRIVATE)

    /** Spoken alerts: charger connected/disconnected, low battery, and the full-charge reminder. */
    var chargingAlerts: Boolean
        get() = prefs.getBoolean("charging_alerts", true)
        set(value) {
            prefs.edit().putBoolean("charging_alerts", value).apply()
        }

    /** Turn Battery Saver on automatically when the battery drops to 25% or lower while not charging. */
    var autoBatterySaver: Boolean
        get() = prefs.getBoolean("auto_battery_saver", true)
        set(value) {
            prefs.edit().putBoolean("auto_battery_saver", value).apply()
        }
}
