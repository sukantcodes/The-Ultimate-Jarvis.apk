package com.jarvis.assistant.automation.tasks

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
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
        val maxDelay = 30L * 24L * 60L * 60L * 1000L
        if (delayMillis !in 1_000L..maxDelay) {
            return "I can schedule calls between 1 second and 30 days from now. Please choose a supported delay."
        }
        val now = System.currentTimeMillis()
        val id = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)
        val dueAt = now + delayMillis
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val items = readArray(prefs.getString(CALLS, "[]"))
        items.put(JSONObject().put("id", id).put("contact", cleanContact).put("dueAt", dueAt))
        prefs.edit().putString(CALLS, items.toString()).apply()
        scheduleCallAlarm(context, id, dueAt, cleanContact)
        val minutes = (delayMillis / 60_000L).coerceAtLeast(1L)
        return "Okay. I saved a reminder to call $contact in about $minutes minute(s). I will notify you then; you will tap to place the call."
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

    /** Lists reminder and one-shot notification-watch metadata without exposing notification contents. */
    fun listScheduledTasks(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val calls = readArray(prefs.getString(CALLS, "[]"))
        val watches = readArray(prefs.getString(WATCHES, "[]"))
        val output = ArrayList<String>()
        for (i in 0 until calls.length()) {
            val item = calls.optJSONObject(i) ?: continue
            output += "CALL id=" + item.optLong("id") + " contact=" + item.optString("contact") +
                " dueAt=" + item.optLong("dueAt")
        }
        for (i in 0 until watches.length()) {
            val item = watches.optJSONObject(i) ?: continue
            output += "WATCH id=" + item.optString("id") + " app=" + item.optString("app") +
                " keyword=" + item.optString("keyword")
        }
        return if (output.isEmpty()) "There are no active scheduled reminders or notification watches."
        else output.joinToString("\n")
    }

    /** Cancels exactly one reminder or notification watch by its returned ID. */
    fun cancelScheduledTask(context: Context, taskId: String): String {
        val id = taskId.trim()
        if (id.isBlank() || id.length > 64) return "Provide a valid task ID."
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val calls = readArray(prefs.getString(CALLS, "[]"))
        val remainingCalls = JSONArray()
        var removedCall = false
        var callId: Long? = null
        for (i in 0 until calls.length()) {
            val item = calls.optJSONObject(i) ?: continue
            if (item.optLong("id").toString() == id) {
                removedCall = true
                callId = item.optLong("id")
            } else remainingCalls.put(item)
        }
        val watches = readArray(prefs.getString(WATCHES, "[]"))
        val remainingWatches = JSONArray()
        var removedWatch = false
        for (i in 0 until watches.length()) {
            val item = watches.optJSONObject(i) ?: continue
            if (item.optString("id") == id) removedWatch = true else remainingWatches.put(item)
        }
        if (!removedCall && !removedWatch) return "No active scheduled task was found with that ID."
        prefs.edit().putString(CALLS, remainingCalls.toString())
            .putString(WATCHES, remainingWatches.toString()).apply()
        callId?.let { value ->
            val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
                action = ACTION_CALL_DUE
                data = Uri.parse("jarvis-task://call/" + value)
            }
            val pending = PendingIntent.getBroadcast(
                context, value.hashCode(), intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
                pending.cancel()
            }
        }
        return if (removedCall) "Scheduled call reminder cancelled." else "Notification watch cancelled."
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
        val detail = when {
            matches.size > 1 -> "I found more than one contact named $contact. Open JARVIS to choose the right one."
            matches.isEmpty() -> "Open JARVIS to check contact access or the saved contact name for $contact."
            else -> "Your reminder is due. Tap Open dialer when you are ready to place the call."
        }
        // A reminder must never initiate a phone call in the background. The user always makes the final call.
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
