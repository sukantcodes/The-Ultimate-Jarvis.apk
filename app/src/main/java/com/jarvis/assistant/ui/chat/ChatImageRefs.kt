package com.jarvis.assistant.ui.chat

import java.io.File

/** One picture inside an answer: where the file is, and the words that describe it. */
data class ChatImageRef(val path: String, val caption: String)

/**
 * Pictures Jarvis makes are written into the answer as a Markdown image line (`![caption](/path/to/file.png)`), so
 * they are saved with the chat history and come back after the app restarts. This turns those lines into picture
 * cards and removes them from the text that is shown as words.
 */
object ChatImageRefs {
    private val IMAGE_LINE = Regex("""!\[([^\]\n]*)]\((/[^)\s\n]+\.(?:png|jpe?g|webp))\)""", RegexOption.IGNORE_CASE)

    /** The Markdown line for one picture. Quotes and brackets in the caption are removed so the line always parses. */
    fun line(path: String, caption: String): String {
        val safe = caption.replace(Regex("[\\[\\]()\\n]"), " ").trim().take(120)
        return "![$safe]($path)"
    }

    /** [text] without picture lines (and the blank lines they leave), plus the pictures in order. Missing files are skipped. */
    fun extract(text: String): Pair<String, List<ChatImageRef>> {
        if (!text.contains("![")) return text to emptyList()
        val refs = ArrayList<ChatImageRef>()
        for (m in IMAGE_LINE.findAll(text)) {
            val path = m.groupValues[2]
            if (File(path).isFile) refs += ChatImageRef(path, m.groupValues[1].trim())
        }
        if (refs.isEmpty() && !IMAGE_LINE.containsMatchIn(text)) return text to emptyList()
        val cleaned = IMAGE_LINE.replace(text, "").replace(Regex("\n{3,}"), "\n\n").trim()
        return cleaned to refs
    }

    /** Text safe to read aloud: no picture lines. */
    fun forSpeech(text: String): String = IMAGE_LINE.replace(text, " ").trim()
}
