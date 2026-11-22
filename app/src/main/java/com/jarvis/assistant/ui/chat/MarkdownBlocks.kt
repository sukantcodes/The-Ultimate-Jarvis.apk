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
}
