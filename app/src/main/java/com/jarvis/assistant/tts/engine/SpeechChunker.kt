package com.jarvis.assistant.tts.engine

/**
 * Cuts a reply into short, natural pieces so the offline voice can start speaking after the first piece is
 * ready instead of waiting for the whole reply. Short pieces are joined to their neighbour so the voice does not
 * stutter, and very long sentences are cut at a comma or a space.
 */
internal object SpeechChunker {
    const val MIN_CHARS = 25
    const val MAX_CHARS = 240

    private val boundary = Regex("(?<=[.!?\u0964\u0965\u061F\u2026])\\s+|\\n+")

    fun split(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val pieces = trimmed.split(boundary).map { it.trim() }.filter { it.isNotEmpty() }
        val merged = ArrayList<String>()
        var buffer = StringBuilder()
        for (piece in pieces) {
            if (buffer.isNotEmpty()) buffer.append(' ')
            buffer.append(piece)
            if (buffer.length >= MIN_CHARS) {
                merged.add(buffer.toString())
                buffer = StringBuilder()
            }
        }
        if (buffer.isNotEmpty()) {
            if (merged.isNotEmpty()) merged[merged.lastIndex] = merged[merged.lastIndex] + " " + buffer else merged.add(buffer.toString())
        }
        return merged.flatMap { capLength(it) }
    }

    private fun capLength(sentence: String): List<String> {
        if (sentence.length <= MAX_CHARS) return listOf(sentence)
        val out = ArrayList<String>()
        var rest = sentence
        while (rest.length > MAX_CHARS) {
            var end = rest.lastIndexOfAny(charArrayOf(',', ';', ':'), MAX_CHARS - 1)
            if (end >= MIN_CHARS) {
                end += 1
            } else {
                end = rest.lastIndexOf(' ', MAX_CHARS)
                if (end < MIN_CHARS) end = MAX_CHARS
            }
            out.add(rest.substring(0, end).trim())
            rest = rest.substring(end).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }
}
