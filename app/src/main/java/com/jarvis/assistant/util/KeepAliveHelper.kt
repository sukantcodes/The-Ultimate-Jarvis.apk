package com.jarvis.assistant.util

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Helps the person set the phone up so "Hey Jarvis" keeps listening around the clock: it shows what is already
 * allowed and opens the right system screens (battery, and the phone maker's auto-start / background screens).
 */
object KeepAliveHelper {

    private val vendorScreens = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
    )

    fun statusText(context: Context): String {
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        return buildString {
            append(mark(PermissionHelper.hasRecordAudioPermission(context))).append("  Microphone allowed\n")
            append(mark(PermissionHelper.hasNotificationPermission(context))).append("  Notifications allowed\n")
            append(mark(PermissionHelper.hasBatteryOptimizationPermission(context))).append("  Battery: unrestricted\n\n")
            append("For \"Hey Jarvis\" to keep working after you close the app or restart the phone, also turn on Auto-start for Jarvis and lock it in the recent apps list (on Xiaomi, Oppo, Vivo, OnePlus and Samsung phones).")
        }
    }

    /** Opens the phone maker's auto-start / background screen, or Jarvis's app info page when there is none. */
    fun openBackgroundSettings(context: Context) {
        for (component in vendorScreens) {
            try {
                context.startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) { }
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
    }

    fun showDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("Keep Jarvis listening 24/7")
            .setMessage(statusText(activity))
            .setPositiveButton("Allow background") { _, _ -> PermissionHelper.requestBatteryOptimization(activity) }
            .setNeutralButton("Auto-start settings") { _, _ -> openBackgroundSettings(activity) }
            .setNegativeButton("Close", null)
            .show()
    }
}
