package com.jarvis.assistant.automation.studio

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Registers broadcasts that Android only delivers to dynamically registered receivers
 * (notably ACTION_BATTERY_CHANGED). Must be started by an application/service owner and
 * stopped when that owner is disposed. This monitor does not keep the process alive.
 */
class AutomationTriggerMonitor(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val receiver = AutomationTriggerReceiver()
    private var registered = false

    @Synchronized
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction("android.net.wifi.STATE_CHANGE")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
    }

    @Synchronized
    override fun close() {
        if (!registered) return
        try { appContext.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        registered = false
    }
}
