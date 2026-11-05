package com.jarvis.assistant.ui.diagnostics

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Phone-wide shared-storage maintenance. This deliberately does not claim access to another
 * application's private sandbox, cache, or process. Destructive operations always require review.
 */
class CleanupFeatureActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FEATURE = "cleanup_feature"
        private const val LARGE_FILE_BYTES = 50L * 1024L * 1024L
        private const val MAX_RESULTS = 300
        private const val MAX_DEPTH = 18
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
        private val JUNK_DIRS = setOf("cache", ".thumbnails", "thumbnails", "temp", "tmp")
    }

    private data class Candidate(
        val name: String,
        val path: String,
        val size: Long,
        val remove: () -> Boolean
    )

    private var scanMode = 0
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var animationView: LottieAnimationView
    private val feature by lazy { intent.getIntExtra(EXTRA_FEATURE, 0).coerceIn(0, 6) }

    private val storageSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasBroadStorageAccess()) scanSharedStorage()
        else status.text = "Full shared-storage access was not enabled. Choose a folder to scan instead."
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            scanSelectedFolder(uri, scanMode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val names = listOf("Junk Clean", "Boost", "Battery Saver", "Large Files Cleanup", "WhatsApp Clean", "Photos Clean", "App Uninstaller")
        val details = listOf(
            "Scan shared phone storage for temporary files and thumbnails across accessible folders. App-private caches remain protected by Android.",
            "Check device-wide memory pressure and open Android app controls. Android decides which background processes may be stopped.",
            "Open the phone's system Battery Saver controls.",
            "Find files of 50 MB or more across accessible shared phone storage.",
            "Find WhatsApp and WhatsApp Business media in shared phone storage.",
            "Find image files across accessible shared phone storage.",
            "Review installed user apps and use Android's uninstall confirmation."
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
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        animationView = LottieAnimationView(this).apply {
            visibility = View.GONE
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        root.addView(animationView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(190)
        ))
        status = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(status)
        val button = MaterialButton(this).apply {
            isAllCaps = false
            text = when (feature) {
                0 -> "Scan phone for junk"
                1 -> "Check phone memory"
                2 -> "Open Battery Saver"
                3 -> "Find large files"
                4 -> "Scan WhatsApp media"
                5 -> "Scan phone photos"
                else -> "Choose app to uninstall"
            }
            setOnClickListener { runFeature() }
        }
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(18)
        })
        if (feature in 0..5) {
            root.addView(TextView(this).apply {
                text = "Phone-wide scans cover shared storage only. Android protects other apps' private data and caches."
                textSize = 12f
                setPadding(0, dp(14), 0, 0)
            })
        }
        setContentView(root)
    }

    private fun runFeature() {
        when (feature) {
            0, 3, 4, 5 -> {
                scanMode = feature
                ensureStorageAccess()
            }
            1 -> showMemory()
            2 -> {
                playSnaptubeAnimation("animation_battery_loading", loop = true)
                openSystemPage(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            }
            6 -> chooseAppToUninstall()
        }
    }

    private fun hasBroadStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun ensureStorageAccess() {
        if (hasBroadStorageAccess()) {
            scanSharedStorage()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Allow phone-wide file scanning")
            .setMessage(
                "Android requires special 'All files access' for a scan across shared phone storage. " +
                    "JARVIS will only list matching files and will ask before deleting them. " +
                    "This does not unlock other apps' private caches or data."
            )
            .setNegativeButton("Choose one folder") { _, _ -> folderPicker.launch(null) }
            .setPositiveButton("Open access settings") { _, _ ->
                try {
                    storageSettings.launch(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + packageName))
                    )
                } catch (_: Exception) {
                    status.text = "Open Android Settings > Apps > Special app access > All files access > JARVIS."
                }
            }.show()
    }

    private fun showMemory() {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val report = "Available RAM: " + formatBytes(info.availMem) +
            "\nTotal RAM: " + formatBytes(info.totalMem) +
            "\nLow-memory state: " + if (info.lowMemory) "Yes" else "No"
        status.text = report
        playSnaptubeAnimation("animation_boost_loading", loop = false)
        MaterialAlertDialogBuilder(this)
            .setTitle("Phone memory diagnostics")
            .setMessage(
                report + "\n\nAndroid manages background apps automatically. JARVIS cannot safely force-stop every app or clear other apps' private memory."
            )
            .setPositiveButton("Open app management") { _, _ ->
                openSystemPage(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
            }
            .setNegativeButton("Close", null).show()
    }

    private fun scanSharedStorage() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            status.text = "Storage access is not enabled."
            return
        }
        showScanning()
        thread(name = "jarvis-phone-storage-scan") {
            val root = Environment.getExternalStorageDirectory()
            val results = scanFileTree(root, scanMode)
            runOnUiThread {
                hideScanning()
                showCandidates(results, scanMode)
            }
        }
    }

    private fun scanFileTree(root: File, mode: Int): List<Candidate> {
        val results = mutableListOf<Candidate>()
        val junkFound = mutableSetOf<String>()
        fun visit(dir: File, depth: Int, inJunkFolder: Boolean) {
            if (depth > MAX_DEPTH || results.size >= MAX_RESULTS) return
            val children = try { dir.listFiles() } catch (_: SecurityException) { null } ?: return
            for (file in children) {
                if (results.size >= MAX_RESULTS) break
                val lowerPath = file.absolutePath.lowercase(Locale.ROOT)
                if (file.isDirectory) {
                    val name = file.name.lowercase(Locale.ROOT)
                    if (lowerPath.contains("/android/data/") || lowerPath.endsWith("/android/data") ||
                        lowerPath.contains("/android/obb/") || lowerPath.endsWith("/android/obb")) continue
                    val junkFolder = inJunkFolder || name in JUNK_DIRS
                    if (mode == 0 && junkFolder) junkFound.add(file.absolutePath)
                    visit(file, depth + 1, junkFolder)
                } else if (file.isFile && file.length() > 0L) {
                    val include = when (mode) {
                        0 -> inJunkFolder
                        3 -> file.length() >= LARGE_FILE_BYTES
                        4 -> lowerPath.contains("/whatsapp/") ||
                            lowerPath.contains("/com.whatsapp/") ||
                            lowerPath.contains("/com.whatsapp.w4b/")
                        5 -> file.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS
                        else -> false
                    }
                    if (include) {
                        val candidate = Candidate(
                            file.name, file.absolutePath, file.length()
                        ) { file.exists() && file.isFile && file.delete() }
                        results.add(candidate)
                    }
                }
            }
        }
        visit(root, 0, false)
        return results.sortedByDescending { it.size }.take(MAX_RESULTS)
    }

    private fun scanSelectedFolder(uri: Uri, mode: Int) {
        showScanning()
        thread(name = "jarvis-selected-folder-scan") {
            val found = mutableListOf<Candidate>()
            val root = DocumentFile.fromTreeUri(this, uri)
            fun walk(folder: DocumentFile, depth: Int) {
                if (depth > MAX_DEPTH || found.size >= MAX_RESULTS) return
                val children = try { folder.listFiles() } catch (_: Exception) { emptyArray() }
                for (item in children) {
                    if (found.size >= MAX_RESULTS) break
                    if (item.isDirectory) walk(item, depth + 1)
                    else {
                        val name = item.name.orEmpty()
                        val lower = name.lowercase(Locale.ROOT)
                        val include = when (mode) {
                            0 -> lower.endsWith(".tmp") || lower.endsWith(".temp") ||
                                lower.contains("thumbnail") || lower.contains("cache")
                            3 -> item.length() >= LARGE_FILE_BYTES
                            4 -> true
                            5 -> lower.substringAfterLast('.', "") in IMAGE_EXTENSIONS
                            else -> false
                        }
                        if (include) found.add(Candidate(name, item.uri.toString(), item.length()) { item.delete() })
                    }
                }
            }
            if (root != null && root.isDirectory) walk(root, 0)
            runOnUiThread {
                hideScanning()
                showCandidates(found.sortedByDescending { it.size }.take(MAX_RESULTS), mode)
            }
        }
    }

    private fun showCandidates(items: List<Candidate>, mode: Int) {
        val category = when (mode) {
            0 -> "potential junk files"
            3 -> "large files (50 MB or more)"
            4 -> "WhatsApp media files"
            else -> "photos"
        }
        if (items.isEmpty()) {
            status.text = "No " + category + " found in accessible shared storage."
            val emptyAnimation = if (mode == 4) "animation_scan_whats_app_empty" else "animation_clean_noting"
            playSnaptubeAnimation(emptyAnimation, loop = false)
            return
        }
        playSnaptubeAnimation("animation_all_finish", loop = false)
        val total = items.sumOf { it.size }
        status.text = "Found " + items.size + " " + category + " • " + formatBytes(total) +
            ". Select only the files you want removed."
        val labels = items.map { it.name + " • " + formatBytes(it.size) }.toTypedArray()
        val checked = BooleanArray(items.size)
        MaterialAlertDialogBuilder(this)
            .setTitle("Review " + category)
            .setMultiChoiceItems(labels, checked) { _, which, selected -> checked[which] = selected }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete selected") { _, _ ->
                val selectedItems = items.filterIndexed { index, _ -> checked[index] }
                if (selectedItems.isEmpty()) {
                    status.text = "Nothing selected. No files were deleted."
                } else confirmDelete(selectedItems)
            }.show()
    }

    private fun confirmDelete(items: List<Candidate>) {
        val bytes = items.sumOf { it.size }
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete " + items.size + " selected files?")
            .setMessage(
                "Selected size: " + formatBytes(bytes) +
                    "\n\nOnly the selected shared-storage files will be removed. This cannot be undone."
            )
            .setNegativeButton("Keep files", null)
            .setPositiveButton("Delete") { _, _ ->
                var deleted = 0
                var failed = 0
                items.forEach {
                    val ok = try { it.remove() } catch (_: Exception) { false }
                    if (ok) deleted++ else failed++
                }
                status.text = "Cleanup complete: " + deleted + " deleted, " + failed + " could not be deleted."
            }.show()
    }

    private fun chooseAppToUninstall() {
        val apps = packageManager.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT) }
        if (apps.isEmpty()) { status.text = "No uninstallable user apps found."; return }
        val labels = apps.map { packageManager.getApplicationLabel(it).toString() }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle("Select an installed app").setItems(labels) { _, which ->
            val app = apps[which]
            MaterialAlertDialogBuilder(this).setTitle("Uninstall " + labels[which] + "?")
                .setMessage("Android will show its own final uninstall confirmation.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ ->
                    try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.packageName))) }
                    catch (_: Exception) { status.text = "Android could not open the uninstall screen." }
                }.show()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showScanning() {
        status.text = "Scanning shared phone storage…"
        status.clearAnimation()
        val animation = when (scanMode) {
            0 -> "clean_app_junk"
            3 -> "animation_large_file_scan"
            4 -> "animation_scan_whats_app_loading"
            5 -> "animation_clean_noting"
            else -> "animation_boost_loading"
        }
        if (!playSnaptubeAnimation(animation, loop = true)) {
            progress.visibility = View.VISIBLE
            val pulse = AlphaAnimation(0.45f, 1f).apply {
                duration = 650
                repeatMode = AlphaAnimation.REVERSE
                repeatCount = AlphaAnimation.INFINITE
            }
            progress.startAnimation(pulse)
        }
    }

    private fun playSnaptubeAnimation(name: String, loop: Boolean): Boolean {
        val folder = "lottie/snaptube/$name/"
        val animationFile = folder + "animation.json"
        val available = try {
            assets.open(animationFile).close()
            true
        } catch (_: Exception) {
            false
        }
        if (!available) {
            animationView.visibility = View.GONE
            return false
        }
        progress.clearAnimation()
        progress.visibility = View.GONE
        animationView.cancelAnimation()
        animationView.setImageAssetsFolder(folder)
        animationView.setAnimation(animationFile)
        animationView.repeatCount = if (loop) LottieDrawable.INFINITE else 0
        animationView.visibility = View.VISIBLE
        animationView.playAnimation()
        return true
    }

    private fun hideScanning() {
        status.clearAnimation()
        progress.clearAnimation()
        progress.visibility = View.GONE
        animationView.cancelAnimation()
        animationView.visibility = View.GONE
    }

    private fun openSystemPage(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            catch (_: Exception) { status.text = "The requested system page is unavailable." }
        }
    }

    private fun formatBytes(value: Long) = android.text.format.Formatter.formatShortFileSize(this, value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
