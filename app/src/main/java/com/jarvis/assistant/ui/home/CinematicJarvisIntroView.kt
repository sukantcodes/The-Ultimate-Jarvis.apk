package com.jarvis.assistant.ui.home

import com.jarvis.assistant.R

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
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.min

/**
 * Cinematic JARVIS brand reveal.
 *
 * The mark remains the hero and is rendered in its original artwork colours. Motion is
 * authored as one deterministic timeline: atmosphere -> aperture -> mark reveal -> scan ->
 * creator signature -> purpose -> welcome. Geometry is recalculated for the actual window.
 *
 * No per-frame object allocation is performed in onDraw; shaders and reusable geometry are
 * prepared in onSizeChanged. The public host contract is intentionally unchanged.
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
    private val aperturePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val microPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val logoRect = RectF()
    private val frameRect = RectF()
    private val aperturePath = Path()

    private var cx = 0f
    private var cy = 0f
    private var logoWidth = 0f
    private var logoHeight = 0f
    private var titleSize = 0f
    private var microSize = 0f
    private var detailSize = 0f
    private var frameLength = 0f
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var skipAnimator: ValueAnimator? = null
    private var completion: (() -> Unit)? = null
    private var completed = false
    private var skipping = false

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "JARVIS cinematic opening animation"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val wf = w.toFloat()
        val hf = h.toFloat()
        val shortSide = min(wf, hf)
        val landscape = wf > hf
        cx = wf * 0.5f
        cy = if (landscape) hf * 0.365f else hf * 0.365f

        backgroundPaint.shader = LinearGradient(
            0f, 0f, wf, hf,
            intArrayOf(
                Color.rgb(2, 7, 15),
                Color.rgb(4, 13, 25),
                Color.rgb(2, 5, 12)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        atmospherePaint.shader = RadialGradient(
            cx, cy, shortSide * 0.78f,
            intArrayOf(
                Color.argb(48, 31, 128, 190),
                Color.argb(22, 17, 76, 126),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        aperturePaint.shader = LinearGradient(
            0f, 0f, wf, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(35, 70, 180, 235),
                Color.argb(220, 190, 239, 255),
                Color.argb(35, 70, 180, 235),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.38f, 0.5f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        scanPaint.shader = LinearGradient(
            0f, 0f, 0f, shortSide * 0.34f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(0, 115, 211, 255),
                Color.argb(95, 118, 215, 255),
                Color.argb(0, 115, 211, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.30f, 0.50f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )

        val artwork = logo
        if (artwork != null && !artwork.isRecycled) {
            val maxWidth = if (landscape) wf * 0.62f else wf * 0.84f
            val maxHeight = if (landscape) hf * 0.29f else hf * 0.235f
            val scale = min(maxWidth / artwork.width, maxHeight / artwork.height)
            logoWidth = artwork.width * scale
            logoHeight = artwork.height * scale
        } else {
            logoWidth = 0f
            logoHeight = 0f
        }

        titleSize = (shortSide * 0.040f).coerceIn(sp(13f), sp(22f))
        microSize = (shortSide * 0.020f).coerceIn(sp(8f), sp(11f))
        detailSize = (shortSide * 0.023f).coerceIn(sp(9f), sp(13f))
        frameLength = (shortSide * 0.035f).coerceIn(dp(12f), dp(26f))
        framePaint.strokeWidth = dp(1f)
        accentPaint.strokeWidth = dp(1.15f)
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
                if (dissolveOnFinish) {
                    alpha = 1f - segment(progress, 0.972f, 1f, EASE_IN)
                }
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
            addUpdateListener {
                alpha = it.animatedValue as Float
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
        val shortSide = min(w, h)
        val landscape = w > h
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        // A slow, broad atmospheric arrival gives the black stage depth without visual noise.
        val atmosphereIn = segment(p, 0.015f, 0.48f, EASE_OUT)
        atmospherePaint.alpha = (255f * atmosphereIn).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, atmospherePaint)

        // The aperture opens horizontally from one precise centre point.
        val apertureIn = segment(p, 0.045f, 0.31f, EASE_OUT)
        val apertureOut = 1f - segment(p, 0.38f, 0.53f, EASE_IN_OUT)
        val apertureHalf = w * 0.47f * apertureIn
        aperturePaint.alpha = (255f * apertureOut).toInt().coerceIn(0, 255)
        canvas.drawRect(
            cx - apertureHalf, cy - dp(0.7f),
            cx + apertureHalf, cy + dp(0.7f), aperturePaint
        )

        // Fine corner registration marks frame the logo like a precision instrument.
        val logoIn = segment(p, 0.20f, 0.57f, EASE_OUT)
        if (logoWidth > 0f && logoIn > 0f) {
            val logoSettle = segment(p, 0.18f, 0.64f, EASE_OUT_BACK)
            val scale = 0.965f + 0.035f * logoSettle
            val drawW = logoWidth * scale
            val drawH = logoHeight * scale
            logoRect.set(
                cx - drawW * 0.5f, cy - drawH * 0.5f,
                cx + drawW * 0.5f, cy + drawH * 0.5f
            )

            val reveal = segment(p, 0.20f, 0.57f, EASE_OUT)
            val clipHalf = (drawW * 0.5f + dp(2f)) * reveal
            canvas.save()
            canvas.clipRect(cx - clipHalf, logoRect.top - dp(3f), cx + clipHalf, logoRect.bottom + dp(3f))
            logoPaint.alpha = (255f * min(1f, reveal * 1.16f)).toInt().coerceIn(0, 255)
            // Intentionally no colour filter: preserve the supplied JARVIS logo artwork.
            canvas.drawBitmap(logo!!, null, logoRect, logoPaint)
            canvas.restore()

            val frameIn = segment(p, 0.31f, 0.62f, EASE_OUT)
            frameRect.set(
                logoRect.left - dp(17f), logoRect.top - dp(13f),
                logoRect.right + dp(17f), logoRect.bottom + dp(13f)
            )
            framePaint.color = Color.argb((145f * frameIn).toInt(), 103, 195, 235)
            drawCornerFrame(canvas, frameRect, frameLength * frameIn)

            // A narrow scan passes across the artwork once; it never loops or distracts.
            val scan = segment(p, 0.39f, 0.69f, EASE_IN_OUT)
            if (scan > 0f && scan < 1f) {
                val scanX = logoRect.left - dp(12f) + (logoRect.width() + dp(24f)) * scan
                val bandHalf = dp(13f)
                canvas.save()
                canvas.clipRect(logoRect.left, logoRect.top, logoRect.right, logoRect.bottom)
                scanPaint.alpha = (255f * (1f - kotlin.math.abs(scan - 0.5f) * 0.55f)).toInt()
                canvas.drawRect(scanX - bandHalf, logoRect.top, scanX + bandHalf, logoRect.bottom, scanPaint)
                canvas.restore()
                accentPaint.color = Color.argb(185, 133, 220, 255)
                accentPaint.alpha = 185
                canvas.drawLine(scanX, logoRect.top - dp(5f), scanX, logoRect.bottom + dp(5f), accentPaint)
            }
        }

        // Small uppercase descriptor is introduced only after the logo has settled.
        val descriptorIn = segment(p, 0.48f, 0.65f, EASE_OUT)
        if (descriptorIn > 0f) {
            microPaint.textSize = microSize
            microPaint.color = Color.argb((150f * descriptorIn).toInt(), 142, 183, 207)
            drawTrackedText(canvas, "PERSONAL INTELLIGENCE SYSTEM", cx, h * if (landscape) 0.665f else 0.625f, microPaint, 0.16f)
        }

        // Creator signature: restrained hierarchy, no competing panel or decorative card.
        val creditIn = segment(p, 0.60f, 0.78f, EASE_OUT)
        if (creditIn > 0f) {
            microPaint.textSize = microSize
            microPaint.color = Color.argb((170f * creditIn).toInt(), 153, 181, 201)
            drawTrackedText(
                canvas, "CREATED AND DESIGNED BY", cx,
                h * if (landscape) 0.735f else 0.687f,
                microPaint, 0.19f
            )

            textPaint.textSize = titleSize
            textPaint.color = Color.argb((248f * creditIn).toInt(), 239, 247, 255)
            textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            drawFittedText(
                canvas, "SUKANTA BHATTACHARYA", cx,
                h * if (landscape) 0.795f else 0.738f,
                textPaint, w * 0.88f
            )
        }

        val purposeIn = segment(p, 0.72f, 0.86f, EASE_OUT)
        if (purposeIn > 0f) {
            microPaint.textSize = detailSize
            microPaint.color = Color.argb((172f * purposeIn).toInt(), 151, 179, 201)
            drawFittedText(
                canvas, "TO HANDLE ALL YOUR NECESSARY WORKS", cx,
                h * if (landscape) 0.845f else 0.785f,
                microPaint, w * 0.88f
            )
        }

        // Final lockup is the last thing to arrive and the last thing the viewer reads.
        val welcomeIn = segment(p, 0.85f, 0.965f, EASE_OUT)
        if (welcomeIn > 0f) {
            val baseline = h * if (landscape) 0.925f else 0.875f
            accentPaint.color = Color.argb((205f * welcomeIn).toInt(), 83, 190, 244)
            accentPaint.alpha = (205f * welcomeIn).toInt().coerceIn(0, 255)
            val ruleHalf = dp(27f) * welcomeIn
            canvas.drawLine(cx - ruleHalf, baseline - dp(27f), cx + ruleHalf, baseline - dp(27f), accentPaint)

            textPaint.textSize = (titleSize * 0.92f).coerceAtLeast(sp(12f))
            textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textPaint.color = Color.argb((250f * welcomeIn).toInt(), 226, 243, 255)
            drawTrackedText(canvas, "WELCOME TO JARVIS", cx, baseline, textPaint, 0.105f)
        }

        // A subtle edge vignette keeps attention on the mark without a visible frame.
        val edgeAlpha = (18f * atmosphereIn).toInt().coerceIn(0, 18)
        framePaint.style = Paint.Style.STROKE
        framePaint.strokeWidth = dp(1f)
        framePaint.color = Color.argb(edgeAlpha, 70, 135, 175)
        val inset = shortSide * 0.035f
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, dp(9f), dp(9f), framePaint)
    }

    private fun drawCornerFrame(canvas: Canvas, rect: RectF, length: Float) {
        if (length <= 0f) return
        val l = min(length, min(rect.width(), rect.height()) * 0.24f)
        canvas.drawLine(rect.left, rect.top, rect.left + l, rect.top, framePaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + l, framePaint)
        canvas.drawLine(rect.right - l, rect.top, rect.right, rect.top, framePaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + l, framePaint)
        canvas.drawLine(rect.left, rect.bottom - l, rect.left, rect.bottom, framePaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + l, rect.bottom, framePaint)
        canvas.drawLine(rect.right - l, rect.bottom, rect.right, rect.bottom, framePaint)
        canvas.drawLine(rect.right, rect.bottom - l, rect.right, rect.bottom, framePaint)
    }

    private fun drawFittedText(canvas: Canvas, value: String, x: Float, baseline: Float, paint: Paint, maxWidth: Float) {
        val measured = paint.measureText(value)
        val scale = if (measured > maxWidth && measured > 0f) maxWidth / measured else 1f
        canvas.save()
        canvas.scale(scale, 1f, x, baseline)
        canvas.drawText(value, x, baseline, paint)
        canvas.restore()
    }

    private fun drawTrackedText(
        canvas: Canvas,
        value: String,
        x: Float,
        baseline: Float,
        paint: Paint,
        trackingEm: Float
    ) {
        val tracking = paint.textSize * trackingEm
        val total = paint.measureText(value) + tracking * (value.length - 1).coerceAtLeast(0)
        var cursor = x - total * 0.5f
        val scale = if (total > width * 0.90f && total > 0f) width * 0.90f / total else 1f
        canvas.save()
        canvas.scale(scale, 1f, x, baseline)
        value.forEach { character ->
            val glyph = character.toString()
            canvas.drawText(glyph, cursor, baseline, paint)
            cursor += paint.measureText(glyph) + tracking
        }
        canvas.restore()
    }

    private fun segment(value: Float, start: Float, end: Float, easing: Interpolator): Float {
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return easing.getInterpolation(t)
    }

    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private companion object {
        const val DURATION_MS = 5900L
        const val SKIP_FADE_MS = 220L
        val EASE_OUT: Interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val EASE_OUT_BACK: Interpolator = PathInterpolator(0.18f, 1.12f, 0.28f, 1f)
        val EASE_IN_OUT: Interpolator = PathInterpolator(0.65f, 0f, 0.35f, 1f)
        val EASE_IN: Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
    }
}
