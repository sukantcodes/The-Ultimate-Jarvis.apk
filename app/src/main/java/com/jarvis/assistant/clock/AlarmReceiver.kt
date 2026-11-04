package com.jarvis.assistant.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Android calls this when an alarm's time arrives, even if JARVIS is closed and its process was killed (Android starts the
 * process for it). It asks the shared engine whether the alarm should ring (it may have been deleted, switched off, or
 * delivered twice) and, if so, starts the ringing service.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_FIRED) return
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ID, -1L)
        if (id < 0L) return
        val trigger = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER, 0L)
        val kind = runCatching { TriggerKind.valueOf(intent.getStringExtra(AlarmScheduler.EXTRA_KIND) ?: "") }
            .getOrDefault(TriggerKind.REGULAR)

        val outcome = try {
            ClockServices.engine(context).onTriggered(id, trigger, kind)
        } catch (e: Exception) {
            Log.e(TAG, "Alarm $id could not be checked: ${e.message}", e)
            // If the check itself failed, ringing is safer than staying silent.
            TriggerOutcome(true, null, "check failed")
        }
        if (!outcome.ring) {
            Log.i(TAG, "Alarm $id ($kind) not rung: ${outcome.reason}")
            return
        }
        try {
            AlarmRingService.start(context, id)
        } catch (e: Exception) {
            // The system refused to start the service: at least show a notification the person can act on.
            Log.e(TAG, "Could not start the ring service: ${e.message}", e)
            AlarmNotifications.postProblem(context, context.getString(com.jarvis.assistant.R.string.clock_err_ring_service))
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
