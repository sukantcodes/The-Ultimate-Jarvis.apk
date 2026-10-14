package com.jarvis.assistant

import android.content.Context

object JarvisPreferences {
    private const val PREF_NAME = "jarvis_prefs"
    private const val KEY_LANG = "selected_lang"

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANG, "en") ?: "en" // Default English
    }

    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANG, langCode).apply()
    }
}
