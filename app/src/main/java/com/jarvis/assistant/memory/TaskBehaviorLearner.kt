package com.jarvis.assistant.memory

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Learns only coarse task categories after the task engine verifies completion.
 * It stores no raw transcript, contact names, message bodies, or screen contents.
 *
 * The profile is a ranking hint, never an authorization source: a frequent task
 * must still be explicitly requested with the Jarvis wake phrase.
 */
object TaskBehaviorLearner {
    private const val PREFS = "jarvis_task_behavior_v1"
    private const val KEY_COUNTS = "verified_category_counts"
    private const val KEY_UPDATED = "profile_updated_at"

    private val categories = linkedMapOf(
        "messaging" to listOf("message", "whatsapp", "sms", "text", "send"),
        "calling" to listOf("call", "phone", "dial"),
        "media playback" to listOf("youtube", "spotify", "play music", "play video", "song"),
        "app navigation" to listOf("open app", "launch", "open instagram", "open whatsapp"),
        "social actions" to listOf("like", "comment", "follow", "unlike", "save post"),
        "device controls" to listOf("brightness", "volume", "wifi", "bluetooth", "flashlight", "battery"),
        "search and browsing" to listOf("search", "google", "chrome", "website", "find"),
        "time and reminders" to listOf("alarm", "timer", "remind", "schedule")
    )

    fun categoryFor(goal: String): String {
        val normalized = goal.lowercase(Locale.ROOT)
        return categories.entries.firstOrNull { (_, terms) -> terms.any(normalized::contains) }?.key
            ?: "general assistance"
    }

    @Synchronized
    fun recordVerifiedCompletion(context: Context, goal: String) {
        if (goal.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val counts = try {
            JSONObject(prefs.getString(KEY_COUNTS, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val category = categoryFor(goal)
        counts.put(category, counts.optInt(category, 0) + 1)
        prefs.edit()
            .putString(KEY_COUNTS, counts.toString())
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun promptSection(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val counts = try {
            JSONObject(prefs.getString(KEY_COUNTS, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val ranked = categories.keys.mapNotNull { category ->
            val count = counts.optInt(category, 0)
            if (count > 0) category to count else null
        }.sortedByDescending { it.second }.take(5)
        if (ranked.isEmpty()) return ""
        val summary = ranked.joinToString("; ") { "${it.first}: ${it.second} verified completions" }
        return """
            
            USER TASK BEHAVIOUR PROFILE (learned from verified completions):
            $summary
            Use this only to reduce friction and choose relevant explanations or efficient known workflows after the user explicitly asks.
            Never infer a command from this profile, never act on overheard conversation, and never skip required clarification, confirmation, or Android permission.
        """.trimIndent()
    }
}
