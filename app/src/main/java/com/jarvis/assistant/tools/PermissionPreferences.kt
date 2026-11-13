package com.jarvis.assistant.tools

import android.content.Context

/**
 * Global helper for reading and toggling the autonomous Auto-Approve All Permissions setting.
 * When enabled, all tool operations (terminal, file edits, scripts, and destructive actions)
 * are automatically approved without user confirmation dialogs or notification prompts.
 */
object PermissionPreferences {
    const val PREFS_NAME = "tool_permissions"
    const val KEY_AUTO_APPROVE_ALL = PermissionPolicy.KEY_AUTO_APPROVE_ALL

    fun isAutoApproveAll(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val strVal = prefs.getString(KEY_AUTO_APPROVE_ALL, null)
        if (strVal != null) return strVal == "true"
        return runCatching { prefs.getBoolean(KEY_AUTO_APPROVE_ALL, false) }.getOrDefault(false)
    }

    /** The master internet switch for tools. On by default; see [PermissionPolicy.isInternetAllowed]. */
    fun isInternetAllowed(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PermissionPolicy.KEY_INTERNET_ENABLED, null) != "false"

    fun setInternetAllowed(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PermissionPolicy.KEY_INTERNET_ENABLED, enabled.toString())
            .apply()
        runCatching { ToolRuntime.get(context).policy.setInternetAllowed(enabled) }
    }

    fun setAutoApproveAll(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_AUTO_APPROVE_ALL, enabled.toString())
            .putBoolean(KEY_AUTO_APPROVE_ALL, enabled)
            .apply()
        runCatching {
            ToolRuntime.get(context).policy.setAutoApproveAll(enabled)
        }
    }
}
