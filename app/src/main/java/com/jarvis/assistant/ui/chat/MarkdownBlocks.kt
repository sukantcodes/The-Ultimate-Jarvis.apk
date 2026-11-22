package com.jarvis.assistant.ui.chat

/** Pure text helpers for block-level markdown streaming (no Android types, so they are unit-testable on the JVM). */
object MarkdownBlocks {

    /**
     * Index of the last blank-line break in [source] that has real text after it, or -1.
     * Everything before it is a run of finished blocks that will not change while the answer keeps streaming in
     * (answers only grow at the end); everything after it is the block still being written.
     */
    fun stableSplit(source: String): Int {
        val end = source.trimEnd().length          // ignore trailing blank lines: nothing is being written after them yet
        if (end <= 0) return -1
        val idx = source.lastIndexOf("\n\n", end - 1)
        return if (idx <= 0) -1 else idx
    }

    private val separator = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    /**
     * While a table is still being typed, hides the part that would look wrong: a header whose separator row has not
     * arrived yet (it would show as raw pipes and then jump into columns) and a last row that is cut off. A table is
     * therefore only ever shown formed. Only a run of lines at the very end of [text] is touched; once any line follows
     * the table (or the answer is finished) nothing is hidden.
     */
    fun trimUnsettledTable(text: String): String {
        if (text.endsWith("\n\n")) return text            // a blank line after the table: it is finished
        val terminated = text.endsWith("\n")                // the last row has its line break, so it is complete
        val lines = text.removeSuffix("\n").split('\n')
        var start = lines.size
        while (start > 0 && lines[start - 1].trimStart().startsWith("|")) start--
        if (start == lines.size) return text
        val run = lines.subList(start, lines.size)
        val confirmed = run.size >= 2 && run[1].contains('-') && separator.matches(run[1])
        val keep = when {
            !confirmed -> 0
            terminated || isCompleteRow(run.last(), run.first()) -> run.size
            run.size - 1 >= 2 -> run.size - 1
            else -> 0
        }
        if (keep == run.size) return text
        return lines.subList(0, start + keep).joinToString("\n").trimEnd('\n')
    }

    /** A row still being typed is cut off: it lacks the closing pipe or has fewer cells than the header. */
    private fun isCompleteRow(row: String, header: String): Boolean {
        val r = row.trim()
        return r.endsWith("|") && r.count { it == '|' } >= header.count { it == '|' }
    }
}
