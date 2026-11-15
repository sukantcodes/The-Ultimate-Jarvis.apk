package com.jarvis.assistant.background

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R

/**
 * Keeps "Hey Jarvis" listening around the clock.
 *
 * The wake-word listener lives in [JarvisConversationService]'s standby mode. Phones kill background apps, reboot, and
 * clear recents, so this object holds the pieces that bring the service back:
 *  - a flag that says the person wants Jarvis listening (set when standby starts, cleared only by an explicit stop);
 *  - a self-renewing alarm that checks every ~15 minutes and restarts standby if the service is gone;
 *  - a start helper that, when Android refuses a background microphone start, leaves a "Tap to resume" notification
 *    (a tap is always allowed to start the service).
 */
object WakeKeepAlive {
    private const val TAG = "WakeKeepAlive"
    private const val PREFS = "jarvis_keepalive"
    private const val KEY_ENABLED = "enabled"
    const val ACTION_WATCHDOG = "com.jarvis.assistant.KEEPALIVE_WATCHDOG"
    private const val WATCHDOG_INTERVAL_MS = 15 * 60_000L
    private const val WATCHDOG_REQUEST = 4101
    private const val RESUME_REQUEST = 4102
    private const val RESUME_CHANNEL = "jarvis_resume"
    private const val RESUME_NOTIFICATION_ID = 43

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** True = keep Jarvis listening and bring it back when it dies. False = the person stopped it on purpose. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) scheduleWatchdog(app) else {
            cancelWatchdog(app)
            clearResumeNotification(app)
        }
    }

    fun hasMicrophone(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun watchdogIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, WATCHDOG_REQUEST,
            Intent(context, WakeKeepAliveReceiver::class.java).setAction(ACTION_WATCHDOG),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Arms (or re-arms) the check-in alarm. Calling it again replaces the earlier one. */
    fun scheduleWatchdog(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
        if (!isEnabled(context)) return
        try {
            val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, watchdogIntent(context.applicationContext))
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule the keep-alive check: ${e.message}")
        }
    }

    fun cancelWatchdog(context: Context) {
        try {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(watchdogIntent(context.applicationContext))
        } catch (_: Exception) { }
    }

    private fun standbyIntent(context: Context): Intent =
        Intent(context, JarvisConversationService::class.java).setAction(JarvisConversationService.ACTION_ENTER_STANDBY)

    /** Starts standby listening. If Android refuses, a "Tap to resume" notification is shown instead. */
    fun startStandby(context: Context, reason: String): Boolean {
        val app = context.applicationContext
        return try {
            ContextCompat.startForegroundService(app, standbyIntent(app))
            Log.i(TAG, "Standby start requested ($reason)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Android did not allow starting the listener from the background ($reason): ${e.message}")
            postResumeNotification(app)
            false
        }
    }

    /** A notification whose tap restarts Jarvis's listening; a tap is always an allowed way to start it. */
    fun postResumeNotification(context: Context) {
        try {
            val app = context.applicationContext
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(RESUME_CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(RESUME_CHANNEL, "Jarvis listening", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shown when Android stopped Jarvis from listening in the background"
                    setShowBadge(false)
                })
            }
            val tap = PendingIntent.getForegroundService(
                app, RESUME_REQUEST, standbyIntent(app), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(app, RESUME_CHANNEL)
                .setSmallIcon(R.drawable.ic_jarvis_logo)
                .setContentTitle("Jarvis stopped listening")
                .setContentText("Tap to turn \"Hey Jarvis\" back on")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(RESUME_NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "Could not show the resume notification: ${e.message}")
        }
    }

    fun clearResumeNotification(context: Context) {
        try {
            context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(RESUME_NOTIFICATION_ID)
        } catch (_: Exception) { }
    }
}

/** Runs after a reboot, an app update and on the keep-alive alarm: brings the listener back if it is gone. */
class WakeKeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (!WakeKeepAlive.isEnabled(app)) return
        // Always line up the next check first, so one failed start never ends the chain.
        WakeKeepAlive.scheduleWatchdog(app)
        if (!WakeKeepAlive.hasMicrophone(app)) return
        if (JarvisConversationService.isServiceRunning()) return
        WakeKeepAlive.startStandby(app, intent?.action ?: "alarm")
    }
}
