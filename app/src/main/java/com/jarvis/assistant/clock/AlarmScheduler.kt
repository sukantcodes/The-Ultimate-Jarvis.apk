package com.jarvis.assistant.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.ui.clock.AlarmsActivity

/**
 * Hands alarms to Android's AlarmManager.
 *
 * It uses [AlarmManager.setAlarmClock]: the call Android provides for real alarm clocks. It is exact, it wakes the phone
 * from Doze, it works with the app closed, and it needs NO special exact-alarm permission on any Android version (the
 * Android 12+ "Alarms & reminders" permission does not apply to it). The system also shows the next alarm in the status
 * bar and on the lock screen.
 *
 * Android has no call that lists one app's alarms, so "verified" means: Android accepted the call without an error and the
 * registration (a PendingIntent keyed by alarm id and kind) now exists. After a restart the registrations are gone and
 * [ClockEngine.restoreAll] makes them again.
 */
class AlarmScheduler(private val context: Context) : AlarmSchedulerPort {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun schedule(alarm: AlarmItem, triggerAtMillis: Long, kind: TriggerKind): ScheduleOutcome {
        return try {
            val operation = pending(alarm.id, kind, triggerAtMillis, create = true)
                ?: return ScheduleOutcome.Rejected(ClockError.SCHEDULE_FAILED, "Android did not create the alarm registration.")
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, AlarmsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), operation)
            ScheduleOutcome.Scheduled
        } catch (e: SecurityException) {
            Log.w(TAG, "Android refused the alarm: ${e.message}")
            ScheduleOutcome.Rejected(ClockError.EXACT_ALARM_DENIED, "Android refused to schedule the alarm: ${e.message ?: "permission denied"}")
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling failed", e)
            ScheduleOutcome.Rejected(ClockError.SCHEDULE_FAILED, "Android could not schedule the alarm: ${e.message ?: "unknown error"}")
        }
    }

    override fun cancel(alarmId: Long, kind: TriggerKind) {
        try {
            val existing = pending(alarmId, kind, 0L, create = false) ?: return
            alarmManager.cancel(existing)
            existing.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Cancel failed for $alarmId/$kind: ${e.message}")
        }
    }

    override fun isScheduled(alarmId: Long, kind: TriggerKind): Boolean =
        try { pending(alarmId, kind, 0L, create = false) != null } catch (_: Exception) { false }

    private fun pending(id: Long, kind: TriggerKind, triggerAtMillis: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRED
            // The data URI makes each (alarm, kind) pair its own registration.
            data = Uri.parse("jarvis-alarm://$id/${kind.name}")
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_TRIGGER, triggerAtMillis)
            putExtra(EXTRA_KIND, kind.name)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, requestCode(id, kind), intent, flags)
    }

    private fun requestCode(id: Long, kind: TriggerKind): Int = ((id % 1_000_000L).toInt() * 2) + kind.ordinal

    companion object {
        private const val TAG = "AlarmScheduler"
        const val ACTION_FIRED = "com.jarvis.assistant.clock.ALARM_FIRED"
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_TRIGGER = "trigger_millis"
        const val EXTRA_KIND = "trigger_kind"
    }
}
