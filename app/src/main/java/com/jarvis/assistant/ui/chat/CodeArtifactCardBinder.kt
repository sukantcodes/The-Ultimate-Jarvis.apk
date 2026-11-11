package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.LayoutCodeArtifactCardBinding
import java.util.Locale

object CodeArtifactCardBinder {

    fun bind(
        binding: LayoutCodeArtifactCardBinding,
        language: String,
        code: String,
        isWriting: Boolean
    ) {
        val context = binding.root.context
        val hasTarget = language.contains(':')
        val baseLang = if (hasTarget) language.substringBefore(':').trim() else language.trim()
        val targetName = if (hasTarget) language.substringAfter(':').trim() else ""
        val isTerminal = baseLang.equals("terminal", true) || baseLang.equals("bash", true) || baseLang.equals("sh", true)

        val displayTitle = when {
            targetName.isNotBlank() -> targetName
            baseLang.isNotBlank() -> baseLang.uppercase(Locale.ROOT)
            else -> "CODE"
        }
        binding.tvCardLanguage.text = displayTitle

        if (isTerminal) {
            binding.ivCardIcon.setImageResource(R.drawable.ic_menu)
            binding.ivCardIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.brand_neon_cyan)
            binding.tvTerminalTitle.text = if (targetName.isNotBlank()) "terminal: ~/workspace $ $targetName" else "terminal ~/workspace"
        } else {
            binding.ivCardIcon.setImageResource(R.drawable.ic_code_chip)
            binding.ivCardIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.brand_neon_cyan)
            binding.tvTerminalTitle.text = if (targetName.isNotBlank()) "file: $targetName" else "$displayTitle snippet"
        }

        if (isWriting) {
            binding.tvWritingStatus.visibility = View.VISIBLE
            val statusLabel = when {
                isTerminal -> "✦ Running terminal…"
                targetName.isNotBlank() -> "✦ Creating $targetName…"
                else -> "✦ Writing code…"
            }
            binding.tvWritingStatus.text = statusLabel
            binding.tvWritingStatus.setTextColor(Color.parseColor("#64B5F6"))
            binding.tvWritingStatus.background = ContextCompat.getDrawable(context, R.drawable.bg_code_writing_badge)
            val pulse = AlphaAnimation(0.35f, 1.0f).apply {
                duration = 650
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            binding.tvWritingStatus.startAnimation(pulse)
            binding.tvCardBottomHint.text = "✦ Actively executing · Tap tag to collapse/expand mini window"
        } else {
            binding.tvWritingStatus.clearAnimation()
            binding.tvWritingStatus.visibility = View.VISIBLE
            val statusDone = when {
                isTerminal -> "✓ Finished"
                targetName.isNotBlank() -> "✓ Created $targetName"
                else -> "✓ Done"
            }
            binding.tvWritingStatus.text = statusDone
            binding.tvWritingStatus.setTextColor(Color.parseColor("#00E676"))
            binding.tvWritingStatus.background = ContextCompat.getDrawable(context, R.drawable.bg_chat_status)
            val lineCount = code.lines().size
            binding.tvCardBottomHint.text = "Click to toggle mini window · Tap Open ↗ for full viewer ($lineCount lines)"
        }

        val tagPrefix = if (isTerminal) "Terminal" else "Code"
        fun updateToggleText() {
            val isVisible = binding.layoutMiniTerminal.visibility == View.VISIBLE
            binding.tvToggleTagText.text = if (isVisible) "▲ Hide $tagPrefix" else "▼ $tagPrefix Window"
        }
        updateToggleText()

        binding.btnToggleMiniTerminal.setOnClickListener {
            val willShow = binding.layoutMiniTerminal.visibility != View.VISIBLE
            binding.layoutMiniTerminal.visibility = if (willShow) View.VISIBLE else View.GONE
            updateToggleText()
        }

        val lines = code.split('\n')
        val previewLines = lines.take(12)
        val lineNums = StringBuilder()
        for (i in 1..previewLines.size) {
            lineNums.append(i).append('\n')
        }
        binding.tvPreviewLineNumbers.text = lineNums.toString().trimEnd()
        binding.tvPreviewCode.text = previewLines.joinToString("\n")

        val openViewer = {
            CodeViewerBottomSheet.show(context, baseLang, code, isWriting)
        }

        binding.btnCardExpand.setOnClickListener { openViewer() }

        binding.btnCardCopy.setOnClickListener {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("$displayTitle code", code))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't copy code", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
