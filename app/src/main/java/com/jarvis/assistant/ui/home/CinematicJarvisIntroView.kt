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
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.jarvis.assistant.R
import kotlin.math.min

/**
 * Minimal cinematic JARVIS brand ident.
 *
 * Art direction: near-black negative space, one controlled horizon light, a centre-out
 * wordmark reveal and deliberately typeset creator / welcome credits. The logo is treated
 * as the hero; there are no decorative orbit rings, floating particles or faux-glass panels.
 *
 * Rendering is allocation-free in onDraw. All geometry and shaders are prepared when the
 * view is sized. A single animator drives the complete ident and the existing host contract
 * (start, skip, dispose and dissolveOnFinish) is preserved.
 */
internal class CinematicJarvisIntroView(context: Context) : View(context) {

    var dissolveOnFinish: Boolean = true

    private val density = resources.displayMetrics.density
    private val logo: Bitmap? = runCatching {
        BitmapFactory.decodeResource(
            resources,
            R.drawable.ic_jarvis_logo,
            BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }.getOrNull()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val atmospherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.rgb(232, 245, 255), PorterDuff.Mode.SRC_IN)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacingCompat = 0f
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val logoRect = RectF()
    private var cx = 0f
    private var cy = 0f
    private var logoWidth = 0f
    private var logoHeight = 0f
    private var horizonHalfWidth = 0f
    private var titleSize = 0f
    private var creditSize = 0f
    private var detailSize = 0f
    private var animator: ValueAnimator? = null
    private var skipAnimator: ValueAnimator? = null
    private var completion: (() -> Unit)? = null
    private var progress = 0f
    private var completed = false
    private var skipping = false

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "JARVIS opening animation"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val wf = w.toFloat()
        val hf = h.toFloat()
        cx = wf * 0.5f
        cy = hf * 0.405f

        backgroundPaint.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(Color.rgb(1, 3, 8), Color.rgb(4, 9, 18), Color.rgb(1, 2, 6)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        val glowRadius = min(wf, hf) * 0.64f
        atmospherePaint.shader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(
                Color.argb(42, 35, 112, 180),
                Color.argb(18, 20, 65, 112),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        horizonPaint.shader = LinearGradient(
            0f, 0f, wf, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(70, 80, 178, 235),
                Color.argb(235, 190, 232, 255),
                Color.argb(70, 80, 178, 235),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.34f, 0.5f, 0.66f, 1f),
            Shader.TileMode.CLAMP
        )

        val artwork = logo
        if (artwork != null && !artwork.isRecycled) {
            val scale = min(wf * 0.84f / artwork.width, hf * 0.25f / artwork.height)
            logoWidth = artwork.width * scale
            logoHeight = artwork.height * scale
        } else {
            logoWidth = 0f
            logoHeight = 0f
        }

        horizonHalfWidth = wf * 0.44f
        titleSize = sp(15f).coerceAtMost(wf * 0.052f)
        creditSize = sp(9f).coerceAtMost(wf * 0.032f)
        detailSize = sp(10f).coerceAtMost(wf * 0.036f)
        textPaint.textSize = titleSize
        secondaryPaint.textSize = creditSize
        accentPaint.strokeWidth = dp(1f)
    }

