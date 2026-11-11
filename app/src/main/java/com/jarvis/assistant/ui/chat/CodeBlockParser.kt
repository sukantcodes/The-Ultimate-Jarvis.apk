package com.jarvis.assistant.ui.chat

sealed class ParsedContent {
    data class Text(val text: String) : ParsedContent()
    data class Code(
        val language: String,
        val code: String,
        val isWriting: Boolean
    ) : ParsedContent()
}

/**
 * Parses markdown assistant replies into natural text segments and structured code blocks.
 * Supports open/unclosed code blocks during active streaming so live code typing animations
 * can start immediately as the model writes code.
 */
object CodeBlockParser {

    fun parse(content: String, isActivelyStreaming: Boolean): List<ParsedContent> {
        if (!content.contains("```")) {
            return if (content.isNotBlank()) listOf(ParsedContent.Text(content)) else emptyList()
        }
        val segments = ArrayList<ParsedContent>()
        var cursor = 0
        val length = content.length

        while (cursor < length) {
            val fenceIndex = content.indexOf("```", cursor)
            if (fenceIndex == -1) {
                val remaining = content.substring(cursor)
                if (remaining.isNotBlank()) segments.add(ParsedContent.Text(remaining))
                break
            }

            if (fenceIndex > cursor) {
                val preText = content.substring(cursor, fenceIndex)
                if (preText.isNotBlank()) segments.add(ParsedContent.Text(preText))
            }

            val langStart = fenceIndex + 3
            val firstLineEnd = content.indexOf('\n', langStart)
            if (firstLineEnd == -1) {
                val lang = content.substring(langStart).trim()
                segments.add(ParsedContent.Code(
                    language = if (lang.isNotBlank()) lang else "code",
                    code = "",
                    isWriting = isActivelyStreaming
                ))
                break
            }

            val rawLang = content.substring(langStart, firstLineEnd).trim()
            val language = if (rawLang.isNotBlank()) rawLang else "code"
            val codeStart = firstLineEnd + 1

            val closingFence = content.indexOf("```", codeStart)
            if (closingFence == -1) {
                val streamedCode = content.substring(codeStart)
                segments.add(ParsedContent.Code(
                    language = language,
                    code = streamedCode,
                    isWriting = isActivelyStreaming
                ))
                break
            } else {
                val code = content.substring(codeStart, closingFence).trimEnd('\r', '\n')
                segments.add(ParsedContent.Code(
                    language = language,
                    code = code,
                    isWriting = false
                ))
                cursor = closingFence + 3
                if (cursor < length && content[cursor] == '\r') cursor++
                if (cursor < length && content[cursor] == '\n') cursor++
            }
        }
        return segments
    }
}
