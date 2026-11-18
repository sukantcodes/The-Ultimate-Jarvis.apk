package com.jarvis.assistant.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why the app closed. The error is written to a small file just before the app dies; the next time the app
 * opens it offers to show, copy or share it, so a crash can be fixed from the real error instead of guesswork.
 * It never hides the crash: the normal Android handler still runs afterwards.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(app.filesDir, FILE).writeText("Time: $stamp\nThread: ${thread.name}\n\n${sw.toString().take(12_000)}")
            } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    /** Asks (once) about the last crash, if there was one. Call from the main screen. */
    fun showIfAny(activity: Activity) {
        val f = file(activity)
        if (!f.isFile || activity.isFinishing) return
        val text = try { f.readText() } catch (_: Exception) { return }
        f.delete()
        val summary = text.lineSequence().firstOrNull { it.startsWith("java.") || it.startsWith("kotlin.") || it.startsWith("android.") || it.contains("Exception") || it.contains("Error") }.orEmpty()
        try {
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("JARVIS closed unexpectedly last time")
                .setMessage((if (summary.isNotBlank()) summary.take(300) + "\n\n" else "") + "Copy the details and send them so it can be fixed.")
                .setPositiveButton("Copy details") { _, _ ->
                    (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("JARVIS crash", text))
                }
                .setNeutralButton("Share") { _, _ ->
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                    runCatching { activity.startActivity(Intent.createChooser(send, "Share crash details")) }
                }
                .setNegativeButton("Dismiss", null)
                .show()
        } catch (_: Throwable) { }
    }
}
