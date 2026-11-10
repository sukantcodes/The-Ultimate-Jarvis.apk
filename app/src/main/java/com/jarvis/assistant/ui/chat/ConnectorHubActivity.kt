package com.jarvis.assistant.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jarvis.assistant.tools.GitHubConnectionActivity
import org.json.JSONObject
import java.io.File

/** ChatBox connector catalogue. Installed entries are declarative HTTPS REST tools, never downloaded code. */
class ConnectorHubActivity : AppCompatActivity() {
    private val pad by lazy { (resources.displayMetrics.density * 18).toInt() }
    private val connectorDir by lazy { File(filesDir, "chatbox-workspace/connectors") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply {
            text = "CONNECTORS"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Connect supported services from ChatBox. Installed REST connectors become callable by JARVIS with permission checks."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(18))
        })
        root.addView(action("GitHub · Connect account", "Encrypted account connection with approved repository operations") {
            startActivity(Intent(this, GitHubConnectionActivity::class.java))
        })
        root.addView(action("Browse connector projects", "Explore MCP server projects; downloaded code is never executed by ChatBox") {
            openUrl("https://github.com/modelcontextprotocol/servers")
        })
        root.addView(action("Build a REST connector", "Configure a public HTTPS GET or POST endpoint") { showBuilder() })
        root.addView(action("Manage installed connectors", "Install, enable, configure credentials, disable or remove connectors") { showDrafts() })
        root.addView(TextView(this).apply {
            text = "Supported runtime: HTTPS REST endpoints (GET/POST). Credentials are stored with Android Keystore-backed encryption. MCP server binaries, arbitrary scripts and OAuth flows are not executed automatically."
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
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    }

    private fun showBuilder() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            form.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val name = field("Connector name (e.g. My Weather API)")
        val description = field("What should JARVIS use it for?")
        val baseUrl = field("HTTPS base URL (https://api.example.com)")
        val path = field("Endpoint path (e.g. /v1/status)")
        val method = field("HTTP method: GET or POST").apply { setText("GET") }
        AlertDialog.Builder(this)
            .setTitle("Create REST connector")
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
                    uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.port in listOf(-1, 443) &&
                    uri.userInfo == null && uri.query == null && uri.fragment == null &&
                    endpoint.startsWith("/") && !endpoint.contains("..") && !endpoint.contains('\\') &&
                    verb in setOf("GET", "POST")
                if (!valid) {
                    Toast.makeText(this, "Use a name, description, public HTTPS URL on port 443, safe path and GET/POST.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val slug = n.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
                if (!connectorDir.exists() && !connectorDir.mkdirs()) {
                    Toast.makeText(this, "Could not create connector workspace.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val target = File(connectorDir, "$slug.json")
                if (target.exists()) {
                    Toast.makeText(this, "A connector with this name already exists.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val json = JSONObject()
                    .put("schemaVersion", 1).put("slug", slug).put("name", n).put("description", d)
                    .put("baseUrl", base.trimEnd('/')).put("path", endpoint).put("method", verb)
                    .put("status", "draft").put("executionEnabled", false)
                runCatching { target.writeText(json.toString(2), Charsets.UTF_8) }
                    .onSuccess { Toast.makeText(this, "Draft saved. Open Manage installed connectors to install it.", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, "Could not save draft: ${it.message}", Toast.LENGTH_LONG).show() }
            }.show()
    }

    private fun showDrafts() {
        val files = connectorDir.listFiles { f -> f.isFile && f.extension.equals("json", true) }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Connectors").setMessage("No connector drafts yet. Create a REST connector first.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = files.map { file ->
            runCatching {
                val item = JSONObject(file.readText())
                "${item.optString("name", file.nameWithoutExtension)} · ${if (item.optBoolean("executionEnabled")) "Enabled" else item.optString("status", "Draft")}"
            }.getOrDefault(file.name + " · Invalid")
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Your connectors").setItems(labels) { _, which -> manageConnector(files[which]) }
            .setNegativeButton("Close", null).show()
    }

    private fun manageConnector(file: File) {
        if (!isConnectorFile(file)) return
        val item = runCatching { JSONObject(file.readText()) }.getOrElse {
            Toast.makeText(this, "Invalid connector manifest.", Toast.LENGTH_LONG).show(); return
        }
        val slug = item.optString("slug", file.nameWithoutExtension)
        val options = arrayOf("Install / Enable", "Configure API token", "Disable", "Remove")
        AlertDialog.Builder(this).setTitle(item.optString("name", file.name)).setItems(options) { _, which ->
            when (which) {
                0 -> updateManifest(file, item.put("status", "installed").put("executionEnabled", true),
                    "Installed. JARVIS can now call this endpoint after the permission prompt.")
                1 -> configureToken(slug)
                2 -> updateManifest(file, item.put("status", "disabled").put("executionEnabled", false), "Connector disabled.")
                3 -> AlertDialog.Builder(this).setTitle("Remove connector?")
                    .setMessage("This removes its manifest and saved API token.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                        val removed = runCatching { file.delete() }.getOrDefault(false)
                        removeToken(slug)
                        Toast.makeText(this, if (removed) "Connector removed." else "Could not remove connector.", Toast.LENGTH_LONG).show()
                    }.show()
            }
        }.show()
    }

    private fun updateManifest(file: File, item: JSONObject, message: String) {
        if (!isConnectorFile(file)) return
        runCatching { file.writeText(item.toString(2), Charsets.UTF_8) }
            .onSuccess { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(this, "Could not update connector: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun configureToken(slug: String) {
        val input = EditText(this).apply {
            hint = "Optional bearer API token"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("API credential").setMessage("Stored encrypted on this device. Leave blank to remove the saved token.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val token = input.text.toString()
                runCatching {
                    val prefs = encryptedPrefs()
                    if (token.isBlank()) prefs.edit().remove("token:$slug").apply()
                    else prefs.edit().putString("token:$slug", token).apply()
                }.onSuccess { Toast.makeText(this, "Credential saved securely.", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this, "Secure storage unavailable: ${it.message}", Toast.LENGTH_LONG).show() }
            }.show()
    }

    private fun encryptedPrefs() = EncryptedSharedPreferences.create(
        this, "jarvis_connector_secrets",
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun removeToken(slug: String) { runCatching { encryptedPrefs().edit().remove("token:$slug").apply() } }
    private fun isConnectorFile(file: File): Boolean {
        val root = connectorDir.canonicalFile
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return target.parentFile == root && target.isFile && !java.nio.file.Files.isSymbolicLink(target.toPath())
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "Could not open connector catalogue.", Toast.LENGTH_SHORT).show() }
    }
}
