package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.StrikethroughSpan
import android.text.util.Linkify
import android.widget.Toast
import java.util.regex.Pattern

/** Dependency-free Markdown renderer for readable assistant messages and copyable code blocks. */
object MarkdownMessageRenderer {
    private const val COPY_LABEL = "   Copy code   "

    fun render(context: Context, source: String): CharSequence {
        if (source.isBlank()) return source
        val out = SpannableStringBuilder()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var inCode = false
        var language = ""
        val code = mutableListOf<String>()

        fun newlineIfNeeded() {
            if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                if (!inCode) {
                    inCode = true
                    language = trimmed.removePrefix("```").trim()
                    code.clear()
                } else {
                    appendCodeBlock(context, out, code.joinToString("\n"), language)
                    inCode = false
                    language = ""
                }
                continue
            }
            if (inCode) {
                code += line
                continue
            }
            when {
                trimmed.isBlank() -> {
                    if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append("\n\n")
                }
                trimmed.startsWith("### ") -> appendHeading(out, trimmed.substring(4), 1.08f)
                trimmed.startsWith("## ") -> appendHeading(out, trimmed.substring(3), 1.16f)
                trimmed.startsWith("# ") -> appendHeading(out, trimmed.substring(2), 1.28f)
                trimmed.startsWith("> ") -> appendQuote(out, trimmed.substring(2))
                trimmed.matches(Regex("^[-*+]\\s+.*")) -> appendBullet(out, trimmed.drop(2))
                trimmed.matches(Regex("^\\d+[.)]\\s+.*")) -> appendNumbered(out, trimmed)
                trimmed == "---" || trimmed == "***" -> appendHeading(out, "────────────────────────", 0.85f)
                else -> {
                    newlineIfNeeded()
                    out.append(line).append('\n')
                }
            }
        }
        if (inCode) appendCodeBlock(context, out, code.joinToString("\n"), language)
        applyInlineFormatting(out)
        Linkify.addLinks(out, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
        return out
    }

    private fun appendHeading(out: SpannableStringBuilder, value: String, size: Float) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        val start = out.length
        out.append(value)
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(size), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append('\n')
    }

    private fun appendBullet(out: SpannableStringBuilder, value: String) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        val start = out.length
        out.append(value)
        out.setSpan(BulletSpan(14), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(LeadingMarginSpan.Standard(18, 18), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append('\n')
    }

    private fun appendNumbered(out: SpannableStringBuilder, value: String) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        val match = Regex("^(\\d+[.)])\\s+(.*)$").find(value) ?: return
        val start = out.length
        out.append(match.groupValues[1]).append("  ").append(match.groupValues[2])
        out.setSpan(StyleSpan(Typeface.BOLD), start, start + match.groupValues[1].length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(LeadingMarginSpan.Standard(24, 24), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append('\n')
    }

    private fun appendQuote(out: SpannableStringBuilder, value: String) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        val start = out.length
        out.append(value)
        out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(0xFF9AA4B2.toInt()), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(LeadingMarginSpan.Standard(18, 18), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append('\n')
    }

    private fun appendCodeBlock(context: Context, out: SpannableStringBuilder, value: String, language: String) {
        if (out.isNotEmpty() && out.last() != '\n') out.append("\n\n")
        val start = out.length
        out.append("  ").append(language.ifBlank { "CODE" }.uppercase().take(24)).append('\n')
        val codeStart = out.length
        out.append(value.ifBlank { " " })
        val codeEnd = out.length
        out.setSpan(TypefaceSpan("monospace"), codeStart, codeEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(0x1F8B98A8), start, codeEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(0.92f), codeStart, codeEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append('\n')
        val actionStart = out.length
        out.append(COPY_LABEL)
        val actionEnd = out.length
        out.setSpan(ForegroundColorSpan(0xFF65A9FF.toInt()), actionStart, actionEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(StyleSpan(Typeface.BOLD), actionStart, actionEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(object : ClickableSpan() {
            override fun onClick(widget: android.view.View) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Code", value))
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            }
            override fun updateDrawState(ds: TextPaint) {
                ds.isUnderlineText = false
                ds.color = 0xFF65A9FF.toInt()
            }
        }, actionStart, actionEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append("\n")
    }

    private fun applyInlineFormatting(text: SpannableStringBuilder) {
        val rules = listOf(
            Pattern.compile("\\*\\*(.+?)\\*\\*") to 2,
            Pattern.compile("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)") to 1,
            Pattern.compile("~~(.+?)~~") to 1,
            Pattern.compile("`([^`\\n]+)`") to 1
        )
        for ((pattern, markerSize) in rules) {
            val matcher = pattern.matcher(text)
            val matches = mutableListOf<Pair<Int, Int>>()
            while (matcher.find()) matches += matcher.start() to matcher.end()
            for ((start, end) in matches.asReversed()) {
                val contentStart = start + markerSize
                val contentEnd = end - markerSize
                if (contentEnd <= contentStart) continue
                val span: Any = when {
                    pattern.pattern().startsWith("~~") -> StrikethroughSpan()
                    pattern.pattern().startsWith("`") -> TypefaceSpan("monospace")
                    markerSize == 2 -> StyleSpan(Typeface.BOLD)
                    else -> StyleSpan(Typeface.ITALIC)
                }
                text.setSpan(span, contentStart, contentEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (span is TypefaceSpan) text.setSpan(BackgroundColorSpan(0x1F8B98A8), contentStart, contentEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.delete(contentEnd, end)
                text.delete(start, contentStart)
            }
        }
    }
}
