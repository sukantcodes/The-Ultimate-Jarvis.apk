package com.jarvis.assistant.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.home.MainActivity

object TaskProgressNotifier {
    private const val CHANNEL_ID = "jarvis_task_progress"
    private const val NOTIFICATION_ID = 7777

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Task Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live progress updates for background tasks"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    fun postProgress(context: Context, taskTitle: String, progressText: String, percent: Int) {
        ensureChannel(context)
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle("⚡ JARVIS: $taskTitle")
            .setContentText(progressText)
            .setProgress(100, percent, percent == 0)
            .setOngoing(percent < 100)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(progressText))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            android.util.Log.w("TaskProgressNotifier", "Notification permission not granted", e)
        }
    }

    fun postPhaseUpdate(context: Context, phase: String) {
        postProgress(context, "Working…", phase, 0)
    }

    fun postComplete(context: Context, taskTitle: String, summary: String) {
        ensureChannel(context)
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle("✓ JARVIS Complete: $taskTitle")
            .setContentText(summary)
            .setProgress(100, 100, false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            android.util.Log.w("TaskProgressNotifier", "Notification permission not granted", e)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
