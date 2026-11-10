package com.jarvis.assistant.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Hosts the separate coding-agent session inside the existing JARVIS ChatBox surface. */
internal object ClozedCodingAgentDialog {
    fun show(context: Context) {
                val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun color(attr: Int, fallback: Int): Int {
            val value = android.util.TypedValue()
            return if (context.theme.resolveAttribute(attr, value, true)) {
                if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
            } else fallback
        }
        val primary = color(com.jarvis.assistant.R.attr.themeTextPrimary, 0xfff4f7fb.toInt())
        val secondary = color(com.jarvis.assistant.R.attr.themeTextSecondary, 0xffaab5c4.toInt())
        val accent = color(com.jarvis.assistant.R.attr.themeAccent, 0xff55b9ff.toInt())
        val card = color(com.jarvis.assistant.R.attr.themeCardBg, 0xff182330.toInt())
        val border = color(com.jarvis.assistant.R.attr.themeCardBorder, 0xff34485e.toInt())

        val dialog = BottomSheetDialog(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val handler = Handler(Looper.getMainLooper())
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xff0b111a.toInt())
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val heading = TextView(context).apply {
            text = "CLOZED CODING AGENT"
            textSize = 15f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        val status = TextView(context).apply {
            text = "● READY"
            textSize = 10f
            setTextColor(color(com.jarvis.assistant.R.attr.themeAccent1, 0xff55d69e.toInt()))
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        top.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(status)
        header.addView(top)
        val pathLabel = TextView(context).apply {
            textSize = 10f
            setTextColor(secondary)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(8))
        }
        header.addView(pathLabel)
        var engineRef: ClozedCodingAgentEngine? = null
        var renderSession: () -> Unit = {}
        var submitInput: () -> Unit = {}
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        fun action(label: String, click: () -> Unit): TextView = TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(primary)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(card, border, dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        val modeButton = action("BUILD", {})
        val sessionsButton = action("SESSIONS") { engineRef?.let { showSessions(context, it, renderSession) } }
        val newButton = action("NEW") { engineRef?.newSession() }
        val undoButton = action("UNDO") { engineRef?.undo() }
        val folderButton = action("PROJECT") {
            val projectPath = engineRef?.projectPath().orEmpty()
            MaterialAlertDialogBuilder(context)
                .setTitle("Coding project folder")
                .setMessage(projectPath)
                .setNegativeButton("Close", null)
                .setPositiveButton("Copy path") { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Coding project folder", projectPath))
                }
                .show()
        }
        val providerButton = action("ROUTE") {
            runCatching { context.startActivity(Intent(context, com.jarvis.assistant.ui.settings.WorkRoutingActivity::class.java)) }
        }
        listOf(modeButton, sessionsButton, newButton, undoButton, folderButton, providerButton).forEachIndexed { index, view ->
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (index > 0) params.marginStart = dp(6)
            actions.addView(view, params)
        }
        header.addView(actions)
        root.addView(header)

        val divider = View(context).apply { setBackgroundColor(border) }
        root.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(8) })

        val transcriptScroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(12))
        }
        val transcript = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        transcriptScroll.addView(transcript, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(transcriptScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val plan = TextView(context).apply {
            textSize = 10f
            setTextColor(secondary)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        root.addView(plan)
        val composer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(card, border, dp(16))
        }
        val input = EditText(context).apply {
            hint = "Ask JARVIS to inspect or change this project…"
            setTextColor(primary)
            setHintTextColor(secondary)
            textSize = 14f
            minLines = 1
            maxLines = 5
            gravity = Gravity.TOP
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        composer.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val controls = LinearLayout(context).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val stop = action("STOP") { engineRef?.interrupt() }.apply { visibility = View.GONE }
        val send = action("SEND  ↑") { submitInput() }
        controls.addView(stop)
        controls.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        composer.addView(controls)
        root.addView(composer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        var activeAssistant: TextView? = null
        val toolViews = LinkedHashMap<String, TextView>()
        var running = false
        var currentMode = "build"

        fun scrollBottom() { transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) } }
        fun addText(text: CharSequence, background: android.graphics.drawable.Drawable?, align: Int = Gravity.START, textColor: Int = primary) {
            val view = TextView(context).apply {
                this.text = text
                textSize = 14f
                setTextColor(textColor)
                setPadding(dp(13), dp(10), dp(13), dp(10))
                this.background = background
                gravity = align
                setTextIsSelectable(true)
            }
            transcript.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
                if (align == Gravity.END) leftMargin = dp(38) else rightMargin = dp(18)
            })
            scrollBottom()
        }
        fun addUser(text: String) {
            addText(text, rounded(color(com.jarvis.assistant.R.attr.themeAccent, 0xff205e9b.toInt()), border, dp(14)), Gravity.END, color(com.jarvis.assistant.R.attr.themeTextPrimary, 0xffffffff.toInt()))
            activeAssistant = null
        }
        fun addAssistant(text: String) {
            val rendered = MarkdownMessageRenderer.render(context, text)
            addText(rendered, rounded(card, border, dp(14)), Gravity.START, primary)
            activeAssistant = transcript.getChildAt(transcript.childCount - 1) as? TextView
        }
        fun addNotice(text: String) {
            addText(text, rounded(0xff25303d.toInt(), border, dp(10)), Gravity.CENTER, secondary)
            activeAssistant = null
        }
        renderSession = {
            transcript.removeAllViews()
            toolViews.clear()
            activeAssistant = null
            engineRef?.currentSession()?.messages?.forEach { item ->
                when (item.role) {
                    "user" -> addUser(item.text)
                    "assistant" -> if (item.text.isNotBlank()) addAssistant(item.text)
                    "tool" -> {
                        val v = TextView(context).apply {
                            text = "▸ " + item.toolName + "\n" + item.text.take(3000)
                            textSize = 11f
                            typeface = Typeface.MONOSPACE
                            setTextColor(secondary)
                            setPadding(dp(10), dp(8), dp(10), dp(8))
                            background = rounded(0xff111a24.toInt(), border, dp(10))
                        }
                        transcript.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
                        toolViews[item.callId] = v
                    }
                }
            }
            pathLabel.text = "PROJECT  " + engineRef?.projectPath().orEmpty()
            scrollBottom()
        }

        engineRef = ClozedCodingAgentEngine(
            context = context,
            emit = { event -> handler.post {
                when (event.type) {
                    "reload" -> renderSession()
                    "user" -> addUser(event.text)
                    "text" -> {
                        val view = activeAssistant ?: run {
                            addAssistant("")
                            activeAssistant
                        }
                        view?.append(event.text)
                        scrollBottom()
                    }
                    "reasoning" -> {
                        val reasoning = TextView(context).apply {
                            text = "THINKING  ·  " + event.text
                            textSize = 10f
                            setTextColor(secondary)
                            setPadding(dp(8), dp(5), dp(8), dp(5))
                            setOnClickListener { text = if (text.startsWith("THINKING")) event.text else "THINKING  ·  " + event.text }
                        }
                        transcript.addView(reasoning)
                        scrollBottom()
                    }
                    "tool_start" -> {
                        val view = TextView(context).apply {
                            text = "◌  " + event.name + "  ·  RUNNING\n" + event.input.take(700)
                            textSize = 11f
                            typeface = Typeface.MONOSPACE
                            setTextColor(accent)
                            setPadding(dp(10), dp(8), dp(10), dp(8))
                            background = rounded(0xff111a24.toInt(), border, dp(10))
                        }
                        transcript.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
                        toolViews[event.id] = view
                        activeAssistant = null
                        scrollBottom()
                    }
                    "tool_end" -> {
                        val view = toolViews[event.id]
                        val collapsed = (if (event.ok) "✓  " else "⚠  ") + event.name + "  ·  " + (if (event.ok) "DONE" else "FAILED") + "  ·  Tap to expand"
                        val expanded = event.name + "\n" + event.output.take(5000)
                        view?.text = collapsed
                        view?.tag = expanded
                        view?.setOnClickListener { card ->
                            val target = card as TextView
                            target.text = if (target.text == collapsed) expanded else collapsed
                        }
                        view?.setTextColor(if (event.ok) secondary else 0xffffb36b.toInt())
                        scrollBottom()
                    }
                    "notice" -> addNotice(event.text)
                    "state" -> {
                        running = event.text.isNotBlank()
                        status.text = if (running) "● " + event.text.uppercase() else "● READY"
                        stop.visibility = if (running) View.VISIBLE else View.GONE
                        send.isEnabled = !running
                    }
                    "todos" -> {
                        plan.text = if (event.text.isBlank()) "" else "PLAN\n" + event.text
                        plan.visibility = if (event.text.isBlank()) View.GONE else View.VISIBLE
                    }
                    "sessions" -> engineRef?.let { showSessions(context, it, renderSession) }
                }
            } },
            askQuestion = { question, options -> askQuestion(context, question, options) },
            openProviderSettings = {
                runCatching { context.startActivity(Intent(context, com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java)) }
            }
        )
        pathLabel.text = "PROJECT  " + engineRef?.projectPath().orEmpty()
        modeButton.setOnClickListener {
            currentMode = if (currentMode == "build") "plan" else "build"
            modeButton.text = currentMode.uppercase()
            engineRef?.setAgent(currentMode)
        }
        sessionsButton.setOnClickListener { engineRef?.let { showSessions(context, it, renderSession) } }
        newButton.setOnClickListener { engineRef?.newSession() }
        undoButton.setOnClickListener { engineRef?.undo() }
        providerButton.setOnClickListener { runCatching { context.startActivity(Intent(context, com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java)) } }
        send.setOnClickListener { submitInput() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submitInput(); true } else false
        }
        submitInput = submit@ {
            val value = input.text?.toString().orEmpty()
            if (value.isBlank() || running) return@submit
            input.setText("")
            engineRef?.let { agent -> scope.launch { agent.submit(value) } }
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { scope.cancel() }
        dialog.setOnShowListener {
            dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (context.resources.displayMetrics.heightPixels * 0.94f).toInt())
        renderSession()
    }

    private fun showSessions(context: Context, engine: ClozedCodingAgentEngine, onLoaded: () -> Unit) {
        val sessions = engine.sessions()
        if (sessions.isEmpty()) {
            MaterialAlertDialogBuilder(context).setMessage("No saved coding sessions yet.").setPositiveButton("OK", null).show()
            return
        }
        val labels = sessions.map { it.title + "  ·  " + it.provider + " / " + it.model }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle("Coding sessions")
            .setItems(labels) { _, which -> if (engine.openSession(sessions[which].id)) onLoaded() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun askQuestion(context: Context, question: String, options: List<String>): String? =
        suspendCancellableCoroutine { continuation: CancellableContinuation<String?> ->
            val show = {
                val builder = MaterialAlertDialogBuilder(context).setTitle("JARVIS needs your input").setMessage(question)
                    .setNegativeButton("Cancel") { _, _ -> if (continuation.isActive) continuation.resume(null) }
                if (options.isNotEmpty()) {
                    builder.setItems(options.toTypedArray()) { _, which -> if (continuation.isActive) continuation.resume(options[which]) }
                } else {
                    val input = EditText(context).apply { hint = "Your answer" }
                    builder.setView(input).setPositiveButton("Submit") { _, _ -> if (continuation.isActive) continuation.resume(input.text?.toString()) }
                }
                val dialog = builder.create()
                dialog.setOnCancelListener { if (continuation.isActive) continuation.resume(null) }
                continuation.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) show() else Handler(Looper.getMainLooper()).post { show() }
        }

    private fun rounded(fill: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); setStroke(1, stroke) }
}
