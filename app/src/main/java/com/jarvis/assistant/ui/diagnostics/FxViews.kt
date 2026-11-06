package com.jarvis.assistant.ui.diagnostics

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
    return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}

/**
 * One animated stage for every cleaning step. All drawing is original and done on a canvas, so there are no
 * image or animation files to load, nothing to squeeze into a box and nothing that can fail to play.
 *
 *  IDLE    breathing core inside slow HUD rings
 *  SCAN    radar sweep that lights up blips as it passes
 *  CLEAN   particles spiral into the core, which pulses as it absorbs them
 *  BOOST   warp streaks and expanding pulse rings
 *  BATTERY a liquid-filled cell with rising bubbles
 *  DONE    ring draws itself, a check mark traces in, sparks burst outward
 */
class FxView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { IDLE, SCAN, CLEAN, BOOST, BATTERY, DONE }

    var accent: Int = Color.rgb(64, 214, 255)
        set(value) { field = value; rebuildShaders(); invalidate() }
    var accent2: Int = Color.rgb(150, 112, 255)
        set(value) { field = value; invalidate() }
    var success: Int = Color.rgb(52, 211, 153)

    /** 0..1 draws a determinate ring around the stage; a negative value hides it. */
    var progress: Float = -1f
        set(value) { field = value; invalidate() }

    /** Battery fill 0..1, used in BATTERY mode. */
    var level: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field != value) {
                field = value
                modeStart = SystemClock.uptimeMillis()
                invalidate()
            }
        }

    private var modeStart = SystemClock.uptimeMillis()
    private var animator: ValueAnimator? = null
    private val density = resources.displayMetrics.density

    private fun dp(v: Float) = v * density

    // Fixed random seeds so the pattern is stable between frames.
    private val rnd = Random(7)
    private val particleCount = 72
    private val pAngle = FloatArray(particleCount) { rnd.nextFloat() * 6.2831855f }
    private val pSpeed = FloatArray(particleCount) { 0.6f + rnd.nextFloat() * 0.9f }
    private val pPhase = FloatArray(particleCount) { rnd.nextFloat() }
    private val pSize = FloatArray(particleCount) { 1.1f + rnd.nextFloat() * 2.2f }
    private val blipAngle = FloatArray(16) { rnd.nextFloat() * 360f }
    private val blipDist = FloatArray(16) { 0.35f + rnd.nextFloat() * 0.62f }
    private val streakAngle = FloatArray(44) { rnd.nextFloat() * 6.2831855f }
    private val streakSpeed = FloatArray(44) { 0.7f + rnd.nextFloat() * 0.8f }
    private val streakPhase = FloatArray(44) { rnd.nextFloat() }
    private val bubbleX = FloatArray(14) { rnd.nextFloat() * 2f - 1f }
    private val bubblePhase = FloatArray(14) { rnd.nextFloat() }
    private val burstAngle = FloatArray(56) { rnd.nextFloat() * 6.2831855f }
    private val burstSpeed = FloatArray(56) { 0.5f + rnd.nextFloat() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val fxMatrix = Matrix()
    private val path = Path()
    private val segment = Path()
    private val measure = PathMeasure()

    private var glow: RadialGradient? = null
    private var sweep: SweepGradient? = null
    private var dashes: Array<DashPathEffect> = emptyArray()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        rebuildShaders()
    }

    private fun rebuildShaders() {
        glow = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(withAlpha(Color.WHITE, 0.95f), withAlpha(accent, 0.75f), withAlpha(accent, 0.22f), withAlpha(accent, 0f)),
            floatArrayOf(0f, 0.28f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        sweep = SweepGradient(
            0f, 0f,
            intArrayOf(withAlpha(accent, 0f), withAlpha(accent, 0f), withAlpha(accent, 0.55f), withAlpha(accent, 0.95f)),
            floatArrayOf(0f, 0.55f, 0.92f, 1f)
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        dashes = arrayOf(
            DashPathEffect(floatArrayOf(dp(22f), dp(12f)), 0f),
            DashPathEffect(floatArrayOf(dp(54f), dp(18f)), 0f),
            DashPathEffect(floatArrayOf(dp(4f), dp(8f)), 0f)
        )
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun easeOut(t: Float) = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Float) {
        if (radius <= 0.5f) return
        fxMatrix.setScale(radius, radius)
        fxMatrix.postTranslate(cx, cy)
        glow?.setLocalMatrix(fxMatrix)
        fill.shader = glow
        fill.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null
        fill.alpha = 255
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.uptimeMillis()
        val t = (now % 3_600_000L) / 1000f
        val mt = (now - modeStart) / 1000f
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) * 0.30f

        when (mode) {
            Mode.IDLE -> drawIdle(canvas, cx, cy, r, t)
            Mode.SCAN -> drawScan(canvas, cx, cy, r, t)
            Mode.CLEAN -> drawClean(canvas, cx, cy, r, t)
            Mode.BOOST -> drawBoost(canvas, cx, cy, r, t)
            Mode.BATTERY -> drawBattery(canvas, cx, cy, r, t)
            Mode.DONE -> drawDone(canvas, cx, cy, r, t, mt)
        }

        if (progress >= 0f && mode != Mode.DONE) {
            val pr = r * 1.34f
            rect.set(cx - pr, cy - pr, cx + pr, cy + pr)
            stroke.pathEffect = null
            stroke.strokeWidth = dp(5f)
            stroke.color = withAlpha(Color.WHITE, 0.12f)
            canvas.drawArc(rect, 0f, 360f, false, stroke)
            stroke.color = accent
            canvas.drawArc(rect, -90f, 360f * progress.coerceIn(0f, 1f), false, stroke)
        }
    }

    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, strength: Float) {
        val radii = floatArrayOf(0.74f, 1.0f, 1.22f)
        val speeds = floatArrayOf(22f, -14f, 9f)
        val widths = floatArrayOf(dp(1.6f), dp(2.2f), dp(1.2f))
        for (i in 0 until 3) {
            stroke.pathEffect = if (dashes.size == 3) dashes[i] else null
            stroke.strokeWidth = widths[i]
            stroke.color = withAlpha(if (i == 1) accent2 else accent, (0.35f + 0.2f * sin(t * 1.4f + i)) * strength)
            canvas.save()
            canvas.rotate(t * speeds[i], cx, cy)
            canvas.drawCircle(cx, cy, r * radii[i], stroke)
            canvas.restore()
        }
        stroke.pathEffect = null
    }

    private fun drawIdle(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val breathe = 1f + 0.06f * sin(t * 1.8f)
        drawGlow(canvas, cx, cy, r * 1.5f * breathe, 0.55f)
        drawRings(canvas, cx, cy, r, t, 1f)
        drawGlow(canvas, cx, cy, r * 0.62f * breathe, 0.95f)
        fill.color = withAlpha(accent2, 0.9f)
        for (i in 0 until 5) {
            val a = t * 0.5f + i * (2f * PI.toFloat() / 5f)
            canvas.drawCircle(cx + cos(a) * r * 1.22f, cy + sin(a) * r * 1.22f, dp(3f), fill)
        }
    }

    private fun drawScan(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val outer = r * 1.22f
        drawGlow(canvas, cx, cy, outer * 1.25f, 0.28f)
        stroke.pathEffect = null
        stroke.strokeWidth = dp(1.2f)
        for (k in 1..4) {
            stroke.color = withAlpha(accent, 0.12f + 0.05f * k)
            canvas.drawCircle(cx, cy, outer * k / 4f, stroke)
        }
        stroke.color = withAlpha(accent, 0.18f)
        canvas.drawLine(cx - outer, cy, cx + outer, cy, stroke)
        canvas.drawLine(cx, cy - outer, cx, cy + outer, stroke)

        val angle = (t * 150f) % 360f
        fxMatrix.setRotate(angle)
        fxMatrix.postTranslate(cx, cy)
        sweep?.setLocalMatrix(fxMatrix)
        fill.shader = sweep
        canvas.drawCircle(cx, cy, outer, fill)
        fill.shader = null

        for (i in blipAngle.indices) {
            var delta = angle - blipAngle[i]
            if (delta < 0f) delta += 360f
            val a = (1f - delta / 360f).pow(2.4f)
            if (a < 0.03f) continue
            val rad = Math.toRadians(blipAngle[i].toDouble())
            val bx = cx + (cos(rad) * outer * blipDist[i]).toFloat()
            val by = cy + (sin(rad) * outer * blipDist[i]).toFloat()
            fill.color = withAlpha(accent2, a)
            canvas.drawCircle(bx, by, dp(2.5f) + dp(4f) * a, fill)
            fill.color = withAlpha(Color.WHITE, a)
            canvas.drawCircle(bx, by, dp(1.6f), fill)
        }
        drawGlow(canvas, cx, cy, r * 0.30f, 0.9f)
    }

    private fun drawClean(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val pulse = 1f + 0.10f * sin(t * 7f)
        drawGlow(canvas, cx, cy, r * 1.55f * pulse, 0.45f)
        for (k in 0 until 3) {
            val ph = ((t * 0.55f) + k / 3f) % 1f
            stroke.strokeWidth = dp(1.5f)
            stroke.color = withAlpha(accent, (1f - ph) * 0.5f)
            canvas.drawCircle(cx, cy, r * (1.9f - 1.3f * ph), stroke)
        }
        for (i in 0 until particleCount) {
            val cycle = (t * 0.5f * pSpeed[i] + pPhase[i]) % 1f
            val dist = r * 2.0f * (1f - cycle).pow(1.25f) + r * 0.18f
            val ang = pAngle[i] + cycle * 7.2f
            val x = cx + cos(ang) * dist
            val y = cy + sin(ang) * dist
            val a = sin(cycle * PI.toFloat()).coerceIn(0f, 1f)
            fill.color = withAlpha(
                when (i % 3) { 0 -> accent; 1 -> accent2; else -> Color.WHITE }, a * 0.95f
            )
            canvas.drawCircle(x, y, dp(pSize[i]) * (1f - cycle * 0.45f), fill)
        }
        drawGlow(canvas, cx, cy, r * 0.66f * pulse, 1f)
    }

    private fun drawBoost(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val pulse = 1f + 0.07f * sin(t * 5f)
        drawGlow(canvas, cx, cy, r * 1.5f * pulse, 0.5f)
        for (k in 0 until 3) {
            val ph = ((t * 0.7f) + k / 3f) % 1f
            stroke.strokeWidth = dp(2f)
            stroke.color = withAlpha(accent2, (1f - ph) * 0.55f)
            canvas.drawCircle(cx, cy, r * (0.55f + 1.55f * ph), stroke)
        }
        stroke.strokeWidth = dp(1.6f)
        for (i in streakAngle.indices) {
            val cycle = (t * 0.95f * streakSpeed[i] + streakPhase[i]) % 1f
            val inner = r * 0.55f + cycle * r * 2.0f
            val outer = inner + dp(6f) + cycle * r * 0.55f
            val a = sin(cycle * PI.toFloat()).coerceIn(0f, 1f)
            stroke.color = withAlpha(if (i % 2 == 0) accent else Color.WHITE, a * 0.85f)
            canvas.drawLine(
                cx + cos(streakAngle[i]) * inner, cy + sin(streakAngle[i]) * inner,
                cx + cos(streakAngle[i]) * outer, cy + sin(streakAngle[i]) * outer, stroke
            )
        }
        drawGlow(canvas, cx, cy, r * 0.62f * pulse, 1f)
    }

    private fun drawBattery(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val tint = when {
            level >= 0.5f -> success
            level >= 0.2f -> Color.rgb(255, 176, 32)
            else -> Color.rgb(239, 68, 68)
        }
        val cell = r * 1.08f
        stroke.pathEffect = null
        stroke.strokeWidth = dp(3f)
        stroke.color = withAlpha(tint, 0.85f)
        canvas.drawCircle(cx, cy, cell + dp(7f), stroke)
        stroke.strokeWidth = dp(1.2f)
        stroke.color = withAlpha(tint, 0.3f)
        canvas.drawCircle(cx, cy, cell + dp(16f), stroke)

        val baseY = cy + cell - 2f * cell * level
        path.reset()
        path.addCircle(cx, cy, cell, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        // back wave, then front wave
        for (layer in 0..1) {
            val amp = dp(if (layer == 0) 6f else 4.5f)
            val speed = if (layer == 0) 1.6f else 2.4f
            val phase = if (layer == 0) 0f else 1.7f
            val wave = Path()
            wave.moveTo(cx - cell, cy + cell + dp(4f))
            var x = cx - cell
            while (x <= cx + cell + dp(4f)) {
                val y = baseY + sin(((x - cx) / cell) * 3.6f + t * speed + phase) * amp
                wave.lineTo(x, y)
                x += dp(5f)
            }
            wave.lineTo(cx + cell, cy + cell + dp(4f))
            wave.close()
            fill.color = withAlpha(tint, if (layer == 0) 0.38f else 0.72f)
            canvas.drawPath(wave, fill)
        }
        for (i in bubbleX.indices) {
            val ph = (t * 0.35f + bubblePhase[i]) % 1f
            val by = cy + cell - ph * (cy + cell - baseY)
            if (by < baseY) continue
            fill.color = withAlpha(Color.WHITE, (1f - ph) * 0.6f)
            canvas.drawCircle(cx + bubbleX[i] * cell * 0.72f + sin(t * 2f + i) * dp(3f), by, dp(2.2f), fill)
        }
        canvas.restore()
    }

    private fun drawDone(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, mt: Float) {
        val ringT = easeOut(mt / 0.75f)
        val fillT = easeOut((mt - 0.35f) / 0.5f)
        val checkT = easeOut((mt - 0.55f) / 0.55f)

        drawGlow(canvas, cx, cy, r * (1.5f + 0.08f * sin(t * 2.2f)), 0.35f * fillT)
        fill.color = withAlpha(success, 0.20f * fillT)
        canvas.drawCircle(cx, cy, r, fill)

        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke.pathEffect = null
        stroke.strokeWidth = dp(5f)
        stroke.color = withAlpha(success, 0.2f)
        canvas.drawArc(rect, 0f, 360f, false, stroke)
        stroke.color = success
        canvas.drawArc(rect, -90f, 360f * ringT, false, stroke)

        if (checkT > 0f) {
            path.reset()
            path.moveTo(cx - r * 0.36f, cy + r * 0.02f)
            path.lineTo(cx - r * 0.09f, cy + r * 0.30f)
            path.lineTo(cx + r * 0.40f, cy - r * 0.26f)
            measure.setPath(path, false)
            segment.reset()
            measure.getSegment(0f, measure.length * checkT, segment, true)
            stroke.strokeWidth = dp(7f)
            stroke.color = Color.WHITE
            canvas.drawPath(segment, stroke)
        }

        // Sparks burst outward once, then fade.
        val burst = mt - 0.5f
        if (burst in 0f..1.6f) {
            for (i in burstAngle.indices) {
                val p = easeOut(burst / 1.4f * burstSpeed[i])
                val d = r * (1.05f + 1.5f * p)
                val a = (1f - burst / 1.6f).coerceIn(0f, 1f)
                fill.color = withAlpha(if (i % 2 == 0) success else accent, a)
                canvas.drawCircle(cx + cos(burstAngle[i]) * d, cy + sin(burstAngle[i]) * d, dp(2.6f) * (1f - p * 0.6f), fill)
            }
        }
        val ripple = (mt - 0.7f) / 1.4f
        if (ripple in 0f..1f) {
            stroke.strokeWidth = dp(2f)
            stroke.color = withAlpha(success, (1f - ripple) * 0.6f)
            canvas.drawCircle(cx, cy, r * (1f + 0.9f * easeOut(ripple)), stroke)
        }
    }
}

