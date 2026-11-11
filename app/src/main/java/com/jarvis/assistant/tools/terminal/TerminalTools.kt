package com.jarvis.assistant.tools.terminal

import com.google.gson.JsonObject
import com.jarvis.assistant.tools.*

private fun JsonObject.s(n: String): String? = get(n)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonObject.i(n: String): Int? = get(n)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
private fun JsonObject.b(n: String): Boolean? = get(n)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }

object TerminalTools {
    fun all(m: TerminalManager): List<Tool> = listOf(
        TerminalRun(m), TerminalRead(m), TerminalStop(m), TerminalSessions(m), TerminalEnvironmentTool(m), ProcessList(m), ProcessKill(m)
    )

    class TerminalRun(private val m: TerminalManager) : Tool {
        override val spec = ToolSpec(
            "terminal_run",
            "Run a shell command in the workspace terminal and return its output and exit code. Commands stay inside the workspace folder. Use background=true for servers or long tasks, then terminal_read to follow them.",
            listOf(
                ToolParam("command", required = true, description = "The shell command line."),
                ToolParam("session", description = "Terminal session name (default \"main\"). Use separate sessions for parallel work."),
                ToolParam("timeout_seconds", "integer", "Stop the command after this long (default 60, at most 900)."),
                ToolParam("background", "boolean", "Start it and return immediately.")
            ), Risk.EXECUTE, "terminal"
        )

        private fun verdict(args: JsonObject) = m.guard.classify(args.s("command").orEmpty())

        override fun describe(args: JsonObject): String {
            val cmd = args.s("command").orEmpty().trim()
            val g = verdict(args)
            val session = args.s("session") ?: TerminalManager.DEFAULT_ID
            val where = m.get(session)?.relativeCwd() ?: "."
            val shown = if (cmd.length > 400) cmd.take(400) + "…" else cmd
            return when (g.verdict) {
                CommandGuard.Verdict.SAFE -> "Run in session \"$session\" (folder: $where):\n$shown"
                CommandGuard.Verdict.DESTRUCTIVE -> "Run in session \"$session\" (folder: $where):\n$shown\n⚠ ${g.summary}"
                CommandGuard.Verdict.BLOCKED -> "REFUSED (will not run): $shown\n⛔ ${g.summary}"
            }
        }

        // Blocked commands are also treated as destructive so they can never be auto-approved by a session grant.
        override fun isDestructive(args: JsonObject, ctx: ToolContext) = verdict(args).verdict != CommandGuard.Verdict.SAFE

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val command = args.s("command")?.trim().orEmpty()
            val g = m.guard.classify(command)
            if (g.verdict == CommandGuard.Verdict.BLOCKED) return ToolResult.Error("Refused: ${g.summary}. This command is never run.")
            val session = try { m.getOrCreate(args.s("session") ?: TerminalManager.DEFAULT_ID) } catch (e: IllegalArgumentException) { return ToolResult.Error(e.message ?: "Bad session name.") } catch (e: IllegalStateException) { return ToolResult.Error(e.message ?: "No session available.") }

