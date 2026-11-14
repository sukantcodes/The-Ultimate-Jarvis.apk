package com.jarvis.assistant.tools.chat

import com.google.gson.JsonObject
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.repository.ChatRepository
import com.jarvis.assistant.tools.*

private fun JsonObject.str(n: String): String? = get(n)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonObject.int(n: String): Int? = get(n)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
private fun JsonObject.bool(n: String): Boolean? = get(n)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }

/**
 * Gives Jarvis full access to the ChatBox: read and search the conversation, add a message, edit one and delete
 * one (or all). Reading is free; deleting asks like any other destructive action unless the user turned approvals off.
 */
object ChatAccessTools {
    fun all(repo: () -> ChatRepository): List<Tool> = listOf(ChatboxRead(repo), ChatboxPost(repo), ChatboxEdit(repo), ChatboxDelete(repo))

    private const val GROUP = "chatbox"

    internal fun describeTurn(t: ChatTurn, maxChars: Int): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        fun cut(s: String) = if (s.length > maxChars) s.take(maxChars) + "… (${s.length - maxChars} more characters)" else s
        return buildString {
            append("#").append(t.id.take(8)).append("  ").append(fmt.format(java.util.Date(t.timestamp))).append('\n')
            append("USER: ").append(cut(t.userTranscript)).append('\n')
            append("JARVIS: ").append(cut(t.jarvisResponse.ifBlank { "(no answer yet)" }))
            if (!t.attachmentName.isNullOrBlank()) append("\nATTACHMENT: ").append(t.attachmentName)
        }
    }

    /** The turn whose id is [ref] or starts with it (the short "#abc12345" form is enough). */
    internal fun find(turns: List<ChatTurn>, ref: String): ChatTurn? {
        val key = ref.trim().removePrefix("#")
        if (key.length < 4) return null
        val exact = turns.firstOrNull { it.id == key }
        if (exact != null) return exact
        val matches = turns.filter { it.id.startsWith(key) }
        return matches.singleOrNull()
    }

    class ChatboxRead(private val repo: () -> ChatRepository) : Tool {
        override val spec = ToolSpec(
            "chatbox_read",
            "Read the ChatBox conversation: the latest turns, a search through all turns, or one whole turn by id. Each turn shows a short id you can pass to chatbox_edit and chatbox_delete.",
            listOf(
                ToolParam("limit", "integer", "How many of the latest turns (default 10, at most 50)."),
                ToolParam("search", description = "Only turns whose user message or answer contains this text."),
                ToolParam("turn_id", description = "Show this one turn in full (the short id is enough).")
            ), Risk.READ, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val turns = repo().turns.value
            if (turns.isEmpty()) return ToolResult.Ok("The ChatBox is empty.")
            args.str("turn_id")?.takeIf { it.isNotBlank() }?.let { ref ->
                val t = find(turns, ref) ?: return ToolResult.Error("No single turn matches \"$ref\". Use chatbox_read to list ids.")
                return ToolResult.Ok(describeTurn(t, 20_000))
            }
            val needle = args.str("search")?.trim().orEmpty()
            val pool = if (needle.isEmpty()) turns else turns.filter { it.userTranscript.contains(needle, true) || it.jarvisResponse.contains(needle, true) }
            if (pool.isEmpty()) return ToolResult.Ok("No turn contains \"$needle\".")
            val shown = pool.takeLast((args.int("limit") ?: 10).coerceIn(1, 50))
            val head = "Showing ${shown.size} of ${pool.size} turn(s) (${turns.size} in the ChatBox):\n\n"
            return ToolResult.Ok(head + shown.joinToString("\n\n") { describeTurn(it, 1_500) })
        }
    }

    class ChatboxPost(private val repo: () -> ChatRepository) : Tool {
        override val spec = ToolSpec(
            "chatbox_post",
            "Add a finished message to the ChatBox, for example a summary or a result the user should find there. It appears as a turn with your text as the answer.",
            listOf(
                ToolParam("text", required = true, description = "What Jarvis shows in the ChatBox."),
                ToolParam("title", description = "Optional short label shown as the user side of the turn.")
            ), Risk.WRITE, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val text = args.str("text")?.trim().orEmpty()
            if (text.isEmpty()) return ToolResult.Error("text is empty.")
            repo().addTurn(args.str("title")?.trim()?.ifEmpty { null } ?: "Note from Jarvis", text)
            return ToolResult.Ok("Added to the ChatBox.")
        }
    }

    class ChatboxEdit(private val repo: () -> ChatRepository) : Tool {
        override val spec = ToolSpec(
            "chatbox_edit",
            "Rewrite the text of one saved ChatBox turn (the user's message, Jarvis's answer, or both). Leave a field out to keep it.",
            listOf(
                ToolParam("turn_id", required = true, description = "Short or full id from chatbox_read."),
                ToolParam("user_text", description = "New text for the user's message."),
                ToolParam("jarvis_text", description = "New text for Jarvis's answer.")
            ), Risk.WRITE, GROUP
        )

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val r = repo()
            val t = find(r.turns.value, args.str("turn_id").orEmpty()) ?: return ToolResult.Error("No single turn matches that id. Use chatbox_read to list ids.")
            val user = args.str("user_text")
            val jarvis = args.str("jarvis_text")
            if (user.isNullOrBlank() && jarvis.isNullOrBlank()) return ToolResult.Error("Give user_text, jarvis_text or both.")
            return if (r.editTurn(t.id, user, jarvis)) ToolResult.Ok("Updated turn #${t.id.take(8)}.")
            else ToolResult.Error("That turn is still being answered, so it can not be edited yet.")
        }
    }

    class ChatboxDelete(private val repo: () -> ChatRepository) : Tool {
        override val spec = ToolSpec(
            "chatbox_delete",
            "Delete one ChatBox turn by id, or the whole conversation with all=true. This can not be undone.",
            listOf(
                ToolParam("turn_id", description = "Short or full id from chatbox_read."),
                ToolParam("all", "boolean", "true deletes the entire ChatBox history.")
            ), Risk.DESTRUCTIVE, GROUP
        )

        override fun describe(args: JsonObject): String =
            if (args.bool("all") == true) "Delete the whole ChatBox conversation" else "Delete ChatBox turn ${args.str("turn_id").orEmpty()}"

        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            val r = repo()
            if (args.bool("all") == true) {
                r.clearHistory()
                return ToolResult.Ok("The ChatBox conversation was deleted.")
            }
            val t = find(r.turns.value, args.str("turn_id").orEmpty()) ?: return ToolResult.Error("No single turn matches that id. Use chatbox_read to list ids.")
            return if (r.deleteTurn(t.id)) ToolResult.Ok("Deleted turn #${t.id.take(8)}.")
            else ToolResult.Error("That turn is still being answered, so it can not be deleted yet.")
        }
    }
}
