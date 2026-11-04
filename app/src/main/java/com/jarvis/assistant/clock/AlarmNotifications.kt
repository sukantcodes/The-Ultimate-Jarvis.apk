package com.jarvis.assistant.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.clock.AlarmRingActivity
import com.jarvis.assistant.ui.clock.AlarmsActivity

/** Notification channels and notifications for ringing, missed and problem alarms. */
object AlarmNotifications {
    const val CHANNEL_RING = "jarvis_alarm_ring"
    const val CHANNEL_INFO = "jarvis_alarm_info"
    const val ID_RING = 7101
    private const val ID_MISSED_BASE = 7200
    private const val ID_PROBLEM = 7190

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ring = NotificationChannel(CHANNEL_RING, context.getString(R.string.clock_channel_ring), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.clock_channel_ring_desc)
            // The service plays the sound and vibration itself on the alarm stream, so the channel stays quiet.
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val info = NotificationChannel(CHANNEL_INFO, context.getString(R.string.clock_channel_info), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.clock_channel_info_desc)
        }
        nm.createNotificationChannel(ring)
        nm.createNotificationChannel(info)
    }

    private fun ringScreenIntent(context: Context, alarmId: Long): PendingIntent {
        val intent = Intent(context, AlarmRingActivity::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ID, alarmId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceAction(context: Context, action: String, alarmId: Long, requestCode: Int): PendingIntent {
        val intent = Intent(context, AlarmRingService::class.java).setAction(action).putExtra(AlarmScheduler.EXTRA_ID, alarmId)
        return PendingIntent.getForegroundService(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The ongoing notification of a ringing alarm: full-screen on a locked phone, and Stop / Snooze buttons otherwise. */
    fun ringing(context: Context, alarm: AlarmItem, settings: ClockSettings): Notification {
        ensureChannels(context)
        val screen = ringScreenIntent(context, alarm.id)
        return NotificationCompat.Builder(context, CHANNEL_RING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(ClockFormat.title(context, alarm))
            .setContentText(ClockFormat.time(context, alarm.hour, alarm.minute, settings))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(0, context.getString(R.string.clock_snooze), serviceAction(context, AlarmRingService.ACTION_SNOOZE, alarm.id, 2))
            .addAction(0, context.getString(R.string.clock_stop), serviceAction(context, AlarmRingService.ACTION_STOP, alarm.id, 3))
            .build()
    }

    /** A bare notification used only to satisfy Android while the service shuts down. */
    fun idle(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.clock_alarm_default_label))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun postMissed(context: Context, alarm: AlarmItem, settings: ClockSettings) {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 4, Intent(context, AlarmsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.clock_missed_title))
            .setContentText(context.getString(R.string.clock_missed_text, ClockFormat.title(context, alarm), ClockFormat.time(context, alarm.hour, alarm.minute, settings)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        notify(context, ID_MISSED_BASE + (alarm.id % 90L).toInt(), n)
    }

    /** Tells the person an alarm could not be set up, so a broken alarm is never silent about it. */
    fun postProblem(context: Context, text: String) {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 5, Intent(context, AlarmsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.clock_problem_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        notify(context, ID_PROBLEM, n)
    }

    @Suppress("MissingPermission")
    private fun notify(context: Context, id: Int, n: Notification) {
        try {
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) {
                manager.notify(id, n)
            }
        } catch (_: SecurityException) {
            // Notifications are off for this app; the Clock screen explains how to turn them on.
        }
    }
}
