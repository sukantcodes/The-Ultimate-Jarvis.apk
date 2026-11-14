package com.jarvis.assistant.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import com.jarvis.assistant.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** A colour from the current theme (for example the card or text colour), or [fallback] when the theme does not give one. */
internal fun themeColor(context: Context, attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    return if (context.theme.resolveAttribute(attr, tv, true) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) tv.data else fallback
}

private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** "42s", "5m 11s", "1h 02m": the running time shown next to the live verb. */
fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "%ds".format(s)
    }
}

/** Four dots in a 2x2 grid that swell and fade in turn, so a light seems to chase around the square. */
class DotsSpinnerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var animator: ValueAnimator? = null
    var dotColor: Int = 0xFF00E5FF.toInt()
        set(value) { field = value; invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startAnim() else stopAnim()
    }

    private fun startAnim() {
        if (animator != null || !isShown) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (18 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cell = width / 2f
        val baseR = cell * 0.32f
        // Top-left, top-right, bottom-right, bottom-left: the order the light travels in.
        val cx = floatArrayOf(cell * 0.5f, cell * 1.5f, cell * 1.5f, cell * 0.5f)
        val cy = floatArrayOf(cell * 0.5f, cell * 0.5f, cell * 1.5f, cell * 1.5f)
        for (i in 0 until 4) {
            val wave = 0.5f + 0.5f * cos(2.0 * PI * (phase - i * 0.25f)).toFloat()
            paint.color = dotColor
            paint.alpha = (80 + 175 * wave).toInt()
            canvas.drawCircle(cx[i], cy[i], baseR * (0.6f + 0.4f * wave), paint)
        }
    }
}

/**
 * The small round indicator floating above the reply box while Jarvis is working. Its three dots ripple, pull into
 * one dot, turn into a small square, then spread back out, over and over.
 */
class ThinkingPillView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.themeCardBg, 0xFF151A21.toInt()) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x33FFFFFF }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f
    private var animator: ValueAnimator? = null
    var dotColor: Int = 0xFF00E5FF.toInt()
        set(value) { field = value; invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startAnim() else stopAnim()
    }

    private fun startAnim() {
        if (animator != null || !isShown) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension(resolveSize((54 * d).toInt(), widthMeasureSpec), resolveSize((34 * d).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        stroke.strokeWidth = d
        rect.set(d, d, w - d, h - d)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fill)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, stroke)

        // Which part of the loop we are in.
        var spread = 1f      // 1 = three separate dots, 0 = pulled into one
        var square = 0f      // 1 = the single dot has become a square
        when {
            t < 0.50f -> {}
            t < 0.62f -> spread = 1f - smooth((t - 0.50f) / 0.12f)
            t < 0.80f -> { spread = 0f; square = smooth((t - 0.62f) / 0.18f) }
            t < 0.92f -> { spread = 0f; square = 1f }
            else -> { val u = smooth((t - 0.92f) / 0.08f); spread = u; square = 1f - u }
        }
        val r = 3.1f * d
        val gap = 9f * d
        val cx = w / 2f
        val cy = h / 2f
        dot.color = dotColor

        for (i in -1..1) {
            val bounce = max(0f, sin(PI * ((t * 3f - (i + 1) * 0.16f) % 1f).let { if (it < 0f) it + 1f else it }).toFloat())
            val lift = -2.6f * d * bounce * spread
            if (i == 0) {
                val half = r * (1f + 0.6f * (1f - spread))
                val corner = lerp(half, 2.2f * d, square)
                dot.alpha = 255
                rect.set(cx - half, cy + lift - half, cx + half, cy + lift + half)
                canvas.drawRoundRect(rect, corner, corner, dot)
            } else {
                dot.alpha = (255 * spread).toInt()
                if (dot.alpha > 0) canvas.drawCircle(cx + i * gap * spread, cy + lift, r, dot)
            }
        }
    }
}

/**
 * The header of the live activity panel above the reply box: the chasing-dots spinner, a verb that changes every few
 * seconds, the running time and a chevron that opens or closes the step list. Under it sits a slim progress bar with
 * a caption that fills smoothly. When the work ends it turns into "Worked for 1m 12s".
 */
class WorkActivityHeaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var onToggle: ((Boolean) -> Unit)? = null
    var expanded: Boolean = true
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var endedAt = 0L
    private var running = false
    private var verbIndex = 0
    private var accent = 0xFF00E5FF.toInt()

    private val spinner = DotsSpinnerView(context)
    private val doneMark = text(15f, bold = true).apply { visibility = GONE }
    private val verb = text(14f, bold = true)
    private val timer = text(13f)
    private val chevron = text(18f, bold = true).apply { text = "›" }

    private val progressBlock = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val caption = text(12f)
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0 }
    private val percent = text(11f, mono = true)
    private var shownProgress = 0f
    private var progressAnimator: ValueAnimator? = null

    private val verbTick = object : Runnable {
        override fun run() {
            if (!running) return
            nextVerb()
            handler.postDelayed(this, VERB_EVERY_MS)
        }
    }
    private val timeTick = object : Runnable {
        override fun run() {
            if (!running) return
            timer.text = formatElapsed(System.currentTimeMillis() - startedAt)
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        orientation = VERTICAL
        visibility = GONE
        setPadding(dp(14), dp(10), dp(14), dp(8))
        val primary = themeColor(context, R.attr.themeTextPrimary, 0xFFE0E6EA.toInt())
        val muted = themeColor(context, R.attr.themeTextSecondary, 0xFF90A4AE.toInt())
        verb.setTextColor(primary)
        timer.setTextColor(muted)
        chevron.setTextColor(muted)
        caption.setTextColor(muted)
        percent.setTextColor(muted)

        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(spinner, LayoutParams(dp(18), dp(18)))
        row.addView(doneMark, LayoutParams(dp(18), LayoutParams.WRAP_CONTENT))
        row.addView(verb, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        row.addView(timer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        row.addView(chevron, LayoutParams(dp(24), LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { setExpanded(!expanded, animate = true); onToggle?.invoke(expanded) }
        addView(row)

        bar.progressTintList = ColorStateList.valueOf(accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        progressBlock.addView(caption)
        val barRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        barRow.addView(bar, LayoutParams(0, dp(4), 1f))
        barRow.addView(percent, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        progressBlock.addView(barRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        addView(progressBlock, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        chevron.rotation = 90f
    }

    /** Starts the live display: spinner, verbs and timer. */
    fun startRun(accentColor: Int) {
        accent = accentColor
        spinner.dotColor = accentColor
        bar.progressTintList = ColorStateList.valueOf(accentColor)
        running = true
        startedAt = System.currentTimeMillis()
        endedAt = 0L
        visibility = VISIBLE
        alpha = 1f
        spinner.visibility = VISIBLE
        doneMark.visibility = GONE
        verbIndex = (System.nanoTime() % VERBS.size).toInt().coerceAtLeast(0)
        verb.text = VERBS[verbIndex]
        verb.alpha = 1f
        verb.translationY = 0f
        timer.text = formatElapsed(0)
        shownProgress = 0f
        bar.progress = 0
        handler.removeCallbacks(verbTick)
        handler.removeCallbacks(timeTick)
        handler.postDelayed(verbTick, VERB_EVERY_MS)
        handler.postDelayed(timeTick, 1000L)
    }

    /** Ends the live display and shows how long the work took. */
    fun finishRun(failed: Boolean) {
        if (!running && visibility != VISIBLE) return
        running = false
        endedAt = System.currentTimeMillis()
        handler.removeCallbacks(verbTick)
        handler.removeCallbacks(timeTick)
        spinner.visibility = GONE
        doneMark.visibility = VISIBLE
        doneMark.text = if (failed) "✕" else "✓"
        doneMark.setTextColor(if (failed) 0xFFFF6E6E.toInt() else 0xFF00E676.toInt())
        verb.text = if (failed) "Stopped after" else "Worked for"
        timer.text = formatElapsed(endedAt - startedAt)
        setProgress(null, null)
    }

    /** Hides the whole panel header. */
    fun reset() {
        running = false
        handler.removeCallbacks(verbTick)
        handler.removeCallbacks(timeTick)
        progressAnimator?.cancel()
        visibility = GONE
        progressBlock.visibility = GONE
    }

    /** A caption with a bar that fills smoothly towards [percent]; null hides it. */
    fun setProgress(percent: Int?, captionText: String?) {
        if (percent == null) {
            progressAnimator?.cancel()
            progressBlock.visibility = GONE
            return
        }
        progressBlock.visibility = VISIBLE
        caption.text = captionText ?: "Working on your request…"
        val target = percent.coerceIn(0, 100).toFloat()
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(shownProgress, target).apply {
            duration = 420
            addUpdateListener {
                shownProgress = it.animatedValue as Float
                bar.progress = (shownProgress * 10f).toInt()
                this@WorkActivityHeaderView.percent.text = "${shownProgress.toInt()}%"
            }
            start()
        }
    }

    fun setExpanded(value: Boolean, animate: Boolean) {
        expanded = value
        val target = if (value) 90f else 0f
        if (animate) chevron.animate().rotation(target).setDuration(180).start() else chevron.rotation = target
    }

    private fun nextVerb() {
        var next = (System.nanoTime() % VERBS.size).toInt().coerceAtLeast(0)
        if (next == verbIndex) next = (next + 1) % VERBS.size
        verbIndex = next
        val lift = dp(6).toFloat()
        verb.animate().alpha(0f).translationY(-lift).setDuration(160).withEndAction {
            verb.text = VERBS[verbIndex]
            verb.translationY = lift
            verb.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }.start()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(verbTick)
        handler.removeCallbacks(timeTick)
        progressAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun text(sp: Float, bold: Boolean = false, mono: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (mono) typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val VERB_EVERY_MS = 3200L
        val VERBS = listOf("Thinking", "Contemplating", "Honing", "Analyzing", "Crafting", "Assembling", "Refining", "Pondering", "Wiring up", "Building")
    }
}
