package com.jarvis.assistant.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import androidx.core.graphics.ColorUtils
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.tools.ToolRuntime
import com.jarvis.assistant.tools.terminal.CommandGuard
import com.jarvis.assistant.tools.terminal.PackageCommand
import com.jarvis.assistant.tools.terminal.PackagePlan
import com.jarvis.assistant.tools.terminal.SwitchingBackend
import com.jarvis.assistant.tools.terminal.TermuxBackend
import com.jarvis.assistant.tools.terminal.TerminalEnvironment
import com.jarvis.assistant.tools.terminal.TerminalManager
import com.jarvis.assistant.tools.terminal.TerminalSession
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * Jarvis Terminal, laid out like the Termux app: a full-screen black terminal with the prompt inline after the output,
 * a two-row extra-keys bar (ESC / - HOME UP END PGUP and TAB CTRL ALT LEFT DOWN RIGHT PGDN), a sessions drawer that
 * slides in from the left edge, a long-press menu, pinch-to-zoom and colour styles.
 *
 * It uses the same [TerminalManager] that Jarvis uses from ChatBox, so everything behaves exactly as before (same
 * shell, same workspace folder, same safety rules). The person's tabs are named me1, me2 ... and are separate from
 * Jarvis's own sessions; Jarvis can still list, read, run commands in, clear and close every tab, and the person can
 * open and watch Jarvis's sessions here too.
 */
class JarvisTerminalActivity : AppCompatActivity(), com.jarvis.assistant.util.JarvisHandlesInsets {

    private class Scheme(
        val name: String, val bg: Int, val fg: Int, val prompt: Int, val dim: Int, val error: Int, val ok: Int,
        val key: Int, val keyPressed: Int, val keyActive: Int, val panel: Int, val light: Boolean = false
    )

    private val manager: TerminalManager? by lazy { runCatching { ToolRuntime.get(applicationContext).terminal }.getOrNull() }
    private val prefs by lazy { getSharedPreferences("jarvis_terminal_ui", Context.MODE_PRIVATE) }
    private val imm get() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var current: TerminalSession? = null
    private var outputJob: Job? = null
    private var backgroundMode = false
    private var historyPos = -1
    private var lastEnd = -1L
    private var lastTabsSignature = ""
    private var refreshQueued = false
    private val ui = Handler(Looper.getMainLooper())

    private var scheme: Scheme = SCHEMES[0]
    private var fontSp = 13f
    private var ctrlOn = false
    private var altOn = false
    private var selecting = false
    private var drawerOpen = false
    private var keepScreenOn = false
    private var suppressWatcher = false
    private var edgeStartX = -1f
    private var edgeStartY = 0f

