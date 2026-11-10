package com.jarvis.assistant.tools.github

/** Unified-diff generator for showing a change before it is committed. Pure and dependency free. */
object DiffUtil {
    private enum class T { EQ, DEL, INS }
    private class Op(val t: T, val line: String)

    /**
     * Returns a unified diff from [oldText] (null = the file does not exist yet) to [newText], or an empty string
     * when nothing changed. Very large differing regions (more than [maxCells] line pairs) are reported as one
     * whole replacement instead of a minimal diff, so a huge file can not stall the app.
     */
    fun unified(oldText: String?, newText: String, path: String, context: Int = 3, maxCells: Long = 4_000_000L): String {
        if (oldText != null && oldText == newText) return ""
        val a = lines(oldText.orEmpty())
        val b = lines(newText)
        val ops = diff(a, b, maxCells)
        val header = StringBuilder()
        header.append(if (oldText == null) "--- /dev/null\n" else "--- a/$path\n").append("+++ b/$path\n")
        val changes = ops.indices.filter { ops[it].t != T.EQ }
        if (changes.isEmpty()) return header.toString()

        val oldBefore = IntArray(ops.size + 1); val newBefore = IntArray(ops.size + 1)
        for (i in ops.indices) {
            oldBefore[i + 1] = oldBefore[i] + if (ops[i].t != T.INS) 1 else 0
            newBefore[i + 1] = newBefore[i] + if (ops[i].t != T.DEL) 1 else 0
        }
        // Merge the context windows around each change into hunks.
        val ranges = ArrayList<IntArray>()
        for (c in changes) {
            val s = maxOf(0, c - context); val e = minOf(ops.size - 1, c + context)
            if (ranges.isNotEmpty() && s <= ranges.last()[1] + 1) ranges.last()[1] = maxOf(ranges.last()[1], e) else ranges += intArrayOf(s, e)
        }
        val out = StringBuilder(header)
        for ((s, e) in ranges.map { it[0] to it[1] }) {
            var oldLen = 0; var newLen = 0
            for (i in s..e) { if (ops[i].t != T.INS) oldLen++; if (ops[i].t != T.DEL) newLen++ }
            val oldStart = if (oldLen == 0) oldBefore[s] else oldBefore[s] + 1
            val newStart = if (newLen == 0) newBefore[s] else newBefore[s] + 1
            out.append("@@ -$oldStart,$oldLen +$newStart,$newLen @@\n")
            for (i in s..e) out.append(when (ops[i].t) { T.EQ -> ' '; T.DEL -> '-'; T.INS -> '+' }).append(ops[i].line).append('\n')
        }
        return out.toString()
    }

    /** Lines added and removed in a diff produced by [unified]. */
    fun counts(diff: String): Pair<Int, Int> {
        var add = 0; var del = 0
        for (l in diff.lineSequence()) {
            if (l.startsWith("+++") || l.startsWith("---")) continue
            if (l.startsWith("+")) add++ else if (l.startsWith("-")) del++
        }
        return add to del
    }

    fun truncate(diff: String, maxChars: Int): String =
        if (diff.length <= maxChars) diff else diff.take(maxChars) + "\n… (diff shortened, ${diff.length - maxChars} more characters)"

    private fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n')
        return if (parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    private fun diff(a: List<String>, b: List<String>, maxCells: Long): List<Op> {
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.subList(pre, a.size - suf); val bm = b.subList(pre, b.size - suf)
        val ops = ArrayList<Op>(a.size + b.size)
        for (i in 0 until pre) ops += Op(T.EQ, a[i])
        if (am.isEmpty()) bm.forEach { ops += Op(T.INS, it) }
        else if (bm.isEmpty()) am.forEach { ops += Op(T.DEL, it) }
        else if (am.size.toLong() * bm.size > maxCells) { am.forEach { ops += Op(T.DEL, it) }; bm.forEach { ops += Op(T.INS, it) } }
        else lcs(am, bm, ops)
        for (i in a.size - suf until a.size) ops += Op(T.EQ, a[i])
        return ops
    }

    private fun lcs(a: List<String>, b: List<String>, out: MutableList<Op>) {
        val n = a.size; val m = b.size
        val dp = IntArray((n + 1) * (m + 1))
        val w = m + 1
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i * w + j] = if (a[i] == b[j]) dp[(i + 1) * w + j + 1] + 1 else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
        }
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += Op(T.EQ, a[i]); i++; j++ }
                dp[(i + 1) * w + j] >= dp[i * w + j + 1] -> { out += Op(T.DEL, a[i]); i++ }
                else -> { out += Op(T.INS, b[j]); j++ }
            }
        }
        while (i < n) out += Op(T.DEL, a[i++])
        while (j < m) out += Op(T.INS, b[j++])
    }
}
