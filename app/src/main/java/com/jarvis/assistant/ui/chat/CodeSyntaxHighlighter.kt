package com.jarvis.assistant.ui.chat

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

object CodeSyntaxHighlighter {

    enum class Theme(
        val displayName: String,
        val bgColor: Int,
        val defaultTextColor: Int,
        val keywordColor: Int,
        val functionColor: Int,
        val stringColor: Int,
        val numberColor: Int,
        val commentColor: Int,
        val typeColor: Int
    ) {
        CYBER_DARK(
            displayName = "Cyber Dark+",
            bgColor = Color.parseColor("#0A0C14"),
            defaultTextColor = Color.parseColor("#E0E6ED"),
            keywordColor = Color.parseColor("#00E5FF"),
            functionColor = Color.parseColor("#FFD700"),
            stringColor = Color.parseColor("#00E676"),
            numberColor = Color.parseColor("#FF9100"),
            commentColor = Color.parseColor("#78909C"),
            typeColor = Color.parseColor("#B388FF")
        ),
        DRACULA(
            displayName = "Dracula",
            bgColor = Color.parseColor("#282A36"),
            defaultTextColor = Color.parseColor("#F8F8F2"),
            keywordColor = Color.parseColor("#FF79C6"),
            functionColor = Color.parseColor("#50FA7B"),
            stringColor = Color.parseColor("#F1FA8C"),
            numberColor = Color.parseColor("#BD93F9"),
            commentColor = Color.parseColor("#6272A4"),
            typeColor = Color.parseColor("#8BE9FD")
        ),
        MONOKAI(
            displayName = "Monokai Neon",
            bgColor = Color.parseColor("#272822"),
            defaultTextColor = Color.parseColor("#F8F8F2"),
            keywordColor = Color.parseColor("#F92672"),
            functionColor = Color.parseColor("#A6E22E"),
            stringColor = Color.parseColor("#E6DB74"),
            numberColor = Color.parseColor("#AE81FF"),
            commentColor = Color.parseColor("#75715E"),
            typeColor = Color.parseColor("#66D9EF")
        )
    }

    // Common syntax patterns
    private val PATTERN_STRING = Pattern.compile("(\"(?:\\\\\"|[^\"])*\"|'(?:\\\\'|[^'])*'|`[^`]*`)")
    private val PATTERN_NUMBER = Pattern.compile("\\b(\\d+\\.\\d+|0x[0-9a-fA-F]+|\\d+L?|\\d+f?)\\b")

    // Keywords
    private val PATTERN_KOTLIN_KEYWORDS = Pattern.compile(
        "\\b(package|import|fun|val|var|class|interface|object|enum|sealed|data|override|private|public|protected|internal|return|if|else|when|for|while|do|try|catch|finally|throw|this|super|is|in|as|null|true|false)\\b"
    )
    private val PATTERN_PYTHON_KEYWORDS = Pattern.compile(
        "\\b(def|class|return|if|elif|else|for|while|try|except|finally|import|from|as|with|yield|lambda|in|not|and|or|is|None|True|False|async|await|pass|break|continue)\\b"
    )
    private val PATTERN_JS_KEYWORDS = Pattern.compile(
        "\\b(function|const|let|var|return|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|class|extends|import|export|default|from|new|this|super|async|await|typeof|instanceof|null|undefined|true|false)\\b"
    )
    private val PATTERN_SHELL_KEYWORDS = Pattern.compile(
        "\\b(echo|cd|ls|mkdir|rm|cp|mv|cat|grep|find|sed|awk|chmod|chown|sudo|curl|wget|git|npm|yarn|npx|node|python|python3|pip|docker|exit|source|export|alias)\\b"
    )

    // Comments
    private val PATTERN_SLASH_COMMENT = Pattern.compile("(//.*|/\\*[\\s\\S]*?\\*/)")
    private val PATTERN_HASH_COMMENT = Pattern.compile("(#.*)")
    private val PATTERN_XML_COMMENT = Pattern.compile("(<!--[\\s\\S]*?-->)")

