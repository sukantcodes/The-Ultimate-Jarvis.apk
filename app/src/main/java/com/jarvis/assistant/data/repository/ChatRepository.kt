package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.sessions.ChatSessionMeta
import com.jarvis.assistant.data.sessions.ChatSessionStore
import com.jarvis.assistant.data.preferences.AppPreferences
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatRepository(private val preferences: AppPreferences, private val store: ChatSessionStore? = null) {
    /** The chat shown now. Always set when [store] is used. */
    @Volatile var currentSessionId: String? = null
        private set

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    /** Turns whose answer is still streaming. They live in memory only and are never written to disk. */
    private val pendingIds = HashSet<String>()

    init {
        // A turn saved without an answer can only come from a process that died mid-answer; show it as
        // interrupted instead of leaving the chat waiting forever.
        val s = store
        if (s != null) {
            // First start with sessions: move the old single history into the first chat so nothing is lost.
            if (s.sessions(includeArchived = true).isEmpty()) {
                val legacy = preferences.loadChatHistory()
                val m = s.create()
                if (legacy.isNotEmpty()) { s.save(m.id, legacy); preferences.clearChatHistory() }
            }
            currentSessionId = preferences.currentChatSessionId?.takeIf { s.meta(it) != null } ?: s.sessions().firstOrNull()?.id ?: s.create().id
            preferences.currentChatSessionId = currentSessionId
            _turns.value = interrupted(s.load(currentSessionId!!))
        } else {
            _turns.value = interrupted(preferences.loadChatHistory())
        }
    }

    private fun interrupted(list: List<ChatTurn>) = list.map {
        if (it.jarvisResponse.isBlank()) it.copy(jarvisResponse = "⚠ This answer was interrupted. Tap Retry to ask again.") else it
    }

    // ---- sessions ----
    val canManageSessions: Boolean get() = store != null
    fun sessions(includeArchived: Boolean = false): List<ChatSessionMeta> = store?.sessions(includeArchived).orEmpty()
    fun searchSessions(q: String) = store?.search(q).orEmpty()
    fun exportSession(id: String) = store?.exportMarkdown(id).orEmpty()
    fun renameSession(id: String, title: String) { store?.rename(id, title) }
    fun pinSession(id: String, pinned: Boolean) { store?.pin(id, pinned) }
    fun archiveSession(id: String, archived: Boolean) { store?.archive(id, archived) }
    val hasPending: Boolean @Synchronized get() = pendingIds.isNotEmpty()

    /** Starts an empty chat and shows it. The previous chat stays in the list. Refused while an answer is streaming. */
    @Synchronized fun newSession(): Boolean {
        val s = store ?: return false; if (pendingIds.isNotEmpty()) return false
        // Do not pile up empty chats: reuse the current one if it has no messages.
        if (_turns.value.isEmpty()) return true
        return switchTo(s.create().id)
    }

    @Synchronized fun switchTo(id: String): Boolean {
        val s = store ?: return false; if (pendingIds.isNotEmpty() || s.meta(id) == null) return false
        currentSessionId = id; preferences.currentChatSessionId = id; _turns.value = interrupted(s.load(id)); return true
    }

    /** Deletes a chat (and its attachment files). If it was the one on screen, shows the newest other chat or a new one. */
    @Synchronized fun deleteSession(id: String): Boolean {
        val s = store ?: return false
        if (id == currentSessionId && pendingIds.isNotEmpty()) return false
        s.delete(id).forEach { path -> try { File(path).delete() } catch (_: Exception) { } }
        if (id == currentSessionId) { val next = s.sessions().firstOrNull()?.id ?: s.create().id; currentSessionId = next; preferences.currentChatSessionId = next; _turns.value = interrupted(s.load(next)) }
        return true
    }

    private fun persist(list: List<ChatTurn>) {
        val toSave = list.filter { it.id !in pendingIds }
        val s = store; val id = currentSessionId
        if (s != null && id != null) s.save(id, toSave) else preferences.saveChatHistory(toSave)
    }

    /** Starts a text-chat turn whose answer will stream in. Returns its id. Not persisted until [finishPending]. */
    @Synchronized
    fun beginPending(userText: String, attachmentPath: String?, attachmentName: String?, attachmentMimeType: String?): String {
        val turn = ChatTurn(
            userTranscript = userText.trim().ifEmpty { "(Message)" },
            jarvisResponse = "",
            attachmentPath = attachmentPath,
            attachmentName = attachmentName,
            attachmentMimeType = attachmentMimeType
        )
        pendingIds.add(turn.id)
        _turns.value = _turns.value + turn
        return turn.id
    }

    /** Updates the streaming text of a pending turn (memory only). */
    @Synchronized
    fun updatePending(id: String, text: String) {
        if (id !in pendingIds) return
        _turns.value = _turns.value.map { if (it.id == id) it.copy(jarvisResponse = text) else it }
    }

    /** Stores the final answer (or an error notice) of a turn and saves the history. */
    @Synchronized
    fun finishPending(id: String, text: String) {
        val clean = text.trim().ifEmpty { "⚠ The provider returned an empty answer. Tap Retry to ask again." }
        pendingIds.remove(id)
        val list = _turns.value.map { if (it.id == id) it.copy(jarvisResponse = clean) else it }
        _turns.value = list
        persist(list)
    }

    /** Clears a failed answer so the same turn can be asked again without adding a duplicate message. */
    @Synchronized
    fun resetPending(id: String): Boolean {
        if (_turns.value.none { it.id == id }) return false
        pendingIds.add(id)
        _turns.value = _turns.value.map { if (it.id == id) it.copy(jarvisResponse = "") else it }
        return true
    }

    fun userTextOf(id: String): String? = _turns.value.firstOrNull { it.id == id }?.userTranscript

    fun turnById(id: String): ChatTurn? = _turns.value.firstOrNull { it.id == id }

    @Synchronized
    fun addTurn(
        userText: String,
        jarvisText: String,
        attachmentPath: String? = null,
        attachmentName: String? = null,
        attachmentMimeType: String? = null
    ) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()
        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val currentList = _turns.value.toMutableList()
        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && last.jarvisResponse == cleanJarvis &&
                last.attachmentPath == attachmentPath) return
        }

        currentList.add(
            ChatTurn(
                userTranscript = cleanUser.ifEmpty { "(Audio query)" },
                jarvisResponse = cleanJarvis.ifEmpty { "(Audio response)" },
                attachmentPath = attachmentPath,
                attachmentName = attachmentName,
                attachmentMimeType = attachmentMimeType
            )
        )
        // Chat history is persisted locally; don't silently discard older conversations at 100 turns.
        _turns.value = currentList
        persist(currentList)
    }

    /** Completes the most recent pending text-chat turn with its written assistant response. */
    @Synchronized
    fun completeLatestTextTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanResponse = jarvisText.trim()
        if (cleanUser.isEmpty() || cleanResponse.isEmpty()) return

        val currentList = _turns.value.toMutableList()
        val index = currentList.indexOfLast {
            it.userTranscript == cleanUser &&
                (it.jarvisResponse == "(Audio response)" || it.jarvisResponse.isBlank())
        }
        if (index < 0) return

        currentList[index] = currentList[index].copy(jarvisResponse = cleanResponse)
        _turns.value = currentList
        persist(currentList)
    }

    @Synchronized
    fun clearHistory() {
        _turns.value.forEach { turn ->
            turn.attachmentPath?.let { path ->
                try { File(path).delete() } catch (_: Exception) { }
            }
        }
        _turns.value = emptyList()
        persist(emptyList())
        if (store == null) preferences.clearChatHistory()
    }
}
