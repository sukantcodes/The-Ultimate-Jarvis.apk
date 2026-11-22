package com.jarvis.assistant.tools.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Keeps JARVIS's process alive while at least one public link is open. The preview server that a link points at runs
 * inside this app, so without this the server (and therefore the link) disappears when the app is swiped away.
 * Started when the first link opens and stopped when the last one ends; closing the app screen does not stop it.
 */
class TunnelKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Public links", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Public link is open")
            .setContentText("JARVIS keeps it running until its time is up.")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_public_links"
        private const val NOTIFICATION_ID = 7421

        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, TunnelKeepAliveService::class.java)) } catch (_: Exception) { }
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, TunnelKeepAliveService::class.java)) } catch (_: Exception) { }
        }
    }
}
