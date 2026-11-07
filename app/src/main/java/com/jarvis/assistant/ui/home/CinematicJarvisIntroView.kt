package com.jarvis.assistant.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.jarvis.assistant.R
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * JARVIS brand ident, played on every cold launch.
 *
 * The bundled logo has near-black letterforms, so instead of lighting it from the front on a dark
 * screen the ident *backlights* it: a hairline of light opens into a luminous bloom and the
 * letterforms emerge as crisp silhouettes through a feathered wipe. A specular sheen crosses the
 * glyphs, a ripple leaves the orb of the "i", the credit line resolves letter by letter, and the
 * whole scene dissolves into whatever is underneath.
 *
 * Engineering notes
 *  - One linear [ValueAnimator] drives a single 0..1 timeline; every phase is a window on that
 *    timeline with its own cubic-bezier easing, so no phase is distorted by a global interpolator.
 *  - [onDraw] performs no allocation: every shader, paint, matrix and rect is built once in
 *    [onSizeChanged] and only transformed per frame (shader local matrices, canvas transforms).
 *  - The logo is decoded once with `inScaled = false` (no density up-scaling of the bitmap).
 *  - A missing logo never crashes the launch; the ident simply completes immediately.
 *  - The completion callback fires exactly once, whether the ident ends, is skipped or is disposed.
 *  - The timeline pauses while the window is not visible and resumes afterwards.
 */
internal class CinematicJarvisIntroView(context: Context) : View(context) {

    /**
     * When true the finished ident dissolves to transparent so the content underneath is revealed
     * smoothly. Set it to false when the view sits over a blank surface and the host is about to
     * switch screens, so that nothing flashes through.
     */
    var dissolveOnFinish: Boolean = true

    private val density = resources.displayMetrics.density

