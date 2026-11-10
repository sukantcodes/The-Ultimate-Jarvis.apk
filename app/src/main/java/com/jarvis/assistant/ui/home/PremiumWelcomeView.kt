package com.jarvis.assistant.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Cinematic "Welcome to JARVIS" sequence drawn on one canvas, so it stays smooth and light:
 *
 *  1. a deep-space backdrop with a scan line sweeping down the screen,
 *  2. a glowing core that blooms, surrounded by three counter-rotating HUD rings and a tick ring,
 *  3. orbiting sparks that twinkle and drift outward,
 *  4. the title appearing letter by letter, followed by a shimmer of light across it,
 *  5. a gentle burst outward before the screen hands over to Home.
 */
class PremiumWelcomeView(context: Context) : View(context) {

    var eyebrow: String = "SYSTEMS ONLINE"
    var title: String = "WELCOME TO JARVIS"
    var subtitle: String = "Created by Sukant"

    private val density = resources.displayMetrics.density
    private val metrics = resources.displayMetrics

    private val cyan = Color.rgb(64, 214, 255)
    private val violet = Color.rgb(150, 112, 255)
    private val deep = Color.rgb(3, 6, 14)
    private val navy = Color.rgb(10, 20, 44)

    private val totalMs = 4200f
    private var elapsed = 0f
    private var animator: ValueAnimator? = null
    private var cancelled = false

    private class Spark(
        val angle: Float,
        val orbit: Float,
        val speed: Float,
        val size: Float,
        val phase: Float,
        val violet: Boolean
    )

    private val sparks: List<Spark> = Random(11).let { rnd ->
        List(84) {
            Spark(
                angle = rnd.nextFloat() * (2f * PI.toFloat()),
                orbit = 0.55f + rnd.nextFloat() * 1.25f,
                speed = (0.4f + rnd.nextFloat() * 1.2f) * (if (rnd.nextBoolean()) 1f else -1f),
                size = 0.8f + rnd.nextFloat() * 2.0f,
                phase = rnd.nextFloat() * 6.28f,
                violet = rnd.nextInt(3) == 0
            )
        }
    }

