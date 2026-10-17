package com.jarvis.assistant.automation.studio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Adapter for system broadcasts. It does not perform calls/messages directly.
 * A host-installed AutomationTriggerSink decides which enabled rules should run.
 */
class AutomationTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val job = SupervisorJob()
        CoroutineScope(Dispatchers.IO + job).launch {
            try {
                val extras = intent.extras
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = extras?.getInt(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale = extras?.getInt(BatteryManager.EXTRA_SCALE, 100) ?: 100
                        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                        val status = extras?.getInt(BatteryManager.EXTRA_STATUS, -1) ?: -1
                        if (percent >= 0) {
                            AutomationTriggerSink.dispatch(appContext, RuleTriggerType.BATTERY_BELOW, mapOf("level" to percent.toString()))
                            if (status == BatteryManager.BATTERY_STATUS_FULL) {
                                AutomationTriggerSink.dispatch(appContext, RuleTriggerType.BATTERY_FULL, mapOf("level" to percent.toString()))
                            }
                        }
                        val plugged = extras?.getInt(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                        if (plugged != 0) AutomationTriggerSink.dispatch(appContext, RuleTriggerType.CHARGER_CONNECTED, mapOf("plugged" to plugged.toString()))
                        else AutomationTriggerSink.dispatch(appContext, RuleTriggerType.CHARGER_DISCONNECTED, emptyMap())
                    }
                    Intent.ACTION_POWER_CONNECTED ->
                        AutomationTriggerSink.dispatch(appContext, RuleTriggerType.CHARGER_CONNECTED, emptyMap())
                    Intent.ACTION_POWER_DISCONNECTED ->
                        AutomationTriggerSink.dispatch(appContext, RuleTriggerType.CHARGER_DISCONNECTED, emptyMap())
                    Intent.ACTION_BOOT_COMPLETED ->
                        AutomationTriggerSink.dispatch(appContext, RuleTriggerType.DEVICE_BOOT, emptyMap())
                    "android.net.wifi.STATE_CHANGE" -> {
                        val info = @Suppress("DEPRECATION") (intent.getParcelableExtra<android.net.wifi.WifiInfo>("wifiInfo"))
                        val connected = info?.networkId?.let { it >= 0 } == true
                        AutomationTriggerSink.dispatch(
                            appContext,
                            if (connected) RuleTriggerType.WIFI_CONNECTED else RuleTriggerType.WIFI_DISCONNECTED,
                            mapOf("connected" to connected.toString())
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e("AutomationTriggerReceiver", "Could not dispatch automation trigger", t)
            } finally {
                pending.finish()
                job.cancel()
            }
        }
    }
}