    private val logo: Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeResource(resources, R.drawable.ic_jarvis_logo, options)
    }.getOrNull()

    // ---- Paints: created once, never in onDraw -------------------------------------------------
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hazePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 220, 255) }
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(150, 215, 255)
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 225, 255)
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    // ---- Geometry and shaders: rebuilt only when the size changes -------------------------------
    private val logoRect = RectF()
    private val sheenMatrix = Matrix()
    private val maskMatrix = Matrix()
    private var sheenShader: LinearGradient? = null
    private var maskShader: LinearGradient? = null
    private var centerX = 0f
    private var centerY = 0f
    private var baseLogoW = 0f
    private var baseLogoH = 0f
    private var featherPx = 0f
    private var sheenHalfW = 0f
    private var bloomRadius = 0f
    private var hazeRadius = 0f
    private var taglineBaseline = 0f
    private var underlineHalfW = 0f
    private val tagChars = TAGLINE.toCharArray()
    private val tagX = FloatArray(TAGLINE.length)

    // ---- Ambient motes (deterministic, allocation-free) -----------------------------------------
    private val moteX = FloatArray(MOTES)
    private val moteY = FloatArray(MOTES)
    private val moteSpeed = FloatArray(MOTES)
    private val moteSize = FloatArray(MOTES)
    private val motePhase = FloatArray(MOTES)

    // ---- Playback state -------------------------------------------------------------------------
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var skipAnimator: ValueAnimator? = null
    private var completion: (() -> Unit)? = null
    private var completed = false
    private var skipping = false

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "JARVIS opening animation"
        val random = Random(7)
        for (i in 0 until MOTES) {
            moteX[i] = random.nextFloat()
            moteY[i] = random.nextFloat()
            moteSpeed[i] = 0.012f + random.nextFloat() * 0.03f
            moteSize[i] = 0.7f + random.nextFloat() * 1.3f
            motePhase[i] = random.nextFloat() * (2f * PI.toFloat())
        }
    }

    // =============================================================================================
    // Layout
    // =============================================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val wf = w.toFloat()
        val hf = h.toFloat()

        backdropPaint.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(Color.rgb(2, 5, 14), Color.rgb(7, 15, 34), Color.rgb(1, 3, 10)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )

        centerX = wf * 0.5f
        centerY = hf * 0.46f

        val logoBitmap = logo
        if (logoBitmap != null && !logoBitmap.isRecycled) {
            val scale = min(wf * 0.74f / logoBitmap.width, hf * 0.30f / logoBitmap.height)
            baseLogoW = logoBitmap.width * scale
            baseLogoH = logoBitmap.height * scale
        }
        featherPx = baseLogoW * 0.30f
        sheenHalfW = baseLogoW * 0.20f

        // The backlight. Shaders are defined around the origin; onDraw translates and scales.
        bloomRadius = wf * 0.62f
        bloomPaint.shader = RadialGradient(
            0f, 0f, bloomRadius,
            intArrayOf(
                Color.argb(235, 222, 236, 255),
                Color.argb(150, 120, 170, 255),
                Color.argb(50, 60, 70, 200),
                Color.argb(0, 40, 40, 160)
            ),
            floatArrayOf(0f, 0.35f, 0.70f, 1f), Shader.TileMode.CLAMP
        )
        hazeRadius = wf * 0.95f
        hazePaint.shader = RadialGradient(
            0f, 0f, hazeRadius,
            intArrayOf(Color.argb(46, 100, 70, 220), Color.argb(0, 60, 40, 160)),
            null, Shader.TileMode.CLAMP
        )

        // Opening hairline (clipped per frame to grow from the centre).
        hairlinePaint.shader = LinearGradient(
            0f, 0f, wf, 0f,
            intArrayOf(Color.TRANSPARENT, Color.rgb(190, 235, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )

        // Specular sheen: a soft band defined around the origin, rotated and slid per frame.
        sheenShader = LinearGradient(
            -sheenHalfW, 0f, sheenHalfW, 0f,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Color.argb(200, 225, 245, 255),
                Color.argb(0, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP
        )

        // Feathered reveal mask: opaque to the left of the edge, transparent to the right.
        maskShader = LinearGradient(
            0f, 0f, featherPx, 0f,
            intArrayOf(Color.WHITE, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )

        // Credit line: measured once so the per-letter animation never measures text.
        val tagSize = min(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics),
            dp(15f)
        )
        tagPaint.textSize = tagSize
        val tracking = dp(5.5f)
        val wordGap = dp(12f)
        var total = 0f
        for (i in tagChars.indices) {
            tagX[i] = total
            total += if (tagChars[i] == ' ') wordGap else tagPaint.measureText(tagChars, i, 1) + tracking
        }
        total -= tracking
        val startX = centerX - total / 2f
        for (i in tagX.indices) tagX[i] += startX
        taglineBaseline = centerY + baseLogoH / 2f + dp(44f)

        underlineHalfW = wf * 0.15f
        underlinePaint.shader = LinearGradient(
            centerX - underlineHalfW, 0f, centerX + underlineHalfW, 0f,
            intArrayOf(Color.TRANSPARENT, Color.rgb(64, 202, 255), Color.rgb(158, 110, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
    }

    // =============================================================================================
    // Playback
    // =============================================================================================

    fun start(onDone: () -> Unit) {
        completion = onDone
        completed = false
        skipping = false
        animator?.removeAllListeners()
        animator?.cancel()
        skipAnimator?.removeAllListeners()
        skipAnimator?.cancel()
        alpha = 1f

        val artwork = logo
        if (artwork == null || artwork.isRecycled) {
            // Never block or crash the launch because of a decorative element.
            finishOnce()
            return
        }

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                if (dissolveOnFinish) {
                    alpha = 1f - seg(progress, EXIT_START, 1f, EASE_IN_CUBIC)
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) finishOnce()
                }
            })
            start()
        }
    }

    /** Fast, smooth skip: the scene fades out in a fraction of a second instead of cutting. */
    fun skip() {
        if (completed || skipping) return
        val running = animator
        if (running == null || !running.isRunning) {
            finishOnce()
            return
        }
        skipping = true
        running.removeAllListeners()
        running.cancel()
        skipAnimator = ValueAnimator.ofFloat(alpha, 0f).apply {
            duration = SKIP_FADE_MS
            interpolator = EASE_IN_OUT
            addUpdateListener { alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) finishOnce()
                }
            })
            start()
        }
    }

    private fun finishOnce() {
        if (completed) return
        completed = true
        val callback = completion
        completion = null
        callback?.invoke()
    }

    fun dispose() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        skipAnimator?.removeAllListeners()
        skipAnimator?.cancel()
        skipAnimator = null
        completion = null
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            animator?.resume()
            skipAnimator?.resume()
        } else {
            animator?.pause()
            skipAnimator?.pause()
        }
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
        // The bitmap is intentionally not recycled here: it is small, owned by this view only and
        // is released with it, which keeps a re-attached view from drawing a recycled bitmap.
    }

    // =============================================================================================
    // Drawing
    // =============================================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, backdropPaint)

        val artwork = logo
        if (artwork == null || artwork.isRecycled || baseLogoW <= 0f) return

        val p = progress
        val seconds = p * (DURATION_MS / 1000f)
        val exit = seg(p, EXIT_START, 1f, EASE_IN_CUBIC)

        // Phase windows on the shared timeline.
        val lineGrow = seg(p, 0.02f, 0.20f, EASE_OUT_QUINT)
        val lineFade = 1f - seg(p, 0.20f, 0.36f, EASE_IN_OUT)
        val bloomOpen = seg(p, 0.10f, 0.52f, EASE_OUT_EXPO)
        val bloomLevel = bloomOpen * (1f - 0.14f * seg(p, 0.60f, 1f, EASE_IN_OUT))
        val reveal = seg(p, 0.14f, 0.60f, EASE_OUT_QUINT)
        val settle = seg(p, 0.12f, 0.62f, EASE_OUT_EXPO)
        val push = seg(p, 0.12f, 1f, LINEAR)
        val sheen = seg(p, 0.50f, 0.82f, EASE_IN_OUT)

        // Everything after the backdrop eases outward slightly as the ident leaves.
        val exitScale = 1f + 0.035f * exit
        canvas.save()
        canvas.scale(exitScale, exitScale, centerX, centerY)

        drawMotes(canvas, w, h, seconds, seg(p, 0.05f, 0.35f, EASE_IN_OUT))

        // Wide violet haze, then the bright backlight opening from the hairline.
        if (bloomOpen > 0f) {
            canvas.save()
            canvas.translate(centerX, centerY)
            canvas.scale(0.6f + 0.4f * bloomOpen, 0.8f * (0.05f + 0.95f * bloomOpen))
            hazePaint.alpha = (255f * bloomOpen).toInt().coerceIn(0, 255)
            canvas.drawCircle(0f, 0f, hazeRadius, hazePaint)
            canvas.restore()

            canvas.save()
            canvas.translate(centerX, centerY)
            canvas.scale(0.55f + 0.45f * bloomOpen, 0.5f * (0.04f + 0.96f * bloomOpen))
            bloomPaint.alpha = (255f * bloomLevel).toInt().coerceIn(0, 255)
            canvas.drawCircle(0f, 0f, bloomRadius, bloomPaint)
            canvas.restore()
        }

        // The opening hairline grows from the centre and gives way to the bloom.
        if (lineGrow > 0f && lineFade > 0f) {
            val half = width * 0.46f * lineGrow
            hairlinePaint.alpha = (255f * lineFade).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.clipRect(centerX - half, centerY - dp(0.75f), centerX + half, centerY + dp(0.75f))
            canvas.drawRect(0f, centerY - dp(0.75f), w, centerY + dp(0.75f), hairlinePaint)
            canvas.restore()
        }

        // Logo: scale settles in with a very slow push-in so the end state is never static.
        val logoScale = 0.94f + 0.06f * settle + 0.016f * push
        val logoW = baseLogoW * logoScale
        val logoH = baseLogoH * logoScale
        val rise = dp(12f) * (1f - settle)
        logoRect.set(
            centerX - logoW / 2f, centerY - logoH / 2f + rise,
            centerX + logoW / 2f, centerY + logoH / 2f + rise
        )

        if (reveal > 0f) {
            val layer = canvas.saveLayer(logoRect, null)
            canvas.drawBitmap(artwork, null, logoRect, logoPaint)

            if (sheen > 0f && sheen < 1f) {
                val shader = sheenShader
                if (shader != null) {
                    val travel = logoW + 2f * sheenHalfW
                    val x = logoRect.left - sheenHalfW + travel * sheen
                    sheenMatrix.setRotate(16f)
                    sheenMatrix.postTranslate(x, logoRect.centerY())
                    shader.setLocalMatrix(sheenMatrix)
                    sheenPaint.shader = shader
                    sheenPaint.alpha = (255f * sin(PI.toFloat() * sheen)).toInt().coerceIn(0, 255)
                    canvas.drawRect(logoRect, sheenPaint)
                }
            }

            if (reveal < 1f) {
                val shader = maskShader
                if (shader != null) {
                    val edge = logoRect.left + (logoW + featherPx) * reveal
                    maskMatrix.setTranslate(edge - featherPx, 0f)
                    shader.setLocalMatrix(maskMatrix)
                    maskPaint.shader = shader
                    canvas.drawRect(logoRect, maskPaint)
                }
            }
            canvas.restoreToCount(layer)
        }

        // Two ripples leave the orb on the "i" once the wipe has passed it.
        val dotX = logoRect.left + I_DOT_X * logoW
        val dotY = logoRect.top + I_DOT_Y * logoH
        drawRipple(canvas, dotX, dotY, seg(p, 0.52f, 0.86f, EASE_OUT_QUINT))
        drawRipple(canvas, dotX, dotY, seg(p, 0.60f, 0.92f, EASE_OUT_QUINT))

        // Credit line resolving letter by letter.
        for (i in tagChars.indices) {
            val c = tagChars[i]
            if (c == ' ') continue
            val t = seg(p, 0.60f + i * 0.010f, 0.74f + i * 0.010f, EASE_OUT_QUINT)
            if (t <= 0f) continue
            tagPaint.alpha = (215f * t).toInt().coerceIn(0, 255)
            canvas.drawText(tagChars, i, 1, tagX[i], taglineBaseline + dp(8f) * (1f - t), tagPaint)
        }

        // Closing light line under the credit.
        val underline = seg(p, 0.74f, 0.88f, EASE_OUT_QUINT)
        if (underline > 0f) {
            val half = underlineHalfW * underline
            val y = taglineBaseline + dp(16f)
            canvas.save()
            canvas.clipRect(centerX - half, y, centerX + half, y + dp(1.5f))
            canvas.drawRect(centerX - underlineHalfW, y, centerX + underlineHalfW, y + dp(1.5f), underlinePaint)
            canvas.restore()
        }

        canvas.restore()
    }

    private fun drawRipple(canvas: Canvas, x: Float, y: Float, t: Float) {
        if (t <= 0f || t >= 1f) return
        ringPaint.alpha = (190f * (1f - t).pow(1.5f)).toInt().coerceIn(0, 255)
        ringPaint.strokeWidth = dp(1.4f) * (1f - t) + dp(0.4f)
        canvas.drawCircle(x, y, dp(5f) + dp(70f) * t, ringPaint)
    }

    private fun drawMotes(canvas: Canvas, w: Float, h: Float, seconds: Float, level: Float) {
        if (level <= 0f) return
        for (i in 0 until MOTES) {
            var y = moteY[i] - moteSpeed[i] * seconds
            y -= floor(y)
            val x = moteX[i] * w + sin(seconds * 1.1f + motePhase[i]) * dp(4f)
            val twinkle = 0.25f + 0.75f * (0.5f + 0.5f * sin(seconds * 2.2f + motePhase[i]))
            motePaint.alpha = (110f * twinkle * level).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y * h, dp(moteSize[i]), motePaint)
        }
    }

    /** Eased progress of [p] through the window [start]..[end] of the shared timeline. */
    private fun seg(p: Float, start: Float, end: Float, ease: Interpolator): Float {
        val t = ((p - start) / (end - start)).coerceIn(0f, 1f)
        return ease.getInterpolation(t)
    }

    private fun dp(value: Float): Float = value * density

    private companion object {
        const val TAGLINE = "JARVIS BY SUKANT"
        const val DURATION_MS = 3600L
        const val SKIP_FADE_MS = 220L
        const val EXIT_START = 0.91f
        const val MOTES = 26

        // Position of the orb on the "i" within the 868x256 master logo.
        const val I_DOT_X = 0.787f
        const val I_DOT_Y = 0.234f

        val LINEAR: Interpolator = LinearInterpolator()
        val EASE_OUT_EXPO: Interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val EASE_OUT_QUINT: Interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
        val EASE_IN_OUT: Interpolator = PathInterpolator(0.65f, 0f, 0.35f, 1f)
        val EASE_IN_CUBIC: Interpolator = PathInterpolator(0.32f, 0f, 0.67f, 0f)
    }
}
