package com.jarvis.assistant.ui.chat

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import com.jarvis.assistant.tools.McpManager
import com.jarvis.assistant.tools.McpOAuth
import com.jarvis.assistant.tools.McpServerConfig
import kotlinx.coroutines.launch
import java.util.UUID

/** Small native settings surface for adding and managing MCP Streamable-HTTP servers. */
class McpServersActivity : AppCompatActivity() {
    private lateinit var manager: McpManager
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = McpManager.get(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val header = TextView(this).apply {
            text = "MCP SERVERS"
            textSize = 21f
            setTextColor(0xffe9f1ff.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Connect Streamable HTTP MCP servers. Tool calls use JARVIS permission checks. OAuth tokens are stored in Android Keystore-backed encrypted preferences."
            textSize = 13f
            setTextColor(0xffaab8cc.toInt())
            setPadding(0, dp(8), 0, dp(14))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(Button(this).apply { text = "Add server"; setOnClickListener { addServer() } })
        actions.addView(Button(this).apply {
            text = "Refresh tools"
            setOnClickListener { refreshTools() }
        })
        root.addView(actions)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::manager.isInitialized) {
            render()
            if (manager.servers().any { it.enabled }) refreshTools()
        }
    }

    private fun render() {
        content.removeAllViews()
        val servers = manager.servers()
        if (servers.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "No MCP servers added yet."
                textSize = 14f
                setTextColor(0xffaab8cc.toInt())
                setPadding(0, dp(24), 0, dp(24))
            })
        }
        servers.forEach { server ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(com.jarvis.assistant.R.drawable.bg_chat_attachment)
            }
            card.addView(TextView(this).apply {
                text = server.name + if (server.enabled) "  ·  ENABLED" else "  ·  DISABLED"
                textSize = 15f
                setTextColor(0xffe9f1ff.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = server.url + "\n" + server.status
                textSize = 12f
                setTextColor(0xffaab8cc.toInt())
                setPadding(0, dp(5), 0, dp(8))
            })
            card.addView(Button(this).apply {
                text = "Manage"
                setOnClickListener { showActions(server.id) }
            })
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun addServer(existing: McpServerConfig? = null) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        val name = EditText(this).apply { hint = "Server name"; setText(existing?.name.orEmpty()) }
        val url = EditText(this).apply {
            hint = "https://server.example/mcp"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url.orEmpty())
        }
        panel.addView(name)
        panel.addView(url)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add MCP server" else "Edit MCP server")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val config = McpServerConfig(
                    id = existing?.id ?: UUID.randomUUID().toString().replace("-", "").take(12),
                    name = name.text.toString().trim(),
                    url = url.text.toString().trim(),
                    enabled = existing?.enabled ?: true,
                    clientId = existing?.clientId.orEmpty(),
                    authorizationEndpoint = existing?.authorizationEndpoint.orEmpty(),
                    tokenEndpoint = existing?.tokenEndpoint.orEmpty(),
                    registrationEndpoint = existing?.registrationEndpoint.orEmpty(),
                    tokenExpiresAt = existing?.tokenExpiresAt ?: 0L,
                    status = existing?.status ?: "Needs refresh"
                )
                val error = manager.save(config)
                if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                render()
                if (error == null) refreshTools()
            }
            .show()
    }

    private fun showActions(id: String) {
        val server = manager.server(id) ?: return
        val actions = arrayOf(
            if (server.enabled) "Disable" else "Enable",
            "Login with OAuth (PKCE)",
            "Set bearer token",
            "Clear saved credentials",
            "Refresh this server",
            "Edit",
            "Delete"
        )
        AlertDialog.Builder(this)
            .setTitle(server.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> { manager.setEnabled(id, !server.enabled); render(); if (!server.enabled) refreshTools() }
                    1 -> lifecycleScope.launch {
                        runCatching { McpOAuth.start(this@McpServersActivity, id) }
                            .onFailure { Toast.makeText(this@McpServersActivity, it.message ?: "MCP login failed.", Toast.LENGTH_LONG).show() }
                        render()
                    }
                    2 -> setBearerToken(id)
                    3 -> {
                        manager.clearBearerToken(id)
                        render()
                        Toast.makeText(this, "Saved credentials cleared.", Toast.LENGTH_SHORT).show()
                    }
                    4 -> refreshTools()
                    5 -> addServer(server)
                    6 -> AlertDialog.Builder(this)
                        .setTitle("Delete MCP server?")
                        .setMessage("This removes the server and its encrypted tokens.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ -> manager.remove(id); render() }
                        .show()
                }
            }
            .show()
    }

    private fun setBearerToken(id: String) {
        val input = EditText(this).apply {
            hint = "Bearer token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Set bearer token")
            .setMessage("The token is saved in encrypted Android Keystore-backed storage.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val error = manager.setBearerToken(id, input.text.toString())
                if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                render()
                if (error == null) refreshTools()
            }
            .show()
    }

    private fun refreshTools() {
        lifecycleScope.launch {
            runCatching { manager.refreshAll() }
                .onFailure { Toast.makeText(this@McpServersActivity, it.message ?: "MCP refresh failed.", Toast.LENGTH_LONG).show() }
            render()
            val count = manager.tools().size
            Toast.makeText(this@McpServersActivity, "$count MCP tools available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
