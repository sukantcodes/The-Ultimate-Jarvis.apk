package com.jarvis.assistant.automation.studio.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import com.jarvis.assistant.automation.studio.engine.AutomationEngine
import com.jarvis.assistant.automation.studio.model.DeviceEventType

class AutomationDeviceReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutomationDeviceReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Device power event received: $action")

        val engine = AutomationEngine.getInstance(context)

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                engine.evaluateDeviceEvent(DeviceEventType.POWER_CONNECTED)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                engine.evaluateDeviceEvent(DeviceEventType.POWER_DISCONNECTED)
            }
            Intent.ACTION_BATTERY_LOW -> {
                engine.evaluateDeviceEvent(DeviceEventType.BATTERY_LOW, 15)
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) {
                    val pct = level * 100 / scale
                    if (pct <= 20) {
                        engine.evaluateDeviceEvent(DeviceEventType.BATTERY_LOW, pct)
                    } else if (pct >= 90) {
                        engine.evaluateDeviceEvent(DeviceEventType.BATTERY_FULL, pct)
                    }
                }
            }
        }
    }
}