    fun start(onDone: () -> Unit) {
        completion = onDone
        completed = false
        skipping = false
        animator?.removeAllListeners()
        animator?.cancel()
        skipAnimator?.removeAllListeners()
        skipAnimator?.cancel()
        progress = 0f
        alpha = 1f

        val artwork = logo
        if (artwork == null || artwork.isRecycled || width <= 0 || height <= 0) {
            finishOnce()
            return
        }

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                if (dissolveOnFinish) alpha = 1f - segment(progress, 0.965f, 1f, EASE_IN)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) finishOnce()
                }
            })
            start()
        }
    }

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
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
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
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val p = progress
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val atmosphere = segment(p, 0.08f, 0.62f, EASE_OUT)
        atmospherePaint.alpha = (255f * atmosphere).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, atmospherePaint)

        // A single hairline opens from the centre: the only environmental motion.
        val lineIn = segment(p, 0.02f, 0.30f, EASE_OUT)
        val lineOut = 1f - segment(p, 0.36f, 0.52f, EASE_IN_OUT)
        val lineHalf = horizonHalfWidth * lineIn
        horizonPaint.alpha = (255f * lineOut).toInt().coerceIn(0, 255)
        canvas.drawRect(cx - lineHalf, cy - dp(0.65f), cx + lineHalf, cy + dp(0.65f), horizonPaint)

        val logoReveal = segment(p, 0.22f, 0.68f, EASE_OUT)
        if (logoWidth > 0f && logoReveal > 0f) {
            val settle = segment(p, 0.20f, 0.72f, EASE_OUT_BACK)
            val scale = 0.965f + 0.035f * settle
            val drawW = logoWidth * scale
            val drawH = logoHeight * scale
            logoRect.set(
                cx - drawW * 0.5f,
                cy - drawH * 0.5f,
                cx + drawW * 0.5f,
                cy + drawH * 0.5f
            )
            logoPaint.alpha = (255f * min(1f, logoReveal * 1.18f)).toInt().coerceIn(0, 255)

            // Symmetrical reveal keeps the wordmark composed and makes the logo read as one mark.
            val halfReveal = (drawW * 0.5f + dp(18f)) * logoReveal
            canvas.save()
            canvas.clipRect(cx - halfReveal, logoRect.top, cx + halfReveal, logoRect.bottom)
            canvas.drawBitmap(logo!!, null, logoRect, logoPaint)
            canvas.restore()
        }

        // Creator credit resolves below the mark with deliberate hierarchy and generous spacing.
        val creditIn = segment(p, 0.60f, 0.77f, EASE_OUT)
        if (creditIn > 0f) {
            secondaryPaint.textSize = creditSize
            secondaryPaint.color = Color.argb((150f * creditIn).toInt(), 166, 190, 211)
            canvas.drawText("CREATED AND DESIGNED BY", cx, h * 0.625f, secondaryPaint)

            textPaint.textSize = titleSize
            textPaint.color = Color.argb((245f * creditIn).toInt(), 239, 247, 255)
            drawFittedCentered(canvas, "SUKANTA BHATTACHARYA", cx, h * 0.674f, textPaint, w * 0.86f)
        }

        val purposeIn = segment(p, 0.70f, 0.84f, EASE_OUT)
        if (purposeIn > 0f) {
            secondaryPaint.textSize = detailSize
            secondaryPaint.color = Color.argb((165f * purposeIn).toInt(), 151, 177, 201)
            drawFittedCentered(
                canvas,
                "TO HANDLE ALL YOUR NECESSARY WORKS",
                cx,
                h * 0.735f,
                secondaryPaint,
                w * 0.88f
            )
        }

        // The welcome is intentionally the final message, not competing with the logo reveal.
        val welcomeIn = segment(p, 0.82f, 0.94f, EASE_OUT)
        if (welcomeIn > 0f) {
            accentPaint.color = Color.argb((190f * welcomeIn).toInt(), 86, 190, 245)
            accentPaint.alpha = (190f * welcomeIn).toInt().coerceIn(0, 255)
            val lineW = dp(24f) * welcomeIn
            canvas.drawLine(cx - lineW, h * 0.815f, cx + lineW, h * 0.815f, accentPaint)

            textPaint.textSize = titleSize * 0.92f
            textPaint.color = Color.argb((245f * welcomeIn).toInt(), 226, 242, 255)
            drawFittedCentered(canvas, "WELCOME TO JARVIS", cx, h * 0.865f, textPaint, w * 0.84f)
        }
    }

    private fun drawFittedCentered(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        paint: Paint,
        maxWidth: Float
    ) {
        val measured = paint.measureText(value)
        val scale = if (measured > maxWidth && measured > 0f) maxWidth / measured else 1f
        canvas.save()
        canvas.scale(scale, 1f, x, baseline)
        canvas.drawText(value, x, baseline, paint)
        canvas.restore()
    }

    private fun segment(value: Float, start: Float, end: Float, easing: Interpolator): Float {
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return easing.getInterpolation(t)
    }

    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private companion object {
        const val DURATION_MS = 4400L
        const val SKIP_FADE_MS = 220L
        val EASE_OUT: Interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val EASE_OUT_BACK: Interpolator = PathInterpolator(0.18f, 1.18f, 0.28f, 1f)
        val EASE_IN_OUT: Interpolator = PathInterpolator(0.65f, 0f, 0.35f, 1f)
        val EASE_IN: Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
    }
}

/** Keeps the Paint API surface compatible with older Android releases. */
private var Paint.letterSpacingCompat: Float
    get() = 0f
    set(@Suppress("UNUSED_PARAMETER") value) = Unit
