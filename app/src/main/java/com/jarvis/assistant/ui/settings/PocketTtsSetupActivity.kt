package com.jarvis.assistant.ui.settings

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * One-stop launcher for the optional Pocket TTS Android engine.
 *
 * Android deliberately requires user confirmation to install another APK and to change the
 * system-wide preferred TTS engine. The voice pack is downloaded by JARVIS, but the current
 * upstream engine has no public import/deep-link API, so its own Import action is still required.
 */
class PocketTtsSetupActivity : AppCompatActivity() {
    private val enginePackage = "org.pockettts.android.engine"
    private val engineApkUrl =
        "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/download/v0.5.4/PocketTTS-Android-Engine-0.5.4.apk"
    private val englishPackUrl =
        "https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/download/v0.5.4/PocketTTS-english-FP32.zip"
    private val prefs by lazy { getSharedPreferences("pocket_tts_setup", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var installButton: Button
    private lateinit var packButton: Button
    private lateinit var openEngineButton: Button
    private lateinit var chooseEngineButton: Button
    private var receiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == prefs.getLong(KEY_APK_DOWNLOAD, -2L)) {
                status.text = "Engine APK downloaded. Tap Install engine and approve Android's installer prompt."
                installButton.visibility = View.VISIBLE
                Toast.makeText(this@PocketTtsSetupActivity, "Pocket TTS engine download complete", Toast.LENGTH_LONG).show()
            } else if (id == prefs.getLong(KEY_PACK_DOWNLOAD, -2L)) {
                status.text = "English voice pack downloaded to Downloads. Open Pocket TTS and use its Import action for this ZIP."
                openEngineButton.visibility = View.VISIBLE
                Toast.makeText(this@PocketTtsSetupActivity, "Voice pack downloaded", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Pocket TTS Setup"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(24))
            setBackgroundColor(0xFF080D16.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val heading = TextView(this).apply {
            text = "JARVIS • OFFLINE VOICE"
            textSize = 22f
            setTextColor(0xFF75D8FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(14))
        }
        val description = TextView(this).apply {
            text = "Set up Pocket TTS from here. The engine APK is about 12 MB; the English voice pack is about 208 MB and needs free storage and Wi-Fi. Downloads are optional."
            textSize = 15f
            setTextColor(0xFFE0E8F2.toInt())
            setPadding(0, 0, 0, dp(18))
        }
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB7C6D8.toInt())
            text = currentStatus()
            setPadding(0, 0, 0, dp(16))
        }
        installButton = button("Install engine APK") { installDownloadedApk() }
        packButton = button("Download English voice pack (~208 MB)") { downloadPack() }
        openEngineButton = button("Open Pocket TTS / import pack") { openEngine() }
        chooseEngineButton = button("Choose Pocket TTS in Android TTS settings") { openTtsSettings() }
        val note = TextView(this).apply {
            text = "Android security note: JARVIS cannot silently install another app or change your system TTS engine. After the one-time install, import the downloaded ZIP inside Pocket TTS, then select Pocket TTS in Android's Text-to-speech settings. JARVIS keeps using your current system voice until setup is complete."
            textSize = 13f
            setTextColor(0xFF9BAEC2.toInt())
            setPadding(0, dp(14), 0, dp(8))
        }
        content.addView(heading, matchWidth())
        content.addView(description, matchWidth())
        content.addView(status, matchWidth())
        content.addView(button("Download Pocket TTS engine (~12 MB)") { downloadEngine() }, matchWidth())
        content.addView(installButton, matchWidth())
        content.addView(packButton, matchWidth())
        content.addView(openEngineButton, matchWidth())
        content.addView(chooseEngineButton, matchWidth())
        content.addView(note, matchWidth())
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshButtons()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(downloadReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun currentStatus(): String {
        val installed = isEngineInstalled()
        val apk = prefs.getLong(KEY_APK_DOWNLOAD, -1L) >= 0L
        val pack = prefs.getLong(KEY_PACK_DOWNLOAD, -1L) >= 0L
        return buildString {
            append(if (installed) "Engine: installed" else "Engine: not installed")
            append("\nVoice pack: ")
            append(if (pack) "download requested (check Downloads/import in engine)" else "not downloaded")
            if (!installed && apk) append("\nAn engine APK download was started previously.")
        }
    }

    private fun refreshButtons() {
        installButton.visibility = if (prefs.getLong(KEY_APK_DOWNLOAD, -1L) >= 0L && !isEngineInstalled()) View.VISIBLE else View.GONE
        openEngineButton.visibility = if (isEngineInstalled()) View.VISIBLE else View.GONE
    }

    private fun downloadEngine() = enqueueDownload(
        engineApkUrl,
        "PocketTTS-Android-Engine-0.5.4.apk",
        KEY_APK_DOWNLOAD,
        "Downloading official Pocket TTS engine APK. Android will ask before installing it."
    )

    private fun downloadPack() = enqueueDownload(
        englishPackUrl,
        "PocketTTS-english-FP32.zip",
        KEY_PACK_DOWNLOAD,
        "Downloading English voice pack (~208 MB). Keep enough storage available."
    )

    private fun enqueueDownload(url: String, fileName: String, key: String, message: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("JARVIS Pocket TTS setup")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val id = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            prefs.edit().putLong(key, id).apply()
            status.text = message
            Toast.makeText(this, "Download started. Android will show progress in notifications.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            status.text = "Could not start download: ${e.message ?: "unknown error"}"
        }
    }

    private fun installDownloadedApk() {
        val id = prefs.getLong(KEY_APK_DOWNLOAD, -1L)
        if (id < 0L) {
            status.text = "Download the engine APK first."
            return
        }
        try {
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(id)
            if (uri == null) {
                status.text = "APK is not ready yet. Wait for the download notification, then tap Install engine APK again."
                return
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } else {
                status.text = "Android blocked APK installation. Open the downloaded APK from Downloads and follow Android's prompts."
            }
        } catch (e: Exception) {
            status.text = "Could not open installer: ${e.message ?: "unknown error"}"
        }
    }

    private fun openEngine() {
        val launch = packageManager.getLaunchIntentForPackage(enginePackage)
        if (launch == null) {
            status.text = "Pocket TTS is not installed yet. Download and install the engine APK first."
            return
        }
        startActivity(launch)
        status.text = "In Pocket TTS, import PocketTTS-english-FP32.zip from Downloads, then return here to choose the engine."
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                status.text = "Open Android Settings and search for Text-to-speech output."
            }
        }
    }

    private fun isEngineInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(enginePackage, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(enginePackage, 0)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply {
            bottomMargin = dp(8)
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_APK_DOWNLOAD = "apk_download_id"
        private const val KEY_PACK_DOWNLOAD = "pack_download_id"
    }
}
