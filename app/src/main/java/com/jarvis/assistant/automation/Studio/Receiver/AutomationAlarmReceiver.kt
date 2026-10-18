package com.jarvis.assistant.automation.studio.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.jarvis.assistant.automation.studio.engine.AutomationEngine

class AutomationAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutomationAlarmReceiver"
        private const val WAKELOCK_TIMEOUT_MS = 10_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ruleId = intent.getStringExtra(AutomationEngine.EXTRA_RULE_ID)
        val source = intent.getStringExtra(AutomationEngine.EXTRA_TRIGGER_SOURCE) ?: "AlarmManager"

        Log.i(TAG, "Automation Alarm received for rule: $ruleId, source: $source")

        if (ruleId.isNullOrBlank()) {
            Log.w(TAG, "Received alarm without rule ID.")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "jarvis:automation_alarm_execution"
        )?.apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }

        try {
            val engine = AutomationEngine.getInstance(context)
            engine.executeRule(ruleId, source)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing scheduled rule '$ruleId'", e)
        } finally {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (_: Exception) {}
        }
    }
}
