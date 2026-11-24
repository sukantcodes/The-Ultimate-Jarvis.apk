package com.jarvis.assistant.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.CycleInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.NestedScrollView
import com.jarvis.assistant.R
import com.jarvis.assistant.provider.ActivityStep
import com.jarvis.assistant.provider.StepState
import com.jarvis.assistant.provider.TurnActivity
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ------------------------------------------------------------------------------------------------
// Small helpers
// ------------------------------------------------------------------------------------------------

/** A colour from the current theme (for example the card or text colour), or [fallback] when the theme does not give one. */
internal fun themeColor(context: Context, attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    return if (context.theme.resolveAttribute(attr, tv, true) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) tv.data else fallback
}

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

private fun density(v: View) = v.resources.displayMetrics.density

/** Grows or shrinks a view's height smoothly; the view goes back to wrap-content (or GONE) at the end. */
private fun animateHeight(view: View, from: Int, to: Int, hideAtEnd: Boolean, durationMs: Long = 220) {
    ValueAnimator.ofInt(from, to).apply {
        duration = durationMs
        interpolator = DecelerateInterpolator()
        addUpdateListener { va ->
            val lp = view.layoutParams
            lp.height = va.animatedValue as Int
            view.layoutParams = lp
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                val lp = view.layoutParams
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                view.layoutParams = lp
                if (hideAtEnd) view.visibility = View.GONE
            }
        })
        start()
    }
}

// ------------------------------------------------------------------------------------------------
// Orbit spinner: a glowing arc circling a ring with a pulsing core. It turns into a drawn check or cross at the end.
// ------------------------------------------------------------------------------------------------

class OrbitSpinnerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Mode { RUNNING, DONE, FAILED }

    var accent: Int = 0xFF00E5FF.toInt()
        set(value) { field = value; rebuildShader(); invalidate() }

    private var mode = Mode.RUNNING
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val box = RectF()
    private val checkPath = Path()
    private val crossA = Path()
    private val crossB = Path()
    private val seg = Path()
    private val overshoot = OvershootInterpolator(2.2f)
    private var angle = 0f
    private var pulse = 0f
    private var resultT = 1f
    private var spin: ValueAnimator? = null
    private var result: ValueAnimator? = null

    fun setMode(newMode: Mode, animate: Boolean) {
        mode = newMode
        result?.cancel()
        if (newMode == Mode.RUNNING) {
            resultT = 1f
            startSpin()
        } else {
            stopSpin()
            if (animate && isShown) {
                result = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 460
                    addUpdateListener { resultT = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else {
                resultT = 1f
            }
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode == Mode.RUNNING) startSpin()
    }

    override fun onDetachedFromWindow() {
        stopSpin()
        result?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && mode == Mode.RUNNING) startSpin() else if (visibility != VISIBLE) stopSpin()
    }

    private fun startSpin() {
        if (spin != null || !isShown) return
        spin = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                angle = f * 360f
                pulse = 0.5f + 0.5f * sin(2.0 * PI * f * 2.0).toFloat()
                invalidate()
            }
            start()
        }
    }

    private fun stopSpin() {
        spin?.cancel()
        spin = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (22 * density(this)).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = min(w, h).toFloat()
        ring.strokeWidth = s * 0.11f
        arc.strokeWidth = s * 0.14f
        mark.strokeWidth = s * 0.12f
        val pad = arc.strokeWidth / 2f
        box.set(pad, pad, w - pad, h - pad)
        checkPath.reset()
        checkPath.moveTo(w * 0.28f, h * 0.53f)
        checkPath.lineTo(w * 0.44f, h * 0.69f)
        checkPath.lineTo(w * 0.74f, h * 0.35f)
        crossA.reset()
        crossA.moveTo(w * 0.33f, h * 0.33f)
        crossA.lineTo(w * 0.67f, h * 0.67f)
        crossB.reset()
        crossB.moveTo(w * 0.67f, h * 0.33f)
        crossB.lineTo(w * 0.33f, h * 0.67f)
        rebuildShader()
    }

    private fun rebuildShader() {
        if (width == 0 || height == 0) return
        arc.shader = SweepGradient(width / 2f, height / 2f, intArrayOf(accent and 0x00FFFFFF, accent), floatArrayOf(0f, 0.75f))
    }

    private fun drawPartial(canvas: Canvas, path: Path, t: Float) {
        val pm = PathMeasure(path, false)
        seg.reset()
        pm.getSegment(0f, pm.length * t.coerceIn(0f, 1f), seg, true)
        canvas.drawPath(seg, mark)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (mode == Mode.RUNNING) {
            ring.color = accent
            ring.alpha = 46
            canvas.drawOval(box, ring)
            canvas.save()
            canvas.rotate(angle, cx, cy)
            canvas.drawArc(box, 0f, 270f, false, arc)
            canvas.restore()
            core.color = accent
            core.alpha = (150 + 105 * pulse).toInt()
            canvas.drawCircle(cx, cy, min(width, height) * 0.13f * (0.8f + 0.4f * pulse), core)
        } else {
            val tone = if (mode == Mode.DONE) 0xFF00E676.toInt() else 0xFFFF6E6E.toInt()
            val sc = overshoot.getInterpolation(resultT)
            canvas.save()
            canvas.scale(sc, sc, cx, cy)
            ring.color = tone
            ring.alpha = (60 + 120 * resultT).toInt()
            canvas.drawOval(box, ring)
            mark.color = tone
            if (mode == Mode.DONE) {
                drawPartial(canvas, checkPath, resultT)
            } else {
                drawPartial(canvas, crossA, resultT)
                drawPartial(canvas, crossB, resultT)
            }
            canvas.restore()
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Shimmer text: a bright band sweeps across the text while something is running.
// ------------------------------------------------------------------------------------------------

class ShimmerTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {
    var shimmering: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) start() else stop()
            invalidate()
        }
    var shimmerColor: Int = 0xFFFFFFFF.toInt()

    private val shaderMatrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderBase = 0
    private var shaderBand = 0f
    private var shaderHighlight = 0
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (shimmering) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (animator != null || !isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1700
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        paint.shader = null
    }

    override fun onDraw(canvas: Canvas) {
        if (shimmering && width > 0) {
            val base = currentTextColor
            val band = max(width * 0.55f, 48f)
            if (shader == null || shaderBase != base || shaderBand != band || shaderHighlight != shimmerColor) {
                shader = LinearGradient(0f, 0f, band, 0f, intArrayOf(base, shimmerColor, base), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                shaderBase = base
                shaderBand = band
                shaderHighlight = shimmerColor
            }
            shaderMatrix.setTranslate(phase * (width + band) - band, 0f)
            shader?.setLocalMatrix(shaderMatrix)
            paint.shader = shader
        } else {
            paint.shader = null
        }
        super.onDraw(canvas)
    }
}

// ------------------------------------------------------------------------------------------------
// A thin line under the running step with a light that scans across it.
// ------------------------------------------------------------------------------------------------

class ScanLineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var color: Int = 0xFF00E5FF.toInt()
        set(value) { field = value; shader = null; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()
    private var shader: LinearGradient? = null
    private var shaderWidth = 0f
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (animator != null || !isShown) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f) return
        paint.shader = null
        paint.color = color
        paint.alpha = 28
        canvas.drawRect(0f, 0f, w, height.toFloat(), paint)
        val band = w * 0.4f
        if (shader == null || shaderWidth != band) {
            shader = LinearGradient(0f, 0f, band, 0f, intArrayOf(color and 0x00FFFFFF, color, color and 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            shaderWidth = band
        }
        shaderMatrix.setTranslate(phase * (w + band) - band, 0f)
        shader?.setLocalMatrix(shaderMatrix)
        paint.shader = shader
        paint.alpha = 255
        canvas.drawRect(0f, 0f, w, height.toFloat(), paint)
    }
}

// ------------------------------------------------------------------------------------------------
// Glow progress bar: a gradient fill with a halo and a moving highlight. It fills smoothly.
// ------------------------------------------------------------------------------------------------

class GlowProgressBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var accent: Int = 0xFF00E5FF.toInt()
        set(value) { field = value; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val shineMatrix = Matrix()
    private var shown = 0f
    private var sweep = 0f
    private var fillShader: LinearGradient? = null
    private var shineShader: LinearGradient? = null
    private var lastFillWidth = -1f
    private var lastFillAccent = 0
    private var lastBand = -1f
    private var progressAnimator: ValueAnimator? = null
    private var sweepAnimator: ValueAnimator? = null

    fun setPercent(percent: Int) {
        val target = percent.coerceIn(0, 100).toFloat()
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 480
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    val percentShown: Int get() = shown.toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startSweep()
    }

    override fun onDetachedFromWindow() {
        sweepAnimator?.cancel()
        sweepAnimator = null
        progressAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startSweep() else { sweepAnimator?.cancel(); sweepAnimator = null }
    }

    private fun startSweep() {
        if (sweepAnimator != null || !isShown) return
        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(0, widthMeasureSpec), resolveSize((8 * density(this)).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bar = h * 0.5f
        val top = (h - bar) / 2f
        val r = bar / 2f
        track.color = accent
        track.alpha = 36
        rect.set(0f, top, w, top + bar)
        canvas.drawRoundRect(rect, r, r, track)
        val fw = w * shown / 100f
        if (fw <= 0.5f) return
        val light = ColorUtils.blendARGB(accent, Color.WHITE, 0.45f)
        halo.color = accent
        halo.alpha = 48
        rect.set(0f, top - h * 0.18f, fw, top + bar + h * 0.18f)
        canvas.drawRoundRect(rect, r * 1.6f, r * 1.6f, halo)
        if (fillShader == null || lastFillWidth != fw || lastFillAccent != accent) {
            fillShader = LinearGradient(0f, 0f, max(fw, 1f), 0f, accent, light, Shader.TileMode.CLAMP)
            lastFillWidth = fw
            lastFillAccent = accent
        }
        fill.shader = fillShader
        rect.set(0f, top, fw, top + bar)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.save()
        canvas.clipRect(rect)
        val band = max(fw * 0.35f, 24f)
        if (shineShader == null || lastBand != band) {
            shineShader = LinearGradient(0f, 0f, band, 0f, intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            lastBand = band
        }
        shineMatrix.setTranslate(sweep * (fw + band) - band, 0f)
        shineShader?.setLocalMatrix(shineMatrix)
        shine.shader = shineShader
        canvas.drawRoundRect(rect, r, r, shine)
        canvas.restore()
    }
}

// ------------------------------------------------------------------------------------------------
// Code colouring for the request/response panels.
// ------------------------------------------------------------------------------------------------

object CodeTint {
    private val strings = Regex("\"[^\"\\n]*\"|'[^'\\n]*'")
    private val comments = Regex("(?m)(?:^|\\s)#.*$|//.*$")
    private val keywords = Regex("\\b(cd|ls|cat|grep|git|rm|mv|cp|mkdir|echo|sed|for|in|do|done|if|then|fi|else|fun|val|var|class|object|import|package|return|def|function|const|let|public|private)\\b")
    private val flags = Regex("(?<=\\s)--?[A-Za-z][\\w-]*")
    private val numbers = Regex("\\b\\d+(?:\\.\\d+)?\\b")

    fun apply(text: String, accent: Int): CharSequence {
        val out = SpannableString(text)
        fun color(regex: Regex, c: Int) {
            for (m in regex.findAll(text)) out.setSpan(ForegroundColorSpan(c), m.range.first, m.range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        color(numbers, 0xFFD19A66.toInt())
        color(flags, 0xFF61AFEF.toInt())
        color(keywords, 0xFFC678DD.toInt())
        color(comments, 0xFF7F848E.toInt())
        color(strings, 0xFF98C379.toInt())
        if (text.startsWith("$ ")) {
            out.setSpan(ForegroundColorSpan(accent), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
}

// ------------------------------------------------------------------------------------------------
// One line in the step list.
// ------------------------------------------------------------------------------------------------

class ActivityStepRow(private val context: Context, parent: ViewGroup) {
    val root: View = LayoutInflater.from(context).inflate(R.layout.item_work_step_card, parent, false)
    private val divider: View = root.findViewById(R.id.viewRowDivider)
    private val rowMain: View = root.findViewById(R.id.layoutRowMain)
    private val frame: View = root.findViewById(R.id.ivWorkFrame)
    private val icon: ImageView = root.findViewById(R.id.ivWorkIcon)
    private val title: ShimmerTextView = root.findViewById(R.id.tvWorkStepTitle)
    private val status: TextView = root.findViewById(R.id.tvWorkStepStatus)
    private val chevron: TextView = root.findViewById(R.id.tvWorkStepChevron)
    private val scan: ScanLineView = root.findViewById(R.id.viewScanLine)
    private val detail: ViewGroup = root.findViewById(R.id.layoutDetail)
    private val requestLabel: View = root.findViewById(R.id.tvRequestLabel)
    private val requestBody: TextView = root.findViewById(R.id.tvRequestBody)
    private val responseLabel: View = root.findViewById(R.id.tvResponseLabel)
    private val responseBody: TextView = root.findViewById(R.id.tvResponseBody)

    private var lastState: StepState? = null
    private var open = false

    /** The text of the line, taken from the kind of tool: "Creating index.html", "Executing: ./gradlew build". */
    fun titleOf(step: ActivityStep): String {
        val type = WorkActionType.resolve(step.language, step.target, step.toolName)
        val raw = if (step.state == StepState.RUNNING) type.activeBadgeText(step.target) else type.doneBadgeText(step.target)
        return raw.removePrefix("✦ ").removePrefix("✓ ").trimEnd('…', '.').ifBlank { step.target.ifBlank { step.toolName } }
    }

    fun bind(step: ActivityStep, accent: Int, showDivider: Boolean, animateIn: Boolean) {
        val type = WorkActionType.resolve(step.language, step.target, step.toolName)
        val running = step.state == StepState.RUNNING
        divider.visibility = if (showDivider) View.VISIBLE else View.GONE
        title.text = titleOf(step)
        title.shimmerColor = themeColor(context, R.attr.themeTextPrimary, Color.WHITE)
        title.shimmering = running
        scan.color = accent
        scan.visibility = if (running) View.VISIBLE else View.GONE
        status.visibility = if (step.state == StepState.FAILED) View.VISIBLE else View.GONE

        if (lastState != step.state) {
            when (step.state) {
                StepState.RUNNING -> WorkIconAnimator.applyWorkAnimation(icon, frame, type, isWriting = true)
                StepState.DONE -> WorkIconAnimator.applyWorkAnimation(icon, frame, type, isWriting = false)
                StepState.FAILED -> {
                    icon.clearAnimation()
                    frame.clearAnimation()
                    val red = 0xFFFF6E6E.toInt()
                    icon.setImageResource(type.iconRes)
                    icon.imageTintList = android.content.res.ColorStateList.valueOf(red)
                    ContextCompat.getDrawable(context, R.drawable.bg_work_icon_frame)?.mutate()?.let {
                        DrawableCompat.setTint(it, red)
                        frame.background = it
                    }
                    if (lastState != null) {
                        frame.startAnimation(TranslateAnimation(0f, 6f * density(root), 0f, 0f).apply {
                            duration = 380
                            interpolator = CycleInterpolator(3f)
                        })
                    }
                }
            }
            lastState = step.state
        }

        val hasRequest = step.request.isNotBlank()
        val hasResponse = !step.response.isNullOrBlank()
        val canOpen = (hasRequest || hasResponse) && !running
        requestLabel.visibility = if (hasRequest) View.VISIBLE else View.GONE
        requestBody.visibility = if (hasRequest) View.VISIBLE else View.GONE
        responseLabel.visibility = if (hasResponse) View.VISIBLE else View.GONE
        responseBody.visibility = if (hasResponse) View.VISIBLE else View.GONE
        if (hasRequest) requestBody.text = CodeTint.apply(clip(step.request), accent)
        if (hasResponse) responseBody.text = CodeTint.apply(clip(step.response.orEmpty()), accent)

        chevron.visibility = if (canOpen) View.VISIBLE else View.GONE
        if (!canOpen) open = false
        chevron.rotation = if (open) 90f else 0f
        detail.visibility = if (open && canOpen) View.VISIBLE else View.GONE
        rowMain.isClickable = canOpen
        rowMain.setOnClickListener(if (canOpen) View.OnClickListener { toggle() } else null)

        if (animateIn) {
            root.alpha = 0f
            root.translationY = 10f * density(root)
            root.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun clip(text: String): String = if (text.length > MAX_DETAIL_CHARS) text.take(MAX_DETAIL_CHARS) + "\n… (${text.length - MAX_DETAIL_CHARS} more characters)" else text

    private fun toggle() {
        open = !open
        chevron.animate().rotation(if (open) 90f else 0f).setDuration(180).start()
        if (open) {
            detail.visibility = View.VISIBLE
            detail.measure(
                View.MeasureSpec.makeMeasureSpec(root.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            animateHeight(detail, 0, detail.measuredHeight, hideAtEnd = false)
        } else {
            animateHeight(detail, detail.height, 0, hideAtEnd = true)
        }
    }

    fun release() {
        icon.clearAnimation()
        frame.clearAnimation()
        title.shimmering = false
        root.animate().cancel()
    }

    private companion object {
        const val MAX_DETAIL_CHARS = 2500
    }
}

/** A scroll area that never grows taller than [maxHeightDp], so a long list of steps stays compact. */
class MaxHeightNestedScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : NestedScrollView(context, attrs) {
    var maxHeightDp: Int = 260

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (maxHeightDp * density(this)).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

// ------------------------------------------------------------------------------------------------
// The whole block shown inside a chat message while Jarvis works, and kept (collapsed) afterwards.
// ------------------------------------------------------------------------------------------------

class InlineActivityView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val handler = Handler(Looper.getMainLooper())
    private var turnId: String? = null
    private var current: TurnActivity? = null
    private var accent = 0xFF00E5FF.toInt()
    private var expanded = true
    private var userToggled = false
    private var wasRunning = false
    private var verbIndex = 0
    private var cardPulse: ValueAnimator? = null
    private val rows = LinkedHashMap<String, ActivityStepRow>()

    private val spinner = OrbitSpinnerView(context)
    private val verb = ShimmerTextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = com.jarvis.assistant.util.JarvisFonts.medium
        letterSpacing = 0.01f
        maxLines = 1
    }
    private val timer = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = com.jarvis.assistant.util.JarvisFonts.mono
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }
    private val stepsInfo = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
    private val chevron = TextView(context).apply {
        text = "›"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        typeface = com.jarvis.assistant.util.JarvisFonts.bold
        rotation = 90f
    }
    private val ticker = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = com.jarvis.assistant.util.JarvisFonts.regular
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val caption = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
    private val percentLabel = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = com.jarvis.assistant.util.JarvisFonts.mono }
    private val bar = GlowProgressBarView(context)
    private val progressBlock = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val stepsList = LinearLayout(context).apply { orientation = VERTICAL }
    private val stepsScroll = MaxHeightNestedScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    private val stepsFrame = FrameLayout(context).apply { visibility = GONE; clipToOutline = true }

    private val verbTick = object : Runnable {
        override fun run() {
            if (current?.running != true) return
            nextVerb()
            handler.postDelayed(this, VERB_EVERY_MS)
        }
    }
    private val timeTick = object : Runnable {
        override fun run() {
            val a = current ?: return
            if (!a.running) return
            timer.text = formatElapsed(a.elapsedMs(System.currentTimeMillis()))
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        orientation = VERTICAL
        visibility = GONE
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(spinner, LayoutParams(dp(24), dp(24)))
        header.addView(verb, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        header.addView(timer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        header.addView(View(context), LayoutParams(0, 1, 1f))
        header.addView(stepsInfo, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(chevron, LayoutParams(dp(26), LayoutParams.WRAP_CONTENT).apply { marginStart = dp(2) })
        header.isClickable = true
        header.isFocusable = true
        header.setOnClickListener {
            if (current?.steps.isNullOrEmpty()) return@setOnClickListener
            userToggled = true
            setExpanded(!expanded, animate = true)
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(ticker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); marginStart = dp(34) })

        val barRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        barRow.addView(bar, LayoutParams(0, dp(8), 1f))
        barRow.addView(percentLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        progressBlock.addView(barRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(progressBlock, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); marginStart = dp(34) })

        stepsScroll.addView(stepsList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        stepsFrame.background = com.jarvis.assistant.ui.chat.liquid.LiquidPane.make(context, com.jarvis.assistant.ui.chat.liquid.LiquidPane.Kind.INSET, 20f)
        stepsFrame.addView(stepsScroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(stepsFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        ticker.visibility = GONE
    }

    /** Shows [activity]. Safe to call again and again as the work moves on. */
    fun bind(activity: TurnActivity, accentColor: Int) {
        val newTurn = turnId != activity.turnId
        accent = accentColor
        if (newTurn) {
            clearRows()
            turnId = activity.turnId
            userToggled = false
            expanded = activity.running
            wasRunning = activity.running
            stepsFrame.visibility = GONE
            progressBlock.visibility = GONE
            if (activity.running) {
                alpha = 0f
                translationY = dp(8).toFloat()
                animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
            } else {
                alpha = 1f
                translationY = 0f
            }
        }
        current = activity

        if (!activity.running && activity.steps.isEmpty()) {
            // A plain answer with no tool work: nothing to keep showing.
            stopTicks()
            visibility = GONE
            return
        }
        visibility = VISIBLE
        applyColors()
        spinner.accent = accent

        if (activity.running) {
            spinner.setMode(OrbitSpinnerView.Mode.RUNNING, animate = false)
            verb.shimmering = true
            if (newTurn || verb.text.isNullOrEmpty()) {
                verbIndex = (System.nanoTime() % VERBS.size).toInt().coerceAtLeast(0)
                verb.text = VERBS[verbIndex]
            }
            timer.text = formatElapsed(activity.elapsedMs(System.currentTimeMillis()))
            startTicks()
        } else {
            stopTicks()
            verb.shimmering = false
            verb.alpha = 1f
            verb.translationY = 0f
            verb.text = if (activity.failed) "Stopped after" else "Worked for"
            timer.text = formatElapsed(activity.elapsedMs(0))
            spinner.setMode(if (activity.failed) OrbitSpinnerView.Mode.FAILED else OrbitSpinnerView.Mode.DONE, animate = wasRunning)
        }

        syncRows(activity, animateNewRows = !newTurn)

        val n = activity.steps.size
        stepsInfo.text = if (n == 0) "" else if (n == 1) "1 step" else "$n steps"
        chevron.visibility = if (n == 0) GONE else VISIBLE

        val liveStep = activity.steps.lastOrNull { it.state == StepState.RUNNING }
        if (activity.running && liveStep != null) {
            ticker.visibility = VISIBLE
            val line = "● " + (rows[liveStep.id]?.titleOf(liveStep) ?: liveStep.target)
            if (ticker.text.toString() != line) {
                ticker.alpha = 0f
                ticker.text = line
                ticker.animate().alpha(1f).setDuration(200).start()
            }
        } else {
            ticker.visibility = GONE
        }

        if (activity.running && activity.percent >= 0) {
            progressBlock.visibility = VISIBLE
            bar.accent = accent
            bar.setPercent(activity.percent)
            percentLabel.text = "${activity.percent}%"
        } else {
            progressBlock.visibility = GONE
        }

        if (n > 0 && expanded && stepsFrame.visibility != VISIBLE) stepsFrame.visibility = VISIBLE
        if (n == 0) stepsFrame.visibility = GONE
        updateCardPulse(activity.running && stepsFrame.visibility == VISIBLE)

        if (wasRunning && !activity.running) {
            wasRunning = false
            // Like Claude: once the work is done the list folds away, leaving "Worked for 1m 12s".
            if (!userToggled && n > 0) handler.postDelayed({ if (!userToggled && current?.running == false) setExpanded(false, animate = true) }, 1500L)
        } else if (activity.running) {
            wasRunning = true
        }
    }

    // ---- rows -------------------------------------------------------------------------------------

    private fun syncRows(activity: TurnActivity, animateNewRows: Boolean) {
        val seen = HashSet<String>()
        activity.steps.forEachIndexed { index, step ->
            seen += step.id
            var row = rows[step.id]
            val isNew = row == null
            if (row == null) {
                row = ActivityStepRow(context, stepsList)
                rows[step.id] = row
                stepsList.addView(row.root, index)
            }
            row.bind(step, accent, showDivider = index > 0, animateIn = isNew && animateNewRows)
        }
        val gone = rows.keys.filter { it !in seen }
        for (id in gone) {
            rows.remove(id)?.let { stepsList.removeView(it.root); it.release() }
        }
        if (activity.running && animateNewRows && activity.steps.isNotEmpty()) stepsScroll.post { stepsScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearRows() {
        rows.values.forEach { it.release() }
        rows.clear()
        stepsList.removeAllViews()
    }

    // ---- expand / collapse ------------------------------------------------------------------------

    private fun setExpanded(value: Boolean, animate: Boolean) {
        if (expanded == value && (value == (stepsFrame.visibility == VISIBLE))) return
        expanded = value
        if (animate) chevron.animate().rotation(if (value) 90f else 0f).setDuration(180).start() else chevron.rotation = if (value) 90f else 0f
        if (value) {
            stepsFrame.visibility = VISIBLE
            if (animate) {
                stepsFrame.measure(
                    MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                animateHeight(stepsFrame, 0, stepsFrame.measuredHeight, hideAtEnd = false)
            }
        } else {
            if (animate) animateHeight(stepsFrame, stepsFrame.height, 0, hideAtEnd = true) else stepsFrame.visibility = GONE
            updateCardPulse(false)
        }
    }

    /** The step list keeps a calm, still edge; all the motion lives in the spinner, the shimmer and the running row. */
    private fun updateCardPulse(on: Boolean) {
        cardPulse?.cancel()
        cardPulse = null
    }

    // ---- look -------------------------------------------------------------------------------------

    private fun applyColors() {
        val primary = themeColor(context, R.attr.themeTextPrimary, 0xFFE0E6EA.toInt())
        val muted = themeColor(context, R.attr.themeTextSecondary, 0xFF90A4AE.toInt())
        verb.setTextColor(primary)
        verb.shimmerColor = accent
        timer.setTextColor(muted)
        timer.background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(ColorUtils.setAlphaComponent(muted, 38)) }
        stepsInfo.setTextColor(muted)
        chevron.setTextColor(muted)
        ticker.setTextColor(ColorUtils.setAlphaComponent(accent, 230))
        caption.setTextColor(muted)
        percentLabel.setTextColor(muted)
    }

    // ---- verbs and timer --------------------------------------------------------------------------

    private fun startTicks() {
        handler.removeCallbacks(verbTick)
        handler.removeCallbacks(timeTick)
        handler.postDelayed(verbTick, VERB_EVERY_MS)
        handler.postDelayed(timeTick, 1000L)
    }

    private fun stopTicks() {
        handler.removeCallbacks(verbTick)
        handler.removeCallbacks(timeTick)
        updateCardPulse(false)
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (current?.running == true) startTicks()
    }

    override fun onDetachedFromWindow() {
        stopTicks()
        animate().cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val VERB_EVERY_MS = 3200L
        val VERBS = listOf("Thinking", "Contemplating", "Honing", "Analyzing", "Crafting", "Assembling", "Refining", "Pondering", "Wiring up", "Building")
    }
}