            if (args.b("background") == true) {
                session.preflight(command)?.let { return ToolResult.Error(it) }
                session.startBackground(command)?.let { return ToolResult.Error(it) }
                return ToolResult.Ok("Started in the background in session \"${session.id}\" (pid ${session.runningPid ?: "unknown"}). Use terminal_read to see output and terminal_stop to stop it.")
            }
            val timeout = ((args.i("timeout_seconds") ?: 60).coerceIn(1, 900)) * 1000L
            var last = 0L
            val r = session.run(command, timeout) { chunk ->
                val now = System.currentTimeMillis()
                if (now - last > 500) { last = now; chunk.trim().lineSequence().lastOrNull { it.isNotBlank() }?.let { ctx.progress(it.take(120)) } }
            }
            r.rejected?.let { return ToolResult.Error(it) }
            val status = when {
                r.timedOut -> "[Stopped: it ran longer than ${timeout / 1000} s and was killed]"
                r.cancelled -> "[Stopped before it finished]"
                else -> "[exit code ${r.exitCode}, ${"%.1f".format(r.elapsedMs / 1000.0)} s, folder: ${r.cwd}]"
            }
            val body = (if (r.outputTruncated) "… (earlier output cut)\n" else "") + r.output
            val text = if (body.isBlank()) status else "$body\n$status"
            return if (r.ok) ToolResult.Ok(text) else ToolResult.Error(text)
        }
    }

    class TerminalRead(private val m: TerminalManager) : Tool {
        override val spec = ToolSpec("terminal_read", "Read output a terminal session produced since the last read, and say whether a command is still running.",
            listOf(ToolParam("session", description = "Session name (default \"main\")."), ToolParam("max_chars", "integer", "Default 20000.")), Risk.READ, "terminal")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val s = m.get(args.s("session") ?: TerminalManager.DEFAULT_ID) ?: return ToolResult.Error("No such session.")
            val out = s.readNew((args.i("max_chars") ?: 20_000).coerceIn(100, 100_000))
            val state = if (s.isRunning) "[still running: ${s.runningCommand?.take(80)}]" else "[idle; last exit code ${s.lastExitCode ?: "n/a"}; folder: ${s.relativeCwd()}]"
            return ToolResult.Ok(if (out.isBlank()) "(no new output)\n$state" else "$out\n$state")
        }
    }

    class TerminalStop(private val m: TerminalManager) : Tool {
        override val spec = ToolSpec("terminal_stop", "Stop the command running in a session (and everything it started). With close=true the session is removed too.",
            listOf(ToolParam("session", description = "Session name (default \"main\")."), ToolParam("close", "boolean")), Risk.READ, "terminal")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val id = args.s("session") ?: TerminalManager.DEFAULT_ID
            val s = m.get(id) ?: return ToolResult.Error("No such session.")
            val stopped = s.cancel()
            if (args.b("close") == true) m.close(id)
            return ToolResult.Ok(if (stopped) "Stopped the command in \"$id\"." else "Nothing was running in \"$id\".")
        }
    }

    class TerminalSessions(private val m: TerminalManager) : Tool {
        override val spec = ToolSpec("terminal_sessions", "List terminal sessions with their folder and whether a command is running.", emptyList(), Risk.READ, "terminal")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val l = m.list()
            if (l.isEmpty()) return ToolResult.Ok("No terminal sessions yet. terminal_run creates \"main\".")
            return ToolResult.Ok(l.joinToString("\n") { "${it.id}: folder ${it.relativeCwd()}, " + (if (it.isRunning) "running \"${it.runningCommand?.take(60)}\"" else "idle") + ", ${it.history.size} command(s) run" })
        }
    }

    class TerminalEnvironmentTool(private val m: TerminalManager) : Tool {
        override val spec = ToolSpec("terminal_environment", "Report which shell, working folder and programs (git, node, npm, python, ...) the terminal can really use.", emptyList(), Risk.READ, "terminal")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = ToolResult.Ok(TerminalEnvironment.detect(m).format())
    }

    class ProcessList(private val m: TerminalManager) : Tool {
        override val spec = ToolSpec("process_list", "List commands started from the terminal that are still running.", emptyList(), Risk.READ, "terminal")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val p = m.processes()
            if (p.isEmpty()) return ToolResult.Ok("No running terminal commands.")
            val now = System.currentTimeMillis()
            return ToolResult.Ok(p.joinToString("\n") { "pid ${it.pid ?: "?"} · session ${it.sessionId} · ${(now - it.startedAt) / 1000}s · ${it.command.take(80)}" })
        }
    }

    class ProcessKill(private val m: TerminalManager) : Tool {
        override val spec = ToolSpec("process_kill", "Stop a command started from the terminal, by session name or pid. Other processes can not be stopped.",
            listOf(ToolParam("target", required = true, description = "Session name or pid.")), Risk.EXECUTE, "terminal")
        override fun describe(args: JsonObject) = "Stop terminal command: ${args.s("target")}"
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = ToolResult.Ok(m.kill(args.s("target").orEmpty().trim()))
    }
}