    // Types & Functions
    private val PATTERN_TYPE = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*)\\b")
    private val PATTERN_FUNCTION = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)(?=\\s*\\()")

    // HTML / XML
    private val PATTERN_XML_TAG = Pattern.compile("(<[a-zA-Z0-9_:-]+|</[a-zA-Z0-9_:-]+|/?>)")
    private val PATTERN_XML_ATTR = Pattern.compile("\\b([a-zA-Z0-9_:-]+)(?=\\s*=)")

    /**
     * Highlights code into a Spannable for rich text rendering.
     */
    fun highlight(code: String, language: String, theme: Theme = Theme.CYBER_DARK): CharSequence {
        if (code.isBlank()) return code

        val lang = language.lowercase().substringBefore(':').trim()
        val ssb = SpannableStringBuilder(code)

        // Apply default text color
        ssb.setSpan(
            ForegroundColorSpan(theme.defaultTextColor),
            0, ssb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        when (lang) {
            "html", "xml", "svg" -> highlightHtml(ssb, theme)
            "python", "py" -> highlightPython(ssb, theme)
            "js", "javascript", "ts", "typescript", "jsx", "tsx" -> highlightJs(ssb, theme)
            "bash", "sh", "terminal", "zsh", "shell" -> highlightShell(ssb, theme)
            "json" -> highlightJson(ssb, theme)
            else -> highlightKotlin(ssb, theme) // Default to Kotlin/Java/General
        }

        return ssb
    }

    private fun highlightKotlin(ssb: SpannableStringBuilder, theme: Theme) {
        applyRegex(ssb, PATTERN_TYPE, theme.typeColor)
        applyRegex(ssb, PATTERN_FUNCTION, theme.functionColor)
        applyRegex(ssb, PATTERN_KOTLIN_KEYWORDS, theme.keywordColor)
        applyRegex(ssb, PATTERN_NUMBER, theme.numberColor)
        applyRegex(ssb, PATTERN_STRING, theme.stringColor)
        applyRegex(ssb, PATTERN_SLASH_COMMENT, theme.commentColor)
    }

    private fun highlightPython(ssb: SpannableStringBuilder, theme: Theme) {
        applyRegex(ssb, PATTERN_TYPE, theme.typeColor)
        applyRegex(ssb, PATTERN_FUNCTION, theme.functionColor)
        applyRegex(ssb, PATTERN_PYTHON_KEYWORDS, theme.keywordColor)
        applyRegex(ssb, PATTERN_NUMBER, theme.numberColor)
        applyRegex(ssb, PATTERN_STRING, theme.stringColor)
        applyRegex(ssb, PATTERN_HASH_COMMENT, theme.commentColor)
    }

    private fun highlightJs(ssb: SpannableStringBuilder, theme: Theme) {
        applyRegex(ssb, PATTERN_TYPE, theme.typeColor)
        applyRegex(ssb, PATTERN_FUNCTION, theme.functionColor)
        applyRegex(ssb, PATTERN_JS_KEYWORDS, theme.keywordColor)
        applyRegex(ssb, PATTERN_NUMBER, theme.numberColor)
        applyRegex(ssb, PATTERN_STRING, theme.stringColor)
        applyRegex(ssb, PATTERN_SLASH_COMMENT, theme.commentColor)
    }

    private fun highlightShell(ssb: SpannableStringBuilder, theme: Theme) {
        applyRegex(ssb, PATTERN_SHELL_KEYWORDS, theme.keywordColor)
        applyRegex(ssb, PATTERN_NUMBER, theme.numberColor)
        applyRegex(ssb, PATTERN_STRING, theme.stringColor)
        applyRegex(ssb, PATTERN_HASH_COMMENT, theme.commentColor)
    }

    private fun highlightHtml(ssb: SpannableStringBuilder, theme: Theme) {
        applyRegex(ssb, PATTERN_XML_TAG, theme.keywordColor)
        applyRegex(ssb, PATTERN_XML_ATTR, theme.typeColor)
        applyRegex(ssb, PATTERN_STRING, theme.stringColor)
        applyRegex(ssb, PATTERN_XML_COMMENT, theme.commentColor)
    }

    private fun highlightJson(ssb: SpannableStringBuilder, theme: Theme) {
        applyRegex(ssb, PATTERN_NUMBER, theme.numberColor)
        applyRegex(ssb, PATTERN_STRING, theme.stringColor)
    }

    private fun applyRegex(ssb: SpannableStringBuilder, pattern: Pattern, color: Int) {
        val matcher = pattern.matcher(ssb)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            ssb.setSpan(
                ForegroundColorSpan(color),
                start, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