    private lateinit var root: FrameLayout
    private lateinit var main: LinearLayout
    private lateinit var scroll: TermScroll
    private lateinit var outputView: TextView
    private lateinit var promptLabel: TextView
    private lateinit var input: EditText
    private val handlesImeItself = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    private lateinit var extraKeys: LinearLayout
    private lateinit var keyScroll: android.widget.HorizontalScrollView
    private lateinit var menuButton: TextView
    private lateinit var scrim: View
    private lateinit var panel: LinearLayout
    private lateinit var sessionList: LinearLayout
    private lateinit var panelButtons: LinearLayout
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView
    private val keyViews = ArrayList<TextView>()
    private val panelButtonViews = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        try { ThemeManager.applyTheme(this) } catch (_: Throwable) { }
        super.onCreate(savedInstanceState)
        fontSp = prefs.getFloat("font_sp", 13f).coerceIn(MIN_FONT, MAX_FONT)
        scheme = SCHEMES.getOrElse(prefs.getInt("scheme", 0)) { SCHEMES[0] }
        keepScreenOn = prefs.getBoolean("keep_on", false)
        // The keyboard must move the layout exactly once. On Android 11+ the window stops resizing for the keyboard and
        // the inset listener below lifts the extra keys by the keyboard height. Before that, Android resizes the window
        // itself and the listener must not add the keyboard a second time (that is what threw the keys far up).
        if (handlesImeItself) WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildUi())
        installSelectionActions()
        applyScheme()
        applyFont()
        applyKeepScreenOn()
        onBackPressedDispatcher.addCallback(this, object : com.jarvis.assistant.util.PredictiveBack.MotionCallback(this, shouldAnimate = { !drawerOpen }) {
            override fun handleOnBackPressed() {
                if (drawerOpen) closeDrawer() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
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
            s.note(
                "Welcome to Jarvis Terminal!\n\n" +
                    "Working with packages:\n" +
                    " - Search:  pkg search <query>\n" +
                    " - Install: pkg install <package>\n" +
                    " - Upgrade: pkg upgrade\n\n" +
                    "AI tools:  jpkg ai        Everything:  jpkg help\n" +
                    "Folder: ${m.workspace.root.path}\n" +
                    "Jarvis can see and use this terminal too.\n"
            )
            // Find out honestly which programs exist here, and print it once at the top of the tab.
            runScope.launch {
                runCatching { TerminalEnvironment.detect(m).format() }.getOrNull()?.let { s.note("\n$it\n\n") }
            }
        }
        return s
    }

    private fun select(session: TerminalSession) {
        if (selecting) setSelecting(false)
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
        val signature = sessions.joinToString("|") { "${it.id}:${it.isRunning}:${it.id == current?.id}" } + scheme.name
        if (!force && signature == lastTabsSignature) return
        lastTabsSignature = signature
        sessionList.removeAllViews()
        sessions.forEachIndexed { index, s ->
            val mine = m.isUserSession(s.id)
            val selected = s.id == current?.id
            val label = "${index + 1}: " + (if (mine) "" else "Jarvis · ") + s.id + if (s.isRunning) "  ●" else ""
            val row = TextView(this).apply {
                text = label
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(scheme.fg)
                if (selected) setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = if (selected) ColorDrawable(scheme.keyPressed) else null
                isClickable = true
                setOnClickListener { select(s); closeDrawer() }
                setOnLongClickListener { closeDrawer(); confirmCloseTab(s); true }
            }
            sessionList.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        menuButton.text = "☰ ${sessions.size.coerceAtLeast(1)}"
    }

    private fun confirmCloseTab(s: TerminalSession) {
        val m = manager ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Close ${s.id}?")
            .setMessage("Stops anything running in it and removes the session.")
            .setPositiveButton("Close") { _, _ ->
                m.close(s.id)
                if (current?.id == s.id) {
                    current = null
                    val next = m.list().firstOrNull { m.isUserSession(it.id) } ?: m.list().firstOrNull() ?: openNewTab(announce = true)
                    if (next != null) select(next) else outputView.text = ""
                }
                refreshTabs(force = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------------------------------
    // Running commands
    // ------------------------------------------------------------------------------------------

    /** `~`, `~/project`, or the Termux path, the way a shell shows it. */
    private fun promptText(s: TerminalSession): String {
        val rel = s.relativeCwd()
        return when {
            rel == "." || rel.isEmpty() -> "~"
            rel.startsWith("~") || rel.startsWith("/") -> rel
            else -> "~/$rel"
        }
    }

    private fun submit() {
        val m = manager ?: return
        val session = current ?: openNewTab(announce = true)?.also { select(it) } ?: return
        val command = input.text?.toString()?.trim().orEmpty()
        input.setText("")
        historyPos = -1
        if (selecting) setSelecting(false)
        if (command.isEmpty()) {
            // A bare Enter just gives a fresh prompt, as in any shell.
            session.note("\n${promptText(session)} $\n")
            return
        }

        if (command == "clear" || command == "cls") {
            m.clear(session.id)
            refreshOutput(forceBottom = true)
            return
        }
        if (command == "exit" || command == "logout") {
            confirmCloseTab(session)
            return
        }
        if (PackageCommand.handles(command)) { handlePackageCommand(session, command); return }
        val verdict = m.guard.classify(command)
        if (verdict.verdict == CommandGuard.Verdict.BLOCKED) {
            session.note("\n⛔ Refused: ${verdict.summary}. This command is never run.\n")
            return
        }
        session.preflight(command)?.let { problem ->
            session.note("\n⚠ $problem\n")
            return
        }
        session.note("\n${promptText(session)} $ $command\n")
        if (verdict.verdict == CommandGuard.Verdict.DESTRUCTIVE) session.note("⚠ ${verdict.summary}\n")

        if (backgroundMode) {
            val problem = session.startBackground(command)
            session.note(if (problem != null) "⚠ $problem\n" else "[started in the background, pid ${session.runningPid ?: "unknown"}]\n")
            return
        }
        // Runs on the app's own scope, so leaving this screen does not kill a long command.
        runScope.launch {
            val r = try { session.run(command, COMMAND_TIMEOUT_MS) } catch (e: Exception) { session.note("⚠ ${e.message ?: e.javaClass.simpleName}\n"); return@launch }
            val footer = when {
                r.rejected != null -> "⚠ ${r.rejected}"
                r.timedOut -> "[stopped: it ran longer than ${COMMAND_TIMEOUT_MS / 60_000} minutes]"
                r.cancelled -> "[stopped]"
                else -> "[exit code ${r.exitCode} · ${"%.1f".format(r.elapsedMs / 1000.0)} s]"
            }
            session.note(footer + "\n")
        }
    }

    /** `pkg install git`, `jpkg install gemini` and friends: turned into real installs in Termux. */
    private fun handlePackageCommand(session: TerminalSession, command: String) {
        val plan = PackageCommand.parse(command) ?: return
        session.note("\n${promptText(session)} $ $command\n")
        val installed = TermuxBackend.isInstalled(this)
        when (plan) {
            is PackagePlan.Show -> session.note(plan.text + "\n")
            is PackagePlan.Engine -> {
                if (plan.termux && !installed) { session.note(PackageCommand.termuxRequiredText(false) + "\n"); return }
                SwitchingBackend.setTermux(this, plan.termux)
                session.note(if (plan.termux)
                    "Engine: Termux. Commands now run in real Linux (git, node, npm, python, pkg).\nTermux needs allow-external-apps=true and the \"Run commands in Termux\" permission; if a command is refused, see: jpkg setup\nOutput streams live, like in the Termux app. Commands start in ~/jarvis-workspace inside Termux.\n"
                else "Engine: app shell (small, no installs).\n")
                refreshPanelButtons()
                refreshOutput(forceBottom = true)
            }
            is PackagePlan.Run -> {
                if (!installed) { session.note(PackageCommand.termuxRequiredText(false) + "\n"); return }
                if (!SwitchingBackend.useTermux(this)) {
                    // Installs only make sense in real Linux, so switch for the person instead of refusing.
                    SwitchingBackend.setTermux(this, true)
                    refreshPanelButtons()
                    session.note("Switched to the Termux engine so this runs for real, like in the Termux app.\n")
                }
                session.note(plan.summary + "\n(Live output below, like Termux. Large installs can take several minutes.)\n")
                runScope.launch {
                    val watcher = launch { watchInstall(session) }
                    try {
                        val r = try { session.run(plan.script, INSTALL_TIMEOUT_MS) } catch (e: Exception) { session.note("⚠ ${e.message ?: e.javaClass.simpleName}\n"); return@launch }
                        session.note(when {
                            r.rejected != null -> "⚠ ${r.rejected}"
                            r.timedOut -> "[stopped: it ran longer than ${INSTALL_TIMEOUT_MS / 60_000} minutes]"
                            r.cancelled -> "[stopped]"
                            r.exitCode == 0 -> "✔ finished [${"%.1f".format(r.elapsedMs / 1000.0)} s]"
                            else -> "✖ failed [exit code ${r.exitCode}]" + failureHint(r.exitCode)
                        } + "\n")
                    } finally { watcher.cancel() }
                }
            }
        }
    }

    /**
     * While an install runs: say so when Termux has not answered at all (the usual setup problems), and print a heartbeat during
     * long quiet stretches (downloads, builds) so it never looks frozen.
     */
    private suspend fun watchInstall(session: TerminalSession) {
        val started = System.currentTimeMillis()
        var seen = session.buffer.endOffset
        var silentFor = 0
        var heard = false      // did Termux print anything yet
        var warned = false
        while (true) {
            delay(5_000)
            val end = session.buffer.endOffset
            if (end != seen) { seen = end; silentFor = 0; heard = true; continue }
            silentFor += 5
            val total = (System.currentTimeMillis() - started) / 1000
            if (!heard) {
                if (!warned && total >= 20) {
                    warned = true
                    session.note("⚠ No answer from Termux after 20 seconds. Check:\n" +
                        "  1. Termux is installed from F-Droid or GitHub (the Play Store build does not work)\n" +
                        "  2. ~/.termux/termux.properties contains  allow-external-apps=true  (then restart Termux)\n" +
                        "  3. JARVIS has the \"Run commands in Termux environment\" permission (Android Settings > Apps > JARVIS > Permissions)\n" +
                        "  4. Termux is not battery-restricted: Settings > Apps > Termux > Battery > Unrestricted\n" +
                        "  Full steps: jpkg setup\n")
                    seen = session.buffer.endOffset
                } else if (warned && silentFor % 30 == 0) {
                    session.note("… still waiting for Termux (${total} s)\n"); seen = session.buffer.endOffset
                }
            } else if (silentFor % 30 == 0) {
                session.note("… still working (${total / 60} min ${total % 60} s). Downloads and builds can be quiet for a while.\n"); seen = session.buffer.endOffset
            }
        }
    }

    /** Plain-language help for the failures people actually hit. */
    private fun failureHint(code: Int?): String = when (code) {
        137 -> "\nAndroid killed the install (exit 137). On Android 12 and newer its \"phantom process killer\" stops long Termux jobs.\n" +
            "  Set Settings > Apps > Termux > Battery > Unrestricted, and once, over Wireless debugging from a computer, run:\n" +
            "  adb shell settings put global settings_enable_monitor_phantom_procs false\n" +
            "  Then run the same command again."
        else -> ""
    }

    /** A checklist of ready-made tools and AI command-line tools; the chosen ones are installed in one go. */
    private fun showToolPicker() {
        val items = PackageCommand.catalog
        val labels = items.map { "${it.title} - ${it.description}" }.toTypedArray()
        val chosen = BooleanArray(items.size)
        MaterialAlertDialogBuilder(this)
            .setTitle("Install tools")
            .setMultiChoiceItems(labels, chosen) { _, i, on -> chosen[i] = on }
            .setPositiveButton("Install") { _, _ ->
                val keys = items.filterIndexed { i, _ -> chosen[i] }.map { it.key }
                if (keys.isEmpty()) return@setPositiveButton
                input.setText("jpkg install " + keys.joinToString(" "))
                submit()
            }
            .setNeutralButton(if (SwitchingBackend.useTermux(this)) "Use app shell" else "Use Termux") { _, _ ->
                input.setText(if (SwitchingBackend.useTermux(this)) "jpkg engine app" else "jpkg engine termux")
                submit()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun recall(direction: Int) {
        val s = current ?: return
        val cmds = s.history.map { it.command }
        if (cmds.isEmpty()) return
        historyPos = when (direction) {
            -1 -> if (historyPos < 0) cmds.lastIndex else maxOf(0, historyPos - 1)
            else -> if (historyPos < 0) -1 else if (historyPos >= cmds.lastIndex) -1 else historyPos + 1
        }
        setInput(if (historyPos < 0) "" else cmds[historyPos])
    }

    private fun setInput(text: String) {
        suppressWatcher = true
        input.setText(text)
        input.setSelection(input.text?.length ?: 0)
        suppressWatcher = false
    }

    // ------------------------------------------------------------------------------------------
    // Keys: extra-keys bar, Ctrl / Alt, Tab completion
    // ------------------------------------------------------------------------------------------

    private fun insertText(text: String) {
        val e = input.text ?: return
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        e.replace(start, end, text)
    }

    private fun setCtrl(on: Boolean) { ctrlOn = on; styleKey(ctrlKey, on) }
    private fun setAlt(on: Boolean) { altOn = on; styleKey(altKey, on) }

    /** A letter typed while CTRL is on, as in a real terminal. */
    private fun ctrlChar(c: Char) {
        val text = input.text?.toString().orEmpty()
        val sel = input.selectionStart.coerceIn(0, text.length)
        when (c.lowercaseChar()) {
            'c' -> interrupt()
            'd' -> if (text.isEmpty()) current?.let { confirmCloseTab(it) } else if (sel < text.length) input.text?.delete(sel, sel + 1)
            'l' -> current?.let { manager?.clear(it.id); refreshOutput(true) }
            'a' -> input.setSelection(0)
            'e' -> input.setSelection(text.length)
            'u' -> input.text?.delete(0, sel)
            'k' -> input.text?.delete(sel, text.length)
            'w' -> {
                var i = sel
                while (i > 0 && text[i - 1] == ' ') i--
                while (i > 0 && text[i - 1] != ' ') i--
                input.text?.delete(i, sel)
            }
            'p' -> recall(-1)
            'n' -> recall(1)
            'b' -> input.setSelection((sel - 1).coerceAtLeast(0))
            'f' -> input.setSelection((sel + 1).coerceAtMost(text.length))
            'h' -> if (sel > 0) input.text?.delete(sel - 1, sel)
            'j', 'm' -> submit()
            else -> Unit
        }
    }

    /** A letter typed while ALT is on: word movement, otherwise the letter itself. */
    private fun altChar(c: Char) {
        val text = input.text?.toString().orEmpty()
        val sel = input.selectionStart.coerceIn(0, text.length)
        when (c.lowercaseChar()) {
            'b' -> { var i = sel; while (i > 0 && text[i - 1] == ' ') i--; while (i > 0 && text[i - 1] != ' ') i--; input.setSelection(i) }
            'f' -> { var i = sel; while (i < text.length && text[i] == ' ') i++; while (i < text.length && text[i] != ' ') i++; input.setSelection(i) }
            else -> insertText(c.toString())
        }
    }

    /** CTRL+C: stops what is running; on an empty prompt it just shows ^C and gives a fresh line. */
    private fun interrupt() {
        val s = current ?: return
        if (s.cancel()) { s.note("^C\n"); return }
        s.note("\n${promptText(s)} $ ${input.text}^C\n")
        setInput("")
    }

    /** Tab completion of file and folder names in the app shell. */
    private fun complete() {
        val m = manager ?: return
        val s = current ?: return
        if (SwitchingBackend.useTermux(this)) {
            Toast.makeText(this, "Tab completion works in the app shell. In Termux mode, type the full name.", Toast.LENGTH_SHORT).show()
            return
        }
        val text = input.text?.toString().orEmpty()
        val cursor = input.selectionStart.coerceIn(0, text.length)
        val before = text.substring(0, cursor)
        val wordStart = before.lastIndexOf(' ') + 1
        val word = before.substring(wordStart)
        val prefix = word.substringAfterLast('/')
        val dirPart = word.substringBeforeLast('/', "")
        val rel = s.relativeCwd()
        val base = if (rel == "." || rel.isEmpty()) m.workspace.root else File(m.workspace.root, rel)
        val dir = when {
            !word.contains('/') -> base
            word.startsWith("/") -> File(dirPart.ifEmpty { "/" })
            else -> File(base, dirPart)
        }
        val names = dir.list()?.filter { it.startsWith(prefix) && (prefix.startsWith(".") || !it.startsWith(".")) }?.sorted().orEmpty()
        if (names.isEmpty()) return
        val common = names.reduce { a, b -> a.commonPrefixWith(b) }
        val completed = when {
            names.size == 1 -> names[0] + if (File(dir, names[0]).isDirectory) "/" else " "
            else -> common
        }
        if (names.size > 1 && common.length <= prefix.length) {
            s.note("\n" + names.joinToString("  ") { if (File(dir, it).isDirectory) "$it/" else it } + "\n")
        }
        val keep = word.length - prefix.length
        val replaced = text.substring(0, wordStart + keep) + completed + text.substring(cursor)
        setInput(replaced)
        input.setSelection((wordStart + keep + completed.length).coerceAtMost(input.text?.length ?: 0))
    }

    private fun pageScroll(direction: Int) {
        scroll.smoothScrollBy(0, direction * (scroll.height - dp(48)))
    }

    private fun moveCursor(delta: Int) {
        val len = input.text?.length ?: 0
        input.setSelection((input.selectionStart + delta).coerceIn(0, len))
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
        // While text is being selected the output is frozen, otherwise every refresh would wipe the selection.
        if (selecting && !forceBottom) return
        val child = scroll.getChildAt(0)
        val atBottom = forceBottom || child == null || child.bottom - (scroll.height + scroll.scrollY) < dp(80)
        lastEnd = s.buffer.endOffset
        outputView.text = render(s.buffer.tail(MAX_SHOWN_CHARS))
        if (atBottom) scrollToBottom()
    }

    /**
     * Scrolls to the end WITHOUT touching keyboard focus. ScrollView.fullScroll(FOCUS_DOWN) also moves focus, which pulled it
     * out of the input box after the first typed letter; scrollTo never does.
     */
    private fun scrollToBottom() {
        scroll.post {
            val child = scroll.getChildAt(0) ?: return@post
            scroll.scrollTo(0, child.height)
        }
    }

    /**
     * Draws the output with real terminal colours: ANSI colour codes from the tools themselves (apt, npm, git, pip...) are
     * honoured, progress lines that rewrite themselves with carriage returns show only their latest state, and plain
     * lines get helpful colours (headings, errors, warnings, successes).
     */
    private fun render(text: String): CharSequence {
        if (text.isEmpty()) return text
        val sb = SpannableStringBuilder()
        val warn = 0xFFFFB86C.toInt()
        var fg = 0
        var bold = false
        var pos = 0
        while (pos <= text.length) {
            var e = text.indexOf('\n', pos)
            val hasNl = e >= 0
            if (e < 0) e = text.length
            var raw = text.substring(pos, e)
            if (raw.contains('\r')) raw = raw.trimEnd('\r').substringAfterLast('\r')
            val lineStart = sb.length
            // ANSI colour codes
            var hadAnsi = false
            var i = 0
            var segStart = sb.length
            var segFg = fg
            var segBold = bold
            fun closeSeg() {
                val end = sb.length
                if (end > segStart) {
                    if (segFg != 0) sb.setSpan(ForegroundColorSpan(segFg), segStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (segBold) sb.setSpan(StyleSpan(Typeface.BOLD), segStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                segStart = end
            }
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\u001B' && i + 1 < raw.length && raw[i + 1] == '[') {
                    val m = raw.indexOf('m', i + 2)
                    if (m > 0 && m - i < 24) {
                        hadAnsi = true
                        closeSeg()
                        for (code in raw.substring(i + 2, m).split(';').map { it.toIntOrNull() ?: 0 }.let { if (it.isEmpty()) listOf(0) else it }) {
                            when (code) {
                                0 -> { fg = 0; bold = false }
                                1 -> bold = true
                                22 -> bold = false
                                39 -> fg = 0
                                in 30..37 -> fg = ansi(code - 30, false)
                                in 90..97 -> fg = ansi(code - 90, true)
                            }
                        }
                        segFg = fg; segBold = bold
                        i = m + 1
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            closeSeg()
            val end = sb.length
            // plain lines get helpful colours
            if (!hadAnsi && end > lineStart) {
                val line = sb.substring(lineStart, end)
                val lower = line.lowercase()
                val color = when {
                    line[0] == '⚠' || line[0] == '✖' || line[0] == '⛔' -> scheme.error
                    line[0] == '✔' -> scheme.ok
                    line[0] == '[' || line[0] == 'ℹ' || line[0] == '^' -> scheme.dim
                    line.startsWith("==>") -> scheme.prompt
                    line.startsWith("E:") || lower.contains("npm err") || lower.contains("error:") || lower.contains("fatal:") || lower.contains("traceback") -> scheme.error
                    line.startsWith("W:") || lower.startsWith("warning") || lower.contains("npm warn") || lower.contains("deprecat") -> warn
                    line.startsWith("Get:") || line.startsWith("Hit:") || line.startsWith("Ign:") || line.startsWith("Fetched") || line.startsWith("Reading ") || line.startsWith("Building dependency") -> scheme.dim
                    line.startsWith("Unpacking") || line.startsWith("Setting up") || line.startsWith("Preparing to") || lower.startsWith("added ") || lower.contains("successfully installed") -> scheme.ok
                    else -> 0
                }
                if (color != 0) {
                    sb.setSpan(ForegroundColorSpan(color), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (line.startsWith("==>")) sb.setSpan(StyleSpan(Typeface.BOLD), lineStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    val idx = line.indexOf(" $ ")
                    if (idx in 0 until minOf(line.length, 90) && line.substring(0, idx).none { it == ' ' }) {
                        sb.setSpan(ForegroundColorSpan(scheme.prompt), lineStart, lineStart + idx + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.BOLD), lineStart, lineStart + idx + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            if (!hasNl) break
            sb.append('\n')
            pos = e + 1
        }
        return sb
    }

    /** The 16 terminal colours, tuned to read on dark backgrounds (and darkened on the light style). */
    private fun ansi(n: Int, bright: Boolean): Int {
        val dark = intArrayOf(0xFF3B4252.toInt(), 0xFFFF5C5C.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(), 0xFF6CA6FF.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFE5E9F0.toInt())
        val light = intArrayOf(0xFF6C7A89.toInt(), 0xFFFF8585.toInt(), 0xFF7CFF9B.toInt(), 0xFFFFFFA5.toInt(), 0xFF9CC2FF.toInt(), 0xFFFF9AD5.toInt(), 0xFFA4F2FF.toInt(), 0xFFFFFFFF.toInt())
        if (scheme.light) {
            val lt = intArrayOf(0xFF333333.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(), 0xFF9A6700.toInt(), 0xFF1565C0.toInt(), 0xFF8E24AA.toInt(), 0xFF00838F.toInt(), 0xFF555555.toInt())
            return lt[n.coerceIn(0, 7)]
        }
        return (if (bright) light else dark)[n.coerceIn(0, 7)]
    }

    private var shownPrompt = ""
    private var shownHint = ""
    private var shownAlpha = -1f

    /** Updates the prompt only when it changed: re-setting identical text every tick re-laid-out the page and yanked the scroll position. */
    private fun updatePrompt() {
        val s = current
        val label = if (s == null) "$ " else "${promptText(s)} $ "
        val running = s?.isRunning == true
        val alpha = if (running) 0.5f else 1f
        val hint = if (running) "running: ${s?.runningCommand?.take(40).orEmpty()}" else ""
        if (label != shownPrompt) { shownPrompt = label; promptLabel.text = label }
        if (alpha != shownAlpha) { shownAlpha = alpha; promptLabel.alpha = alpha }
        if (hint != shownHint) { shownHint = hint; input.hint = hint }
    }

    private fun tick() {
        // Safety net: the output text must never keep keyboard focus (except while the person is selecting it).
        if (!selecting && !drawerOpen && outputView.isFocused) input.requestFocus()
        val s = current
        if (s != null && s.buffer.endOffset != lastEnd) refreshOutput(forceBottom = false)
        updatePrompt()
        refreshTabs()
    }

    // ------------------------------------------------------------------------------------------
    // Menu, drawer, style
    // ------------------------------------------------------------------------------------------

    private fun transcript(): String = current?.buffer?.tail(MAX_SHOWN_CHARS).orEmpty()

    private fun copyOutput() {
        val text = transcript()
        if (text.isBlank()) { Toast.makeText(this, "Nothing to copy.", Toast.LENGTH_SHORT).show(); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jarvis Terminal", text))
        Toast.makeText(this, "Output copied.", Toast.LENGTH_SHORT).show()
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) { Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show(); return }
        insertText(text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' '))
        showKeyboard()
    }

    private fun shareTranscript() {
        val text = transcript().trim()
        if (text.isEmpty()) { Toast.makeText(this, "Nothing to share.", Toast.LENGTH_SHORT).show(); return }
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "Jarvis Terminal transcript") }
        runCatching { startActivity(Intent.createChooser(send, "Share transcript")) }
    }

    private fun sendToChat() {
        val s = current ?: return
        val text = s.buffer.tail(6_000).trim()
        if (text.isEmpty()) { Toast.makeText(this, "Nothing to send.", Toast.LENGTH_SHORT).show(); return }
        JarvisApp.instance.chatRepository.addTurn("Terminal output (${s.id})", "```\n$text\n```")
        Toast.makeText(this, "Added to the ChatBox.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Selection mode makes the output selectable (and therefore focusable). It is only on while the person is selecting and is
     * switched off again afterwards, so normal typing never loses focus to the output text.
     */
    private fun setSelecting(on: Boolean) {
        if (!on && !selecting) return
        selecting = on
        if (on) {
            outputView.setTextIsSelectable(true)
            Toast.makeText(this, "Long-press the text to select it. Tap anywhere else to finish.", Toast.LENGTH_SHORT).show()
        } else {
            outputView.clearFocus()
            outputView.setTextIsSelectable(false)
            input.requestFocus()
            refreshOutput(forceBottom = false)
        }
    }

    /** Copies the selected text, or all the output when nothing is selected. */
    private fun copySelectionOrAll() {
        val a = outputView.selectionStart
        val b = outputView.selectionEnd
        if (outputView.hasSelection() && a >= 0 && b > a) {
            val t = outputView.text.subSequence(minOf(a, b), maxOf(a, b)).toString()
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jarvis Terminal", t))
            Toast.makeText(this, "Copied.", Toast.LENGTH_SHORT).show()
        } else copyOutput()
    }

    private fun installSelectionActions() {
        outputView.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                menu.add(0, 7001, 90, "Paste")
                menu.add(0, 7002, 91, "Use as command")
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean = when (item.itemId) {
                7001 -> { mode.finish(); paste(); true }
                7002 -> {
                    val a = outputView.selectionStart; val b = outputView.selectionEnd
                    val t = if (a >= 0 && b > a) outputView.text.subSequence(minOf(a, b), maxOf(a, b)).toString() else ""
                    mode.finish()
                    insertText(t.replace("\r\n", " ").replace('\n', ' ').trim()); showKeyboard(); true
                }
                else -> false
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) {}
        }
    }

    private fun showContextMenu() {
        val items = arrayOf(
            "Select text", "Copy all output", "Paste", "Share transcript", "Reset (clear screen)", "Kill process",
            "Font bigger", "Font smaller", "Style…", if (keepScreenOn) "Keep screen on: ON" else "Keep screen on: OFF",
            "Install tools…", "Send to ChatBox", "Help"
        )
        MaterialAlertDialogBuilder(this).setItems(items) { _, which ->
            when (which) {
                0 -> setSelecting(true)
                1 -> copyOutput()
                2 -> paste()
                3 -> shareTranscript()
                4 -> current?.let { manager?.clear(it.id); refreshOutput(true) }
                5 -> interruptOrToast()
                6 -> setFont(fontSp + 1.5f, save = true)
                7 -> setFont(fontSp - 1.5f, save = true)
                8 -> showStylePicker()
                9 -> { keepScreenOn = !keepScreenOn; prefs.edit().putBoolean("keep_on", keepScreenOn).apply(); applyKeepScreenOn(); refreshPanelButtons() }
                10 -> showToolPicker()
                11 -> sendToChat()
                12 -> showHelp()
            }
        }.show()
    }

    private fun interruptOrToast() {
        val s = current ?: return
        if (s.cancel()) Toast.makeText(this, "Stopped.", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "Nothing is running in ${s.id}.", Toast.LENGTH_SHORT).show()
    }

    private fun showStylePicker() {
        val names = SCHEMES.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Style")
            .setSingleChoiceItems(names, SCHEMES.indexOf(scheme)) { d, i ->
                scheme = SCHEMES[i]
                prefs.edit().putInt("scheme", i).apply()
                applyScheme()
                d.dismiss()
            }
            .show()
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Jarvis Terminal")
            .setMessage(
                "Swipe in from the left edge (or tap ☰) for sessions.\n" +
                    "Long-press the screen for the menu. Pinch to change the font size.\n\n" +
                    "Extra keys: ESC clears the line · CTRL then C stops a command · CTRL+L clears · CTRL+A / E start / end of line · " +
                    "CTRL+U / K / W delete · TAB completes names · ↑ ↓ walk through history.\n" +
                    "Volume Down works as CTRL, like Termux.\n\n" +
                    "Install programs:  pkg install git nodejs\nAI tools:  jpkg ai"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        refreshTabs(force = true)
        refreshPanelButtons()
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(160).start()
        panel.visibility = View.VISIBLE
        panel.animate().translationX(0f).setDuration(180).start()
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        scrim.animate().alpha(0f).setDuration(140).withEndAction { scrim.visibility = View.GONE }.start()
        panel.animate().translationX(-panel.width.toFloat()).setDuration(160).withEndAction { panel.visibility = View.GONE }.start()
    }

    private fun showKeyboard() {
        input.requestFocus()
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toggleKeyboard() {
        val visible = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (visible) imm.hideSoftInputFromWindow(input.windowToken, 0) else showKeyboard()
    }

    private fun applyKeepScreenOn() {
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setFont(sp: Float, save: Boolean = false) {
        val clamped = sp.coerceIn(MIN_FONT, MAX_FONT)
        if (abs(clamped - fontSp) < 0.05f && !save) return
        fontSp = clamped
        applyFont()
        if (save) prefs.edit().putFloat("font_sp", fontSp).apply()
    }

    private fun applyFont() {
        outputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        promptLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
    }

    private fun applyScheme() {
        root.setBackgroundColor(scheme.bg)
        scroll.setBackgroundColor(scheme.bg)
        outputView.setTextColor(scheme.fg)
        promptLabel.setTextColor(scheme.prompt)
        input.setTextColor(scheme.fg)
        input.setHintTextColor(scheme.dim)
        keyScroll.setBackgroundColor(ColorUtils.blendARGB(scheme.bg, scheme.fg, 0.04f))
        for (k in keyViews) styleKey(k, (k === ctrlKey && ctrlOn) || (k === altKey && altOn))
        menuButton.setTextColor(scheme.fg)
        menuButton.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(scheme.key); alpha = 200 }
        panel.setBackgroundColor(scheme.panel)
        for (b in panelButtonViews) { b.setTextColor(scheme.fg); b.background = keyBackground(false) }
        window.statusBarColor = scheme.bg
        window.navigationBarColor = scheme.key
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = scheme.light
            isAppearanceLightNavigationBars = scheme.light
        }
        lastTabsSignature = ""
        refreshTabs(force = true)
        refreshOutput(forceBottom = false)
    }

    // ------------------------------------------------------------------------------------------
    // UI (built in code)
    // ------------------------------------------------------------------------------------------

    private fun buildUi(): View {
        root = FrameLayout(this)
        main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val types = if (handlesImeItself) {
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            } else {
                WindowInsetsCompat.Type.systemBars()
            }
            val bars = insets.getInsets(types)
            main.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            (menuButton.layoutParams as FrameLayout.LayoutParams).apply { topMargin = bars.top + dp(6); rightMargin = bars.right + dp(8) }
            menuButton.requestLayout()
            panel.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // Terminal: output text, then the prompt line, all in one scrolling page (like Termux).
        outputView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(6), dp(8), 0)
            setLineSpacing(0f, 1.0f)
            setTextIsSelectable(false)
        }
        promptLabel = TextView(this).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); text = "$ " }
        input = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            background = null
            setPadding(0, dp(2), dp(8), dp(2))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setHorizontallyScrolling(false)
            maxLines = 6
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when {
                    keyCode == KeyEvent.KEYCODE_ENTER -> { submit(); true }
                    keyCode == KeyEvent.KEYCODE_TAB -> { complete(); true }
                    event.isCtrlPressed && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> { ctrlChar('a' + (keyCode - KeyEvent.KEYCODE_A)); true }
                    keyCode == KeyEvent.KEYCODE_DPAD_UP -> { recall(-1); true }
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { recall(1); true }
                    else -> false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (suppressWatcher || s == null) return
                    // CTRL / ALT from the extra-keys bar apply to the next letter typed on the keyboard.
                    if ((ctrlOn || altOn) && before == 0 && count == 1 && start < s.length) {
                        val ch = s[start]
                        val ctrl = ctrlOn
                        post {
                            suppressWatcher = true
                            text?.delete(start, (start + 1).coerceAtMost(text?.length ?: 0))
                            suppressWatcher = false
                            setCtrl(false); setAlt(false)
                            if (ctrl) ctrlChar(ch) else altChar(ch)
                        }
                    }
                }
                override fun afterTextChanged(s: Editable?) { scrollToBottom() }
            })
        }
        val promptRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(8), 0, 0, dp(6))
            addView(promptLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(outputView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(promptRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        scroll = TermScroll(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(page) }
        main.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Extra keys: ONE slim row that scrolls sideways, so the keyboard never leaves the terminal squeezed.
        extraKeys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row = extraKeys
        row.addView(key("ESC") { setInput(""); setCtrl(false); setAlt(false) })
        row.addView(key("TAB") { complete() })
        ctrlKey = key("CTRL") { setCtrl(!ctrlOn) }
        altKey = key("ALT") { setAlt(!altOn) }
        row.addView(ctrlKey)
        row.addView(altKey)
        row.addView(key("↑", repeat = true) { recall(-1) })
        row.addView(key("↓", repeat = true) { recall(1) })
        row.addView(key("←", repeat = true) { moveCursor(-1) })
        row.addView(key("→", repeat = true) { moveCursor(1) })
        row.addView(key("/") { insertText("/") })
        row.addView(key("―") { insertText("-") })
        row.addView(key("HOME") { input.setSelection(0) })
        row.addView(key("END") { input.setSelection(input.text?.length ?: 0) })
        row.addView(key("PGUP", repeat = true) { pageScroll(-1) })
        row.addView(key("PGDN", repeat = true) { pageScroll(1) })
        row.addView(key("COPY") { copySelectionOrAll() })
        row.addView(key("PASTE") { paste() })
        row.addView(key("CLEAR") { current?.let { manager?.clear(it.id); refreshOutput(true) } })
        row.addView(key("STOP") { interruptOrToast() })
        row.addView(key("MENU") { showContextMenu() })
        keyScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(extraKeys, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        main.addView(keyScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        root.addView(main, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Small sessions button, top right (the left-edge swipe may clash with Android's back gesture).
        menuButton = TextView(this).apply {
            text = "☰ 1"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            alpha = 0.75f
            setOnClickListener { openDrawer() }
        }
        root.addView(menuButton, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = dp(30); rightMargin = dp(8) })

        // Sessions drawer.
        scrim = View(this).apply { setBackgroundColor(0x88000000.toInt()); visibility = View.GONE; setOnClickListener { closeDrawer() } }
        root.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; translationX = -dp(300).toFloat(); isClickable = true }
        sessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(this).apply { addView(sessionList) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        panelButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        panel.addView(panelButtons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        buildPanelButtons()
        root.addView(panel, FrameLayout.LayoutParams(dp(280), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        return root
    }

    private lateinit var bgButton: TextView
    private lateinit var engineButton: TextView
    private lateinit var keepButton: TextView

    private fun buildPanelButtons() {
        fun add(label: String, click: () -> Unit): TextView {
            val b = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isClickable = true
                setOnClickListener { click() }
            }
            panelButtonViews.add(b)
            panelButtons.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            return b
        }
        add("NEW SESSION") { closeDrawer(); openNewTab(announce = true)?.let { select(it) } }
        add("INSTALL TOOLS") { closeDrawer(); showToolPicker() }
        add("KEYBOARD") { closeDrawer(); ui.postDelayed({ toggleKeyboard() }, 220) }
        engineButton = add("") { closeDrawer(); current?.let { handlePackageCommand(it, if (SwitchingBackend.useTermux(this)) "jpkg engine app" else "jpkg engine termux") } }
        bgButton = add("") { backgroundMode = !backgroundMode; refreshPanelButtons() }
        keepButton = add("") { keepScreenOn = !keepScreenOn; prefs.edit().putBoolean("keep_on", keepScreenOn).apply(); applyKeepScreenOn(); refreshPanelButtons() }
        add("STYLE") { closeDrawer(); showStylePicker() }
        add("SEND TO CHATBOX") { closeDrawer(); sendToChat() }
        refreshPanelButtons()
    }

    private fun refreshPanelButtons() {
        if (!::engineButton.isInitialized) return
        engineButton.text = "ENGINE: " + if (SwitchingBackend.useTermux(this)) "TERMUX" else "APP SHELL"
        bgButton.text = "BACKGROUND: " + if (backgroundMode) "ON" else "OFF"
        keepButton.text = "KEEP SCREEN ON: " + if (keepScreenOn) "ON" else "OFF"
    }

    private fun keyRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
    }

    /** One extra key. Arrow and page keys repeat while held, like Termux. */
    private fun key(label: String, repeat: Boolean = false, action: () -> Unit): TextView {
        val v = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 3) 11f else 14f)
            isClickable = true
            minimumWidth = dp(52)
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val repeater = object : Runnable {
            override fun run() { action(); ui.postDelayed(this, 70) }
        }
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    action()
                    if (repeat) ui.postDelayed(repeater, 380)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.isPressed = false; ui.removeCallbacks(repeater); true }
                else -> true
            }
        }
        keyViews.add(v)
        return v
    }

    private fun keyBackground(active: Boolean): Drawable {
        fun pill(color: Int, stroke: Int) = android.graphics.drawable.InsetDrawable(GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(color)
            setStroke(dp(1), stroke)
        }, dp(2))
        val edge = ColorUtils.setAlphaComponent(scheme.fg, 38)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pill(scheme.keyPressed, ColorUtils.setAlphaComponent(scheme.prompt, 160)))
            addState(intArrayOf(), pill(if (active) scheme.keyActive else scheme.key, if (active) ColorUtils.setAlphaComponent(scheme.prompt, 160) else edge))
        }
    }

    private fun styleKey(k: TextView, active: Boolean) {
        k.setTextColor(scheme.fg)
        k.background = keyBackground(active)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------------------------------
    // Touch: left-edge swipe opens the drawer; hardware volume-down is CTRL
    // ------------------------------------------------------------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { edgeStartX = if (!drawerOpen && ev.x < dp(22)) ev.x else -1f; edgeStartY = ev.y }
            MotionEvent.ACTION_MOVE -> if (edgeStartX >= 0f && ev.x - edgeStartX > dp(56) && abs(ev.y - edgeStartY) < dp(70)) {
                edgeStartX = -1f
                openDrawer()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeStartX = -1f
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && ::ctrlKey.isInitialized) { setCtrl(!ctrlOn); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    /** The scrolling page: tap shows the keyboard, long-press opens the menu, two fingers pinch the font size. */
    private inner class TermScroll(context: Context) : ScrollView(context) {
        private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean { setFont(fontSp * detector.scaleFactor); return true }
            override fun onScaleEnd(detector: ScaleGestureDetector) { prefs.edit().putFloat("font_sp", fontSp).apply() }
        })
        private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { if (selecting) setSelecting(false); showKeyboard(); return false }
            override fun onLongPress(e: MotionEvent) { if (!selecting && !scale.isInProgress) this@JarvisTerminalActivity.showContextMenu() }
            // Tap anywhere to type; double-tap anywhere to paste; long-press for the menu (Select text lives there).
            override fun onDoubleTap(e: MotionEvent): Boolean { if (!scale.isInProgress) paste(); return false }
        })

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            scale.onTouchEvent(ev)
            taps.onTouchEvent(ev)
            return if (scale.isInProgress) true else super.dispatchTouchEvent(ev)
        }
    }

    override fun onDestroy() {
        outputJob?.cancel()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 15 * 60_000L
        const val INSTALL_TIMEOUT_MS = 40 * 60_000L
        const val MAX_SHOWN_CHARS = 60_000
        const val MIN_FONT = 8f
        const val MAX_FONT = 30f

        /** Commands run here, not on the screen's lifecycle, so closing the screen does not stop them. */
        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            // Never let a failed command take the whole app down.
            android.util.Log.w("JarvisTerminal", "command failed", e)
        })

        /** Builds a full style from six colours; the key and panel shades are derived from them. */
        private fun mk(name: String, bg: Long, fg: Long, prompt: Long, dim: Long, error: Long, ok: Long): Scheme {
            val b = bg.toInt(); val f = fg.toInt()
            return Scheme(name, b, f, prompt.toInt(), dim.toInt(), error.toInt(), ok.toInt(),
                ColorUtils.blendARGB(b, f, 0.08f), ColorUtils.blendARGB(b, f, 0.24f), ColorUtils.blendARGB(b, f, 0.36f), ColorUtils.blendARGB(b, f, 0.05f))
        }

        val SCHEMES: List<Scheme> = listOf(
            Scheme("Termux (default)", 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF8A8A8A.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFF111111.toInt(), 0xFF3A3A3A.toInt(), 0xFF555555.toInt(), 0xFF161616.toInt()),
            Scheme("Green on black", 0xFF000000.toInt(), 0xFF00FF41.toInt(), 0xFF00FF41.toInt(), 0xFF008F11.toInt(), 0xFFFF5555.toInt(), 0xFF00FF41.toInt(), 0xFF0A0F0A.toInt(), 0xFF1F3A1F.toInt(), 0xFF2E5A2E.toInt(), 0xFF0A120A.toInt()),
            Scheme("Solarized Dark", 0xFF002B36.toInt(), 0xFF839496.toInt(), 0xFF859900.toInt(), 0xFF586E75.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFF073642.toInt(), 0xFF0E4B5A.toInt(), 0xFF586E75.toInt(), 0xFF073642.toInt()),
            Scheme("Dracula", 0xFF282A36.toInt(), 0xFFF8F8F2.toInt(), 0xFF50FA7B.toInt(), 0xFF6272A4.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFF21222C.toInt(), 0xFF44475A.toInt(), 0xFF6272A4.toInt(), 0xFF21222C.toInt()),
            Scheme("Gruvbox", 0xFF282828.toInt(), 0xFFEBDBB2.toInt(), 0xFFB8BB26.toInt(), 0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFF1D2021.toInt(), 0xFF3C3836.toInt(), 0xFF665C54.toInt(), 0xFF1D2021.toInt()),
            Scheme("Jarvis", 0xFF0B0F14.toInt(), 0xFFD7E3EA.toInt(), 0xFF00E5FF.toInt(), 0xFF78909C.toInt(), 0xFFFF5252.toInt(), 0xFF69F0AE.toInt(), 0xFF121A22.toInt(), 0xFF263238.toInt(), 0xFF37474F.toInt(), 0xFF121A22.toInt()),
            mk("Tokyo Night", 0xFF1A1B26, 0xFFC0CAF5, 0xFF7AA2F7, 0xFF565F89, 0xFFF7768E, 0xFF9ECE6A),
            mk("Nord", 0xFF2E3440, 0xFFD8DEE9, 0xFF88C0D0, 0xFF616E88, 0xFFBF616A, 0xFFA3BE8C),
            mk("One Dark", 0xFF21252B, 0xFFABB2BF, 0xFF61AFEF, 0xFF5C6370, 0xFFE06C75, 0xFF98C379),
            mk("Monokai", 0xFF272822, 0xFFF8F8F2, 0xFFA6E22E, 0xFF75715E, 0xFFF92672, 0xFFA6E22E),
            mk("Cyberpunk", 0xFF0D0221, 0xFFE8D5FF, 0xFFFF2A6D, 0xFF7A5C99, 0xFFFF2A6D, 0xFF05FFA1),
            mk("Neon Purple", 0xFF120B22, 0xFFEBDDFF, 0xFFC77DFF, 0xFF7B6A99, 0xFFFF6B9D, 0xFF72F5C0),
            mk("Ocean", 0xFF06161F, 0xFFCDEBFF, 0xFF4FD8FF, 0xFF527A8F, 0xFFFF6E7F, 0xFF5CF2C0),
            mk("Sunset", 0xFF1F0F14, 0xFFFFE3D6, 0xFFFF9E64, 0xFF9C6F66, 0xFFFF5C7A, 0xFFFFD166),
            mk("Matrix", 0xFF000A00, 0xFF9CFFA0, 0xFF00FF66, 0xFF2F7A3A, 0xFFFF4D4D, 0xFF00FF66),
            mk("Crystal Mirror", 0xFF0A1220, 0xFFF2F9FF, 0xFF7FD4FF, 0xFF7C93AD, 0xFFFF7A8A, 0xFF7CF5D2),
            Scheme("Light", 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF666666.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(), 0xFFEEEEEE.toInt(), 0xFFCCCCCC.toInt(), 0xFFBBBBBB.toInt(), 0xFFF2F2F2.toInt(), light = true)
        )
    }
}
