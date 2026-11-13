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
    const val KEY_ALL_FILES_ACCESS = "all_files_access_enabled"

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

    /**
     * Preference switch for internal device storage and all files access.
     * When enabled, Jarvis tools can directly manage, view, edit, create, and delete files on /sdcard and internal storage.
     */
    fun isAllFilesAccessEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ALL_FILES_ACCESS, true)
    }

    fun setAllFilesAccessEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ALL_FILES_ACCESS, enabled)
            .apply()
    }

    /**
     * Checks whether the application has system-level permission to access all files on internal storage.
     * On Android 11+ (API 30+), verifies Environment.isExternalStorageManager().
     * On older Android versions, verifies WRITE_EXTERNAL_STORAGE.
     */
    fun hasAllFilesAccess(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                android.os.Environment.isExternalStorageManager()
            } catch (_: Exception) {
                false
            }
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Creates an intent directing the user to the system "All files access" settings screen for JARVIS.
     */
    fun createManageAllFilesIntent(context: Context): android.content.Intent {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:" + context.packageName)
                }
            } catch (_: Exception) {
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else {
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:" + context.packageName)
            }
        }
    }
}
