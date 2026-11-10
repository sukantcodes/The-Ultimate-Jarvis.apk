package com.jarvis.assistant.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** User-approved shell runner scoped to the coding workspace and audited by ToolExecutor. */
class CodingShellTool : Tool {
    override val spec = ToolSpec(
        name = "shell",
        description = "Run a shell command in the selected project directory. Long-running commands can write logs in .clozed/background.",
        params = listOf(
            ToolParam("command", "string", "Shell command to execute", true),
            ToolParam("workdir", "string", "Project-relative working directory", false),
            ToolParam("timeout", "integer", "Timeout in milliseconds, default 120000", false),
            ToolParam("background", "boolean", "Start without waiting and save output to a log file", false)
        ),
        risk = Risk.EXECUTE,
        group = "terminal"
    )

    override fun describe(args: JsonObject): String =
        "Run terminal command in ${args.get("workdir")?.asString ?: "."}: ${args.get("command")?.asString.orEmpty().take(1600)}"

    override fun isDestructive(args: JsonObject, ctx: ToolContext): Boolean {
        val command = args.get("command")?.asString.orEmpty().lowercase()
        return Regex("""(^|[;&|]\s*)(rm\s+-rf|mkfs|dd\s+if=|wipefs|shred|git\s+push|git\s+reset\s+--hard|pm\s+install|reboot|shutdown)(\s|$)""")
            .containsMatchIn(command)
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.get("command")?.asString.orEmpty().trim()
        if (command.isBlank() || command.length > 4000) return ToolResult.Error("Command is empty or exceeds 4000 characters.")
        val workdir = try { ctx.workspace.resolve(args.get("workdir")?.asString ?: ".") }
        catch (error: Exception) { return ToolResult.Error(error.message ?: "Invalid working directory.") }
        if (!workdir.isDirectory) return ToolResult.Error("Working directory does not exist.")
        val timeoutMs = args.get("timeout")?.let { runCatching { it.asLong }.getOrNull() }?.coerceIn(1000L, 120000L) ?: 120000L
        val background = args.get("background")?.let { runCatching { it.asBoolean }.getOrNull() } == true
        return try {
            if (background) {
                val logDir = ctx.workspace.resolve(".clozed/background")
                if (!logDir.exists() && !logDir.mkdirs()) return ToolResult.Error("Could not create the background log folder.")
                val log = File(logDir, "shell-${System.currentTimeMillis()}.log")
                ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(workdir)
                    .redirectErrorStream(true)
                    .redirectOutput(log)
                    .start()
                ToolResult.Ok("Started background command. Output log: ${ctx.workspace.relative(log)}")
            } else {
                val process = ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(workdir)
                    .redirectErrorStream(true)
                    .start()
                val capture = ByteArrayOutputStream()
                val reader = Thread {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            synchronized(capture) {
                                if (capture.size() < 64000) capture.write(buffer, 0, minOf(count, 64000 - capture.size()))
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
                val deadline = System.currentTimeMillis() + timeoutMs
                while (process.isAlive && System.currentTimeMillis() < deadline && !ctx.cancelled) {
                    process.waitFor(150, TimeUnit.MILLISECONDS)
                }
                if (ctx.cancelled) {
                    process.destroyForcibly()
                    reader.join(1000)
                    return ToolResult.Error("Command cancelled.")
                }
                if (process.isAlive) {
                    process.destroyForcibly()
                    reader.join(1000)
                    return ToolResult.Error("Command timed out after ${timeoutMs} ms.")
                }
                reader.join(1500)
                val output = synchronized(capture) { capture.toString(Charsets.UTF_8.name()) }
                ToolResult.Ok("exit=${process.exitValue()}\n$output")
            }
        } catch (error: Exception) {
            ToolResult.Error("Terminal execution failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}
