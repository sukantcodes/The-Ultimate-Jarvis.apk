package com.jarvis.assistant.data.sessions

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.data.model.ChatTurn
import java.io.File
import java.util.UUID

data class ChatSessionMeta(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val titleEdited: Boolean = false,
    val turnCount: Int = 0
)

/**
 * Many separate conversations, each in its own file (so one damaged file loses one chat, not all of them). An index
 * file keeps titles and flags. Writes go to a temp file first and are renamed, so a crash mid-save never leaves a
 * half-written chat.
 */
class ChatSessionStore(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val gson = Gson()
    private val indexFile get() = File(dir, "index.json")

    @Synchronized fun sessions(includeArchived: Boolean = false): List<ChatSessionMeta> =
        readIndex().filter { includeArchived || !it.archived }.sortedWith(compareByDescending<ChatSessionMeta> { it.pinned }.thenByDescending { it.updatedAt })

    @Synchronized fun meta(id: String): ChatSessionMeta? = readIndex().firstOrNull { it.id == id }

    @Synchronized fun create(title: String? = null): ChatSessionMeta {
        val m = ChatSessionMeta(title = title?.take(60)?.ifBlank { null } ?: "New chat", createdAt = clock(), updatedAt = clock(), titleEdited = title != null)
        writeIndex(readIndex() + m); writeTurns(m.id, emptyList()); return m
    }

    @Synchronized fun load(id: String): List<ChatTurn> = try {
        val f = File(dir, "$id.json"); if (!f.isFile) emptyList() else gson.fromJson<List<ChatTurn>>(f.readText(), object : TypeToken<List<ChatTurn>>() {}.type) ?: emptyList()
    } catch (e: Exception) { emptyList() }

    /** Saves a session's turns and keeps its title (first message) and time up to date. */
    @Synchronized fun save(id: String, turns: List<ChatTurn>) {
        val idx = readIndex(); val cur = idx.firstOrNull { it.id == id } ?: return
        writeTurns(id, turns)
        val auto = turns.firstOrNull()?.userTranscript?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("📎 ")?.take(48)
        writeIndex(idx.map { if (it.id == id) it.copy(updatedAt = clock(), turnCount = turns.size, title = if (!cur.titleEdited && auto != null) auto else cur.title) else it })
    }

    @Synchronized fun rename(id: String, title: String): Boolean = update(id) { it.copy(title = title.trim().take(60).ifBlank { it.title }, titleEdited = true) }
    @Synchronized fun pin(id: String, pinned: Boolean) = update(id) { it.copy(pinned = pinned) }
    @Synchronized fun archive(id: String, archived: Boolean) = update(id) { it.copy(archived = archived) }

    /** Removes the chat and returns the attachment files it referenced, so the caller can delete them. */
    @Synchronized fun delete(id: String): List<String> {
        val paths = load(id).mapNotNull { it.attachmentPath }
        File(dir, "$id.json").delete(); writeIndex(readIndex().filter { it.id != id }); return paths
    }

    /** Case-insensitive search of titles and messages. Returns chats with a short snippet around the first match. */
    @Synchronized fun search(query: String, includeArchived: Boolean = true): List<Pair<ChatSessionMeta, String>> {
        val q = query.trim().lowercase(); if (q.isEmpty()) return emptyList()
        return sessions(includeArchived).mapNotNull { m ->
            if (m.title.lowercase().contains(q)) return@mapNotNull m to m.title
            for (t in load(m.id)) for (text in listOf(t.userTranscript, t.jarvisResponse)) {
                val i = text.lowercase().indexOf(q); if (i >= 0) return@mapNotNull m to (if (i > 30) "…" else "") + text.substring(maxOf(0, i - 30), minOf(text.length, i + q.length + 60)).replace('\n', ' ')
            }
            null
        }
    }

    @Synchronized fun exportMarkdown(id: String): String {
        val m = meta(id) ?: return ""
        return "# ${m.title}\n\n" + load(id).joinToString("\n\n") { "**You:** ${it.userTranscript}\n\n**Jarvis:** ${it.jarvisResponse}" }
    }

    private fun update(id: String, f: (ChatSessionMeta) -> ChatSessionMeta): Boolean { val idx = readIndex(); if (idx.none { it.id == id }) return false; writeIndex(idx.map { if (it.id == id) f(it) else it }); return true }
    private fun readIndex(): List<ChatSessionMeta> = try { if (!indexFile.isFile) emptyList() else gson.fromJson<List<ChatSessionMeta>>(indexFile.readText(), object : TypeToken<List<ChatSessionMeta>>() {}.type) ?: emptyList() } catch (e: Exception) { emptyList() }
    private fun writeIndex(list: List<ChatSessionMeta>) = atomic(indexFile, gson.toJson(list))
    private fun writeTurns(id: String, turns: List<ChatTurn>) = atomic(File(dir, "$id.json"), gson.toJson(turns))
    private fun atomic(target: File, text: String) { dir.mkdirs(); val tmp = File(dir, target.name + ".tmp"); tmp.writeText(text); if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() } }
}
