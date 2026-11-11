package com.jarvis.assistant.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.jarvis.assistant.tools.ToolCall
import com.jarvis.assistant.tools.ToolRuntime
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hands-on view of the workspace: Files, Terminal, Previews, Tasks and GitHub. Every button runs the same tool the
 * assistant uses, through the same permission prompts, sandbox and activity log, so nothing here is a back door.
 */
class WorkspaceActivity : AppCompatActivity() {
    private val rt by lazy { ToolRuntime.get(applicationContext) }
    private lateinit var panel: LinearLayout
    private lateinit var output: TextView
    private var counter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ThemeManager.applyDynamicBackground(root)
        val bar = UiKit.row(this).apply { setPadding(UiKit.dp(context, 8), UiKit.dp(context, 12), UiKit.dp(context, 16), UiKit.dp(context, 4)) }
        bar.addView(UiKit.button(this, "‹ Back") { finish() }); bar.addView(UiKit.text(this, "  Workspace", 20f, bold = true))
        root.addView(bar)
        val tabs = UiKit.row(this)
        listOf("Files" to ::files, "Terminal" to ::terminal, "Previews" to ::previews, "Tasks" to ::tasks, "GitHub" to ::github).forEach { (n, f) -> tabs.addView(UiKit.button(this, n) { show(f) }) }
        root.addView(HorizontalScrollView(this).apply { addView(tabs); setPadding(UiKit.dp(context, 8), 0, UiKit.dp(context, 8), 0) })
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = UiKit.dp(context, 16); setPadding(p, p, p, 0) }
        root.addView(panel)
        output = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); text = "Output appears here."; val p = UiKit.dp(context, 12); setPadding(p, p, p, p) }
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        show(::files)
    }

    private fun show(build: (LinearLayout) -> Unit) { panel.removeAllViews(); build(panel) }

    private fun call(name: String, vararg args: Pair<String, Any>) {
        val o = JsonObject().apply { args.forEach { (k, v) -> when (v) { is Number -> addProperty(k, v); is Boolean -> addProperty(k, v); else -> addProperty(k, v.toString()) } } }
        output.text = "Running $name…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { rt.executor.execute(ToolCall("ui-${++counter}", name, o, "workspace-panel")) }
            output.text = r.text.ifBlank { "(no output)" }
        }
    }

    private fun field(p: LinearLayout, hint: String, initial: String = ""): EditText = UiKit.input(this, hint).also { it.setText(initial); p.addView(it) }
    private fun buttons(p: LinearLayout, vararg b: Pair<String, () -> Unit>) { val r = UiKit.row(this); b.forEach { (n, f) -> r.addView(UiKit.button(this, n) { f() }) }; p.addView(HorizontalScrollView(this).apply { addView(r) }) }

    private fun files(p: LinearLayout) {
        val path = field(p, "Path in the workspace", ".")
        buttons(p, "List" to { call("list_files", "path" to path.text.toString().ifBlank { "." }) }, "Read" to { call("read_file", "path" to path.text.toString(), "max_chars" to 20000) },
            "Delete" to { call("delete_path", "path" to path.text.toString()) }, "New folder" to { call("make_directory", "path" to path.text.toString()) })
        val q = field(p, "Search text in files")
        buttons(p, "Search" to { call("search_files", "query" to q.text.toString()) })
        call("list_files", "path" to ".")
    }

    private fun terminal(p: LinearLayout) {
        p.addView(UiKit.text(this, "Runs in ${rt.terminal?.backend?.name ?: "no shell available"}. Dangerous commands are blocked or ask first.", 12f, muted = true))
        val cmd = field(p, "Command, for example: ls -la")
        buttons(p, "Run" to { call("terminal_run", "command" to cmd.text.toString()) }, "Stop" to { call("terminal_stop") }, "Sessions" to { call("terminal_sessions") }, "Environment" to { call("terminal_environment") })
    }

    private fun previews(p: LinearLayout) {
        val running = rt.servers.servers.running()
        output.text = if (running.isEmpty()) "No website previews are running. Ask the assistant to \"create a website and preview it\"." else running.entries.joinToString("\n") { "${it.key} → ${it.value.url}  (${it.value.requestCount.get()} requests)" }
        running.forEach { (name, s) -> buttons(p, "Open $name" to { call("open_url", "url" to s.url) }, "Stop $name" to { rt.servers.servers.stop(name); show(::previews) }) }
        buttons(p, "Refresh" to { show(::previews) })
    }

    private fun tasks(p: LinearLayout) {
        val id = field(p, "Task id (from the list below)")
        buttons(p, "Status" to { call("task_status", "task_id" to id.text.toString()) }, "Run" to { call("task_run", "task_id" to id.text.toString()) }, "Cancel" to { call("task_cancel", "task_id" to id.text.toString()) }, "All tasks" to { call("task_status") })
        call("task_status")
    }

    private fun github(p: LinearLayout) {
        val repo = field(p, "Repository, for example owner/name")
        buttons(p, "Status" to { call("github_status") }, "My repos" to { call("github_list_repos") }, "Info" to { call("github_repo_info", "repo" to repo.text.toString()) },
            "Files" to { call("github_list_files", "repo" to repo.text.toString(), "path" to "") }, "PRs" to { call("github_list_prs", "repo" to repo.text.toString()) },
            "Issues" to { call("github_list_issues", "repo" to repo.text.toString()) }, "Actions" to { call("github_actions_runs", "repo" to repo.text.toString()) })
        call("github_status")
    }
}
