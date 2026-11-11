package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
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
        binding.tvCodeContent.text = code

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

        dialog.show()
    }
}
