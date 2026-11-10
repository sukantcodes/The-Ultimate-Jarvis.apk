package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import java.io.File

object LocalServerDialog {
    fun show(context: Context) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (18 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        root.addView(TextView(context).apply {
            text = "LOCAL WEBSITE PREVIEW"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(context).apply {
            text = "Serves static files from your private ChatBox workspace. Open the URL in a browser on this phone. Scripts are served as files, never executed by JARVIS."
            textSize = 12f
            setPadding(0, 8, 0, 12)
        })
        val port = EditText(context).apply {
            hint = "Port (default 8765)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(if (LocalWorkspaceServer.isRunning()) LocalWorkspaceServer.port().toString() else "8765")
        }
        root.addView(port)
        val status = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 12, 0, 12)
        }
        root.addView(status)
        val url = TextView(context).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        root.addView(url)
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(context).apply { text = "Start server" }
        val stop = Button(context).apply { text = "Stop" }
        val copy = Button(context).apply { text = "Copy URL" }
        controls.addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(stop)
        controls.addView(copy)
        root.addView(controls)

        fun refresh() {
            val active = LocalWorkspaceServer.isRunning()
            status.text = if (active) "● RUNNING · this phone only" else "○ STOPPED"
            url.text = LocalWorkspaceServer.url()
            start.isEnabled = !active
            stop.isEnabled = active
            copy.isEnabled = active
            port.isEnabled = !active
        }
        val dialog = MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setView(root)
            .setNegativeButton("Close", null)
            .create()
        start.setOnClickListener {
            val chosenPort = port.text.toString().toIntOrNull() ?: 8765
            try {
                val workspace = File(context.applicationContext.filesDir, "chatbox-workspace")
                val address = LocalWorkspaceServer.start(workspace, chosenPort)
                Toast.makeText(context, "Local preview server started.", Toast.LENGTH_SHORT).show()
                refresh()
                url.text = address
            } catch (error: Exception) {
                status.text = "Could not start: ${error.message ?: "port unavailable"}"
            }
        }
        stop.setOnClickListener {
            LocalWorkspaceServer.stop()
            refresh()
            Toast.makeText(context, "Local preview server stopped.", Toast.LENGTH_SHORT).show()
        }
        copy.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("JARVIS local server URL", LocalWorkspaceServer.url()))
            Toast.makeText(context, "URL copied.", Toast.LENGTH_SHORT).show()
        }
        refresh()
        dialog.show()
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * 0.94f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