    // ---- Paints (created once; only their numbers change per frame) ---------------------------------

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coreMatrix = Matrix()
    private val ringPaints = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scanMatrix = Matrix()
    // Same bundled brand fonts as the intro text sequence, so the welcome screen continues it seamlessly.
    private fun brandFace(res: Int, fallback: Typeface): Typeface =
        try { ResourcesCompat.getFont(context, res) ?: fallback } catch (_: Exception) { fallback }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = brandFace(R.font.orbitron, Typeface.create("sans-serif-black", Typeface.BOLD))
        setFontVariationSettings("'wght' 800")
        color = Color.WHITE
    }
    private val eyebrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = brandFace(R.font.rajdhani_semibold, Typeface.create("sans-serif-medium", Typeface.NORMAL))
        letterSpacing = 0.38f
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = brandFace(R.font.rajdhani_medium, Typeface.create("sans-serif-light", Typeface.NORMAL))
        letterSpacing = 0.14f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    private val ringRect = RectF()
    private val tickLines = FloatArray(72 * 4)
    private val charWidths = FloatArray(32)

    private var core: RadialGradient? = null
    private var scan: LinearGradient? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Welcome to JARVIS. Created by Sukant."
        // The unit-sized gradients are scaled and moved with a matrix each frame, so nothing is allocated while drawing.
        core = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(235, 220, 248, 255), Color.argb(170, 64, 214, 255), Color.argb(70, 150, 112, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.28f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        scan = LinearGradient(
            0f, 0f, 0f, 1f,
            intArrayOf(Color.TRANSPARENT, Color.argb(70, 64, 214, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    // ---- Control -------------------------------------------------------------------------------------

    /** Plays the sequence once and calls [onDone] when it ends (also when it is skipped). */
    fun start(onDone: () -> Unit) {
        cancelled = false
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, totalMs).apply {
            duration = totalMs.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                elapsed = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var called = false
                override fun onAnimationEnd(animation: Animator) {
                    if (called) return
                    called = true
                    onDone()
                }
            })
            start()
        }
    }

    /** Jumps to the end right away. */
    fun skip() {
        animator?.let { running ->
            if (running.isRunning) running.currentPlayTime = running.duration
        }
    }

    fun stop() {
        cancelled = true
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    // ---- Drawing -------------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backdropPaint.shader = RadialGradient(
            w * 0.5f, h * 0.38f, h * 0.75f,
            intArrayOf(navy, deep, Color.BLACK),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)

    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
    private fun phase(startMs: Float, lengthMs: Float) = clamp01((elapsed - startMs) / lengthMs)
    private fun easeOut(t: Float) = 1f - (1f - t).pow(3)
    private fun easeInOut(t: Float) = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
    private fun easeOutBack(t: Float): Float {
        val c1 = 1.35f
        val c3 = c1 + 1f
        return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }

    private fun withAlpha(color: Int, alpha: Float) =
        Color.argb((clamp01(alpha) * 255f).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun mix(from: Int, to: Int, t: Float): Int {
        val k = clamp01(t)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * k).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * k).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, backdropPaint)

        val cx = w / 2f
        val cy = h * 0.37f
        val radius = min(w * 0.40f, h * 0.22f)

        val intro = easeOutBack(phase(150f, 1100f))
        val exit = easeInOut(phase(3550f, 650f))
        val alive = 1f - exit
        val grow = intro * (1f + exit * 0.7f)

        drawScan(canvas, w, h, alive)
        drawCore(canvas, cx, cy, radius, intro, alive)
        drawRings(canvas, cx, cy, radius, grow, alive)
        drawSparks(canvas, cx, cy, radius, intro, exit, alive)
        drawTexts(canvas, w, cy + radius * 1.0f + dp(54f), alive)
    }

    private fun drawScan(canvas: Canvas, w: Float, h: Float, alive: Float) {
        val t = phase(250f, 1500f)
        if (t <= 0f || t >= 1f) return
        val y = h * easeInOut(t)
        val band = dp(120f)
        scanMatrix.setScale(1f, band)
        scanMatrix.postTranslate(0f, y - band / 2f)
        scan?.setLocalMatrix(scanMatrix)
        scanPaint.shader = scan
        scanPaint.alpha = (255 * alive).toInt()
        canvas.drawRect(0f, y - band / 2f, w, y + band / 2f, scanPaint)
    }

    private fun drawCore(canvas: Canvas, cx: Float, cy: Float, radius: Float, intro: Float, alive: Float) {
        val breathe = 0.92f + 0.08f * sin(elapsed / 240f)
        val bloom = 1f + 0.55f * (1f - phase(500f, 1400f)) // the core flares, then settles
        val r = radius * 0.78f * intro * breathe * bloom
        if (r <= 0.5f) return
        coreMatrix.setScale(r, r)
        coreMatrix.postTranslate(cx, cy)
        core?.setLocalMatrix(coreMatrix)
        corePaint.shader = core
        corePaint.alpha = (255 * clamp01(intro) * alive).toInt()
        canvas.drawCircle(cx, cy, r, corePaint)
    }

    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, radius: Float, grow: Float, alive: Float) {
        val fractions = floatArrayOf(0.58f, 0.82f, 1.0f)
        val widths = floatArrayOf(dp(1.6f), dp(2.2f), dp(1.2f))
        val speeds = floatArrayOf(0.050f, -0.034f, 0.021f)
        val dashes = floatArrayOf(dp(26f), dp(60f), dp(5f))
        val gaps = floatArrayOf(dp(14f), dp(22f), dp(9f))
        for (i in 0 until 3) {
            val r = radius * fractions[i] * grow
            if (r <= 1f) continue
            val paint = ringPaints[i]
            paint.strokeWidth = widths[i]
            paint.color = withAlpha(if (i == 1) violet else cyan, (0.55f + 0.2f * sin(elapsed / 500f + i)) * alive)
            paint.pathEffect = DashPathEffect(floatArrayOf(dashes[i], gaps[i]), 0f)
            canvas.save()
            canvas.rotate(elapsed * speeds[i], cx, cy)
            canvas.drawCircle(cx, cy, r, paint)
            canvas.restore()
        }

        // A bright sweeping arc on the middle ring.
        val arcR = radius * 0.82f * grow
        if (arcR > 1f) {
            ringRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
            // A wide, faint stroke under a thin bright one gives the arcs a soft glow without shadow layers.
            val sweep = elapsed * 0.16f
            arcPaint.strokeWidth = dp(10f)
            arcPaint.color = withAlpha(cyan, 0.22f * alive)
            canvas.drawArc(ringRect, sweep, 74f, false, arcPaint)
            arcPaint.color = withAlpha(violet, 0.22f * alive)
            canvas.drawArc(ringRect, sweep + 180f, 48f, false, arcPaint)
            arcPaint.strokeWidth = dp(3.2f)
            arcPaint.color = withAlpha(cyan, 0.95f * alive)
            canvas.drawArc(ringRect, sweep, 74f, false, arcPaint)
            arcPaint.color = withAlpha(violet, 0.9f * alive)
            canvas.drawArc(ringRect, sweep + 180f, 48f, false, arcPaint)
        }

        // Fine tick marks on the outer ring.
        val outer = radius * 1.12f * grow
        if (outer > 1f) {
            val inner = outer - dp(7f)
            var k = 0
            for (n in 0 until 72) {
                val a = (n * 5f - elapsed * 0.012f) * (PI.toFloat() / 180f)
                val long = n % 6 == 0
                val from = if (long) outer - dp(12f) else inner
                tickLines[k++] = cx + cos(a) * from
                tickLines[k++] = cy + sin(a) * from
                tickLines[k++] = cx + cos(a) * outer
                tickLines[k++] = cy + sin(a) * outer
            }
            tickPaint.strokeWidth = dp(1.1f)
            tickPaint.color = withAlpha(Color.WHITE, 0.4f * alive)
            canvas.drawLines(tickLines, tickPaint)
        }
    }

    private fun drawSparks(canvas: Canvas, cx: Float, cy: Float, radius: Float, intro: Float, exit: Float, alive: Float) {
        if (intro <= 0f) return
        for (s in sparks) {
            val angle = s.angle + elapsed * 0.00045f * s.speed
            val drift = 1f + exit * 1.2f + (elapsed / totalMs) * 0.16f
            val r = radius * s.orbit * intro * drift
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r
            val twinkle = 0.5f + 0.5f * sin(elapsed * 0.0042f + s.phase)
            sparkPaint.color = withAlpha(if (s.violet) violet else cyan, (0.15f + 0.85f * twinkle) * intro * alive)
            canvas.drawCircle(x, y, dp(s.size), sparkPaint)
        }
    }

    private fun drawTexts(canvas: Canvas, w: Float, titleBaseline: Float, alive: Float) {
        // Eyebrow
        val eyebrowIn = easeOut(phase(700f, 700f))
        if (eyebrowIn > 0f) {
            eyebrowPaint.textSize = sp(12f)
            eyebrowPaint.color = withAlpha(cyan, eyebrowIn * alive)
            val tw = eyebrowPaint.measureText(eyebrow)
            canvas.drawText(eyebrow, (w - tw) / 2f, titleBaseline - sp(40f), eyebrowPaint)
        }

        // Title: one size that fits, drawn letter by letter with a rise, a pop and a shimmer.
        var size = sp(38f)
        val spacing = 0.10f
        titlePaint.textSize = size
        fun widthOf(): Float {
            var total = 0f
            for (i in title.indices) {
                val cw = titlePaint.measureText(title, i, i + 1) + size * spacing
                if (i < charWidths.size) charWidths[i] = cw
                total += cw
            }
            return total - size * spacing
        }
        var total = widthOf()
        val maxWidth = w * 0.88f
        if (total > maxWidth) {
            size *= maxWidth / total
            titlePaint.textSize = size
            total = widthOf()
        }
        var x = (w - total) / 2f
        val shimmerX = -0.25f * w + (w * 1.5f) * easeInOut(phase(2200f, 1000f))
        val reach = w * 0.11f
        for (i in title.indices) {
            val start = 900f + i * 58f
            val t = easeOut(clamp01((elapsed - start) / 520f))
            val cw = if (i < charWidths.size) charWidths[i] else size
            if (t > 0f && title[i] != ' ') {
                val centerX = x + cw / 2f
                val glow = exp(-((centerX - shimmerX) / reach).let { it * it })
                titlePaint.color = withAlpha(mix(Color.WHITE, Color.rgb(120, 232, 255), glow), t * alive)
                titlePaint.setShadowLayer(dp(10f) + dp(10f) * glow, 0f, 0f, withAlpha(cyan, (0.45f + 0.5f * glow) * t * alive))
                canvas.save()
                val pop = 1f + 0.35f * (1f - t)
                canvas.translate(centerX, titleBaseline + (1f - t) * dp(22f))
                canvas.scale(pop, pop)
                canvas.drawText(title, i, i + 1, -cw / 2f + size * spacing / 2f, 0f, titlePaint)
                canvas.restore()
            }
            x += cw
        }

        // Accent line growing from the centre, then the subtitle.
        val lineT = easeOut(phase(1900f, 700f))
        if (lineT > 0f) {
            val half = total * 0.5f * lineT
            linePaint.strokeWidth = dp(2f)
            linePaint.shader = LinearGradient(
                w / 2f - half, 0f, w / 2f + half, 0f,
                intArrayOf(Color.TRANSPARENT, withAlpha(cyan, alive), withAlpha(violet, alive), Color.TRANSPARENT),
                floatArrayOf(0f, 0.3f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(w / 2f - half, titleBaseline + sp(16f), w / 2f + half, titleBaseline + sp(16f), linePaint)
            linePaint.shader = null
        }
        val subIn = easeOut(phase(2300f, 700f))
        if (subIn > 0f) {
            subtitlePaint.textSize = sp(15f)
            subtitlePaint.color = withAlpha(Color.rgb(190, 206, 230), subIn * alive)
            val tw = subtitlePaint.measureText(subtitle)
            canvas.drawText(subtitle, (w - tw) / 2f, titleBaseline + sp(46f) + (1f - subIn) * dp(10f), subtitlePaint)
        }
    }
}
