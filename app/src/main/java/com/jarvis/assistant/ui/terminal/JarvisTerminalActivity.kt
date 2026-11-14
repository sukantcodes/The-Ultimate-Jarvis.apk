package com.jarvis.assistant.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.tools.ToolRuntime
import com.jarvis.assistant.tools.terminal.CommandGuard
import com.jarvis.assistant.tools.terminal.TerminalEnvironment
import com.jarvis.assistant.tools.terminal.TerminalManager
import com.jarvis.assistant.tools.terminal.TerminalSession
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Jarvis Terminal: a screen where the person types shell commands themselves.
 *
 * It uses the same [TerminalManager] that Jarvis uses from ChatBox, so everything behaves exactly as it does now
 * (same shell, same workspace folder, same safety rules). The person's tabs are named me1, me2 ... and are
 * separate from Jarvis's own sessions, so typing here never interrupts what Jarvis is doing. Jarvis can still list,
 * read, run commands in, clear and close every tab, and the person can open and watch Jarvis's sessions here too.
 */
class JarvisTerminalActivity : AppCompatActivity() {

    private val manager: TerminalManager? by lazy { runCatching { ToolRuntime.get(applicationContext).terminal }.getOrNull() }
    private var current: TerminalSession? = null
    private var outputJob: Job? = null
    private var backgroundMode = false
    private var historyPos = -1
    private var lastEnd = -1L
    private var lastTabsSignature = ""
    private var refreshQueued = false
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tabsRow: LinearLayout
    private lateinit var outputView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var promptLabel: TextView
    private lateinit var input: EditText
    private lateinit var stateView: TextView
    private lateinit var bgChip: TextView

