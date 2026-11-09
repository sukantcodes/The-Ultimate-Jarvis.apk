package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatRepository(private val preferences: AppPreferences) {

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    /** Turns whose answer is still streaming. They live in memory only and are never written to disk. */
    private val pendingIds = HashSet<String>()

    init {
        // A turn saved without an answer can only come from a process that died mid-answer; show it as
        // interrupted instead of leaving the chat waiting forever.
        _turns.value = preferences.loadChatHistory().map {
            if (it.jarvisResponse.isBlank()) it.copy(jarvisResponse = "⚠ This answer was interrupted. Tap Retry to ask again.") else it
        }
    }

    private fun persist(list: List<ChatTurn>) {
        preferences.saveChatHistory(list.filter { it.id !in pendingIds })
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
        preferences.clearChatHistory()
    }
}
