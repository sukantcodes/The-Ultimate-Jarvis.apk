package com.jarvis.assistant.tools.server

import com.google.gson.JsonObject
import com.jarvis.assistant.tools.*
import com.jarvis.assistant.tools.builtin.str
import java.io.File

/** Website tools shared by every provider. Previews are local to this device (127.0.0.1) and say so. */
class ServerTools(val servers: ServerManager = ServerManager(), private val nodeAvailable: () -> Boolean = { false }) {
    fun all(): List<Tool> = listOf(Create(), Check(), PreviewStart(), PreviewStop(), PreviewStatus(), Snapshot(), Restore(), Export())

    private fun dir(args: JsonObject, ctx: ToolContext): File {
        val name = args.str("name") ?: throw IllegalArgumentException("name is required")
        return ctx.workspace.project(name)
    }
    private inline fun guard(block: () -> ToolResult): ToolResult = try { block() }
        catch (e: Workspace.PathRejected) { ToolResult.Error(e.message ?: "Invalid path") }
        catch (e: IllegalArgumentException) { ToolResult.Error(e.message ?: "Invalid arguments") }
        catch (e: java.io.IOException) { ToolResult.Error(e.message ?: "File error") }

    inner class Create : Tool {
        override val spec = ToolSpec("project_create", "Create a website project from a template (static, landing, app). Then edit its files with the file tools.",
            listOf(ToolParam("name", required = true), ToolParam("template", enumValues = ProjectScaffolder.Template.values().map { it.name.lowercase() }), ToolParam("title")), Risk.WRITE, "server")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard {
            val d = dir(args, ctx)
            if (d.listFiles()?.isNotEmpty() == true) return@guard ToolResult.Error("The project ${d.name} already exists and is not empty.")
            val t = ProjectScaffolder.Template.values().firstOrNull { it.name.equals(args.str("template") ?: "static", true) } ?: return@guard ToolResult.Error("Unknown template. Use static, landing or app.")
            val files = ProjectScaffolder.create(d, t, args.str("title") ?: d.name)
            ToolResult.Ok("Created project \"${d.name}\" (${t.label}): ${files.joinToString()} in projects/${d.name}.")
        }
    }

    inner class Check : Tool {
        override val spec = ToolSpec("project_check", "Check a website project: entry page, well-formed HTML, and that every local file it links to exists.", listOf(ToolParam("name", required = true)), Risk.READ, "server")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard { val d = dir(args, ctx); ToolResult.Ok(ProjectBuilder.check(d, nodeAvailable()).text()) }
    }

    inner class PreviewStart : Tool {
        override val spec = ToolSpec("preview_start", "Start a live preview of a website project on this device. The address only works on this phone.", listOf(ToolParam("name", required = true), ToolParam("spa", "boolean", "Serve index.html for unknown paths (single-page apps).")), Risk.EXECUTE, "server")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard {
            val d = dir(args, ctx)
            val report = ProjectBuilder.check(d, nodeAvailable())
            val serveDir = listOf("dist", "build").map { File(d, it) }.firstOrNull { File(it, "index.html").isFile } ?: d
            if (!File(serveDir, "index.html").isFile) return@guard ToolResult.Error("There is nothing to preview: ${report.problems.firstOrNull() ?: "no index.html"}")
            val s = servers.start(d.name, serveDir, args.get("spa")?.asBoolean == true)
            ToolResult.Ok("Preview running at ${s.url} (local to this device only; it stops when you stop it or close the app)." + if (!report.ok) "\nThe project has problems:\n" + report.problems.joinToString("\n") { "- $it" } else "")
        }
    }

    inner class PreviewStop : Tool {
        override val spec = ToolSpec("preview_stop", "Stop the live preview of a project.", listOf(ToolParam("name", required = true)), Risk.EXECUTE, "server")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard { val d = dir(args, ctx); ToolResult.Ok(if (servers.stop(d.name)) "Preview stopped." else "No preview was running for ${d.name}.") }
    }

    inner class PreviewStatus : Tool {
        override val spec = ToolSpec("preview_status", "List running previews.", emptyList(), Risk.READ, "server")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.Ok(servers.running().entries.joinToString("\n") { "${it.key}: ${it.value.url} (${it.value.requestCount.get()} requests)" }.ifBlank { "No previews are running." })
    }

    inner class Snapshot : Tool {
        override val spec = ToolSpec("project_snapshot", "Save a restorable copy of a project (a ZIP) before risky changes.", listOf(ToolParam("name", required = true), ToolParam("label")), Risk.WRITE, "server")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard { val d = dir(args, ctx); val f = ProjectArchive.snapshot(d, args.str("label") ?: System.currentTimeMillis().toString()); ToolResult.Ok("Saved snapshot ${f.name} (${f.length()} bytes).") }
    }

    inner class Restore : Tool {
        override val spec = ToolSpec("project_restore", "Restore a project from a snapshot. Current files are replaced.", listOf(ToolParam("name", required = true), ToolParam("label", required = true, description = "Snapshot label or file name.")), Risk.DESTRUCTIVE, "server")
        override fun describe(args: JsonObject) = "Replace project ${args.str("name")} with snapshot ${args.str("label")}"
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard {
            val d = dir(args, ctx); val label = args.str("label") ?: ""
            val snap = ProjectArchive.list(d).firstOrNull { it.name == label || it.name.endsWith("-$label.zip") } ?: return@guard ToolResult.Error("No snapshot \"$label\". Available: ${ProjectArchive.list(d).joinToString { it.name }.ifBlank { "none" }}")
            ProjectArchive.restore(d, snap); ToolResult.Ok("Restored ${d.name} from ${snap.name}.")
        }
    }

    inner class Export : Tool {
        override val spec = ToolSpec("project_export", "Package a project as a ZIP file to download or host elsewhere.", listOf(ToolParam("name", required = true)), Risk.WRITE, "server")
        override suspend fun run(args: JsonObject, ctx: ToolContext) = guard {
            val d = dir(args, ctx); val out = ctx.workspace.resolve("exports/${d.name}.zip"); out.parentFile.mkdirs(); out.writeBytes(ProjectArchive.zip(d))
            ToolResult.Ok("Exported to exports/${d.name}.zip (${out.length()} bytes).", listOf(ToolArtifact(out.path, out.name, "application/zip")))
        }
    }
}
