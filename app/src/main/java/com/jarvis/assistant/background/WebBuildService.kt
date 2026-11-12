package com.jarvis.assistant.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R

/**
 * Keeps a website / web-game build alive when the app is closed or in the background. It owns nothing itself: it
 * holds a foreground notification so Android keeps the process, and, if the process was killed and restarted
 * (START_STICKY), tells [WebBuildOrchestrator] to resume from the files already saved.
 */
class WebBuildService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
        } catch (e: Exception) {
            // Some Android versions refuse a foreground start from the background; the build still runs under the
            // assistant's own foreground services.
            Log.w(TAG, "Could not enter foreground", e)
        }
        WebBuildOrchestrator.resumeIfPending(applicationContext)
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "JARVIS build keep-alive", NotificationManager.IMPORTANCE_MIN))
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sparkle)
            .setContentTitle("JARVIS is building your project")
            .setContentText("Running in the background")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
    }

    companion object {
        private const val TAG = "WebBuildService"
        private const val CHANNEL = "jarvis_web_build_keepalive"
        private const val ID = 7792
        private const val ACTION_STOP = "com.jarvis.assistant.WEB_BUILD_STOP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WebBuildService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start build service", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, WebBuildService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                runCatching { context.stopService(Intent(context, WebBuildService::class.java)) }
            }
        }
    }
}
