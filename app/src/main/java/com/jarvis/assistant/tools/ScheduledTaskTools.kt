package com.jarvis.assistant.tools

import android.content.Context
import com.google.gson.JsonObject
import com.jarvis.assistant.automation.task.PrefsTaskStore
import com.jarvis.assistant.automation.tasks.ScheduledTaskMemory

/** ChatBox access to JARVIS' existing persistent delayed-task and notification-watch services. */
object ScheduledTaskTools {
    fun all(context: Context): List<Tool> {
        val app = context.applicationContext
        return listOf(
            object : Tool {
                override val spec = ToolSpec("schedule_call_reminder",
                    "Schedule a reminder to call a contact later. At the due time JARVIS shows a notification with a dialer action; it never places a call automatically.",
                    listOf(ToolParam("contact", required = true, description = "Contact name to look up when the reminder is due."),
                        ToolParam("delay_minutes", "integer", "Minutes from now, between 1 and 43200.", required = true)),
                    Risk.WRITE, "task")
                override fun describe(args: JsonObject) = "Schedule a call reminder for ${args.get("contact")?.asString.orEmpty()} in ${args.get("delay_minutes")?.asInt ?: 0} minute(s)"
                override fun isDestructive(args: JsonObject, ctx: ToolContext) = true
                override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                    val contact = args.get("contact")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                    val minutes = args.get("delay_minutes")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
                        ?: return ToolResult.Error("delay_minutes must be an integer.")
                    if (contact.isBlank()) return ToolResult.Error("Tell me the contact name.")
                    if (minutes !in 1L..43_200L) return ToolResult.Error("Choose a delay from 1 minute to 30 days.")
                    return ToolResult.Ok(ScheduledTaskMemory.scheduleCall(app, contact, minutes * 60_000L))
                }
            },
            object : Tool {
                override val spec = ToolSpec("watch_app_notification",
                    "Watch one app's next matching notification and alert the user. Notification content is inspected transiently and is not stored.",
                    listOf(ToolParam("app", required = true, description = "App name, such as Blinkit, or any."),
                        ToolParam("keyword", description = "Optional event keyword, such as delivered. Use any for all notifications.")),
                    Risk.WRITE, "task")
                override fun describe(args: JsonObject) = "Watch ${args.get("app")?.asString.orEmpty()} notifications for ${args.get("keyword")?.asString ?: "any event"}"
                override fun isDestructive(args: JsonObject, ctx: ToolContext) = true
                override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                    val appName = args.get("app")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    val keyword = args.get("keyword")?.takeIf { it.isJsonPrimitive }?.asString ?: "any"
                    if (appName.isBlank()) return ToolResult.Error("Tell me which app to monitor.")
                    return ToolResult.Ok(ScheduledTaskMemory.watchAppEvent(app, appName, keyword))
                }
            },
            object : Tool {
                override val spec = ToolSpec("inspect_long_task",
                    "Read the latest JARVIS long-running task progress, completed steps, blockers and next step.",
                    emptyList(), Risk.READ, "task")
                override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                    val latest = PrefsTaskStore(app).latest() ?: return ToolResult.Ok("There is no saved long-running task yet.")
                    return ToolResult.Ok(latest.promptSummary())
                }
            }
        )
    }
}
