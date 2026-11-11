package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
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
        val langDisplay = language.ifBlank { "Code" }.uppercase(Locale.ROOT)
        binding.tvCardLanguage.text = langDisplay

        if (isWriting) {
            binding.tvWritingStatus.visibility = View.VISIBLE
            val pulse = AlphaAnimation(0.35f, 1.0f).apply {
                duration = 650
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            binding.tvWritingStatus.startAnimation(pulse)
            binding.tvCardBottomHint.text = "Click to inspect code being written ↗"
        } else {
            binding.tvWritingStatus.clearAnimation()
            binding.tvWritingStatus.visibility = View.GONE
            val lineCount = code.count { it == '\n' } + 1
            binding.tvCardBottomHint.text = "Click to view full code in editor ($lineCount lines) ↗"
        }

        val lines = code.split('\n')
        val previewLines = lines.take(6)
        val lineNums = StringBuilder()
        for (i in 1..previewLines.size) {
            lineNums.append(i).append('\n')
        }
        binding.tvPreviewLineNumbers.text = lineNums.toString().trimEnd()
        binding.tvPreviewCode.text = previewLines.joinToString("\n")

        val openViewer = {
            CodeViewerBottomSheet.show(context, language, code, isWriting)
        }

        binding.root.setOnClickListener { openViewer() }
        binding.btnCardExpand.setOnClickListener { openViewer() }

        binding.btnCardCopy.setOnClickListener {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("$language code", code))
                Toast.makeText(context, "Copied code to clipboard", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't copy code", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
