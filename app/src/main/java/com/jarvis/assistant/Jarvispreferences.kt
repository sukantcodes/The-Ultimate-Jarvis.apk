package com.jarvis.assistant

import android.content.Context

object JarvisPreferences {
    private const val PREF_NAME = "jarvis_prefs"
    private const val KEY_LANG = "selected_lang"
    private const val KEY_NOTIF_ENABLED = "notif_enabled"
    private const val KEY_CHARGING_ENABLED = "charging_enabled"

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANG, "en") ?: "en"
    }

    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANG, langCode).apply()
    }

    fun isNotificationEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_NOTIF_ENABLED, true) // Default enabled
    }

    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_NOTIF_ENABLED, enabled).apply()
    }

    fun isChargingAlertsEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CHARGING_ENABLED, true) // Default enabled
    }

    fun setChargingAlertsEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CHARGING_ENABLED, enabled).apply()
    }
}
