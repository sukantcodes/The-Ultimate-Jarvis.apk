package com.jarvis.assistant.automation.studio

import android.content.Context
import android.util.Log

/**
 * Host integration seam for background trigger events.
 *
 * Install the handler from JarvisApp.onCreate after constructing the Round 2 repository
 * and executor. Leave unset until the dispatcher is wired; receivers will safely no-op.
 */
object AutomationTriggerSink {
    fun interface Handler {
        suspend fun onTrigger(context: Context, trigger: RuleTriggerType, payload: Map<String, String>)
    }

    @Volatile
    var handler: Handler? = null

    suspend fun dispatch(context: Context, trigger: RuleTriggerType, payload: Map<String, String>) {
        val active = handler
        if (active == null) {
            Log.i("AutomationTriggerSink", "No trigger handler installed; ignoring $trigger until integration is complete.")
            return
        }
        active.onTrigger(context.applicationContext, trigger, payload)
    }
}
