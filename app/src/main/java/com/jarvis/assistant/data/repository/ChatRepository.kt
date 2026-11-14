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
        // Purge any legacy audio turns from persisted history so ChatBox remains strictly for Work Purpose.
        _turns.value = preferences.loadChatHistory()
            .filter { it.userTranscript != "(Audio query)" && it.jarvisResponse != "(Audio response)" }
            .map {
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
        // ChatBox is strictly for work purpose: conversational audio placeholders are excluded
        if (cleanUser == "(Audio query)" || cleanJarvis == "(Audio response)") return

        val currentList = _turns.value.toMutableList()
        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && last.jarvisResponse == cleanJarvis &&
                last.attachmentPath == attachmentPath) return
        }

        currentList.add(
            ChatTurn(
                userTranscript = cleanUser,
                jarvisResponse = cleanJarvis,
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
            it.userTranscript == cleanUser && it.jarvisResponse.isBlank()
        }
        if (index < 0) return

        currentList[index] = currentList[index].copy(jarvisResponse = cleanResponse)
        _turns.value = currentList
        persist(currentList)
    }

    /** Turns that are finished and saved (a streaming answer is left alone). Oldest first. */
    @Synchronized
    fun savedTurns(): List<ChatTurn> = _turns.value.filter { it.id !in pendingIds }

    /** Removes one saved turn and its attachment file. A turn whose answer is still streaming can not be removed. */
    @Synchronized
    fun deleteTurn(id: String): Boolean {
        if (id in pendingIds) return false
        val turn = _turns.value.firstOrNull { it.id == id } ?: return false
        turn.attachmentPath?.let { path -> try { File(path).delete() } catch (_: Exception) { } }
        val list = _turns.value.filter { it.id != id }
        _turns.value = list
        persist(list)
        return true
    }

    /** Rewrites the text of a saved turn. Null leaves that side as it is. A streaming turn can not be edited. */
    @Synchronized
    fun editTurn(id: String, userText: String?, jarvisText: String?): Boolean {
        if (id in pendingIds || _turns.value.none { it.id == id }) return false
        val list = _turns.value.map {
            if (it.id != id) it
            else it.copy(
                userTranscript = userText?.trim()?.ifEmpty { null } ?: it.userTranscript,
                jarvisResponse = jarvisText?.trim()?.ifEmpty { null } ?: it.jarvisResponse
            )
        }
        _turns.value = list
        persist(list)
        return true
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
