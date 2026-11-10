package com.jarvis.assistant.provider

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable checkpoints for multi-round ChatBox work; stores tool names and outcomes, never prompts or arguments. */
class ChatBoxTaskJournal(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("chatbox_task_journal", Context.MODE_PRIVATE)
    init {
        synchronized(ChatBoxTaskJournal::class.java) {
            if (!processInitialized) {
                markInterruptedAfterProcessRestart()
                processInitialized = true
            }
        }
    }

    @Synchronized fun begin(turnId: String) {
        markRunningAsInterrupted()
        val now = System.currentTimeMillis()
        save(JSONObject().put("turnId", turnId).put("status", "RUNNING").put("startedAt", now)
            .put("updatedAt", now).put("steps", JSONArray()))
    }

    @Synchronized fun toolStarted(turnId: String, toolName: String) {
        val record = current(turnId) ?: return
        val steps = record.optJSONArray("steps") ?: JSONArray()
        steps.put(JSONObject().put("tool", toolName.take(64)).put("status", "RUNNING").put("startedAt", System.currentTimeMillis()))
        record.put("steps", steps).put("updatedAt", System.currentTimeMillis())
        save(record)
    }

    @Synchronized fun toolFinished(turnId: String, toolName: String, success: Boolean) {
        val record = current(turnId) ?: return
        val steps = record.optJSONArray("steps") ?: JSONArray()
        for (index in steps.length() - 1 downTo 0) {
            val step = steps.optJSONObject(index) ?: continue
            if (step.optString("tool") == toolName && step.optString("status") == "RUNNING") {
                step.put("status", if (success) "COMPLETED" else "NEEDS_ATTENTION")
                step.put("finishedAt", System.currentTimeMillis())
                break
            }
        }
        record.put("steps", steps).put("updatedAt", System.currentTimeMillis())
        save(record)
    }

    @Synchronized fun finish(turnId: String, status: String) {
        val record = current(turnId) ?: return
        record.put("status", status.take(24)).put("updatedAt", System.currentTimeMillis())
        save(record)
    }

    @Synchronized fun summary(): String? {
        val record = readCurrent() ?: return null
        if (System.currentTimeMillis() - record.optLong("updatedAt", 0L) > MAX_AGE_MS) return null
        val steps = record.optJSONArray("steps") ?: JSONArray()
        val completed = ArrayList<String>()
        val attention = ArrayList<String>()
        val running = ArrayList<String>()
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index) ?: continue
            val name = step.optString("tool", "tool")
            when (step.optString("status")) {
                "COMPLETED" -> completed += name
                "NEEDS_ATTENTION" -> attention += name
                "RUNNING" -> running += name
            }
        }
        return buildString {
            append("turn=").append(record.optString("turnId").take(12))
            append(" status=").append(record.optString("status"))
            append(" completed=").append(completed.size)
            if (completed.isNotEmpty()) append(" [").append(completed.joinToString()).append(']')
            if (running.isNotEmpty()) append(" interrupted_or_running=[").append(running.joinToString()).append(']')
            if (attention.isNotEmpty()) append(" needs_attention=[").append(attention.joinToString()).append(']')
            append(". These are checkpoints, not proof of final correctness. Do not repeat completed non-idempotent actions; inspect workspace or remote state before continuing.")
        }
    }

    private fun current(turnId: String): JSONObject? = readCurrent()?.takeIf { it.optString("turnId") == turnId && it.optString("status") == "RUNNING" }
    private fun readCurrent(): JSONObject? = runCatching { prefs.getString(KEY_CURRENT, null)?.let(::JSONObject) }.getOrNull()
    private fun save(record: JSONObject) { prefs.edit().putString(KEY_CURRENT, record.toString()).apply() }
    private fun markInterruptedAfterProcessRestart() { synchronized(this) { markRunningAsInterrupted() } }
    private fun markRunningAsInterrupted() {
        val record = readCurrent() ?: return
        if (record.optString("status") == "RUNNING") {
            record.put("status", "INTERRUPTED").put("updatedAt", System.currentTimeMillis())
            save(record)
        }
    }
    private companion object {
        @Volatile private var processInitialized = false
        const val KEY_CURRENT = "current"
        const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
