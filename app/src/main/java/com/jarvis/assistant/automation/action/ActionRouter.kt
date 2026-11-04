package com.jarvis.assistant.automation.action

import android.content.Context
import android.util.Log

data class ActionAttempt(val success: Boolean, val message: String)
enum class ActionPath { DIRECT_SYSTEM_API, EXPLICIT_INTENT, DEEP_LINK, ACCESSIBILITY, SCREEN_VISION, LEGACY_AUTOMATION }

/** Executes adapters in strict native-first order; a failed tier falls through to the next. */
class ActionRouter(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
    private val store = ActionLatencyStore(appContext)
    val isEnabled: Boolean get() = prefs.getBoolean("key_native_action_router_enabled", false)

    suspend fun execute(
        taskName: String,
        directSystemApi: (suspend () -> ActionAttempt?)? = null,
        explicitIntent: (suspend () -> ActionAttempt?)? = null,
        deepLink: (suspend () -> ActionAttempt?)? = null,
        accessibility: (suspend () -> ActionAttempt?)? = null,
        screenVision: (suspend () -> ActionAttempt?)? = null,
        legacyAutomation: (suspend () -> ActionAttempt?)? = null
    ): ActionAttempt {
        val started = System.nanoTime()
        val attempted = mutableListOf<String>()
        val tiers = listOf(
            ActionPath.DIRECT_SYSTEM_API to directSystemApi,
            ActionPath.EXPLICIT_INTENT to explicitIntent,
            ActionPath.DEEP_LINK to deepLink,
            ActionPath.ACCESSIBILITY to accessibility,
            ActionPath.SCREEN_VISION to screenVision,
            ActionPath.LEGACY_AUTOMATION to legacyAutomation
        )
        var outcome = ActionAttempt(false, "No execution route was available.")
        var selected = "NONE"
        for ((path, adapter) in tiers) {
            if (adapter == null) continue
            attempted += path.name
            val result = try { adapter() } catch (error: Exception) {
                Log.w(TAG, "$taskName failed at $path: ${error.message}", error)
                ActionAttempt(false, error.message ?: "Execution failed at $path")
            } ?: continue
            outcome = result
            if (result.success) { selected = path.name; break }
        }
        if (selected == "NONE" && attempted.isNotEmpty()) selected = attempted.last()
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        store.record(ActionLatencyRecord(taskName, selected, elapsed, outcome.success, System.currentTimeMillis(), attempted))
        Log.i(TAG, "task=$taskName path=$selected success=${outcome.success} latencyMs=$elapsed attempted=${attempted.joinToString("->")}")
        return outcome
    }

    companion object { private const val TAG = "JarvisActionRouter" }
}
