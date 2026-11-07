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
import android.graphics.PorterDuffXfermode
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Original JARVIS brand ident. Draws the app's bundled master logo directly (no text recreation),
 * with a restrained lens flare, spectral light trails and a polished metallic reveal.
 * All drawing objects are retained; onDraw performs no bitmap decoding or object allocation.
 */
internal class CinematicJarvisIntroView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    // Share the original master mark used by HomeFragment; the intro never substitutes a new logo.
    private val logo: Bitmap = BitmapFactory.decodeResource(resources, com.jarvis.assistant.R.drawable.ic_jarvis_logo)
        ?: throw IllegalStateException("JARVIS brand artwork is missing")
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reflectionXfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.24f
    }
    private val logoRect = RectF()
    private val trailPath = Path()
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var completion: (() -> Unit)? = null
    private var completed = false
    private val durationMs = 4600L

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "JARVIS BY SUKANT cinematic logo reveal"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backdrop.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.rgb(2, 5, 14), Color.rgb(7, 15, 34), Color.rgb(1, 3, 10)),
            null, Shader.TileMode.CLAMP
        )
    }

    fun start(onDone: () -> Unit) {
        completion = onDone
        completed = false
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finishOnce()
                }
            })
            start()
        }
    }

    fun skip() {
        val running = animator
        if (running?.isRunning == true) running.end() else finishOnce()
    }

    private fun finishOnce() {
        if (completed) return
        completed = true
        completion?.invoke()
        completion = null
    }

    fun dispose() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        completion = null
        if (!logo.isRecycled) logo.recycle()
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
        canvas.drawRect(0f, 0f, w, h, backdrop)

        val centerX = w * 0.5f
        val centerY = h * 0.47f
        val maxLogoW = w * 0.72f
        val maxLogoH = h * 0.34f
        val scale = min(maxLogoW / logo.width, maxLogoH / logo.height)
        val baseW = logo.width * scale
        val baseH = logo.height * scale
        val reveal = smooth((progress - 0.05f) / 0.42f)
        val settle = smooth((progress - 0.60f) / 0.25f)
        val currentScale = 0.72f + 0.28f * reveal + 0.025f * (1f - settle)
        val logoW = baseW * currentScale
        val logoH = baseH * currentScale
        logoRect.set(centerX - logoW / 2f, centerY - logoH / 2f, centerX + logoW / 2f, centerY + logoH / 2f)

        // Diffuse aura is drawn behind the exact source artwork.
        glowPaint.shader = android.graphics.RadialGradient(
            centerX, centerY, max(logoW, logoH) * 0.92f,
            intArrayOf(Color.argb((95 * reveal).toInt(), 47, 155, 255),
                Color.argb((42 * reveal).toInt(), 126, 78, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, max(logoW, logoH), glowPaint)

        // Three restrained spectral ribbons converge behind the logo.
        val trailT = smooth((progress - 0.12f) / 0.58f)
        for (i in 0..2) {
            val y = centerY + (i - 1) * dp(24f)
            trailPath.reset()
            trailPath.moveTo(-w * 0.08f, y + dp(42f))
            trailPath.cubicTo(w * 0.24f, y - dp(74f), w * 0.72f, y + dp(74f), w * (0.30f + trailT * 0.70f), y - dp(36f))
            trailPaint.style = Paint.Style.STROKE
            trailPaint.strokeWidth = dp(if (i == 1) 2.2f else 1.1f)
            trailPaint.shader = LinearGradient(
                0f, y - dp(75f), w, y + dp(75f),
                intArrayOf(Color.TRANSPARENT,
                    if (i == 1) Color.argb((180 * trailT).toInt(), 55, 210, 255)
                    else Color.argb((115 * trailT).toInt(), 151, 105, 255),
                    Color.TRANSPARENT), null, Shader.TileMode.CLAMP
            )
            canvas.drawPath(trailPath, trailPaint)
        }
        trailPaint.shader = null

        val logoAlpha = (255 * reveal).toInt().coerceIn(0, 255)
        logoPaint.alpha = logoAlpha
        // Isolate the logo in a layer so SRC_ATOP clips the reflection to its real alpha silhouette.
        val logoLayer = canvas.saveLayer(logoRect, null)
        canvas.drawBitmap(logo, null, logoRect, logoPaint)

        // A narrow moving white-blue reflection passes over the original logo.
        if (progress in 0.34f..0.82f) {
            val sweep = smooth((progress - 0.34f) / 0.48f)
            val x = logoRect.left - logoW * 0.35f + logoW * 1.7f * sweep
            val half = dp(38f)
            flarePaint.shader = LinearGradient(
                x - half, 0f, x + half, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(0, 255, 255, 255),
                    Color.argb((150 * reveal).toInt(), 205, 245, 255),
                    Color.argb(0, 255, 255, 255), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            flarePaint.xfermode = reflectionXfermode
            canvas.drawRect(logoRect, flarePaint)
            flarePaint.xfermode = null
            flarePaint.shader = null
        }
        canvas.restoreToCount(logoLayer)

        val brandAlpha = smooth((progress - 0.64f) / 0.22f)
        titlePaint.textSize = dp(12f).coerceAtLeast(resources.displayMetrics.scaledDensity * 10f)
        titlePaint.alpha = (220 * brandAlpha).toInt()
        canvas.drawText("J A R V I S   B Y   S U K A N T", centerX, logoRect.bottom + dp(42f), titlePaint)

        // A final horizontal light line closes the ident before the existing welcome slides.
        val lineProgress = smooth((progress - 0.78f) / 0.18f)
        flarePaint.shader = LinearGradient(
            centerX - w * 0.34f * lineProgress, 0f,
            centerX + w * 0.34f * lineProgress, 0f,
            intArrayOf(Color.TRANSPARENT, Color.rgb(64, 202, 255), Color.rgb(158, 110, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        flarePaint.alpha = (220 * (1f - smooth((progress - 0.96f) / 0.04f))).toInt()
        canvas.drawRect(centerX - w * 0.34f * lineProgress, logoRect.bottom + dp(58f),
            centerX + w * 0.34f * lineProgress, logoRect.bottom + dp(60f), flarePaint)
        flarePaint.shader = null
    }

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun dp(value: Float): Float = value * density
}
