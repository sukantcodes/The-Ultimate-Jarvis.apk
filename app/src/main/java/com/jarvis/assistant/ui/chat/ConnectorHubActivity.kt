package com.jarvis.assistant.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.tools.GitHubConnectionActivity
import org.json.JSONObject
import java.io.File

/** ChatBox-only integration hub. Connector drafts are declarative and are not executed as code. */
class ConnectorHubActivity : AppCompatActivity() {
    private val pad by lazy { (resources.displayMetrics.density * 18).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "CONNECTORS"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Connect services to JARVIS ChatBox, browse connector projects, or create your own connector manifest."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(18))
        })
        root.addView(action("GitHub · Connect account", "Encrypted on-device credential, repository access and approved write operations") {
            startActivity(Intent(this, GitHubConnectionActivity::class.java))
        })
        root.addView(action("Browse connectors", "Explore open-source MCP server implementations and connector ideas") {
            openUrl("https://github.com/modelcontextprotocol/servers")
        })
        root.addView(action("Build a custom connector", "Create a declarative HTTPS connector manifest in your private ChatBox workspace") {
            showBuilder()
        })
        root.addView(action("My connector drafts", "View locally saved connector manifests") { showDrafts() })
        root.addView(TextView(this).apply {
            text = "Security: connector drafts are configuration only. They do not execute downloaded code, store API secrets, or gain access until a supported runtime and permissions are configured."
            textSize = 12f
            setPadding(0, dp(18), 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun action(title: String, subtitle: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = getDrawable(com.jarvis.assistant.R.drawable.bg_chat_suggestion)
        addView(TextView(this@ConnectorHubActivity).apply {
            text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(TextView(this@ConnectorHubActivity).apply {
            text = subtitle; textSize = 12f; setPadding(0, dp(5), 0, 0)
        })
        setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        layoutParams = lp
    }

    private fun showBuilder() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            form.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val name = field("Connector name (e.g. My Weather API)")
        val description = field("What should JARVIS use it for?")
        val baseUrl = field("HTTPS base URL (https://api.example.com)")
        val path = field("Endpoint path (e.g. /v1/status)")
        val method = field("HTTP method: GET or POST")
        method.setText("GET")
        AlertDialog.Builder(this)
            .setTitle("Create connector manifest")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save draft") { _, _ ->
                val n = name.text.toString().trim()
                val d = description.text.toString().trim()
                val base = baseUrl.text.toString().trim()
                val endpoint = path.text.toString().trim()
                val verb = method.text.toString().trim().uppercase()
                val uri = runCatching { Uri.parse(base) }.getOrNull()
                val valid = n.length in 3..60 && d.length in 5..240 &&
                    uri?.scheme == "https" && !uri.host.isNullOrBlank() &&
                    uri.userInfo == null && uri.query == null && uri.fragment == null &&
                    endpoint.startsWith("/") && !endpoint.contains("..") &&
                    !endpoint.contains('\\\\') && verb in setOf("GET", "POST")
                if (!valid) {
                    Toast.makeText(this, "Check the name, description, HTTPS URL, path and method.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val slug = n.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
                val dir = File(filesDir, "chatbox-workspace/connectors")
                val target = File(dir, "$slug.json")
                if (!dir.exists() && !dir.mkdirs()) {
                    Toast.makeText(this, "Could not create connector workspace.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val json = JSONObject()
                    .put("schemaVersion", 1)
                    .put("name", n)
                    .put("description", d)
                    .put("baseUrl", base.trimEnd('/'))
                    .put("path", endpoint)
                    .put("method", verb)
                    .put("status", "draft")
                    .put("executionEnabled", false)
                runCatching { target.writeText(json.toString(2), Charsets.UTF_8) }
                    .onSuccess { Toast.makeText(this, "Connector draft saved in ChatBox workspace.", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, "Could not save draft: ${it.message}", Toast.LENGTH_LONG).show() }
            }.show()
    }

    private fun showDrafts() {
        val dir = File(filesDir, "chatbox-workspace/connectors")
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("json", true) }?.sortedBy { it.name }.orEmpty()
        val message = files.joinToString("\n\n") { file ->
            runCatching {
                val item = JSONObject(file.readText())
                "${item.optString("name")} · ${item.optString("method")}\n${item.optString("baseUrl")}${item.optString("path")}\nStatus: ${item.optString("status")}"
            }.getOrDefault(file.name + " · Invalid manifest")
        }.ifBlank { "No connector drafts yet. Choose Build a custom connector to create one." }
        AlertDialog.Builder(this).setTitle("My connector drafts").setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "Could not open connector catalogue.", Toast.LENGTH_SHORT).show() }
    }
}
