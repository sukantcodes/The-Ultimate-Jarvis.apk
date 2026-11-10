package com.jarvis.assistant.provider

import com.jarvis.assistant.data.repository.ChatRepository

/** Lets [ProviderChatEngine] read and write the existing chat history without knowing about Android storage. */
class ProviderChatBridge(private val chat: ChatRepository) : ChatTurnStore {

    override fun completedTurns(excludingId: String?): List<StoredTurn> =
        chat.turns.value.filter { it.id != excludingId && it.jarvisResponse.isNotBlank() }
            .map { StoredTurn(it.id, it.userTranscript, it.jarvisResponse) }

    override fun begin(displayText: String, attachment: AttachmentRef?): String =
        chat.beginPending(displayText, attachment?.path, attachment?.name, attachment?.mimeType)

    override fun updatePartial(id: String, text: String) = chat.updatePending(id, text)

    override fun finish(id: String, text: String) = chat.finishPending(id, text)

    override fun fail(id: String, partial: String, errorText: String) {
        val kept = partial.trim()
        chat.finishPending(id, if (kept.isEmpty()) errorText else kept + "\n\n" + errorText)
    }

    override fun resetForRetry(id: String): Boolean = chat.resetPending(id)

    override fun userText(id: String): String? = chat.userTextOf(id)
}
