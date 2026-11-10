package com.jarvis.assistant.tools

import com.google.gson.JsonObject
import java.io.File

/** Project scaffolding and exact-match patching, confined to the ChatBox workspace. */
object WorkspaceTools {
    fun all(): List<Tool> = listOf(CreateProject, ApplyPatch)

    private fun arg(args: JsonObject, key: String): String? =
        args.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    object CreateProject : Tool {
        override val spec = ToolSpec("create_project", "Create a starter project folder in the isolated ChatBox workspace.",
            listOf(
                ToolParam("name", required = true, description = "Project name."),
                ToolParam("template", description = "Starter template.", enumValues = listOf("web", "python", "kotlin", "empty"))
            ), Risk.WRITE, "files")
        override fun describe(args: JsonObject) = "Create project '${arg(args, "name").orEmpty()}' using the ${arg(args, "template") ?: "empty"} template"
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val name = arg(args, "name").orEmpty()
            val template = arg(args, "template")?.lowercase() ?: "empty"
            if (template !in setOf("web", "python", "kotlin", "empty")) return ToolResult.Error("Choose web, python, kotlin or empty.")
            val dir = try { ctx.workspace.project(name) } catch (e: Exception) { return ToolResult.Error(e.message ?: "Invalid project name.") }
            if (dir.listFiles()?.isNotEmpty() == true) return ToolResult.Error("That project folder already contains files.")
            val files = linkedMapOf<String, String>()
            files["README.md"] = "# ${dir.name}\n\nCreated in the JARVIS ChatBox workspace.\n"
            when (template) {
                "web" -> {
                    files["index.html"] = "<!doctype html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <title>${dir.name}</title>\n  <link rel=\"stylesheet\" href=\"styles.css\">\n</head>\n<body>\n  <main><h1>${dir.name}</h1><p>Your project starts here.</p></main>\n  <script src=\"script.js\"></script>\n</body>\n</html>\n"
                    files["styles.css"] = "body { font-family: system-ui, sans-serif; margin: 0; padding: 2rem; }\nmain { max-width: 60rem; margin: auto; }\n"
                    files["script.js"] = "console.log('Project ready');\n"
                }
                "python" -> files["main.py"] = "def main():\n    print(\"Hello from ${dir.name}\")\n\nif __name__ == \"__main__\":\n    main()\n"
                "kotlin" -> files["Main.kt"] = "fun main() {\n    println(\"Hello from ${dir.name}\")\n}\n"
            }
            files.forEach { (path, content) -> File(dir, path).writeText(content, Charsets.UTF_8) }
            return ToolResult.Ok("Created ${template} project at ${ctx.workspace.relative(dir)} with: ${files.keys.joinToString()}")
        }
    }

    object ApplyPatch : Tool {
        override val spec = ToolSpec("apply_patch", "Replace one exact, unique text block in a workspace file and keep a backup.",
            listOf(
                ToolParam("path", required = true, description = "Workspace-relative file path."),
                ToolParam("find", required = true, description = "Exact text to locate. It must occur exactly once."),
                ToolParam("replace", required = true, description = "Replacement text.")
            ), Risk.WRITE, "files")
        override fun describe(args: JsonObject) = "Patch workspace file '${arg(args, "path").orEmpty()}'"
        override fun isDestructive(args: JsonObject, ctx: ToolContext): Boolean =
            runCatching { ctx.workspace.resolve(arg(args, "path").orEmpty()).isFile }.getOrDefault(true)
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val file = ctx.workspace.resolve(arg(args, "path").orEmpty())
            val find = arg(args, "find") ?: return ToolResult.Error("The exact text to find is required.")
            val replace = arg(args, "replace") ?: return ToolResult.Error("Replacement text is required.")
            if (!file.isFile) return ToolResult.Error("No file exists at that workspace path.")
            if (file.length() > MAX_PATCH_FILE_BYTES) return ToolResult.Error("That file is too large to patch safely.")
            if (find.isEmpty()) return ToolResult.Error("The find text cannot be empty.")
            val original = file.readText(Charsets.UTF_8)
            val first = original.indexOf(find)
            if (first < 0) return ToolResult.Error("The exact text was not found. Read the latest file and try again.")
            if (original.indexOf(find, first + find.length) >= 0) return ToolResult.Error("The exact text occurs more than once. Narrow the patch so it matches only one location.")
            val backup = ctx.workspace.resolve(".jarvis/backups/${System.nanoTime()}-${file.name}.bak")
            backup.parentFile?.mkdirs()
            backup.writeText(original, Charsets.UTF_8)
            val updated = original.substring(0, first) + replace + original.substring(first + find.length)
            file.writeText(updated, Charsets.UTF_8)
            return ToolResult.Ok("Patched ${ctx.workspace.relative(file)}. Backup saved to ${ctx.workspace.relative(backup)}.")
        }
    }

    private const val MAX_PATCH_FILE_BYTES = 2_000_000L
}
