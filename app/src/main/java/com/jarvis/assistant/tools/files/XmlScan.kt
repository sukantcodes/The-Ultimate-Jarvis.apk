package com.jarvis.assistant.tools.files

internal interface XmlEvents {
    /** Called for every start tag; a self-closing tag calls [start] (selfClosing = true) and then [end]. */
    fun start(name: String, attrs: String, selfClosing: Boolean) {}
    fun end(name: String) {}
    fun text(text: String) {}
}

/** A small, tolerant tag scanner for XML and HTML. It never resolves entities from a DTD, so it is safe on untrusted files. */
internal object XmlScan {

    fun local(name: String): String = name.substringAfter(':')

    fun scan(xml: String, h: XmlEvents) {
        var i = 0
        val n = xml.length
        while (i < n) {
            if (xml[i] != '<') {
                val j = xml.indexOf('<', i).let { if (it < 0) n else it }
                h.text(decode(xml.substring(i, j)))
                i = j
                continue
            }
            if (xml.startsWith("<!--", i)) { val e = xml.indexOf("-->", i + 4); i = if (e < 0) n else e + 3; continue }
            if (xml.startsWith("<![CDATA[", i)) {
                val e = xml.indexOf("]]>", i + 9)
                h.text(xml.substring(i + 9, if (e < 0) n else e)); i = if (e < 0) n else e + 3; continue
            }
            if (xml.startsWith("<?", i)) { val e = xml.indexOf("?>", i + 2); i = if (e < 0) n else e + 2; continue }
            if (xml.startsWith("<!", i)) { val e = xml.indexOf('>', i + 2); i = if (e < 0) n else e + 1; continue }
            val next = if (i + 1 < n) xml[i + 1] else ' '
            if (!(next.isLetter() || next == '/' || next == '_')) { h.text("<"); i++; continue }
            var j = i + 1
            var quote = '\u0000'
            while (j < n) {
                val ch = xml[j]
                if (quote != '\u0000') { if (ch == quote) quote = '\u0000' }
                else if (ch == '"' || ch == '\'') quote = ch
                else if (ch == '>') break
                j++
            }
            if (j >= n) break
            val inner = xml.substring(i + 1, j)
            i = j + 1
            if (inner.startsWith("/")) { h.end(inner.substring(1).trim().takeWhile { !it.isWhitespace() }); continue }
            val self = inner.endsWith("/")
            val body = if (self) inner.dropLast(1) else inner
            val name = body.takeWhile { !it.isWhitespace() }
            h.start(name, body.substring(name.length), self)
            if (self) h.end(name)
        }
    }

    fun attr(attrs: String, name: String): String? {
        var from = 0
        while (true) {
            val k = attrs.indexOf(name, from)
            if (k < 0) return null
            val before = if (k == 0) ' ' else attrs[k - 1]
            var p = k + name.length
            if (!(before.isWhitespace() || before == '"' || before == '\'')) { from = k + 1; continue }
            while (p < attrs.length && attrs[p].isWhitespace()) p++
            if (p >= attrs.length || attrs[p] != '=') { from = k + 1; continue }
            p++
            while (p < attrs.length && attrs[p].isWhitespace()) p++
            if (p >= attrs.length) return null
            val q = attrs[p]
            return if (q == '"' || q == '\'') {
                val e = attrs.indexOf(q, p + 1)
                decode(attrs.substring(p + 1, if (e < 0) attrs.length else e))
            } else {
                var e = p
                while (e < attrs.length && !attrs[e].isWhitespace() && attrs[e] != '/') e++
                decode(attrs.substring(p, e))
            }
        }
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "copy" to "©", "reg" to "®",
        "trade" to "™", "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»", "lsquo" to "‘",
        "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "bull" to "•", "middot" to "·", "euro" to "€", "pound" to "£",
        "yen" to "¥", "cent" to "¢", "deg" to "°", "times" to "×", "divide" to "÷", "para" to "¶", "sect" to "§"
    )

    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val e = s.indexOf(';', i + 1)
            if (e < 0 || e - i > 10) { sb.append(c); i++; continue }
            val name = s.substring(i + 1, e)
            val rep: String? = when {
                name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let { cp(it) }
                name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { cp(it) }
                else -> NAMED[name]
            }
            if (rep == null) { sb.append(c); i++ } else { sb.append(rep); i = e + 1 }
        }
        return sb.toString()
    }

    private fun cp(code: Int): String? = if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else null

    /** Null when tags nest correctly, otherwise a short description of the first problem. */
    fun balancedError(xml: String): String? {
        val stack = ArrayList<String>()
        var problem: String? = null
        var roots = 0
        var selfClosed: String? = null
        scan(xml, object : XmlEvents {
            override fun start(name: String, attrs: String, selfClosing: Boolean) { if (problem == null) { if (stack.isEmpty()) roots++; selfClosed = if (selfClosing) name else null; if (!selfClosing) stack.add(name) } }
            override fun end(name: String) {
                if (problem != null) return
                if (selfClosed == name) { selfClosed = null; return }
                if (stack.isEmpty() && roots > 0 && stack.isEmpty()) { /* closing of a self-closing root */ return }
                if (stack.isEmpty() || stack.last() != name) problem = "Unexpected closing tag </$name>"
                else stack.removeAt(stack.size - 1)
            }
        })
        if (problem == null && stack.isNotEmpty()) problem = "Tag <${stack.last()}> is not closed"
        if (problem == null && roots == 0) problem = "No root element"
        return problem
    }

    /** Escapes text for XML and removes characters XML does not allow. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val code = c.code
            when {
                c == '&' -> sb.append("&amp;"); c == '<' -> sb.append("&lt;"); c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;"); c == '\'' -> sb.append("&apos;")
                code == 9 || code == 10 || code == 13 -> sb.append(c)
                code < 32 -> {}
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { sb.append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) || code == 0xFFFE || code == 0xFFFF -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
