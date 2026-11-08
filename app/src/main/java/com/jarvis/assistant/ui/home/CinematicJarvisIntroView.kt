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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
    private val energyGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val energyCore = Paint(Paint.ANTI_ALIAS_FLAG)
    private val energyRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val energyDetail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val energyParticle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val energyRect = RectF()
    private val starX = FloatArray(36)
    private val starY = FloatArray(36)
    private val starSize = FloatArray(36)
    private var coreRadius = 0f

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

        coreRadius = shortSide * if (landscape) 0.105f else 0.125f
        energyGlow.shader = RadialGradient(0f, 0f, coreRadius * 2.8f,
            intArrayOf(Color.argb(105, 39, 205, 255), Color.argb(42, 107, 72, 224), Color.TRANSPARENT),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
        energyCore.shader = RadialGradient(-coreRadius * 0.30f, -coreRadius * 0.35f, coreRadius * 1.55f,
            intArrayOf(Color.rgb(242, 255, 255), Color.rgb(76, 224, 255), Color.rgb(41, 115, 239),
                Color.rgb(119, 67, 222), Color.rgb(8, 15, 43)),
            floatArrayOf(0f, 0.23f, 0.52f, 0.79f, 1f), Shader.TileMode.CLAMP)
        energyRing.strokeWidth = dp(1.2f)
        energyDetail.strokeWidth = dp(0.8f)
        for (i in starX.indices) {
            starX[i] = (((i * 7919) % 997) / 997f) * wf
            starY[i] = (((i * 104729) % 991) / 991f) * hf
            starSize[i] = dp(0.6f + ((i * 17) % 9) * 0.16f)
        }

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

        // Deep-space atmosphere and a living energy core establish depth before the logo arrives.
        val atmosphereIn = segment(p, 0.015f, 0.48f, EASE_OUT)
        atmospherePaint.alpha = (255f * atmosphereIn).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, atmospherePaint)
        drawStarfield(canvas, p, atmosphereIn, h)
        val coreIn = segment(p, 0.075f, 0.47f, EASE_OUT_BACK)
        if (coreIn > 0f) drawEnergyCore(canvas, p, coreIn)

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
        val logoIn = segment(p, 0.39f, 0.66f, EASE_OUT)
        if (logoWidth > 0f && logoIn > 0f) {
            val logoSettle = segment(p, 0.37f, 0.69f, EASE_OUT_BACK)
            val scale = 0.965f + 0.035f * logoSettle
            val drawW = logoWidth * scale
            val drawH = logoHeight * scale
            val logoY = cy + coreRadius * 1.92f
            logoRect.set(
                cx - drawW * 0.5f, logoY - drawH * 0.5f,
                cx + drawW * 0.5f, logoY + drawH * 0.5f
            )

            val reveal = segment(p, 0.39f, 0.66f, EASE_OUT)
            val clipHalf = (drawW * 0.5f + dp(2f)) * reveal
            canvas.save()
            canvas.clipRect(cx - clipHalf, logoRect.top - dp(3f), cx + clipHalf, logoRect.bottom + dp(3f))
            logoPaint.alpha = (255f * min(1f, reveal * 1.16f)).toInt().coerceIn(0, 255)
            // Intentionally no colour filter: preserve the supplied JARVIS logo artwork.
            canvas.drawBitmap(logo!!, null, logoRect, logoPaint)
            canvas.restore()

            val frameIn = segment(p, 0.48f, 0.70f, EASE_OUT)
            frameRect.set(
                logoRect.left - dp(17f), logoRect.top - dp(13f),
                logoRect.right + dp(17f), logoRect.bottom + dp(13f)
            )
            framePaint.color = Color.argb((145f * frameIn).toInt(), 103, 195, 235)
            drawCornerFrame(canvas, frameRect, frameLength * frameIn)

            // A narrow scan passes across the artwork once; it never loops or distracts.
            val scan = segment(p, 0.53f, 0.75f, EASE_IN_OUT)
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
        val descriptorIn = segment(p, 0.58f, 0.73f, EASE_OUT)
        if (descriptorIn > 0f) {
            microPaint.textSize = microSize
            microPaint.color = Color.argb((150f * descriptorIn).toInt(), 142, 183, 207)
            drawTrackedText(canvas, "PERSONAL INTELLIGENCE SYSTEM", cx, h * if (landscape) 0.665f else 0.625f, microPaint, 0.16f)
        }

        // Creator signature: restrained hierarchy, no competing panel or decorative card.
        val creditIn = segment(p, 0.68f, 0.83f, EASE_OUT)
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

        val purposeIn = segment(p, 0.80f, 0.91f, EASE_OUT)
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
        val welcomeIn = segment(p, 0.90f, 0.985f, EASE_OUT)
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


    /** Deterministic drifting particles; no per-frame allocations or random flicker. */
    private fun drawStarfield(canvas: Canvas, p: Float, reveal: Float, h: Float) {
        for (i in starX.indices) {
            val y = (starY[i] - p * h * (0.10f + (i % 7) * 0.025f) + h) % h
            val twinkle = 0.45f + 0.55f * abs(sin(p * 6.28318f * 2f + i * 0.83f))
            energyParticle.color = Color.argb((112f * reveal * twinkle).toInt().coerceIn(0, 112), 116, 211, 245)
            canvas.drawCircle(starX[i], y, starSize[i], energyParticle)
        }
    }

    /** Layered orb with independently moving elliptical orbits, radial spokes and a breathing core. */
    private fun drawEnergyCore(canvas: Canvas, p: Float, reveal: Float) {
        val energy = 0.55f + 0.20f * sin(p * 18.8495f) + 0.10f * sin(p * 43.982f)
        val breath = 1f + 0.028f * sin(p * 15.708f)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(reveal, reveal)
        energyGlow.alpha = (190f + 55f * energy).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, coreRadius * 2.8f, energyGlow)
        energyRing.color = Color.argb((80f + 70f * energy).toInt(), 72, 205, 255)
        energyRing.strokeWidth = dp(1.2f)
        canvas.drawCircle(0f, 0f, coreRadius * 1.78f, energyRing)
        canvas.save()
        canvas.scale(breath, breath)
        canvas.drawCircle(0f, 0f, coreRadius, energyCore)
        canvas.restore()

        drawOrbit(canvas, p, 1.72f, 0.56f, 22f, 1.0f, Color.rgb(70, 222, 255), 0f)
        drawOrbit(canvas, p, 1.58f, 0.48f, -39f, -1.7f, Color.rgb(158, 111, 255), 1.8f)
        drawOrbit(canvas, p, 1.42f, 0.40f, 72f, 2.3f, Color.rgb(73, 147, 255), 3.6f)

        energyDetail.strokeWidth = dp(0.8f)
        for (i in 0 until 48) {
            val a = i * 6.28318f / 48f + p * 0.35f
            val inner = coreRadius * 2.08f
            val outer = inner + dp(if (i % 4 == 0) 7f else 3f)
            energyDetail.color = Color.argb(if (i % 4 == 0) 100 else 42, 95, 197, 235)
            canvas.drawLine(cos(a) * inner, sin(a) * inner, cos(a) * outer, sin(a) * outer, energyDetail)
        }
        val r = coreRadius * 2.18f
        energyDetail.color = Color.argb(62, 92, 185, 226)
        canvas.drawCircle(0f, 0f, r, energyDetail)
        energyRing.color = Color.argb(170, 75, 205, 255)
        energyRing.strokeWidth = dp(1.5f)
        energyRect.set(-r * 1.08f, -r * 1.08f, r * 1.08f, r * 1.08f)
        canvas.drawArc(energyRect, -90f + p * 45f, 248f, false, energyRing)
        for (i in 0 until 72) {
            val a = i * 6.28318f / 72f
            val major = i % 6 == 0
            val r1 = r * if (major) 1.12f else 1.16f
            val r2 = r * if (major) 1.24f else 1.195f
            energyDetail.color = Color.argb(if (major) 105 else 34, 105, 195, 232)
            canvas.drawLine(cos(a) * r1, sin(a) * r1, cos(a) * r2, sin(a) * r2, energyDetail)
        }
        canvas.restore()
    }

    private fun drawOrbit(canvas: Canvas, p: Float, rx: Float, ry: Float, tilt: Float,
                          turns: Float, color: Int, offset: Float) {
        canvas.save()
        canvas.rotate(tilt)
        aperturePath.reset()
        energyRect.set(-coreRadius * rx, -coreRadius * ry, coreRadius * rx, coreRadius * ry)
        aperturePath.addOval(energyRect, Path.Direction.CW)
        energyRing.color = Color.argb(128, Color.red(color), Color.green(color), Color.blue(color))
        energyRing.strokeWidth = dp(1.15f)
        canvas.drawPath(aperturePath, energyRing)
        val a = p * 6.28318f * turns + offset
        val x = coreRadius * rx * cos(a)
        val y = coreRadius * ry * sin(a)
        energyParticle.color = Color.argb(85, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawCircle(x, y, dp(7f), energyParticle)
        energyParticle.color = color
        canvas.drawCircle(x, y, dp(2.6f), energyParticle)
        canvas.restore()
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
        const val DURATION_MS = 8200L
        const val SKIP_FADE_MS = 220L
        val EASE_OUT: Interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val EASE_OUT_BACK: Interpolator = PathInterpolator(0.18f, 1.12f, 0.28f, 1f)
        val EASE_IN_OUT: Interpolator = PathInterpolator(0.65f, 0f, 0.35f, 1f)
        val EASE_IN: Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
    }
}
