package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.SheetCodeViewerBinding
import java.util.Locale

object CodeViewerBottomSheet {

    fun show(context: Context, language: String, code: String, isWriting: Boolean = false) {
        val dialog = BottomSheetDialog(context)
        val binding = SheetCodeViewerBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        val langDisplay = language.ifBlank { "Code" }.uppercase(Locale.ROOT)
        binding.tvCodeTitle.text = if (isWriting) "$langDisplay (Writing…)" else "$langDisplay Snippet"

        val lines = code.split('\n')
        binding.tvCodeSubtitle.text = "${lines.size} lines · ${code.length} characters"

        val lineNums = StringBuilder()
        for (i in 1..lines.size) {
            lineNums.append(i).append('\n')
        }
        binding.tvLineNumbers.text = lineNums.toString().trimEnd()

        var currentTheme = CodeSyntaxHighlighter.Theme.CYBER_DARK

        fun renderTheme(theme: CodeSyntaxHighlighter.Theme) {
            currentTheme = theme
            binding.scrollCodeContainer.setBackgroundColor(theme.bgColor)
            val highlighted = CodeSyntaxHighlighter.highlight(code, language, theme)
            binding.tvCodeContent.text = highlighted

            val activeBg = ContextCompat.getDrawable(context, R.drawable.bg_auto_approve_badge_active)
            val inactiveBg = ContextCompat.getDrawable(context, R.drawable.bg_auto_approve_badge_inactive)

            fun updateChip(chip: TextView, active: Boolean) {
                chip.background = if (active) activeBg else inactiveBg
                chip.setTextColor(if (active) com.jarvis.assistant.util.ThemeManager.getThemeAccentColor() else com.jarvis.assistant.util.ThemeManager.getThemeTextSecondaryColor())
                chip.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }

            updateChip(binding.chipThemeCyber, theme == CodeSyntaxHighlighter.Theme.CYBER_DARK)
            updateChip(binding.chipThemeDracula, theme == CodeSyntaxHighlighter.Theme.DRACULA)
            updateChip(binding.chipThemeMonokai, theme == CodeSyntaxHighlighter.Theme.MONOKAI)
        }

        renderTheme(CodeSyntaxHighlighter.Theme.CYBER_DARK)

        binding.chipThemeCyber.setOnClickListener { renderTheme(CodeSyntaxHighlighter.Theme.CYBER_DARK) }
        binding.chipThemeDracula.setOnClickListener { renderTheme(CodeSyntaxHighlighter.Theme.DRACULA) }
        binding.chipThemeMonokai.setOnClickListener { renderTheme(CodeSyntaxHighlighter.Theme.MONOKAI) }

        binding.btnCodeCopy.setOnClickListener {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("$language code", code))
                Toast.makeText(context, "Copied code to clipboard", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't copy code", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCodeClose.setOnClickListener { dialog.dismiss() }

        // Open fully expanded so the code is readable straight away instead of in a half-height peek.
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
                behavior.skipCollapsed = true
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }

        dialog.show()
    }
}
