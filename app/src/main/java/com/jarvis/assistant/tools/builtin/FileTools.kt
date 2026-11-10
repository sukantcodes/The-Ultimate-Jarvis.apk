package com.jarvis.assistant.tools.builtin

import com.google.gson.JsonObject
import com.jarvis.assistant.tools.*
import java.io.File

internal fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
internal fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
internal fun JsonObject.bool(name: String): Boolean? = get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }

/** Largest file the read/search tools will load, so a huge file can not exhaust memory. */
private const val MAX_READ_BYTES = 2_000_000L
private val PROTECTED_NAMES = setOf(".git", ".jarvis")

object FileTools {
    fun all(): List<Tool> = listOf(ListFiles, ReadFile, WriteFile, AppendFile, MakeDirectory, MovePath, DeletePath, SearchFiles)

    private fun isText(f: File): Boolean = f.inputStream().use { s ->
        val buf = ByteArray(minOf(4096, f.length().toInt())); val n = s.read(buf)
        (0 until maxOf(n, 0)).none { buf[it].toInt() == 0 }
    }

    object ListFiles : Tool {
        override val spec = ToolSpec("list_files", "List files and folders inside the workspace.",
            listOf(ToolParam("path", description = "Folder, relative to the workspace. Default: the workspace root."), ToolParam("recursive", "boolean", "List sub-folders too.")),
            Risk.READ, "files")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val dir = ctx.workspace.resolve(args.str("path") ?: ".")
            if (!dir.isDirectory) return ToolResult.Error("Not a folder: ${args.str("path") ?: "."}")
            val recursive = args.bool("recursive") == true
            val items = (if (recursive) dir.walkTopDown().drop(1).filter { !it.name.startsWith(".git") } else dir.listFiles()?.asSequence() ?: emptySequence())
                .sortedBy { it.path }.take(500).toList()
            if (items.isEmpty()) return ToolResult.Ok("(empty)")
            return ToolResult.Ok(items.joinToString("\n") { f -> ctx.workspace.relative(f) + if (f.isDirectory) "/" else "  (${f.length()} bytes)" })
        }
    }

    object ReadFile : Tool {
        override val spec = ToolSpec("read_file", "Read a text file from the workspace.",
            listOf(ToolParam("path", required = true, description = "File path relative to the workspace."), ToolParam("max_chars", "integer", "Stop after this many characters (default 20000).")),
            Risk.READ, "files")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val f = ctx.workspace.resolve(args.str("path")!!)
            if (!f.isFile) return ToolResult.Error("No such file: ${args.str("path")}")
            if (f.length() > MAX_READ_BYTES) return ToolResult.Error("The file is ${f.length()} bytes, too large to read at once. Search it or read it in parts.")
            if (!isText(f)) return ToolResult.Error("This looks like a binary file; it can not be read as text.")
            val limit = (args.int("max_chars") ?: 20_000).coerceIn(1, 200_000)
            val text = f.readText()
            return ToolResult.Ok(if (text.length > limit) text.take(limit) + "\n… (${text.length - limit} more characters)" else text)
        }
    }

    object WriteFile : Tool {
        override val spec = ToolSpec("write_file", "Create a file in the workspace, or replace it when overwrite is true.",
            listOf(ToolParam("path", required = true), ToolParam("content", required = true, description = "Full file contents."), ToolParam("overwrite", "boolean", "Replace the file if it already exists.")),
            Risk.WRITE, "files")
        override fun describe(args: JsonObject) = "Write ${args.str("path")} (${args.str("content")?.length ?: 0} characters)"
        override fun isDestructive(args: JsonObject, ctx: ToolContext): Boolean =
            runCatching { ctx.workspace.resolve(args.str("path") ?: "").let { it.exists() && args.bool("overwrite") == true } }.getOrDefault(false)
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val f = ctx.workspace.resolve(args.str("path")!!)
            if (f == ctx.workspace.root || f.isDirectory) return ToolResult.Error("That path is a folder.")
            if (f.exists() && args.bool("overwrite") != true) return ToolResult.Error("${args.str("path")} already exists. Set overwrite to true to replace it.")
            if (f.name in PROTECTED_NAMES) return ToolResult.Error("${f.name} is protected.")
            val content = args.str("content")!!
            if (ctx.workspace.sizeOf() + content.length > MAX_WORKSPACE_BYTES) return ToolResult.Error("The workspace is full. Delete something first.")
            f.parentFile?.mkdirs()
            f.writeText(content)
            return ToolResult.Ok("Wrote ${ctx.workspace.relative(f)} (${f.length()} bytes).", listOf(ToolArtifact(f.path, f.name, mimeOf(f.name))))
        }
    }

    object AppendFile : Tool {
        override val spec = ToolSpec("append_file", "Add text to the end of a workspace file (created if missing).",
            listOf(ToolParam("path", required = true), ToolParam("content", required = true)), Risk.WRITE, "files")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val f = ctx.workspace.resolve(args.str("path")!!)
            if (f.isDirectory) return ToolResult.Error("That path is a folder.")
            f.parentFile?.mkdirs(); f.appendText(args.str("content")!!)
            return ToolResult.Ok("Appended to ${ctx.workspace.relative(f)}.")
        }
    }

    object MakeDirectory : Tool {
        override val spec = ToolSpec("make_directory", "Create a folder in the workspace.", listOf(ToolParam("path", required = true)), Risk.WRITE, "files")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val d = ctx.workspace.resolve(args.str("path")!!)
            return if (d.isDirectory || d.mkdirs()) ToolResult.Ok("Folder ready: ${ctx.workspace.relative(d)}") else ToolResult.Error("Could not create the folder.")
        }
    }

    object MovePath : Tool {
        override val spec = ToolSpec("move_path", "Move or rename a file or folder inside the workspace.",
            listOf(ToolParam("from", required = true), ToolParam("to", required = true)), Risk.WRITE, "files")
        override fun describe(args: JsonObject) = "Move ${args.str("from")} to ${args.str("to")}"
        override fun isDestructive(args: JsonObject, ctx: ToolContext) = runCatching { ctx.workspace.resolve(args.str("to") ?: "").exists() }.getOrDefault(false)
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val from = ctx.workspace.resolve(args.str("from")!!); val to = ctx.workspace.resolve(args.str("to")!!)
            if (!from.exists()) return ToolResult.Error("Nothing at ${args.str("from")}.")
            if (from == ctx.workspace.root) return ToolResult.Error("The workspace itself can not be moved.")
            if (to.exists()) return ToolResult.Error("${args.str("to")} already exists. Delete it first, or choose another name.")
            to.parentFile?.mkdirs()
            return if (from.renameTo(to)) ToolResult.Ok("Moved to ${ctx.workspace.relative(to)}.") else ToolResult.Error("The move failed.")
        }
    }

    object DeletePath : Tool {
        override val spec = ToolSpec("delete_path", "Delete a file or folder (and everything in it) from the workspace.",
            listOf(ToolParam("path", required = true)), Risk.DESTRUCTIVE, "files")
        override fun describe(args: JsonObject) = "DELETE ${args.str("path")} permanently"
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val f = ctx.workspace.resolve(args.str("path")!!)
            if (f == ctx.workspace.root) return ToolResult.Error("The workspace root can not be deleted.")
            if (!f.exists()) return ToolResult.Error("Nothing at ${args.str("path")}.")
            val count = if (f.isDirectory) f.walkTopDown().count() else 1
            return if (f.deleteRecursively()) ToolResult.Ok("Deleted ${args.str("path")} ($count item${if (count == 1) "" else "s"}).") else ToolResult.Error("Some items could not be deleted.")
        }
    }

    object SearchFiles : Tool {
        override val spec = ToolSpec("search_files", "Search text inside workspace files (case-insensitive).",
            listOf(ToolParam("query", required = true), ToolParam("path", description = "Folder to search. Default: the workspace root.")), Risk.READ, "files")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val root = ctx.workspace.resolve(args.str("path") ?: ".")
            val q = args.str("query")!!.lowercase()
            if (q.isBlank()) return ToolResult.Error("Give something to search for.")
            val hits = ArrayList<String>()
            for (f in root.walkTopDown().filter { it.isFile && it.length() <= MAX_READ_BYTES && !it.path.contains("/.git/") }) {
                if (ctx.cancelled || hits.size >= 100) break
                if (!isText(f)) continue
                f.useLines { lines -> lines.forEachIndexed { i, line -> if (hits.size < 100 && line.lowercase().contains(q)) hits += "${ctx.workspace.relative(f)}:${i + 1}: ${line.trim().take(200)}" } }
            }
            return ToolResult.Ok(if (hits.isEmpty()) "No matches." else hits.joinToString("\n"))
        }
    }

    const val MAX_WORKSPACE_BYTES = 500L * 1024 * 1024

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "md", "kt", "java", "py", "js", "ts", "css", "sh", "gradle" -> "text/plain"
        "html", "htm" -> "text/html"; "json" -> "application/json"; "xml" -> "application/xml"; "csv" -> "text/csv"
        "pdf" -> "application/pdf"; "zip" -> "application/zip"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "svg" -> "image/svg+xml"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }
}
