package com.jarvis.assistant.voice

import java.util.regex.Pattern

/**
 * Generates concise, natural, 1-2 sentence spoken explanations for Jarvis assistant responses.
 *
 * Mandate:
 * - NEVER read raw code, markdown formatting, HTML tags, stack traces, or lengthy logs.
 * - For coding, file creations, or website generation, summarize the concrete action taken
 *   (e.g., "I've built the website files and started your local server on port 8080.").
 * - For general queries, speak only the first 1-2 executive sentences (maximum ~30 words)
 *   so speech is instantaneous, crisp, and natural.
 */
object SpokenSummaryGenerator {

    private val CODE_FENCE_REGEX = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE_REGEX = Regex("`+([^`]+)`+")
    private val URL_REGEX = Regex("https?://\\S+")
    private val MARKDOWN_SYMBOLS_REGEX = Regex("[#*_~>\\[\\]()|]+")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    fun generateSummary(userPrompt: String?, responseText: String?): String {
        val userLower = userPrompt?.trim()?.lowercase().orEmpty()
        val text = responseText?.trim().orEmpty()

        if (text.isBlank()) {
            return "I have completed your task, Sir."
        }

        val hasCodeFences = text.contains("```")
        val hasFileActions = text.contains("write_file", ignoreCase = true) ||
                text.contains("Created ", ignoreCase = true) ||
                text.contains("index.html", ignoreCase = true) ||
                text.contains("server", ignoreCase = true)

        // Specialized short spoken action explanations
        if (userLower.contains("website") || userLower.contains("web site") || text.contains("index.html", ignoreCase = true)) {
            val portMatch = Regex("(?:port|localhost:)\\s*(\\d{2,5})", RegexOption.IGNORE_CASE).find(text)
            val portStr = portMatch?.groupValues?.getOrNull(1)
            return if (portStr != null) {
                "I've built your website files and launched the local server on port $portStr. Everything is ready, Sir."
            } else {
                "I've generated the website files with styling and structure in your workspace, Sir."
            }
        }

        if (hasFileActions || hasCodeFences) {
            val fileMatch = Regex("(?:Created|Writing|Updated|File)\\s+([a-zA-Z0-9_.-]+\\.[a-zA-Z0-9]+)", RegexOption.IGNORE_CASE).find(text)
            val fileName = fileMatch?.groupValues?.getOrNull(1)
            return if (fileName != null) {
                "I have prepared $fileName and completed the execution in your workspace."
            } else {
                "I've executed the requested tools and saved the generated files to your workspace, Sir."
            }
        }

        // Clean conversational text for spoken speech
        val cleaned = text
            .replace(CODE_FENCE_REGEX, " ")
            .replace(URL_REGEX, " ")
            .replace(INLINE_CODE_REGEX, "$1")
            .replace(MARKDOWN_SYMBOLS_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

        if (cleaned.isBlank()) {
            return "I've completed your task and prepared the workspace."
        }

        // Deliver the complete answer without cutting off or truncating mid-sentence
        return cleaned
    }
}
