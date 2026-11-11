package com.jarvis.assistant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.tools.Mode
import com.jarvis.assistant.tools.Risk
import com.jarvis.assistant.tools.ToolRuntime
import com.jarvis.assistant.util.ThemeManager
import java.text.DateFormat
import java.util.Date

/**
 * Workspace tools: GitHub connection, what the assistant may do without asking, saved memories, the activity log and
 * the workspace itself. Everything shown here is what the shared tool layer really uses for every provider.
 */
class ToolSettingsActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private val rt by lazy { ToolRuntime.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ThemeManager.applyDynamicBackground(root)
        val bar = UiKit.row(this).apply { setPadding(UiKit.dp(context, 8), UiKit.dp(context, 12), UiKit.dp(context, 16), UiKit.dp(context, 8)) }
        bar.addView(UiKit.button(this, "‹ Back") { finish() })
        bar.addView(UiKit.text(this, "  Workspace & Tools", 20f, bold = true))
        root.addView(bar)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = UiKit.dp(context, 16); setPadding(p, 0, p, p) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        body.removeAllViews()
        body.addView(UiKit.button(this, "Open workspace (files, terminal, previews, tasks, GitHub)") { startActivity(android.content.Intent(this, WorkspaceActivity::class.java)) })
        github(); voiceTools(); permissions(); memory(); workspace(); audit()
    }

    private fun section(title: String, hint: String? = null): LinearLayout {
        val c = UiKit.card(this)
        c.addView(UiKit.text(this, title, 16f, bold = true))
        hint?.let { c.addView(UiKit.text(this, it, 12f, muted = true)) }
        body.addView(c)
        return c
    }

    private fun github() {
        val c = section("GitHub", "Used for repositories, files, commits, pull requests, issues, Actions and releases. The token is kept in encrypted storage and is never shown in full. Use a fine-grained token with only the access you need.")
        val store = rt.githubToken
        c.addView(UiKit.text(this, "Status: " + if (store.isSaved) "connected (${store.masked()})" else "not connected", 13f))
        val input = UiKit.input(this, "Paste a GitHub token", secret = true)
        c.addView(input)
        val r = UiKit.row(this)
        r.addView(UiKit.button(this, "Save token", outlined = false) {
            val t = input.text.toString().trim()
            if (t.length < 20) { toast("That does not look like a GitHub token."); return@button }
            toast(if (store.save(t)) "Token saved securely." else "Secure storage is not available on this device."); render()
        })
        if (store.isSaved) r.addView(UiKit.button(this, "Disconnect") { store.remove(); toast("Disconnected."); render() })
        c.addView(r)
    }

    private fun voiceTools() {
        val c = section("Voice and tools", "Lets Gemini voice use the workspace tools: create files and websites, run a local preview, search the web, open pages in Chrome. Risky steps ask you first, by notification if Jarvis is not on screen.")
        val prefs = (application as com.jarvis.assistant.JarvisApp).preferences
        val sw = Switch(this).apply { text = "Workspace tools in voice sessions"; isChecked = prefs.voiceWorkspaceTools }
        sw.setOnCheckedChangeListener { _, on -> prefs.voiceWorkspaceTools = on }
        c.addView(sw)
    }

    private fun permissions() {
        val c = section("What the assistant may do", "Reading and web access are allowed by default. Changing files and running programs ask first. Destructive actions (delete, overwrite, push, install) always ask and can not be switched to automatic.")
        val modes = listOf(Mode.ASK, Mode.ALLOW, Mode.DENY)
        listOf(Risk.WRITE to "Create or change files", Risk.EXECUTE to "Run programs (terminal, previews)", Risk.NETWORK to "Use the internet", Risk.DESTRUCTIVE to "Destructive actions").forEach { (risk, label) ->
            c.addView(UiKit.text(this, label, 13f))
            val allowed = if (risk == Risk.DESTRUCTIVE) listOf(Mode.ASK, Mode.DENY) else modes
            val sp = Spinner(this).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, allowed.map { it.name.lowercase().replaceFirstChar { ch -> ch.uppercase() } }) }
            sp.setSelection(allowed.indexOf(rt.policy.modeFor(risk, "")).coerceAtLeast(0))
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (allowed[pos] != rt.policy.modeFor(risk, "")) rt.policy.setMode(risk, allowed[pos]) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            c.addView(sp)
        }
        c.addView(UiKit.button(this, "Forget \"allow this session\" choices") { rt.policy.endSession(); toast("Session permissions cleared.") })
    }

    private fun memory() {
        val c = section("Memory", "Notes you ask the assistant to remember. Passwords, keys and tokens are never stored.")
        val sw = Switch(this).apply { text = "Use and save memories"; isChecked = rt.memory.enabled }
        sw.setOnCheckedChangeListener { _, on -> rt.memory.enabled = on; getSharedPreferences("tool_permissions", Context.MODE_PRIVATE).edit().putBoolean("memory_enabled", on).apply() }
        c.addView(sw)
        val items = rt.memory.list()
        if (items.isEmpty()) c.addView(UiKit.text(this, "Nothing saved yet.", 13f, muted = true))
        items.takeLast(30).reversed().forEach { m ->
            val r = UiKit.row(this)
            r.addView(UiKit.text(this, m.text, 13f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(UiKit.button(this, "Delete") { rt.memory.delete(m.id); render() })
            c.addView(r)
        }
        val add = UiKit.input(this, "Add a note to remember"); c.addView(add)
        val r = UiKit.row(this)
        r.addView(UiKit.button(this, "Save note", outlined = false) {
            when (val res = rt.memory.save(add.text.toString())) { is com.jarvis.assistant.tools.memory.MemoryStore.SaveResult.Refused -> toast(res.reason); else -> render() }
        })
        r.addView(UiKit.button(this, "Copy all") { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("memories", rt.memory.exportJson())); toast("Copied.") })
        r.addView(UiKit.button(this, "Clear all") { rt.memory.clear(); render() })
        c.addView(r)
    }

    private fun workspace() {
        val c = section("Workspace", "Files the assistant creates live in a private folder inside the app. It can not reach anything outside it.")
        val size = rt.workspace.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        c.addView(UiKit.text(this, "Size: ${size / 1024} KB", 13f))
        val t = rt.terminal
        c.addView(UiKit.text(this, if (t != null) "Terminal: ${t.backend.name} (${t.backend.shell}). Programs such as git, node or python work only if they are installed on this device." else "Terminal: no shell was found on this device.", 13f))
        val installed = com.jarvis.assistant.tools.terminal.TermuxBackend.isInstalled(this)
        val prefs = getSharedPreferences("tool_permissions", Context.MODE_PRIVATE)
        val sw = Switch(this).apply { text = "Run commands in Termux" + if (installed) "" else " (Termux not installed)"; isChecked = prefs.getString("terminal_backend", "app") == "termux"; isEnabled = installed }
        sw.setOnCheckedChangeListener { _, on -> prefs.edit().putString("terminal_backend", if (on) "termux" else "app").apply(); ToolRuntime.invalidate(); toast("Applies from the next chat message."); render() }
        c.addView(sw)
        c.addView(UiKit.text(this, "Termux gives real git, node and python, but it needs setup once: " + com.jarvis.assistant.tools.terminal.TermuxBridgeSpec().userSetupSteps.joinToString(" ") + " Limits: output appears when a command ends, and Termux works in its own folder, so files made by the app's file tools are not visible to it.", 12f, muted = true))
        val running = rt.servers.servers.running()
        c.addView(UiKit.text(this, if (running.isEmpty()) "No website previews are running." else "Previews: " + running.entries.joinToString { "${it.key} → ${it.value.url}" }, 13f))
        if (running.isNotEmpty()) c.addView(UiKit.button(this, "Stop all previews") { rt.servers.servers.stopAll(); render() })
    }

    private fun audit() {
        val c = section("Activity log", "The last things the assistant did with tools, and whether you allowed them. Secrets are removed.")
        val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
        val items = rt.audit.recent(40).reversed()
        if (items.isEmpty()) c.addView(UiKit.text(this, "Nothing yet.", 13f, muted = true))
        items.forEach { c.addView(UiKit.text(this, "${fmt.format(Date(it.time))}  ${it.tool} · ${it.decision} · ${it.outcome.take(80)}", 12f, muted = true)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
