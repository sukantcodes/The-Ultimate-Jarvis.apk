package com.jarvis.assistant.automation.studio.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.automation.studio.engine.AutomationEngine

class AutomationBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutomationBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Automation boot / system event received: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                try {
                    val engine = AutomationEngine.getInstance(context)
                    engine.rescheduleAllScheduledRules()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reschedule rules after $action: ${e.message}", e)
                }
            }
        }
    }
}
