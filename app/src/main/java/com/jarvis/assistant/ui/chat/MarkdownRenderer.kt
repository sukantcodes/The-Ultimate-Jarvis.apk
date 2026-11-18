package com.jarvis.assistant.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.jarvis.assistant.util.ThemeManager

/**
 * Dependency-free markdown renderer for assistant replies. It is built to make answers easy to read on a phone:
 * coloured headings with breathing room, bullets and numbered lists with hanging indents (also nested), task lists,
 * block quotes with a side bar, horizontal rules, tables (aligned when they fit, stacked as cards when they do not),
 * links, **bold**, *italic*, ~~strikethrough~~, `inline code` and fenced code.
 *
 * Anything it does not understand is shown as plain text, so malformed or half-streamed markdown can never hide content.
 */
object MarkdownRenderer {

    private val inline = Regex(
        """(\*\*[^*\n]+\*\*|(?<!\w)__[^_\n]+__(?!\w)|~~[^~\n]+~~|`[^`\n]+`|\[[^\]\n]+]\([^)\s]+\)|""" +
            """https?://[^\s<>()\]]*[^\s<>()\].,;:!?'"]|(?<![*\w])\*[^*\s][^*\n]*\*(?![*\w])|""" +
            """==[^=\n]+==|\{(?:red|green|blue|orange|purple|yellow|big|small):[^{}\n]+})"""
    )
    private val styled = mapOf(
        "red" to 0xFFFF5252.toInt(), "green" to 0xFF00E676.toInt(), "blue" to 0xFF448AFF.toInt(),
        "orange" to 0xFFFFAB40.toInt(), "purple" to 0xFFB388FF.toInt(), "yellow" to 0xFFFFE57F.toInt()
    )
    private val heading = Regex("""^\s{0,3}(#{1,4})\s+(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val quote = Regex("""^\s*>\s?(.*)$""")
    private val task = Regex("""^(\s*)[-*+\u2022]\s+\[( |x|X)]\s+(.*)$""")
    private val bullet = Regex("""^(\s*)[-*+\u2022]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val tableSeparator = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private const val CODE_BG = 0x26808080
    private const val MUTED = 0xFF90A4AE.toInt()
    private const val MAX_ALIGNED_TABLE_CHARS = 44
    private const val MAX_CELL_CHARS = 24

    fun render(source: String, density: Float): CharSequence = render(source, density, defaultAccent())

    fun render(source: String, density: Float, accent: Int): CharSequence {
        if (source.none { it == '*' || it == '`' || it == '#' || it == '-' || it == '+' || it == '\u2022' || it == '>' || it == '[' || it == '~' || it == '|' || it == '_' || it == '{' || it == '=' || it.isDigit() } && !source.contains("http")) return source
        val out = SpannableStringBuilder()
        val lines = source.replace("\r\n", "\n").split('\n')
        var inFence = false
        var gapPending = false
        var i = 0

        // Starts a new block: a line break after earlier content, and a half-height gap when a blank line or a heading asks for one.
        fun beginBlock(extraGap: Boolean) {
            if (out.isEmpty()) { gapPending = false; return }
            out.append('\n')
            if (gapPending || extraGap) {
                val at = out.length
                out.append('\n')
                out.setSpan(RelativeSizeSpan(0.55f), at, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            gapPending = false
        }

        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()

            if (trimmed.startsWith("```")) { inFence = !inFence; i++; continue }
            if (inFence) {
                beginBlock(false)
                val s = out.length
                out.append(raw)
                out.setSpan(TypefaceSpan("monospace"), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(CODE_BG), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.92f), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++; continue
            }
            if (trimmed.isEmpty()) { if (out.isNotEmpty()) gapPending = true; i++; continue }

            // Table: a header row, a separator row, then body rows.
            if (trimmed.startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                val rows = ArrayList<List<String>>()
                rows += cells(trimmed)
                var j = i + 2
                while (j < lines.size && lines[j].trim().startsWith("|")) { rows += cells(lines[j].trim()); j++ }
                beginBlock(false)
                renderTable(out, rows, density, accent)
                i = j
                continue
            }

            val h = heading.find(raw)
            if (h != null) {
                beginBlock(true)
                val s = out.length
                appendInline(out, h.groupValues[2], accent)
                val level = h.groupValues[1].length
                val scale = when (level) { 1 -> 1.32f; 2 -> 1.2f; 3 -> 1.1f; else -> 1.03f }
                out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(scale), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (level <= 3) out.setSpan(ForegroundColorSpan(accent), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++; continue
            }

            if (rule.matches(raw)) {
                beginBlock(true)
                val s = out.length
                out.append("────────────────")
                out.setSpan(ForegroundColorSpan(0x66808080), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.8f), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++; continue
            }

            val q = quote.find(raw)
            if (q != null) {
                beginBlock(false)
                val s = out.length
                appendInline(out, q.groupValues[1], accent)
                out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(MUTED), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BarSpan(accent, dp(3, density), dp(9, density)), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++; continue
            }

            val t = task.find(raw)
            if (t != null) {
                beginBlock(false)
                val s = out.length
                val done = t.groupValues[2].isNotBlank()
                val base = dp(16, density) * level(t.groupValues[1])
                out.append(if (done) "☑  " else "☐  ")
                out.setSpan(ForegroundColorSpan(if (done) 0xFF00E676.toInt() else accent), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                val textStart = out.length
                appendInline(out, t.groupValues[3], accent)
                if (done) out.setSpan(ForegroundColorSpan(MUTED), textStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(LeadingMarginSpan.Standard(base, base + dp(22, density)), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++; continue
            }

            val b = bullet.find(raw)
            if (b != null) {
                beginBlock(false)
                val s = out.length
                val lv = level(b.groupValues[1])
                val base = dp(14, density) * lv
                out.append(when (lv) { 0 -> "•  "; 1 -> "◦  "; else -> "▪  " })
                out.setSpan(ForegroundColorSpan(accent), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                appendInline(out, b.groupValues[2], accent)
                out.setSpan(LeadingMarginSpan.Standard(base, base + dp(18, density)), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++; continue
            }

            val n = numbered.find(raw)
            if (n != null) {
                beginBlock(false)
                val s = out.length
                val base = dp(14, density) * level(n.groupValues[1])
                out.append(n.groupValues[2]).append(".  ")
                out.setSpan(ForegroundColorSpan(accent), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                appendInline(out, n.groupValues[3], accent)
                out.setSpan(LeadingMarginSpan.Standard(base, base + dp(24, density)), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++; continue
            }

            beginBlock(false)
            appendInline(out, raw.trimEnd(), accent)
            i++
        }
        return out
    }

    /** Lets the links in a rendered reply be tapped while the text stays selectable. Call once per TextView. */
    fun attachLinkTouch(view: TextView) {
        view.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val tv = v as TextView
                val text = tv.text as? Spanned
                val layout = tv.layout
                if (text != null && layout != null && tv.selectionStart == tv.selectionEnd) {
                    val x = event.x.toInt() - tv.totalPaddingLeft + tv.scrollX
                    val y = event.y.toInt() - tv.totalPaddingTop + tv.scrollY
                    if (y >= 0 && y < layout.height) {
                        val line = layout.getLineForVertical(y)
                        if (x >= layout.getLineLeft(line) && x <= layout.getLineRight(line)) {
                            val off = layout.getOffsetForHorizontal(line, x.toFloat())
                            text.getSpans(off, off, ClickableSpan::class.java).firstOrNull()?.onClick(tv)
                        }
                    }
                }
            }
            false
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun level(indent: String): Int = (indent.replace("\t", "    ").length / 2).coerceIn(0, 3)

    private fun dp(v: Int, density: Float): Int = (v * density).toInt()

    private fun defaultAccent(): Int = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { 0xFF00E5FF.toInt() }

    private fun cells(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.substring(1)
        if (r.endsWith("|")) r = r.dropLast(1)
        return r.split('|').map { it.trim() }
    }

    private fun plain(s: String): String = s.replace(Regex("""\{(?:red|green|blue|orange|purple|yellow|big|small):([^{}]*)}"""), "$1").replace(Regex("""\*\*|__|~~|==|`"""), "").replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")

    private fun renderTable(out: SpannableStringBuilder, rows: List<List<String>>, density: Float, accent: Int) {
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.size }
        val header = rows.first() + List(cols - rows.first().size) { "" }
        val body = rows.drop(1).map { it + List(cols - it.size) { "" } }
        val widths = IntArray(cols) { c -> minOf(MAX_CELL_CHARS, (listOf(header) + body).maxOf { plain(it[c]).length }.coerceAtLeast(1)) }
        val total = widths.sum() + 3 * (cols - 1)

        fun fit(s: String, w: Int): String {
            val p = plain(s)
            return if (p.length > w) p.take(w - 1) + "…" else p.padEnd(w)
        }

        if (total <= MAX_ALIGNED_TABLE_CHARS) {
            fun line(cells: List<String>, bold: Boolean, first: Boolean) {
                if (!first) out.append('\n')
                val s = out.length
                out.append(cells.indices.joinToString("   ") { fit(cells[it], widths[it]) }.trimEnd())
                out.setSpan(TypefaceSpan("monospace"), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(0.86f), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(0x14808080), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (bold) {
                    out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(accent), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            line(header, bold = true, first = true)
            val sepStart = out.length + 1
            out.append('\n').append(widths.joinToString("───") { "─".repeat(it) })
            out.setSpan(TypefaceSpan("monospace"), sepStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(0.86f), sepStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(0x66808080), sepStart, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            body.forEach { line(it, bold = false, first = false) }
        } else {
            // Too wide for a phone: show every row as a small card, with the first column as its title.
            body.forEachIndexed { idx, row ->
                if (idx > 0) {
                    out.append('\n')
                    val g = out.length
                    out.append('\n')
                    out.setSpan(RelativeSizeSpan(0.55f), g, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val s = out.length
                appendInline(out, row[0].ifBlank { "—" }, accent)
                out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(accent), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                for (c in 1 until cols) {
                    if (row[c].isBlank()) continue
                    out.append('\n')
                    val ls = out.length
                    out.append(plain(header[c]).ifBlank { "•" }).append(": ")
                    out.setSpan(StyleSpan(Typeface.BOLD), ls, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(MUTED), ls, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    appendInline(out, row[c], accent)
                    out.setSpan(LeadingMarginSpan.Standard(dp(12, density), dp(12, density)), ls, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun appendInline(out: SpannableStringBuilder, text: String, accent: Int) {
        var last = 0
        for (m in inline.findAll(text)) {
            out.append(text, last, m.range.first)
            val tok = m.value
            val start = out.length
            when {
                tok.startsWith("**") || tok.startsWith("__") -> {
                    out.append(tok.substring(2, tok.length - 2))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("==") -> {
                    out.append(tok.substring(2, tok.length - 2))
                    out.setSpan(BackgroundColorSpan((accent and 0x00FFFFFF) or 0x40000000), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("{") -> {
                    val colon = tok.indexOf(':')
                    val tag = tok.substring(1, colon)
                    out.append(tok.substring(colon + 1, tok.length - 1))
                    when (tag) {
                        "big" -> {
                            out.setSpan(RelativeSizeSpan(1.3f), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        "small" -> out.setSpan(RelativeSizeSpan(0.8f), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        else -> {
                            out.setSpan(ForegroundColorSpan(styled.getValue(tag)), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
                tok.startsWith("~~") -> {
                    out.append(tok.substring(2, tok.length - 2))
                    out.setSpan(StrikethroughSpan(), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("`") -> {
                    out.append(tok.substring(1, tok.length - 1))
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(CODE_BG), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(0.92f), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("[") -> {
                    val close = tok.indexOf("](")
                    out.append(tok.substring(1, close))
                    out.setSpan(LinkSpan(tok.substring(close + 2, tok.length - 1), accent), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                tok.startsWith("http") -> {
                    out.append(tok)
                    out.setSpan(LinkSpan(tok, accent), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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

    /** A tappable link in the accent colour. Only web addresses are opened. */
    private class LinkSpan(private val url: String, private val color: Int) : ClickableSpan() {
        override fun onClick(widget: View) {
            val uri = try { Uri.parse(url) } catch (_: Exception) { return }
            if (uri.scheme != "http" && uri.scheme != "https") return
            val ctx: Context = widget.context
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = true
        }
    }

    /** The coloured bar down the side of a quoted paragraph. */
    private class BarSpan(private val color: Int, private val barPx: Int, private val gapPx: Int) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean): Int = barPx + gapPx

        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout
        ) {
            val oldStyle = p.style
            val oldColor = p.color
            p.style = Paint.Style.FILL
            p.color = color
            c.drawRect(x.toFloat(), top.toFloat(), (x + dir * barPx).toFloat(), bottom.toFloat(), p)
            p.style = oldStyle
            p.color = oldColor
        }
    }
}