    private val accent: Int get() = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { 0xFF00E5FF.toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        try { ThemeManager.applyTheme(this) } catch (_: Throwable) { }
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        val m = manager
        if (m == null) {
            outputView.text = "The terminal is not available on this device."
            input.isEnabled = false
            return
        }
        val first = m.list().firstOrNull { m.isUserSession(it.id) } ?: openNewTab(announce = true)
        if (first != null) select(first) else m.list().firstOrNull()?.let { select(it) }
        refreshTabs(force = true)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    tick()
                    delay(600)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------------------------------

    private fun openNewTab(announce: Boolean): TerminalSession? {
        val m = manager ?: return null
        val s = try { m.createUserSession() } catch (e: IllegalStateException) {
            Toast.makeText(this, e.message ?: "Too many terminal tabs. Close one first.", Toast.LENGTH_LONG).show()
            return null
        }
        if (announce) {
            s.note("Jarvis Terminal · ${s.id}\nFolder: ${m.workspace.root.path}\nJarvis can see and use this terminal too.\n")
            // Find out honestly which programs exist here, and print it once at the top of the tab.
            runScope.launch {
                runCatching { TerminalEnvironment.detect(m).format() }.getOrNull()?.let { s.note("\n$it\n\n") }
            }
        }
        return s
    }

    private fun select(session: TerminalSession) {
        current = session
        historyPos = -1
        lastEnd = -1L
        outputJob?.cancel()
        outputJob = lifecycleScope.launch { session.output.collect { queueRefresh() } }
        refreshOutput(forceBottom = true)
        refreshTabs(force = true)
        updatePrompt()
        input.requestFocus()
    }

    private fun refreshTabs(force: Boolean = false) {
        val m = manager ?: return
        val sessions = m.list().filter { it.id != "env-probe" }
        val signature = sessions.joinToString("|") { "${it.id}:${it.isRunning}:${it.id == current?.id}" }
        if (!force && signature == lastTabsSignature) return
        lastTabsSignature = signature
        tabsRow.removeAllViews()
        for (s in sessions) {
            val mine = m.isUserSession(s.id)
            val selected = s.id == current?.id
            val label = (if (mine) "You · " else "Jarvis · ") + s.id + if (s.isRunning) " ●" else ""
            val chip = chip(label, selected)
            chip.setOnClickListener { select(s) }
            chip.setOnLongClickListener { confirmCloseTab(s); true }
            tabsRow.addView(chip)
        }
        val plus = chip("＋ New tab", false)
        plus.setOnClickListener { openNewTab(announce = true)?.let { select(it) } }
        tabsRow.addView(plus)
    }

    private fun confirmCloseTab(s: TerminalSession) {
        val m = manager ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Close ${s.id}?")
            .setMessage("Stops anything running in it and removes the tab.")
            .setPositiveButton("Close") { _, _ ->
                m.close(s.id)
                if (current?.id == s.id) {
                    current = null
                    val next = m.list().firstOrNull { m.isUserSession(it.id) } ?: m.list().firstOrNull() ?: openNewTab(announce = true)
                    if (next != null) {
                        select(next)
                    } else {
                        outputView.text = ""
                    }
                }
                refreshTabs(force = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------------------------------
    // Running commands
    // ------------------------------------------------------------------------------------------

    private fun submit() {
        val m = manager ?: return
        val session = current ?: openNewTab(announce = true)?.also { select(it) } ?: return
        val command = input.text?.toString()?.trim().orEmpty()
        if (command.isEmpty()) return
        input.setText("")
        historyPos = -1

        if (command == "clear" || command == "cls") {
            m.clear(session.id)
            refreshOutput(forceBottom = true)
            return
        }
        val verdict = m.guard.classify(command)
        if (verdict.verdict == CommandGuard.Verdict.BLOCKED) {
            session.note("\n⛔ Refused: ${verdict.summary}. This command is never run.\n")
            return
        }
        session.preflight(command)?.let { problem ->
            session.note("\n⚠ $problem\n")
            return
        }
        session.note("\n${session.relativeCwd()} $ $command\n")
        if (verdict.verdict == CommandGuard.Verdict.DESTRUCTIVE) session.note("⚠ ${verdict.summary}\n")

        if (backgroundMode) {
            val problem = session.startBackground(command)
            session.note(if (problem != null) "⚠ $problem\n" else "[started in the background, pid ${session.runningPid ?: "unknown"}]\n")
            return
        }
        // Runs on the app's own scope, so leaving this screen does not kill a long command.
        runScope.launch {
            val r = session.run(command, COMMAND_TIMEOUT_MS)
            val footer = when {
                r.rejected != null -> "⚠ ${r.rejected}"
                r.timedOut -> "[stopped: it ran longer than ${COMMAND_TIMEOUT_MS / 60_000} minutes]"
                r.cancelled -> "[stopped]"
                else -> "[exit code ${r.exitCode} · ${"%.1f".format(r.elapsedMs / 1000.0)} s]"
            }
            session.note(footer + "\n")
        }
    }

    private fun stopRunning() {
        val s = current ?: return
        if (s.cancel()) Toast.makeText(this, "Stopped.", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "Nothing is running in ${s.id}.", Toast.LENGTH_SHORT).show()
    }

    private fun recall(direction: Int) {
        val s = current ?: return
        val cmds = s.history.map { it.command }
        if (cmds.isEmpty()) return
        historyPos = when (direction) {
            -1 -> if (historyPos < 0) cmds.lastIndex else maxOf(0, historyPos - 1)
            else -> if (historyPos < 0) -1 else if (historyPos >= cmds.lastIndex) -1 else historyPos + 1
        }
        input.setText(if (historyPos < 0) "" else cmds[historyPos])
        input.setSelection(input.text?.length ?: 0)
    }

    // ------------------------------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------------------------------

    private fun queueRefresh() {
        if (refreshQueued) return
        refreshQueued = true
        ui.postDelayed({ refreshQueued = false; refreshOutput(forceBottom = false) }, 60)
    }

    private fun refreshOutput(forceBottom: Boolean) {
        val s = current ?: return
        val child = scroll.getChildAt(0)
        val atBottom = forceBottom || child == null || child.bottom - (scroll.height + scroll.scrollY) < dp(80)
        lastEnd = s.buffer.endOffset
        outputView.text = s.buffer.tail(MAX_SHOWN_CHARS)
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updatePrompt() {
        val s = current
        promptLabel.text = if (s == null) "$" else "${s.relativeCwd()} $"
        stateView.text = when {
            s == null -> ""
            s.isRunning -> "● running: ${s.runningCommand?.take(48).orEmpty()}"
            else -> "idle" + (s.lastExitCode?.let { " · last exit $it" } ?: "")
        }
        stateView.setTextColor(if (s?.isRunning == true) 0xFFFFC107.toInt() else 0xFF78909C.toInt())
    }

    private fun tick() {
        val s = current
        if (s != null && s.buffer.endOffset != lastEnd) refreshOutput(forceBottom = false)
        updatePrompt()
        refreshTabs()
    }

    private fun copyOutput() {
        val text = current?.buffer?.tail(MAX_SHOWN_CHARS).orEmpty()
        if (text.isBlank()) { Toast.makeText(this, "Nothing to copy.", Toast.LENGTH_SHORT).show(); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jarvis Terminal", text))
        Toast.makeText(this, "Output copied.", Toast.LENGTH_SHORT).show()
    }

    private fun sendToChat() {
        val s = current ?: return
        val text = s.buffer.tail(6_000).trim()
        if (text.isEmpty()) { Toast.makeText(this, "Nothing to send.", Toast.LENGTH_SHORT).show(); return }
        JarvisApp.instance.chatRepository.addTurn("Terminal output (${s.id})", "```\n$text\n```")
        Toast.makeText(this, "Added to the ChatBox.", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------------------------------
    // UI (built in code)
    // ------------------------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F14.toInt())
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Top bar
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(10), dp(6)) }
        bar.addView(barButton("←") { finish() })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply { text = "Jarvis Terminal"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Typeface.DEFAULT_BOLD })
        stateView = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(0xFF78909C.toInt()); maxLines = 1 }
        titles.addView(stateView)
        bar.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barButton("Copy") { copyOutput() })
        bar.addView(barButton("→ Chat") { sendToChat() })
        bar.addView(barButton("■ Stop") { stopRunning() })
        root.addView(bar)

        // Tabs
        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), dp(6)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabsRow) })

        // Output
        outputView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(0xFFD7E3EA.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF05080B.toInt())
            isFillViewport = true
            addView(outputView)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Quick keys
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), 0) }
        keys.addView(chip("↑", false).apply { setOnClickListener { recall(-1) } })
        keys.addView(chip("↓", false).apply { setOnClickListener { recall(1) } })
        bgChip = chip("Background: off", false).apply {
            setOnClickListener {
                backgroundMode = !backgroundMode
                text = if (backgroundMode) "Background: on" else "Background: off"
                styleChip(this, backgroundMode)
            }
        }
        keys.addView(bgChip)
        keys.addView(chip("Clear", false).apply { setOnClickListener { current?.let { s -> manager?.clear(s.id); refreshOutput(true) } } })
        keys.addView(chip("ls", false).apply { setOnClickListener { input.setText("ls -la"); input.setSelection(input.text?.length ?: 0) } })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(keys) })

        // Prompt line
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(8), dp(8)) }
        promptLabel = TextView(this).apply { typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); setTextColor(accent); text = "$" }
        row.addView(promptLabel)
        input = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF546E7A.toInt())
            hint = "type a command"
            background = null
            setPadding(dp(10), dp(8), dp(10), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "Run"
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(accent) }
            setOnClickListener { submit() }
        })
        root.addView(row)
        return root
    }

    private fun barButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(0xFFCFD8DC.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { click() }
    }

    private fun chip(label: String, selected: Boolean) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        isClickable = true
        isFocusable = true
        styleChip(this, selected)
    }

    private fun styleChip(chip: TextView, on: Boolean) {
        chip.setTextColor(if (on) Color.BLACK else 0xFFB0BEC5.toInt())
        chip.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(if (on) accent else 0x26FFFFFF) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        outputJob?.cancel()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 15 * 60_000L
        const val MAX_SHOWN_CHARS = 60_000
        /** Commands run here, not on the screen's lifecycle, so closing the screen does not stop them. */
        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
