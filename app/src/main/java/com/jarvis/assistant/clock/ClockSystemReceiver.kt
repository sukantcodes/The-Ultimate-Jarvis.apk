package com.jarvis.assistant.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Puts every saved alarm back with Android when something that clears or shifts them happens:
 *  - the phone restarted, or JARVIS was updated (Android forgets all alarms; also reports alarms missed in the meantime);
 *  - the time zone changed, or the date/time was set by hand (alarms are wall-clock times, so they are recalculated).
 * After a restart this runs once the phone is first unlocked, which is when Android lets apps read their saved data.
 */
class ClockSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val detectMissed = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
        val handled = detectMissed || action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED
        if (!handled) return

        try {
            val engine = ClockServices.engine(context)
            val report = engine.restoreAll(detectMissed)
            Log.i(TAG, "$action: rescheduled=${report.rescheduled} missed=${report.missed.size} failed=${report.failed.size}")
            val settings = engine.settings()
            report.missed.forEach { AlarmNotifications.postMissed(context, it.alarm, settings) }
            if (report.failed.isNotEmpty()) {
                AlarmNotifications.postProblem(context, context.getString(com.jarvis.assistant.R.string.clock_err_restore_failed, report.failed.size))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore alarms after $action: ${e.message}", e)
            AlarmNotifications.postProblem(context, context.getString(com.jarvis.assistant.R.string.clock_err_restore_failed, 0))
        }
    }

    private companion object {
        const val TAG = "ClockSystemReceiver"
    }
}
