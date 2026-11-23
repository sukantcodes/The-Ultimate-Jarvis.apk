package com.jarvis.assistant.tools.server

import com.google.gson.JsonObject
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.builtin.str
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chat and voice tools for temporary public links. Starting one always asks the person first (it is classed as
 * destructive, so no "always allow" can skip the question), says exactly which port and for how long, and only reports
 * a link after Cloudflare has accepted it. Stopping and looking never ask.
 */
class TunnelTools(
    private val tunnels: TunnelManager,
    private val servers: ServerManager,
    /** Lets the approval question get a project's preview and tunnel ready while the person is still reading it. */
    private val workspace: Workspace? = null
) {
    fun all(): List<Tool> = listOf(Detect(), Start(), Status(), Stop())

    private fun JsonObject.intArg(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    private sealed class Target {
        data class Ok(val port: Int, val label: String) : Target()
        /** A project that has no preview running yet: [Start] starts its preview itself, so one call gives a link. */
        data class NeedsPreview(val name: String) : Target()
        data class Bad(val message: String) : Target()
    }

    private fun labelFor(port: Int): String =
        servers.running().entries.firstOrNull { it.value.boundPort == port }?.key ?: "port $port"

    private fun resolve(args: JsonObject): Target {
        args.intArg("port")?.let { p ->
            PortPolicy.check(p)?.let { return Target.Bad(it) }
            return Target.Ok(p, labelFor(p))
        }
        args.str("name")?.takeIf { it.isNotBlank() }?.let { n ->
            val s = servers.running().entries.firstOrNull { it.key.equals(n.trim(), ignoreCase = true) }
                ?: return Target.NeedsPreview(n.trim())
            return Target.Ok(s.value.boundPort, s.key)
        }
        val found = tunnels.detect()
        val open = found.filter { !it.shared }.ifEmpty { found }
        return when {
            found.isEmpty() -> Target.Bad("No local server was found. Start one first (preview_start, or a dev server in Termux), or tell me the port number.")
            open.size == 1 -> Target.Ok(open[0].port, open[0].label)
            else -> Target.Bad("Several local servers are running: " + open.joinToString { "${it.label} (port ${it.port})" } + ". Tell me which port to share.")
        }
    }

    /** Starts the preview of project [name] the same way preview_start does (dist/ or build/ if it has an index.html, else the project folder). */
    private fun startPreview(name: String, ctx: ToolContext): Target {
        val d = try { ctx.workspace.project(name) } catch (e: Exception) { return Target.Bad(e.message ?: "Invalid project name.") }
        val serveDir = listOf("dist", "build").map { java.io.File(d, it) }.firstOrNull { java.io.File(it, "index.html").isFile } ?: d
        if (!java.io.File(serveDir, "index.html").isFile) return Target.Bad("Project \"$name\" has no index.html to share. Create the project first.")
        return try {
            val s = servers.start(d.name, serveDir)
            Target.Ok(s.boundPort, d.name)
        } catch (e: java.io.IOException) { Target.Bad(e.message ?: "The preview could not be started.") }
    }

    private fun minutesText(m: Int) = if (m % 60 == 0 && m >= 60) "${m / 60} h" else "$m min"
    private fun clockText(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun describeLive(t: Tunnel, url: String, verified: Boolean, matches: Boolean?): String = buildString {
        append("Public link ready: ").append(url).append('\n')
        append("It shares ").append(t.label).append(" (port ").append(t.port).append(") and stays open until ").append(clockText(t.expiresAt)).append(".\n")
        append(if (verified && matches == true) "Checked from outside this app: the link shows exactly the same page as your phone does."
        else if (verified && matches == false) "The link answers, but its content differs from what your phone serves. Pages that change on every load (live data, random content) will differ."
        else if (verified) "Checked from outside this app: the link answers."
        else "The link is created. New links can take a few more seconds to start answering everywhere; if it does not open at first, retry in a few seconds.")
        append("\nAnyone who has the link can open it. It closes by itself when the server stops. Say \"stop forwarding port ").append(t.port).append("\" to close it now.")
    }

    inner class Detect : Tool {
        override val spec = ToolSpec("port_forward_detect", "List local servers on this phone that could be shared with a public link (JARVIS previews and common dev-server ports), and the public links already open. Use before port_forward_start when the person did not say which port.",
            emptyList(), Risk.READ, "server")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val found = tunnels.detect()
            val text = if (found.isEmpty()) "No local server found." else found.joinToString("\n") {
                "- ${it.label}: port ${it.port}" + if (it.shared) " (already shared)" else ""
            }
            return ToolResult.Ok(text)
        }
    }

    inner class Start : Tool {
        override val spec = ToolSpec("port_forward_start",
            "Create a temporary public HTTPS link (Cloudflare Quick Tunnel) to a website or server running on this phone, so anyone on the internet can open it. Use for \"create a public link for my website\", \"expose my localhost\", \"forward port 3000\". " +
                "Give a port, or a project name (its preview is started automatically if it is not running, so this ONE call is enough: do not call preview_start, deploy, build or run any other command first), or neither to use the one detected local server. The link is ready in seconds and lasts 30 minutes by default, and it keeps working on any device even after the app is closed. The person is always asked first. Only say the link works after this tool returns it, then reply with just the link.",
            listOf(
                ToolParam("port", "integer", "Local port to share, for example 3000."),
                ToolParam("name", "string", "Name of a running preview project (see preview_status)."),
                ToolParam("minutes", "integer", "How long the link stays open (default 30, at most 1440 = 24 hours).")
            ), Risk.NETWORK, "server")

        private fun minutes(args: JsonObject) = (args.intArg("minutes") ?: 30).coerceIn(1, 1440)

        override fun describe(args: JsonObject): String = when (val t = resolve(args)) {
            is Target.Bad -> "Nothing will be shared yet: ${t.message}"
            is Target.NeedsPreview -> { prepareProject(t.name); "PUBLIC INTERNET ACCESS\nStart a preview of ${t.name} on this phone and make it reachable from anywhere through a temporary Cloudflare link. " +
                "Anyone who gets the link can open it and see everything this project serves, for ${minutesText(minutes(args))} or until you stop it." }
            is Target.Ok -> { tunnels.prepare(t.port); "PUBLIC INTERNET ACCESS\nMake ${t.label} (port ${t.port}) on this phone reachable from anywhere through a temporary Cloudflare link. " +
                "Anyone who gets the link can open it and see everything this server serves, for ${minutesText(minutes(args))} or until you stop it." }
        }

        /**
         * While the approval question is on screen the preview of the named project is started (it only listens on this
         * phone) and a tunnel for it is prepared, so approving is nearly instant. Nothing is public until the person approves.
         */
        private fun prepareProject(name: String) {
            val ws = workspace ?: return
            try {
                val d = ws.project(name)
                val serveDir = listOf("dist", "build").map { java.io.File(d, it) }.firstOrNull { java.io.File(it, "index.html").isFile } ?: d
                if (!java.io.File(serveDir, "index.html").isFile) return
                tunnels.prepare(servers.start(d.name, serveDir).boundPort)
            } catch (_: Exception) { }
        }

        override fun isDestructive(args: JsonObject, ctx: ToolContext) = true

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val resolved = resolve(args)
            val t = when (val r = if (resolved is Target.NeedsPreview) startPreview(resolved.name, ctx) else resolved) {
                is Target.Bad -> return ToolResult.Error(r.message)
                is Target.Ok -> r
                is Target.NeedsPreview -> return ToolResult.Error("The preview could not be started.")
            }
            val started = when (val r = tunnels.start(t.port, t.label, minutes(args))) {
                is StartResult.Rejected -> return ToolResult.Error(r.message)
                is StartResult.AlreadyActive -> {
                    val live = r.tunnel.state as? TunnelState.Live
                    if (live != null) return ToolResult.Ok("Port ${t.port} already has a public link:\n" + describeLive(r.tunnel, live.url, live.verified, live.matchesLocal))
                    r.tunnel
                }
                is StartResult.Started -> r.tunnel
            }
            val id = started.id
            val begun = System.currentTimeMillis()
            var lastDetail = ""
            try {
                while (true) {
                    val cur = tunnels.get(id) ?: return ToolResult.Error("The link was closed before it was ready.")
                    when (val s = cur.state) {
                        is TunnelState.Live -> return ToolResult.Ok(describeLive(cur, s.url, s.verified, s.matchesLocal))
                        is TunnelState.Failed -> return ToolResult.Error(s.message)
                        is TunnelState.Stopped -> return ToolResult.Error("The link ended before it was ready. ${s.reason.text}")
                        is TunnelState.Preparing -> if (s.detail != lastDetail) { lastDetail = s.detail; ctx.progress(s.detail) }
                        is TunnelState.Connecting -> if (lastDetail != "c") { lastDetail = "c"; ctx.progress("Connecting to Cloudflare…") }
                        is TunnelState.Reconnecting -> Unit
                    }
                    val limit = if (lastDetail.startsWith("Installing")) 100_000L else 45_000L
                    if (ctx.cancelled) { tunnels.stopById(id); return ToolResult.Error("Cancelled. No public link was left open.") }
                    if (System.currentTimeMillis() - begun > limit) {
                        return ToolResult.Ok("The link is still connecting on port ${t.port}. Its status shows in the chat; ask for port_forward_status in a moment.")
                    }
                    delay(100)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                tunnels.stopById(id)
                throw e
            }
        }
    }

    inner class Status : Tool {
        override val spec = ToolSpec("port_forward_status", "Show the public links that are open or just ended, with their addresses and time left.", emptyList(), Risk.READ, "server")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val list = tunnels.tunnels.value
            if (list.isEmpty()) return ToolResult.Ok("No public links are open.")
            val now = System.currentTimeMillis()
            return ToolResult.Ok(list.joinToString("\n") { t ->
                val left = ((t.expiresAt - now) / 60_000).coerceAtLeast(0)
                "- ${t.label} (port ${t.port}): " + when (val s = t.state) {
                    is TunnelState.Live -> "live at ${s.url}, $left min left" + (if (!s.verified) " (not confirmed from outside yet)" else if (s.matchesLocal == true) ", same page as on your phone" else "")
                    is TunnelState.Reconnecting -> "reconnecting (${s.url})"
                    is TunnelState.Connecting -> "connecting"
                    is TunnelState.Preparing -> s.detail
                    is TunnelState.Stopped -> "stopped. ${s.reason.text}"
                    is TunnelState.Failed -> "failed. ${s.message}"
                } + (t.note?.let { " Note: $it" } ?: "")
            })
        }
    }

    inner class Stop : Tool {
        override val spec = ToolSpec("port_forward_stop", "Close a public link at once, by port or preview name, or all of them.",
            listOf(ToolParam("port", "integer"), ToolParam("name", "string"), ToolParam("all", "boolean", "Close every public link.")), Risk.READ, "server")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            if (args.get("all")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } == true) {
                val n = tunnels.stopAll()
                return ToolResult.Ok(if (n == 0) "No public links were open." else "Closed $n public link(s).")
            }
            val port = args.intArg("port")
                ?: args.str("name")?.let { n -> servers.running().entries.firstOrNull { it.key.equals(n.trim(), true) }?.value?.boundPort }
                ?: tunnels.active().singleOrNull()?.port
                ?: return ToolResult.Error(if (tunnels.active().isEmpty()) "No public links are open." else "Several links are open. Say which port to close, or close all.")
            return ToolResult.Ok(if (tunnels.stop(port)) "Closed the public link for port $port." else "No public link is open for port $port.")
        }
    }
}
