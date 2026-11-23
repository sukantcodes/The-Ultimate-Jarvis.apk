package com.jarvis.assistant.updates

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Entry point called whenever JARVIS comes to the foreground. It restores any download in progress, makes sure the
 * periodic background check is scheduled, and runs a check when automatic checking is on and the last one is stale.
 * The cooldown keeps the metadata requests to a handful per day; an ETag makes an unchanged answer nearly free.
 */
object UpdateNotificationChecker {
    private const val FOREGROUND_COOLDOWN_MS = 30L * 60L * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun checkOnForeground(context: Context) {
        val app = context.applicationContext
        val settings = OtaSettings(app)
        OtaManager.restoreAsync(app)
        OtaScheduler.ensure(app)
        if (!settings.autoCheck) return
        val now = System.currentTimeMillis()
        if (now - settings.lastCheckAt < FOREGROUND_COOLDOWN_MS) return
        scope.launch { OtaManager.check(app, manual = false) }
    }
}
