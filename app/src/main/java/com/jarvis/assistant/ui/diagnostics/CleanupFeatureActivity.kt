package com.jarvis.assistant.ui.diagnostics

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.File
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** Permission-respecting cleanup workflows. Shared storage is accessed only through user-picked SAF folders. */
class CleanupFeatureActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FEATURE = "cleanup_feature"
        private const val LARGE_FILE_BYTES = 50L * 1024L * 1024L
    }

    private data class Candidate(val name: String, val size: Long, val remove: () -> Boolean)
    private var scanMode = 3
    private lateinit var status: TextView
    private val feature by lazy { intent.getIntExtra(EXTRA_FEATURE, 0).coerceIn(0, 6) }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            scanFolder(uri, scanMode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val names = listOf("Junk Clean", "Boost", "Battery Saver", "Large Files Cleanup", "WhatsApp Clean", "Photos Clean", "App Uninstaller")
        val details = listOf(
            "Review and clear temporary files owned by JARVIS. Android does not let ordinary apps erase another app's private cache.",
            "Check available RAM and open Android app-management controls. Android controls other apps' process lifecycle.",
            "Open Android battery-saver settings. The system may require you to confirm the switch.",
            "Choose a folder. Files of 50 MB or more will be listed for review.",
            "Choose the WhatsApp media folder. Only the folder you select will be scanned.",
            "Choose a photo folder. Images will be listed for review.",
            "Choose a user-installed app. Android will show its own uninstall confirmation."
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "‹  Device Diagnostics"
            textSize = 18f
            setPadding(0, 0, 0, dp(20))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = names[feature]
            textSize = 25f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = details[feature]
            textSize = 14f
            setPadding(0, dp(10), 0, dp(20))
        })
        status = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(status)
        val button = MaterialButton(this).apply {
            isAllCaps = false
            text = when (feature) {
                0 -> "Scan JARVIS junk"
                1 -> "Check memory"
                2 -> "Open Battery Saver"
                3 -> "Choose folder to scan"
                4 -> "Choose WhatsApp media folder"
                5 -> "Choose photos folder"
                else -> "Choose app to uninstall"
            }
            setOnClickListener { runFeature() }
        }
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(18)
        })
        setContentView(root)
    }

    private fun runFeature() {
        when (feature) {
            0 -> cleanOwnCache()
            1 -> showMemory()
            2 -> openSystemPage(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            3 -> { scanMode = 3; folderPicker.launch(null) }
            4 -> { scanMode = 4; folderPicker.launch(null) }
            5 -> { scanMode = 5; folderPicker.launch(null) }
            6 -> chooseAppToUninstall()
        }
    }

    private fun cleanOwnCache() {
        val roots = listOfNotNull(cacheDir, externalCacheDir).distinct()
        val files = roots.flatMap { it.walkTopDown().filter(File::isFile).toList() }.distinct()
        val total = files.sumOf { it.length() }
        status.text = "Found ${files.size} JARVIS cache files • ${formatBytes(total)}."
        MaterialAlertDialogBuilder(this)
            .setTitle("Clean JARVIS cache?")
            .setMessage("Only JARVIS temporary cache will be removed. Conversations, settings and other apps' data are not touched.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clean") { _, _ ->
                roots.forEach { root -> root.listFiles()?.forEach { it.deleteRecursively() } }
                status.text = "JARVIS cache cleanup finished."
            }.show()
    }

    private fun showMemory() {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val report = "Available RAM: ${formatBytes(info.availMem)}\nLow-memory state: ${if (info.lowMemory) "Yes" else "No"}"
        status.text = report
        MaterialAlertDialogBuilder(this).setTitle("Memory diagnostics").setMessage(report)
            .setPositiveButton("Open app management") { _, _ ->
                openSystemPage(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            }.setNegativeButton("Close", null).show()
    }

    private fun openSystemPage(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            catch (_: Exception) { status.text = "The requested system page is unavailable." }
        }
    }

    private fun scanFolder(uri: Uri, mode: Int) {
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.isDirectory) {
            status.text = "Could not open the selected folder."
            return
        }
        val found = mutableListOf<Candidate>()
        fun walk(folder: DocumentFile) {
            folder.listFiles().forEach { item ->
                if (item.isDirectory) walk(item) else {
                    val mime = item.type.orEmpty().lowercase(Locale.ROOT)
                    val include = when (mode) {
                        3 -> item.length() >= LARGE_FILE_BYTES
                        4 -> true
                        5 -> mime.startsWith("image/")
                        else -> false
                    }
                    if (include) found += Candidate(item.name ?: "Unnamed file", item.length()) { item.delete() }
                }
            }
        }
        walk(root)
        val candidates = found.sortedByDescending { it.size }.take(250)
        val category = when (mode) { 3 -> "large files"; 4 -> "WhatsApp media files"; else -> "photos" }
        if (candidates.isEmpty()) {
            status.text = "No matching $category found in the selected folder."
            return
        }
        status.text = "Found ${candidates.size} $category. Select an item to review."
        val labels = candidates.map { "${it.name} • ${formatBytes(it.size)}" }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Review $category")
            .setItems(labels) { _, which -> confirmDelete(candidates[which]) }
            .setNegativeButton("Close", null).show()
    }

    private fun confirmDelete(file: Candidate) {
        MaterialAlertDialogBuilder(this).setTitle("Delete this file?")
            .setMessage("${file.name}\n${formatBytes(file.size)}\n\nThis cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val deleted = try { file.remove() } catch (_: Exception) { false }
                status.text = if (deleted) "Deleted: ${file.name}" else "Could not delete this file. The document provider denied the request."
            }.show()
    }

    private fun chooseAppToUninstall() {
        val apps = packageManager.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT) }
        if (apps.isEmpty()) { status.text = "No uninstallable user apps found."; return }
        val labels = apps.map { packageManager.getApplicationLabel(it).toString() }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Select an app").setItems(labels) { _, which ->
            val app = apps[which]
            MaterialAlertDialogBuilder(this).setTitle("Uninstall ${labels[which]}?")
                .setMessage("Android will show the final uninstall confirmation.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ ->
                    try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))) }
                    catch (_: Exception) { status.text = "Android could not open the uninstall screen." }
                }.show()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun formatBytes(value: Long) = android.text.format.Formatter.formatShortFileSize(this, value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
