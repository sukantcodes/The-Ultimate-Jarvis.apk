package com.jarvis.assistant.automation.tasks

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ContactCaller
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Small persistent task memory for delayed calls and app-notification watches.
 * Only the requested contact/app and event keyword are stored; notification bodies are
 * inspected transiently and are never persisted.
 */
object ScheduledTaskMemory {
    private const val PREFS = "jarvis_long_form_tasks"
    private const val CALLS = "scheduled_calls"
    private const val WATCHES = "app_event_watches"
    private const val CHANNEL = "jarvis_task_alerts"
    private const val ACTION_CALL_DUE = "com.jarvis.assistant.tasks.CALL_DUE"
    private const val MAX_WATCHES = 100

    fun scheduleCall(context: Context, contact: String, delayMillis: Long): String {
        val cleanContact = contact.trim()
        if (cleanContact.isBlank()) return "Please tell me who I should call."
        val safeDelay = delayMillis.coerceIn(1_000L, 30L * 24L * 60L * 60L * 1000L)
        val now = System.currentTimeMillis()
        val id = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)
        val dueAt = now + safeDelay
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = readArray(prefs.getString(CALLS, "[]"))
        items.put(JSONObject().put("id", id).put("contact", cleanContact).put("dueAt", dueAt))
        prefs.edit().putString(CALLS, items.toString()).apply()
        scheduleCallAlarm(context, id, dueAt, cleanContact)
        val minutes = (safeDelay / 60_000L).coerceAtLeast(1L)
        return "Okay. I saved the task. I will try to call $contact in about $minutes minute(s)."
    }

    fun watchAppEvent(context: Context, app: String, keyword: String): String {
        val cleanApp = app.trim().lowercase(Locale.ROOT)
        val cleanKeyword = keyword.trim().lowercase(Locale.ROOT).ifBlank { "any" }
        if (cleanApp.isBlank()) return "Please tell me which app I should monitor."
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val watches = readArray(prefs.getString(WATCHES, "[]"))
        val duplicate = (0 until watches.length()).any {
            val item = watches.optJSONObject(it)
            item?.optString("app") == cleanApp && item.optString("keyword") == cleanKeyword
        }
        if (!duplicate) {
            if (watches.length() >= MAX_WATCHES) {
                return "I already have $MAX_WATCHES active notification watches. Complete or remove one before adding another."
            }
            watches.put(JSONObject().put("id", UUID.randomUUID().toString())
                .put("app", cleanApp).put("keyword", cleanKeyword))
            prefs.edit().putString(WATCHES, watches.toString()).apply()
        }
        val eventText = if (cleanKeyword == "delivered") "delivery notification" else "a new notification"
        return "Done. I will monitor $app and inform you when $eventText arrives. Keep JARVIS notification access enabled."
    }

    /** Called by the notification listener. Notification text is only examined in memory. */
    fun onAppNotification(context: Context, packageName: String, title: String, body: String): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val watches = readArray(prefs.getString(WATCHES, "[]"))
        if (watches.length() == 0) return emptyList()
        val appLabel = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { packageName }
        val content = (title + " " + body).lowercase()
        val matched = ArrayList<String>()
        val remaining = JSONArray()
        for (i in 0 until watches.length()) {
            val watch = watches.optJSONObject(i) ?: continue
            val app = watch.optString("app").lowercase()
            val keyword = watch.optString("keyword", "any")
            val appMatches = app == "any" || packageName.lowercase().contains(app) ||
                appLabel.lowercase().contains(app) ||
                (app == "blinkit" && (packageName.contains("grofers", true) || appLabel.contains("blinkit", true)))
            val eventMatches = when (keyword) {
                "any" -> true
                "delivered" -> listOf("delivered", "order delivered", "has been delivered", "successfully delivered", "reached you").any { content.contains(it) }
                else -> content.contains(keyword)
            }
            if (appMatches && eventMatches) {
                val label = if (appLabel.isBlank()) app else appLabel
                matched.add("Your $label update: " + (title.ifBlank { body }).take(180))
            } else {
                remaining.put(watch)
            }
        }
        if (matched.isNotEmpty()) prefs.edit().putString(WATCHES, remaining.toString()).apply()
        return matched
    }

    fun restorePending(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = readArray(prefs.getString(CALLS, "[]"))
        val now = System.currentTimeMillis()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optLong("id", -1L)
            val dueAt = item.optLong("dueAt", 0L)
            val contact = item.optString("contact")
            if (id >= 0L && contact.isNotBlank()) scheduleCallAlarm(context, id, maxOf(now + 1_000L, dueAt), contact)
        }
    }

    internal fun onCallDue(context: Context, id: Long, contact: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = readArray(prefs.getString(CALLS, "[]"))
        val remaining = JSONArray()
        var found = false
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optLong("id") == id) found = true else remaining.put(item)
        }
        if (!found) return
        prefs.edit().putString(CALLS, remaining.toString()).apply()

        val matches = if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            ContactCaller.findMatches(context, contact)
        } else emptyList()
        if (matches.size == 1 && ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val call = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(matches.first().number)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(call)
                return
            } catch (_: Exception) {
                // Android may block a background call launch; fall through to a user-visible alert.
            }
        }
        val detail = when {
            matches.size > 1 -> "I found more than one contact named $contact. Please choose the right one."
            matches.isEmpty() -> "Please open JARVIS and check contact access or the saved contact name for $contact."
            else -> "Android needs you to tap the call notification to continue."
        }
        postAlert(context, "Time to call $contact", detail, matches.singleOrNull()?.number)
    }

    private fun scheduleCallAlarm(context: Context, id: Long, dueAt: Long, contact: String) {
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ACTION_CALL_DUE
            data = Uri.parse("jarvis-task://call/$id")
            putExtra("task_id", id)
            putExtra("contact", contact)
        }
        val pending = PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending)
    }

    internal fun isCallDueAction(action: String?): Boolean = action == ACTION_CALL_DUE

    private fun postAlert(context: Context, title: String, message: String, number: String? = null) {
        ensureChannel(context)
        val dialIntent = if (!number.isNullOrBlank()) {
            PendingIntent.getActivity(
                context, title.hashCode(), Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { if (dialIntent != null) addAction(0, "Open dialer", dialIntent) }
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(title.hashCode(), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "JARVIS task alerts", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun readArray(raw: String?): JSONArray = try { JSONArray(raw ?: "[]") } catch (_: Exception) { JSONArray() }
}

class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ScheduledTaskMemory.restorePending(context)
            return
        }
        if (ScheduledTaskMemory.isCallDueAction(intent.action)) {
            val id = intent.getLongExtra("task_id", -1L)
            val contact = intent.getStringExtra("contact").orEmpty()
            if (id >= 0L && contact.isNotBlank()) ScheduledTaskMemory.onCallDue(context, id, contact)
        }
    }
}
