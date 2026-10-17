package com.jarvis.assistant.automation.studio

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Schedules one-time or daily rules using Android's inexact alarm API. */
object ScheduledRuleScheduler {
    const val ACTION_SCHEDULED_RULE = "com.jarvis.assistant.automation.SCHEDULED_RULE"
    private const val EXTRA_RULE_ID = "scheduled_rule_id"
    private const val EXTRA_DAILY = "scheduled_rule_daily"

    fun schedule(context: Context, rule: AutomationRule) {
        if (rule.triggerType != RuleTriggerType.SCHEDULED_TIME) return
        val parts = rule.triggerPattern.split('|')
        val time = parts.firstOrNull().orEmpty().split(':')
        if (time.size != 2) return
        val hour = time[0].toIntOrNull() ?: return
        val minute = time[1].toIntOrNull() ?: return
        val daily = parts.getOrNull(1) == "DAILY"
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context, rule.id, daily, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarmManager.cancel(pending)
        if (daily) alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, AlarmManager.INTERVAL_DAY, pending)
        else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pending)
    }

    fun cancel(context: Context, ruleId: String) {
        val pending = pendingIntent(context, ruleId, false, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
        pending.cancel()
        val daily = pendingIntent(context, ruleId, true, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (daily != null) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(daily)
            daily.cancel()
        }
    }

    private fun pendingIntent(context: Context, id: String, daily: Boolean, flags: Int): PendingIntent? {
        val intent = Intent(context, AutomationTriggerReceiver::class.java).apply {
            action = ACTION_SCHEDULED_RULE
            putExtra(EXTRA_RULE_ID, id)
            putExtra(EXTRA_DAILY, daily)
        }
        return PendingIntent.getBroadcast(context, id.hashCode() * 31 + if (daily) 1 else 0, intent, flags)
    }

    fun ruleId(intent: Intent): String? = intent.getStringExtra(EXTRA_RULE_ID)
    fun isDaily(intent: Intent): Boolean = intent.getBooleanExtra(EXTRA_DAILY, false)
}
