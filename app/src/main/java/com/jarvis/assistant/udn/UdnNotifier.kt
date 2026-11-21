package com.jarvis.assistant.udn

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.udn.UdnActivity

/** Local, high-priority notifications for network events (a device joined, a circle invite). */
object UdnNotifier {
    private const val CHANNEL = "jarvis_udn_events"

    private fun ready(context: Context): NotificationManager? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return null
        val nm = context.getSystemService(NotificationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Network activity", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New devices joining and circle invitations"
            })
        }
        return nm
    }

    private fun post(context: Context, id: Int, title: String, text: String, page: Int) {
        val nm = ready(context) ?: return
        val open = PendingIntent.getActivity(
            context, id, Intent(context, UdnActivity::class.java).putExtra(UdnActivity.EXTRA_PAGE, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        runCatching {
            nm.notify(id, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_jarvis_logo)
                .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true).setContentIntent(open).build())
        }
    }

    fun deviceJoined(context: Context, name: String, id: String) =
        post(context, 7600 + (id.hashCode() and 0xFFF), "New device joined", "$name just joined the network.", UdnActivity.PAGE_DEVICES)

    fun circleInvite(context: Context, circle: String, id: String) =
        post(context, 7800 + (id.hashCode() and 0xFFF), "Circle invitation", "You were invited to the circle \"$circle\".", UdnActivity.PAGE_CIRCLES)
}
