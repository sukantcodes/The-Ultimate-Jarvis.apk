package com.jarvis.assistant.tools

import android.util.Base64
import com.google.gson.JsonObject

/** Permission-gated binary-safe restore primitive used by the coding agent's /undo command. */
class RestoreSnapshotTool : Tool {
    override val spec = ToolSpec(
        name = "restore_snapshot",
        description = "Restore a previously captured project file or directory snapshot.",
        params = listOf(
            ToolParam("path", "string", "Project-relative path to restore", true),
            ToolParam("contentBase64", "string", "Base64 file bytes; omit for deletion or directory restoration", false),
            ToolParam("delete", "boolean", "Delete a file that did not exist before the change", false),
            ToolParam("directory", "boolean", "Recreate a directory that existed before the change", false)
        ),
        risk = Risk.WRITE,
        group = "files"
    )

    override fun describe(args: JsonObject): String {
        val path = args.get("path")?.asString.orEmpty()
        return when {
            args.get("directory")?.asBoolean == true -> "Recreate directory $path while undoing a coding-agent change."
            args.get("delete")?.asBoolean == true -> "Remove newly-created path $path while undoing a coding-agent change."
            else -> "Restore the previous bytes of $path from the coding-agent undo snapshot."
        }
    }

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = args.get("path")?.asString.orEmpty()
        if (path.isBlank()) return ToolResult.Error("A path is required.")
        val file = try { ctx.workspace.resolve(path) } catch (error: Exception) {
            return ToolResult.Error(error.message ?: "Path is outside the project.")
        }
        return try {
            when {
                args.get("directory")?.asBoolean == true -> {
                    if (!file.exists() && !file.mkdirs()) ToolResult.Error("Could not recreate directory $path.")
                    else ToolResult.Ok("Restored directory $path.")
                }
                args.get("delete")?.asBoolean == true -> {
                    if (file.exists() && !file.deleteRecursively()) ToolResult.Error("Could not remove newly-created path $path.")
                    else ToolResult.Ok("Removed newly-created path $path.")
                }
                else -> {
                    val encoded = args.get("contentBase64")?.asString.orEmpty()
                    if (encoded.isBlank()) return ToolResult.Error("Snapshot bytes are missing.")
                    val bytes = try { Base64.decode(encoded, Base64.NO_WRAP) } catch (_: Exception) {
                        return ToolResult.Error("Snapshot data is invalid.")
                    }
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    ToolResult.Ok("Restored previous contents of $path.")
                }
            }
        } catch (error: Exception) {
            ToolResult.Error("Restore failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}
