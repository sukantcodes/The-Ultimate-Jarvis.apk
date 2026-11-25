package com.jarvis.assistant.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.ActivityLauncherHelper

/**
 * Android sends MY_PACKAGE_REPLACED after an APK update has actually been installed, and the system has already replaced
 * the old process with the new code. Android only installs the update and leaves the app closed, so this receiver opens
 * JARVIS again straight away (through [ActivityLauncherHelper], which uses every exemption Android allows for starting an
 * activity from the background). If Android refuses all of them, it falls back to a "JARVIS updated" notification.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Record the finished OTA update in its history and remove the downloaded APK.
        runCatching { OtaManager.onPackageReplaced(context.applicationContext) }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Reopen the freshly updated app right away (on by default). Nothing is left to "restart", so no prompt is needed.
        if (prefs.getBoolean(KEY_AUTO_OPEN, true)) {
            val reopen = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val launched = runCatching { ActivityLauncherHelper.startActivitySafely(context, reopen) }.getOrDefault(false)
            if (launched) {
                prefs.edit().putBoolean(KEY_PENDING_RESTART, false).putBoolean(KEY_PROMPT_SHOWN, true).apply()
                runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
                return
            }
        }

        prefs.edit()
            .putBoolean(KEY_PENDING_RESTART, true)
            .putBoolean(KEY_PROMPT_SHOWN, false)
            .apply()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "JARVIS update installed",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Tells you when a JARVIS update has finished installing"
                }
            )
        }

        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_PROMPT_UPDATE_RESTART, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("JARVIS update installed")
            .setContentText("Restart JARVIS to finish applying the update.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "The update was installed successfully. Open JARVIS and choose Restart Now when you're ready."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val PREFS = "jarvis_update_install_state"
        const val KEY_PENDING_RESTART = "pending_restart"
        const val KEY_PROMPT_SHOWN = "restart_prompt_shown"
        /** Reopen JARVIS automatically after an update finishes installing (default on). */
        const val KEY_AUTO_OPEN = "auto_open_after_update"
        const val CHANNEL_ID = "jarvis_update_installed"
        const val NOTIFICATION_ID = 8148
    }
}
