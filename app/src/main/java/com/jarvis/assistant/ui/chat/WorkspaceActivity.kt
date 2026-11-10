package com.jarvis.assistant.ui.chat

import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.tools.Workspace
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lets the user inspect, preview, export and remove files created in the private ChatBox workspace. */
class WorkspaceActivity : AppCompatActivity() {
    private val workspace by lazy { Workspace(File(filesDir, "chatbox-workspace")) }
    private var currentDir: File? = null
    private var exportTarget: File? = null
    private lateinit var content: LinearLayout
    private lateinit var pathLabel: TextView

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val source = exportTarget
        if (uri == null || source == null) return@registerForActivityResult
        if (!isInsideWorkspace(source) || !source.isFile || java.nio.file.Files.isSymbolicLink(source.toPath())) {
            Toast.makeText(this, "That workspace file is no longer available.", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Could not open the selected save location.")
                }
                Toast.makeText(this@WorkspaceActivity, "Exported ${source.name} to your selected location.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@WorkspaceActivity, e.message ?: "Export failed.", Toast.LENGTH_LONG).show()
            } finally {
                exportTarget = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        ThemeManager.applyDynamicBackground(scroll)

        root.addView(TextView(this).apply {
            text = "ChatBox Workspace"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Files created by JARVIS stay in this private workspace until you export or remove them."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(14))
        })
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nav.addView(Button(this).apply { text = "Up"; setOnClickListener { goUp() } })
        nav.addView(Button(this).apply { text = "Refresh"; setOnClickListener { render() } })
        root.addView(nav)
        pathLabel = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(pathLabel)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        currentDir = workspace.root
        render()
    }

    private fun goUp() {
        val current = currentDir ?: workspace.root
        if (current.canonicalFile == workspace.root) return
        currentDir = current.parentFile?.takeIf { isInsideWorkspace(it) } ?: workspace.root
        render()
    }

    private fun render() {
        val dir = currentDir?.takeIf { isInsideWorkspace(it) && it.isDirectory } ?: workspace.root.also { currentDir = it }
        pathLabel.text = "/${workspace.relative(dir).ifBlank { "" }}"
        content.removeAllViews()
        val children = dir.listFiles()?.filter { child ->
            runCatching { isInsideWorkspace(child) && !java.nio.file.Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)
        }?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        if (children.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "This folder is empty. Ask JARVIS to create a project or write files here."
                textSize = 14f
                setPadding(dp(8), dp(18), dp(8), dp(18))
            })
            return
        }
        children.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                isFocusable = true
                background = getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener {
                    if (file.isDirectory) {
                        currentDir = file
                        render()
                    } else showFile(file)
                }
            }
            row.addView(TextView(this).apply {
                text = (if (file.isDirectory) "📁  " else "📄  ") + file.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            })
            row.addView(TextView(this).apply {
                text = if (file.isDirectory) "Folder" else "${formatSize(file.length())} · ${workspace.relative(file)}"
                textSize = 11f
            })
            content.addView(row)
            content.addView(View(this).apply {
                setBackgroundColor(0x33808080)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
    }

    private fun showFile(file: File) {
        if (!isInsideWorkspace(file) || !file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return
        val canPreview = file.length() <= MAX_PREVIEW_BYTES && file.extension.lowercase() in TEXT_EXTENSIONS
        val preview = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            text = if (canPreview) runCatching { file.readText(Charsets.UTF_8) }.getOrElse { "Could not read this file as UTF-8: ${it.message}" }
            else "Preview is unavailable for this file type or size. You can export it to another app."
        }
        val scroll = ScrollView(this).apply { addView(preview) }
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton("Export") { _, _ ->
                exportTarget = file
                exportLauncher.launch(file.name)
            }
            .setNeutralButton(if (canPreview) "Edit" else "Delete", null)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (canPreview) editFile(file) else confirmDelete(file)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Edits text artifacts in place, creating a recoverable backup before an atomic replacement. */
    private fun editFile(file: File) {
        if (!isInsideWorkspace(file) || !file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath()) ||
            file.length() > MAX_PREVIEW_BYTES || file.extension.lowercase() !in TEXT_EXTENSIONS
        ) {
            Toast.makeText(this, "This file type or size cannot be edited here.", Toast.LENGTH_LONG).show()
            return
        }
        val original = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            Toast.makeText(this, "Could not read this file as UTF-8.", Toast.LENGTH_LONG).show()
            return
        }
        val editor = android.widget.EditText(this).apply {
            setText(original)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minLines = 12
            maxLines = 24
            setHorizontallyScrolling(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val scroll = ScrollView(this).apply { addView(editor) }
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle("Edit ${file.name}")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = editor.text?.toString().orEmpty()
                if (updated.toByteArray(Charsets.UTF_8).size > MAX_PREVIEW_BYTES) {
                    editor.error = "File exceeds the 1 MB in-app editor limit."
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            if (!isInsideWorkspace(file) || !file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) {
                                error("The workspace file changed or is no longer safe to edit.")
                            }
                            val backupDir = workspace.resolve(".jarvis/backups")
                            if (!backupDir.exists() && !backupDir.mkdirs()) error("Could not create a backup folder.")
                            val backup = File(backupDir, "${System.currentTimeMillis()}-${file.name}.bak")
                            file.copyTo(backup, overwrite = false)
                            val temp = File(file.parentFile, ".${file.name}.jarvis-tmp")
                            temp.writeText(updated, Charsets.UTF_8)
                            if (!temp.renameTo(file)) {
                                temp.delete()
                                error("Could not replace the original file. Your backup is at ${workspace.relative(backup)}.")
                            }
                            backup
                        }
                    }
                    result.onSuccess { backup ->
                        Toast.makeText(this@WorkspaceActivity, "Saved. Backup: ${workspace.relative(backup)}", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        render()
                    }.onFailure { error ->
                        Toast.makeText(this@WorkspaceActivity, error.message ?: "Save failed.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(file: File) {
        if (!isInsideWorkspace(file)) return
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle("Delete ${file.name}?")
            .setMessage("This permanently removes the selected workspace item. Export anything you need first.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        runCatching { isInsideWorkspace(file) && !java.nio.file.Files.isSymbolicLink(file.toPath()) && file.deleteRecursively() }.getOrDefault(false)
                    }
                    Toast.makeText(this@WorkspaceActivity, if (deleted) "Deleted." else "Could not delete this item.", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .show()
    }

    private fun isInsideWorkspace(file: File): Boolean {
        val root = workspace.root.canonicalFile
        val candidate = file.canonicalFile
        return candidate == root || candidate.path.startsWith(root.path + File.separator)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_PREVIEW_BYTES = 1_000_000L
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "kt", "java", "xml", "json", "html", "css", "js", "gradle", "kts",
            "py", "sh", "properties", "yml", "yaml", "csv", "sql", "log", "svg", "toml"
        )
    }
}
