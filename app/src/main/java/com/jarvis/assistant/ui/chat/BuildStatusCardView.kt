package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.provider.BuildStage
import com.jarvis.assistant.provider.BuildStatus
import com.jarvis.assistant.util.ThemeManager

/**
 * The ChatBox status card: shows, step by step, whether a request was really sent to the provider and what is
 * happening now (Checking model → Sent to provider → Generating → Writing files → Done), with a running timer,
 * the number of tool steps and files, and Stop / Retry / Dismiss buttons. Built in code so it needs no layout file.
 */
class BuildStatusCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onStop: (() -> Unit)? = null
    var onRetry: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private var status = BuildStatus()
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (status.stage.isActive) {
                refreshTimer()
                ticker.postDelayed(this, 1000L)
            }
        }
    }

    private val title = text(13f, bold = true)
    private val timer = text(12f, mono = true)
    private val stagesRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val stagePills = STAGES.map { stage -> pill(stage.label.split(' ').first()).also { stagesRow.addView(it) } }
    private val detail = text(13f)
    private val info = text(11f)
    private val warning = text(12f, bold = true)
    private val buttons = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.END }
    private val btnStop = button("Stop") { onStop?.invoke() }
    private val btnRetry = button("Retry") { onRetry?.invoke() }
    private val btnClose = button("Dismiss") { onDismiss?.invoke() }

    init {
        orientation = VERTICAL
        visibility = GONE
        val pad = dp(12)
        setPadding(pad, dp(10), pad, dp(10))
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(timer)
        }
        addView(header)
        addView(stagesRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(detail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(info, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        addView(warning, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        buttons.addView(btnClose)
        buttons.addView(btnRetry)
        buttons.addView(btnStop)
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        title.setTextColor(Color.WHITE)
        timer.setTextColor(0xFFB0BEC5.toInt())
        detail.setTextColor(0xFFECEFF1.toInt())
        info.setTextColor(0xFF90A4AE.toInt())
        warning.setTextColor(0xFFFFC107.toInt())
    }

    fun bind(s: BuildStatus) {
        status = s
        if (!s.isVisible) {
            visibility = GONE
            ticker.removeCallbacks(tick)
            return
        }
        visibility = VISIBLE
        val accent = accent()
        val tone = when (s.stage) {
            BuildStage.DONE -> SUCCESS
            BuildStage.FAILED -> ERROR
            BuildStage.STOPPED -> 0xFFFFB300.toInt()
            else -> accent
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xE6101820.toInt())
            setStroke(dp(1), (tone and 0x00FFFFFF) or 0x99000000.toInt())
        }
        title.text = when (s.stage) {
            BuildStage.DONE -> "✓ Build finished"
            BuildStage.FAILED -> "✕ Build failed"
            BuildStage.STOPPED -> "■ Build stopped"
            else -> "● Build status"
        }
        title.setTextColor(tone)
        detail.text = s.message.ifBlank { s.stage.label }
        info.text = buildInfo(s)
        paintStages(s, tone)
        refreshTimer()

        val notSent = s.stage == BuildStage.CHECKING && !s.requestSent && System.currentTimeMillis() - s.startedAt > NOT_SENT_WARN_MS
        warning.visibility = if (notSent || (s.stage.isFinished && !s.requestSent && s.stage != BuildStage.DONE)) VISIBLE else GONE
        warning.text = "Nothing has been sent to the provider yet."

        btnStop.visibility = if (s.stage.isActive) VISIBLE else GONE
        btnRetry.visibility = if (s.stage == BuildStage.FAILED || s.stage == BuildStage.STOPPED) VISIBLE else GONE
        btnClose.visibility = if (s.stage.isFinished) VISIBLE else GONE

        ticker.removeCallbacks(tick)
        if (s.stage.isActive) ticker.postDelayed(tick, 1000L)
    }

    override fun onDetachedFromWindow() {
        ticker.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun refreshTimer() {
        val total = status.elapsedMs(System.currentTimeMillis()) / 1000
        timer.text = "%02d:%02d".format(total / 60, total % 60)
        // The warning appears by itself if the request still has not left after a few seconds.
        if (status.stage == BuildStage.CHECKING && !status.requestSent && System.currentTimeMillis() - status.startedAt > NOT_SENT_WARN_MS) warning.visibility = VISIBLE
    }

    private fun buildInfo(s: BuildStatus): String {
        val parts = ArrayList<String>()
        if (s.provider.isNotBlank()) parts += s.provider
        if (s.model.isNotBlank()) parts += s.model
        if (s.toolSteps > 0) parts += "${s.toolSteps} step${if (s.toolSteps == 1) "" else "s"}"
        if (s.filesWritten.isNotEmpty()) parts += "${s.filesWritten.size} file${if (s.filesWritten.size == 1) "" else "s"}"
        return parts.joinToString("  ·  ")
    }

    private fun paintStages(s: BuildStatus, tone: Int) {
        // On a failure the pill that was active turns red; on success all of them are green.
        val reached = when (s.stage) {
            BuildStage.CHECKING -> 0
            BuildStage.SENT -> 1
            BuildStage.GENERATING -> 2
            BuildStage.WRITING -> 3
            BuildStage.DONE -> 4
            BuildStage.FAILED, BuildStage.STOPPED -> when {
                s.filesWritten.isNotEmpty() -> 3
                s.toolSteps > 0 -> 2
                s.requestSent -> 1
                else -> 0
            }
            else -> -1
        }
        for ((i, p) in stagePills.withIndex()) {
            val state = when {
                s.stage == BuildStage.DONE -> 2
                i < reached -> 2
                i == reached && s.stage.isActive -> 1
                i == reached -> 3
                else -> 0
            }
            val (fill, ink) = when (state) {
                2 -> (SUCCESS and 0x00FFFFFF or 0x33000000) to SUCCESS
                1 -> (tone and 0x00FFFFFF or 0x44000000) to tone
                3 -> (tone and 0x00FFFFFF or 0x44000000) to tone
                else -> 0x22FFFFFF to 0xFF78909C.toInt()
            }
            p.text = (if (state == 2) "✓ " else "") + STAGES[i].label.split(' ').first()
            p.setTextColor(ink)
            p.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(fill) }
        }
    }

    private fun accent(): Int = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { 0xFF00E5FF.toInt() }

    private fun text(sp: Float, bold: Boolean = false, mono: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (mono) typeface = Typeface.MONOSPACE
    }

    private fun pill(label: String) = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(3), dp(8), dp(3))
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(5) }
        maxLines = 1
    }

    private fun button(label: String, click: () -> Unit) = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x33FFFFFF) }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        visibility = View.GONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val STAGES = listOf(BuildStage.CHECKING, BuildStage.SENT, BuildStage.GENERATING, BuildStage.WRITING, BuildStage.DONE)
        const val SUCCESS = 0xFF00E676.toInt()
        const val ERROR = 0xFFFF5252.toInt()
        const val NOT_SENT_WARN_MS = 8_000L
    }
}
