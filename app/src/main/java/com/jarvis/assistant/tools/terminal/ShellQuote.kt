package com.jarvis.assistant.tools.terminal

/**
 * Safe building blocks for shell command lines. Tools that assemble a command from model-provided pieces (a
 * file name, a repository URL, a package name) must pass every piece through [quote]; a quoted piece is always one
 * single word to the shell, so `; rm -rf ~` in a file name stays part of the name.
 */
object ShellQuote {
    /** POSIX single-quoting. The result is exactly one shell word whatever [s] contains. */
    fun quote(s: String): String {
        if (s.isEmpty()) return "''"
        if (SAFE.matches(s)) return s
        return "'" + s.replace("'", "'\\''") + "'"
    }

    fun join(parts: List<String>): String = parts.joinToString(" ") { quote(it) }

    /** True when [s] is a plain identifier (letters, digits, dot, dash, underscore, slash) that needs no quoting. */
    fun isPlain(s: String): Boolean = s.isNotEmpty() && SAFE.matches(s)

    /** Refuses text that can not be one argument (NUL bytes or line breaks) instead of trying to escape it. */
    fun requireSingleLine(s: String, what: String): String {
        require(!s.contains('\u0000') && !s.contains('\n') && !s.contains('\r')) { "$what must be a single line." }
        return s
    }

    private val SAFE = Regex("^[A-Za-z0-9_@%+=:,./-]+$")
}

/** Splits a command line the way a shell would for word boundaries and quoting, without running anything. */
object ShellWords {
    /** Splits [command] into simple commands (separated by ; && || | & or a line break outside quotes). */
    fun segments(command: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = 0.toChar()
        var i = 0
        var depth = 0 // $( ... ) nesting keeps inner text together
        while (i < command.length) {
            val c = command[i]
            when {
                quote != 0.toChar() -> {
                    cur.append(c)
                    if (c == '\\' && quote == '"' && i + 1 < command.length) { cur.append(command[++i]) }
                    else if (c == quote) quote = 0.toChar()
                }
                c == '\\' && i + 1 < command.length -> { cur.append(c).append(command[++i]) }
                c == '\'' || c == '"' || c == '`' -> { quote = c; cur.append(c) }
                c == '$' && i + 1 < command.length && command[i + 1] == '(' -> { depth++; cur.append("$("); i++ }
                c == '(' && depth > 0 -> { depth++; cur.append(c) }
                c == ')' && depth > 0 -> { depth--; cur.append(c) }
                depth > 0 -> cur.append(c)
                c == ';' || c == '\n' || c == '|' || c == '&' -> {
                    // "&&", "||" and "|&" are single separators; ">&" and "2>&1" are redirections, not separators.
                    val prev = if (cur.isNotEmpty()) cur[cur.length - 1] else ' '
                    if (c == '&' && (prev == '>' || prev == '<')) { cur.append(c) }
                    else {
                        if (i + 1 < command.length && (command[i + 1] == c || (c == '|' && command[i + 1] == '&'))) i++
                        if (cur.isNotBlank()) out += cur.toString().trim()
                        cur.setLength(0)
                    }
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    /** Splits one simple command into words, honouring quotes. Quotes are removed from the returned words. */
    fun words(segment: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = 0.toChar()
        var has = false
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            when {
                quote == '\'' -> if (c == '\'') quote = 0.toChar() else cur.append(c)
                quote == '"' -> when {
                    c == '"' -> quote = 0.toChar()
                    c == '\\' && i + 1 < segment.length -> cur.append(segment[++i])
                    else -> cur.append(c)
                }
                c == '\'' || c == '"' -> { quote = c; has = true }
                c == '\\' && i + 1 < segment.length -> { cur.append(segment[++i]); has = true }
                c.isWhitespace() -> { if (cur.isNotEmpty() || has) { out += cur.toString(); cur.setLength(0); has = false } }
                else -> { cur.append(c); has = true }
            }
            i++
        }
        if (cur.isNotEmpty() || has) out += cur.toString()
        return out
    }
}