/**
 * Three concentric gauges: storage used, memory used and battery. The arcs sweep in when values change.
 * The centre carries a headline value and a small label.
 */
class HealthRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var colorStorage: Int = Color.rgb(57, 198, 255)
    var colorMemory: Int = Color.rgb(167, 139, 250)
    var colorBattery: Int = Color.rgb(52, 211, 153)
    var textPrimary: Int = Color.WHITE
    var textMuted: Int = Color.GRAY

    var centerValue: String = ""
        set(value) { field = value; invalidate() }
    var centerLabel: String = ""
        set(value) { field = value; invalidate() }

    private val target = floatArrayOf(0f, 0f, 0f)
    private val from = floatArrayOf(0f, 0f, 0f)
    private val shown = floatArrayOf(0f, 0f, 0f)
    private var animator: ValueAnimator? = null
    private val density = resources.displayMetrics.density
    private val rect = RectF()

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.16f
    }

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /** Values are fractions 0..1. A negative value hides that ring (for example when there is no battery). */
    fun setValues(storage: Float, memory: Float, battery: Float, animate: Boolean = true) {
        for (i in 0 until 3) from[i] = shown[i]
        target[0] = storage.coerceAtMost(1f)
        target[1] = memory.coerceAtMost(1f)
        target[2] = battery.coerceAtMost(1f)
        animator?.cancel()
        if (!animate) {
            for (i in 0 until 3) shown[i] = target[i]
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val f = it.animatedValue as Float
                for (i in 0 until 3) shown[i] = from[i] + (target[i] - from[i]) * f
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val stroke = dp(12f)
        val gap = dp(8f)
        val outer = min(w, h) / 2f - stroke
        val colors = intArrayOf(colorStorage, colorMemory, colorBattery)
        for (i in 0 until 3) {
            val radius = outer - i * (stroke + gap)
            if (radius <= stroke) continue
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            arc.strokeWidth = stroke
            arc.color = withAlpha(colors[i], 0.16f)
            canvas.drawArc(rect, 0f, 360f, false, arc)
            if (shown[i] > 0.004f) {
                arc.color = colors[i]
                canvas.drawArc(rect, -90f, 360f * shown[i], false, arc)
            }
        }
        valuePaint.color = textPrimary
        valuePaint.textSize = sp(34f)
        canvas.drawText(centerValue, cx, cy + sp(8f), valuePaint)
        labelPaint.color = textMuted
        labelPaint.textSize = sp(10f)
        canvas.drawText(centerLabel, cx, cy + sp(30f), labelPaint)
    }
}
