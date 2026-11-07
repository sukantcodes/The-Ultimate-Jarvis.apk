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

/**
 * Android sends MY_PACKAGE_REPLACED after an APK update has actually been installed.
 * This receiver only informs the user; it never launches an installer or restarts the app.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
        const val CHANNEL_ID = "jarvis_update_installed"
        const val NOTIFICATION_ID = 8148
    }
}
