package com.jarvis.assistant.ui.chat

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Small, dependency-free markdown renderer for assistant replies:
 * headings (#..###), **bold**, *italic*, `inline code`, ``` fenced code ```, "- " / "* " bullets and "1." lists.
 * Anything else is shown as plain text, so malformed markdown can never hide content.
 */
object MarkdownRenderer {

    private val inline = Regex("""(\*\*[^*\n]+\*\*|`[^`\n]+`|(?<![*\w])\*[^*\s][^*\n]*\*(?![*\w]))""")
    private val heading = Regex("""^(#{1,3})\s+(.*)$""")
    private val bullet = Regex("""^\s*[-*\u2022]\s+(.*)$""")
    private val numbered = Regex("""^\s*(\d{1,3})[.)]\s+(.*)$""")
    private const val CODE_BG = 0x26808080

    fun render(source: String, density: Float): CharSequence {
        if (source.none { it == '*' || it == '`' || it == '#' || it == '-' || it == '\u2022' || it.isDigit() }) return source
        val out = SpannableStringBuilder()
        val indent = (18f * density).toInt()
        var inFence = false
        val lines = source.replace("\r\n", "\n").split('\n')
        for ((i, raw) in lines.withIndex()) {
            if (raw.trimStart().startsWith("```")) { inFence = !inFence; continue }
            val lineStart = out.length
            if (inFence) {
                out.append(raw)
                out.setSpan(TypefaceSpan("monospace"), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(CODE_BG), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.92f), lineStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val h = heading.find(raw)
                val b = bullet.find(raw)
                val n = numbered.find(raw)
                when {
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
