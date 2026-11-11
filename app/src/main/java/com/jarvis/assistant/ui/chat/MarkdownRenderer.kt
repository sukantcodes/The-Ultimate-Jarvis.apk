package com.jarvis.assistant.ui.chat

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.URLSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Small, dependency-free markdown renderer for assistant replies:
 * headings (#..###), **bold**, *italic*, ~~strike~~, `inline code`, [links](https://..), fenced code with light syntax
 * colouring, "- " / "* " bullets, "1." lists, > quotes, --- rules and pipe tables (shown aligned in monospace).
 * Anything else is shown as plain text, so malformed markdown can never hide content.
 */
object MarkdownRenderer {

    private val inline = Regex("""(\*\*[^*\n]+\*\*|~~[^~\n]+~~|\[[^\]\n]+\]\(https?://[^)\s]+\)|`[^`\n]+`|(?<![*\w])\*[^*\s][^*\n]*\*(?![*\w]))""")
    private val heading = Regex("""^(#{1,3})\s+(.*)$""")
    private val bullet = Regex("""^\s*[-*\u2022]\s+(.*)$""")
    private val numbered = Regex("""^\s*(\d{1,3})[.)]\s+(.*)$""")
    private val quote = Regex("""^\s*>\s?(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private const val CODE_BG = 0x26808080
    private const val C_COMMENT = 0xFF7A8A7A.toInt()
    private const val C_STRING = 0xFFCE8F4A.toInt()
    private const val C_KEYWORD = 0xFF6FA8DC.toInt()
    private const val C_NUMBER = 0xFFB39DDB.toInt()
    private val keywords = setOf("fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return", "import", "package",
        "def", "function", "const", "let", "async", "await", "public", "private", "static", "void", "int", "new", "this", "self", "try", "catch", "except",
        "finally", "throw", "null", "None", "true", "false", "True", "False", "in", "is", "from", "as", "with", "lambda", "export", "default", "extends", "implements")
    private val codeToken = Regex("""(//.*|#.*|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)*'|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z_0-9]*\b)""")

    private fun highlight(out: SpannableStringBuilder, line: String, start: Int, lang: String) {
        val hashComments = lang in setOf("python", "py", "bash", "sh", "shell", "yaml", "yml", "toml", "ruby", "rb", "")
        for (m in codeToken.findAll(line)) {
            val t = m.value
            val color = when {
                t.startsWith("//") -> C_COMMENT
                t.startsWith("#") -> if (hashComments) C_COMMENT else continue
                t.startsWith("\"") || t.startsWith("'") -> C_STRING
                t[0].isDigit() -> C_NUMBER
                t in keywords -> C_KEYWORD
                else -> continue
            }
            out.setSpan(ForegroundColorSpan(color), start + m.range.first, start + m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    private fun renderTable(out: SpannableStringBuilder, rows: List<String>) {
        val data = rows.filterIndexed { i, r -> !(i == 1 && tableSep.matches(r)) }.map(::cells)
        val cols = data.maxOf { it.size }
        val widths = IntArray(cols) { c -> data.maxOf { row -> row.getOrNull(c)?.length ?: 0 }.coerceAtMost(40) }
        data.forEachIndexed { r, row ->
            val s = out.length
            out.append((0 until cols).joinToString("  ") { c -> (row.getOrNull(c) ?: "").take(40).padEnd(widths[c]) }.trimEnd())
            out.setSpan(TypefaceSpan("monospace"), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(0.9f), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r == 0) out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append('\n')
            if (r == 0) { val u = out.length; out.append("\u2500".repeat(widths.sum() + 2 * (cols - 1)).take(80)); out.setSpan(RelativeSizeSpan(0.9f), u, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); out.append('\n') }
        }
        if (out.isNotEmpty() && out.last() == '\n') out.delete(out.length - 1, out.length)
    }

    fun render(source: String, density: Float): CharSequence {
        if (source.none { it == '*' || it == '`' || it == '#' || it == '-' || it == '\u2022' || it == '|' || it == '>' || it == '~' || it == '[' || it == '_' || it.isDigit() }) return source
        val out = SpannableStringBuilder()
        val indent = (18f * density).toInt()
        var inFence = false
        var lang = ""
        val lines = source.replace("\r\n", "\n").split('\n')
        var i = -1
        while (++i < lines.size) {
            val raw = lines[i]
            if (raw.trimStart().startsWith("```")) { inFence = !inFence; lang = if (inFence) raw.trim().removePrefix("```").trim().lowercase() else ""; continue }
            val lineStart = out.length
            if (!inFence && raw.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1]) && raw.trim().let { it.startsWith("|") || cells(it).size > 1 }) {
                var j = i
                while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) j++
                renderTable(out, lines.subList(i, j))
                i = j - 1
                if (i < lines.lastIndex) out.append('\n')
                continue
            }
            if (inFence) {
                out.append(raw)
                highlight(out, raw, lineStart, lang)
                out.setSpan(TypefaceSpan("monospace"), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(CODE_BG), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.92f), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val h = heading.find(raw)
                val b = bullet.find(raw)
                val n = numbered.find(raw)
                when {
                    rule.matches(raw) -> out.append("\u2500".repeat(24))
                    raw.trimStart().startsWith(">") -> {
                        out.append("\u258E ")
                        appendInline(out, quote.find(raw)!!.groupValues[1])
                        out.setSpan(StyleSpan(Typeface.ITALIC), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(LeadingMarginSpan.Standard(0, indent), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    h != null -> {
                        appendInline(out, h.groupValues[2])
                        val scale = when (h.groupValues[1].length) { 1 -> 1.25f; 2 -> 1.15f; else -> 1.07f }
                        out.setSpan(StyleSpan(Typeface.BOLD), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(RelativeSizeSpan(scale), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    b != null -> {
                        out.append("\u2022  ")
                        appendInline(out, b.groupValues[1])
                        out.setSpan(LeadingMarginSpan.Standard(0, indent), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    n != null -> {
                        out.append(n.groupValues[1]).append(".  ")
                        appendInline(out, n.groupValues[2])
                        out.setSpan(LeadingMarginSpan.Standard(0, indent), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    else -> appendInline(out, raw)
                }
            }
            if (i < lines.lastIndex) out.append('\n')
        }
        return out
    }

    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var last = 0
        for (m in inline.findAll(text)) {
            out.append(text, last, m.range.first)
            val tok = m.value
            val start = out.length
            when {
                tok.startsWith("**") -> {
                    out.append(tok.substring(2, tok.length - 2))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("~~") -> {
                    out.append(tok.substring(2, tok.length - 2))
                    out.setSpan(StrikethroughSpan(), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("[") -> {
                    val close = tok.indexOf("](")
                    out.append(tok.substring(1, close))
                    out.setSpan(URLSpan(tok.substring(close + 2, tok.length - 1)), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("`") -> {
                    out.append(tok.substring(1, tok.length - 1))
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(CODE_BG), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    out.append(tok.substring(1, tok.length - 1))
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
    }
}
