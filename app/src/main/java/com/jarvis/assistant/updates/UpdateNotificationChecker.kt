package com.jarvis.assistant.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.R
import com.jarvis.assistant.auth.SupabaseConfig
import com.jarvis.assistant.ui.home.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Launch/foreground-only release checker. It never schedules background work and never contacts
 * Firebase/FCM. A release is notified once per channel/version, even if the notification is dismissed.
 */
object UpdateNotificationChecker {
    private const val PREFS = "jarvis_app_updates"
    private const val CHANNEL_ID = "jarvis_app_updates"
    private const val NOTIFICATION_ID = 8147
    private const val CHECK_COOLDOWN_MS = 2 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun checkOnForeground(context: Context) {
        if (!SupabaseConfig.isConfigured) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check_at", 0L) < CHECK_COOLDOWN_MS) return
        prefs.edit().putLong("last_check_at", now).apply()

        scope.launch {
            try {
                val endpoint = SupabaseConfig.url +
                    "/functions/v1/app-updates?version_code=${BuildConfig.VERSION_CODE}&channel=stable"
                val request = Request.Builder()
                    .url(endpoint)
                    .header("apikey", SupabaseConfig.publishableKey)
                    .header("Accept", "application/json")
                    .get()
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val payload = response.body?.string()?.let(::JSONObject) ?: return@use
                    if (!payload.optBoolean("update_available", false)) return@use
                    val release = payload.optJSONObject("release") ?: return@use
                    val versionCode = release.optInt("version_code", 0)
                    val versionName = release.optString("version_name", "").take(40)
                    if (versionCode <= BuildConfig.VERSION_CODE || versionName.isBlank()) return@use

                    val apkUrl = release.optString("apk_url", "")
                    val uri = runCatching { Uri.parse(apkUrl) }.getOrNull() ?: return@use
                    val expectedHost = Uri.parse(SupabaseConfig.url).host
                    if (uri.scheme != "https" || uri.host != expectedHost ||
                        !uri.encodedPath.orEmpty().startsWith("/storage/v1/object/public/jarvis-releases/")) {
                        return@use
                    }

                    val notifiedKey = "notified_stable_version"
                    if (prefs.getInt(notifiedKey, 0) >= versionCode) return@use
                    if (!canPostNotifications(app)) return@use

                    val manager = NotificationManagerCompat.from(app)
                    createChannel(app)
                    val openUpdates = Intent(app, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(MainActivity.EXTRA_OPEN_UPDATES, true)
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        app, NOTIFICATION_ID, openUpdates,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val notes = release.optString("release_notes", "").trim()
                        .replace(Regex("\\s+"), " ").take(180)
                    val body = if (notes.isBlank()) {
                        "A newer JARVIS version is available. Open Updates to review it."
                    } else notes

                    val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("JARVIS update available · $versionName")
                        .setContentText(body)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .build()

                    try {
                        manager.notify(NOTIFICATION_ID, notification)
                        prefs.edit().putInt(notifiedKey, versionCode).apply()
                    } catch (_: SecurityException) {
                        // User notification permission may have been revoked between the check and notify.
                    }
                }
            } catch (_: Exception) {
                // Update discovery is best-effort and must never interrupt JARVIS startup.
            }
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "JARVIS app updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important updates published by JARVIS"
                setShowBadge(true)
            }
        )
    }
}
