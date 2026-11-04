package com.jarvis.assistant.automation.action

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ActionLatencyRecord(
    val taskName: String, val path: String, val latencyMs: Long,
    val success: Boolean, val timestampMs: Long, val attemptedPaths: List<String>
)

/** Bounded local-only execution history; no network or external storage. */
class ActionLatencyStore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_action_latency", Context.MODE_PRIVATE)

    @Synchronized fun record(record: ActionLatencyRecord) {
        val all = recent().toMutableList()
        all.add(0, record)
        val json = JSONArray()
        all.take(MAX_RECORDS).forEach { item ->
            json.put(JSONObject().apply {
                put("task", item.taskName); put("path", item.path); put("latency", item.latencyMs)
                put("success", item.success); put("timestamp", item.timestampMs)
                put("attempted", JSONArray(item.attemptedPaths))
            })
        }
        prefs.edit().putString(KEY_RECORDS, json.toString()).apply()
    }

    @Synchronized fun recent(): List<ActionLatencyRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    val item = json.getJSONObject(i)
                    val paths = item.optJSONArray("attempted") ?: JSONArray()
                    add(ActionLatencyRecord(
                        item.optString("task", "Unknown"), item.optString("path", "UNKNOWN"),
                        item.optLong("latency"), item.optBoolean("success"), item.optLong("timestamp"),
                        buildList { for (j in 0 until paths.length()) add(paths.optString(j)) }
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun clear() { prefs.edit().remove(KEY_RECORDS).apply() }
    companion object { private const val KEY_RECORDS = "records_json"; private const val MAX_RECORDS = 200 }
}
