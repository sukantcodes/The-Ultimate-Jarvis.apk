package com.jarvis.assistant.udn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Keeps this device reachable by the network: heartbeat, realtime wake-ups and command execution run here while
 * the app is in the background. It is a normal visible foreground service (data sync), started only after the
 * user enrolled this device and an authorized parent approved it, and it can be switched off in the Device
 * Network screen. It does not use the microphone; background voice stays with JARVIS's existing listening service.
 */
class UdnLinkService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = UdnSettings.get(this)
        val manager = UdnManager.get(this)
        if (!settings.linkEnabled || settings.deviceId == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "Foreground start refused: ${e.javaClass.simpleName}")
            stopSelf(); return START_NOT_STICKY
        }
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { manager.startLink(it) }
        return START_STICKY
    }

    override fun onDestroy() {
        UdnManager.get(this).stopLink()
        scope?.cancel(); scope = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "JARVIS Device Network", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, com.jarvis.assistant.ui.udn.UdnActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis_logo).setContentTitle("JARVIS Device Network")
            .setContentText("This device is reachable by your parent devices").setOngoing(true)
            .setContentIntent(open).setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }

    companion object {
        private const val TAG = "UdnLinkService"
        private const val CHANNEL = "jarvis_udn_link"
        private const val NOTIFICATION_ID = 7310

        fun start(context: Context) {
            val s = UdnSettings.get(context)
            if (!s.linkEnabled || s.deviceId == null) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, UdnLinkService::class.java)) }
                .onFailure { Log.w(TAG, "Android didn't allow starting the network link from the background") }
        }

        fun stop(context: Context) { runCatching { context.stopService(Intent(context, UdnLinkService::class.java)) } }
    }
}
