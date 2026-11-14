package com.jarvis.assistant.tools.terminal

import com.google.gson.JsonObject
import com.jarvis.assistant.tools.*

private fun JsonObject.str(n: String): String? = get(n)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonObject.int(n: String): Int? = get(n)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

/**
 * Extra terminal tools that let Jarvis look at everything in the terminal, including the tabs the person opens in
 * the Jarvis Terminal screen: see a tab's whole recent output without using up "new output since last read",
 * go through the commands that were run, and wipe a tab's screen.
 */
object TerminalAdminTools {
    fun all(m: TerminalManager): List<Tool> = listOf(TerminalTail(m), TerminalHistory(m), TerminalClear(m))

    private fun label(m: TerminalManager, id: String) = if (m.isUserSession(id)) "$id (opened by the user in Jarvis Terminal)" else id

    class TerminalTail(private val m: TerminalManager) : Tool {
        override val spec = ToolSpec(
            "terminal_tail",
            "Show the most recent output of any terminal session, including tabs the user opened in the Jarvis Terminal screen (named me1, me2, ...). " +
                "Unlike terminal_read this does not use up the output, so it can be called again to look at the same text.",
            listOf(
                ToolParam("session", description = "Session name (default \"main\"). Use terminal_sessions to see all of them."),
                ToolParam("max_chars", "integer", "How much of the end of the output to show (default 8000, at most 100000).")
            ), Risk.READ, "terminal"
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val id = args.str("session") ?: TerminalManager.DEFAULT_ID
            val s = m.get(id) ?: return ToolResult.Error("No such session \"$id\". terminal_sessions lists them.")
            val out = s.buffer.tail((args.int("max_chars") ?: 8_000).coerceIn(100, 100_000))
            val state = if (s.isRunning) "[still running: ${s.runningCommand?.take(80)}]" else "[idle; last exit code ${s.lastExitCode ?: "n/a"}; folder: ${s.relativeCwd()}]"
            return ToolResult.Ok((if (out.isBlank()) "(this session has no output)" else out) + "\n" + label(m, id) + " " + state)
        }
    }

    class TerminalHistory(private val m: TerminalManager) : Tool {
        override val spec = ToolSpec(
            "terminal_history",
            "List the commands that were run in a terminal session (oldest first) with their exit codes. Works for the user's Jarvis Terminal tabs too.",
            listOf(
                ToolParam("session", description = "Session name (default \"main\")."),
                ToolParam("limit", "integer", "How many of the latest commands (default 30, at most 200).")
            ), Risk.READ, "terminal"
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val id = args.str("session") ?: TerminalManager.DEFAULT_ID
            val s = m.get(id) ?: return ToolResult.Error("No such session \"$id\".")
            val items = s.history.takeLast((args.int("limit") ?: 30).coerceIn(1, 200))
            if (items.isEmpty()) return ToolResult.Ok("No commands have been run in \"$id\" yet.")
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            return ToolResult.Ok(items.joinToString("\n") { e ->
                "${fmt.format(java.util.Date(e.startedAt))}  [${e.exitCode?.toString() ?: "running/unknown"}]  ${e.command.take(300)}"
            })
        }
    }

    class TerminalClear(private val m: TerminalManager) : Tool {
        override val spec = ToolSpec(
            "terminal_clear",
            "Clear the visible output of a terminal session. Commands, files and the session itself are not touched.",
            listOf(ToolParam("session", description = "Session name (default \"main\").")), Risk.WRITE, "terminal"
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val id = args.str("session") ?: TerminalManager.DEFAULT_ID
            return if (m.clear(id)) ToolResult.Ok("Cleared the output of \"$id\".") else ToolResult.Error("No such session \"$id\".")
        }
    }
}
