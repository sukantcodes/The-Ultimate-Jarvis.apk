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

    init {
        _turns.value = preferences.loadChatHistory()
    }

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
        preferences.saveChatHistory(currentList)
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
