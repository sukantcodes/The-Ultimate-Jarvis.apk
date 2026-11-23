package com.jarvis.assistant.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.settings.SoftwareUpdatesActivity

/** The three things the updater may say outside the app: an update exists, it is ready, or it could not be installed. */
object OtaNotifier {
    const val CHANNEL_ID = "jarvis_app_updates"
    const val ID_AVAILABLE = 8147
    const val ID_READY = 8149
    const val ID_CONFIRM = 8150
    const val ID_FAILED = 8151

    fun available(context: Context, release: OtaRelease) = post(
        context, ID_AVAILABLE,
        "JARVIS update available · ${release.versionName}",
        summary(release, "A newer JARVIS build is available. Open Software Updates to download it.")
    )

    fun ready(context: Context, release: OtaRelease) = post(
        context, ID_READY,
        "JARVIS ${release.versionName} is ready to install",
        "Downloaded and verified (${OtaFormat.bytes(release.sizeBytes)}). Tap to review and install."
    )

    fun failed(context: Context, text: String) = post(context, ID_FAILED, "JARVIS update not installed", text)

    /** Android's own install confirmation, when it could not be opened directly because JARVIS was in the background. */
    fun confirmInstall(context: Context, confirmation: Intent) {
        if (!canPost(context)) return
        ensureChannel(context)
        val pending = PendingIntent.getActivity(
            context, ID_CONFIRM, confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Finish updating JARVIS")
            .setContentText("Tap to confirm the installation with Android.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        notify(context, ID_CONFIRM, notification)
    }

    fun clearInstallPrompts(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(ID_CONFIRM)
        manager.cancel(ID_READY)
        manager.cancel(ID_AVAILABLE)
    }

    private fun summary(release: OtaRelease, fallback: String): String {
        val notes = release.releaseNotes.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("-") }?.trimStart('-', ' ')
        return if (notes.isNullOrBlank()) fallback else notes.take(180)
    }

    private fun post(context: Context, id: Int, title: String, body: String) {
        if (!canPost(context)) return
        ensureChannel(context)
        val open = Intent(context, SoftwareUpdatesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        notify(context, id, notification)
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // The notification permission can be revoked between the check and the post; the in-app screen still works.
        }
    }

    private fun canPost(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "JARVIS software updates", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you when a JARVIS update is available, ready or needs your confirmation"
                setShowBadge(true)
            }
        )
    }
}
