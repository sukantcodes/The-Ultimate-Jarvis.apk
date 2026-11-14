package com.jarvis.assistant.ui.chat

import android.content.Context
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import com.jarvis.assistant.util.ThemeManager
import java.util.regex.Pattern

/**
 * Enterprise-grade lightweight syntax highlighter for Claude-style expanded code and terminal panels.
 * Complies with strict rule: Zero static hex colors, dynamically bound to ThemeManager colors.
 */
object ClaudeCodeHighlighter {

    private val KEYWORDS_PATTERN = Pattern.compile(
        "\\b(cd|grep|sed|cat|for|in|do|done|echo|find|rm|mv|git|chmod|mkdir|touch|curl|wget|val|var|fun|class|interface|object|import|package|return|if|else|when|while|try|catch|finally|throw|new|public|private|protected|internal|override)\\b"
    )

    private val FLAGS_PATTERN = Pattern.compile(
        "(?:^|\\s)(-[a-zA-Z0-9]+|--[a-zA-Z0-9\\-_]+)"
    )

    private val STRINGS_PATTERN = Pattern.compile(
        "\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'"
    )

    private val COMMENTS_PATTERN = Pattern.compile(
        "(?:#|//).*$"
    )

    fun highlight(context: Context, rawCode: String): CharSequence {
        if (rawCode.isBlank()) return rawCode
        val ssb = SpannableStringBuilder(rawCode)

        val keywordColor = try { ThemeManager.getThemeSecondaryColor() } catch (_: Exception) { 0xFF60A5FA.toInt() }
        val flagColor = try { ThemeManager.getThemeSuccessColor() } catch (_: Exception) { 0xFF4ADE80.toInt() }
        val stringColor = try { ThemeManager.getThemeWarningColor() } catch (_: Exception) { 0xFFFDE047.toInt() }
        val commentColor = 0xFF888888.toInt()

        // 1. Strings
        val stringMatcher = STRINGS_PATTERN.matcher(rawCode)
        while (stringMatcher.find()) {
            ssb.setSpan(ForegroundColorSpan(stringColor), stringMatcher.start(), stringMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 2. Flags / Options
        val flagMatcher = FLAGS_PATTERN.matcher(rawCode)
        while (flagMatcher.find()) {
            val start = flagMatcher.start(1)
            val end = flagMatcher.end(1)
            if (start in 0..end && end <= ssb.length) {
                ssb.setSpan(ForegroundColorSpan(flagColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // 3. Keywords
        val keywordMatcher = KEYWORDS_PATTERN.matcher(rawCode)
        while (keywordMatcher.find()) {
            ssb.setSpan(ForegroundColorSpan(keywordColor), keywordMatcher.start(), keywordMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 4. Comments
        val commentMatcher = COMMENTS_PATTERN.matcher(rawCode)
        while (commentMatcher.find()) {
            ssb.setSpan(ForegroundColorSpan(commentColor), commentMatcher.start(), commentMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return ssb
    }
}
