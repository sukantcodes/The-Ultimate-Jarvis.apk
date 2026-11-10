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
                override val spec = ToolSpec("list_scheduled_tasks",
                    "List active delayed-call reminders and notification watches with their IDs so the user can inspect or cancel them.",
                    emptyList(), Risk.READ, "task")
                override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult =
                    ToolResult.Ok(ScheduledTaskMemory.listScheduledTasks(app))
            },
            object : Tool {
                override val spec = ToolSpec("cancel_scheduled_task",
                    "Cancel one active delayed-call reminder or notification watch by its exact ID. Requires explicit user approval.",
                    listOf(ToolParam("task_id", required = true, description = "ID returned by list_scheduled_tasks.")),
                    Risk.DESTRUCTIVE, "task")
                override fun describe(args: JsonObject) = "Cancel scheduled task " + args.get("task_id")?.asString.orEmpty()
                override fun isDestructive(args: JsonObject, ctx: ToolContext) = true
                override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                    val id = args.get("task_id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    if (id.isBlank()) return ToolResult.Error("Provide the task ID from list_scheduled_tasks.")
                    return ToolResult.Ok(ScheduledTaskMemory.cancelScheduledTask(app, id))
                }
            },
            object : Tool {
                override val spec = ToolSpec("inspect_long_task",
                    "Read the latest JARVIS long-running task progress, completed steps, blockers and next step.",
                    emptyList(), Risk.READ, "task")
                override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                    val taskLedger = PrefsTaskStore(app).latest()?.promptSummary()
                    val chatWork = com.jarvis.assistant.provider.ChatBoxTaskJournal(app).summary()
                    val parts = listOfNotNull(
                        chatWork?.let { "CHATBOX WORK:\n$it" },
                        taskLedger?.let { "DEVICE TASK LEDGER:\n$it" }
                    )
                    return ToolResult.Ok(parts.joinToString("\n\n").ifBlank { "There is no saved long-running task yet." })
                }
            }
        )
    }
}
