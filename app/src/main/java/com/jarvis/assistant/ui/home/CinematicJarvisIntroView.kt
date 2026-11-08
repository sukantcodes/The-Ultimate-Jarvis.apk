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
import android.graphics.Matrix
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Premium glassmorphism JARVIS opening.
 *
 * Scene: a deep midnight backdrop lit by four slowly drifting aurora lights, with soft glass
 * bubbles floating above them. A frosted glass card (translucent fill, gradient rim light, top
 * sheen, soft depth shadow) rises into place and carries the JARVIS wordmark; a single light sweep
 * crosses it. Below, a second frosted panel carries the descriptor, creator credit and purpose,
 * and a glowing glass pill delivers the final "WELCOME TO JARVIS" lockup.
 *
 * The wordmark artwork is dark ink on transparency, so it is deliberately placed on the light
 * frosted card where it reads at full contrast, in its original colours.
 *
 * One deterministic timeline drives everything. Geometry and shaders are prepared in
 * onSizeChanged; onDraw performs no allocation. The public host contract is unchanged:
 * [dissolveOnFinish], [start], [skip], [dispose].
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

    // ---- paints -------------------------------------------------------------------------------
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassTint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassSheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pillGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val microPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    // ---- geometry (all reused; nothing is allocated in onDraw) -----------------------------------
    private val cardRect = RectF()
    private val shadowRect = RectF()
    private val logoRect = RectF()
    private val panelRect = RectF()
    private val pillRect = RectF()
    private val cardPath = Path()
    private val sweepMatrix = Matrix()
    private val sheenRect = RectF()

    private val blobColors = intArrayOf(
        Color.rgb(56, 189, 248),   // cyan
        Color.rgb(139, 92, 246),   // violet
        Color.rgb(59, 112, 246),   // blue
        Color.rgb(236, 72, 153)    // magenta
    )
    private val blobShaders = arrayOfNulls<RadialGradient>(4)
    private val blobRadius = FloatArray(4)

    private val bubbleCount = 16
    private val bubbleX = FloatArray(bubbleCount)
    private val bubbleY = FloatArray(bubbleCount)
    private val bubbleR = FloatArray(bubbleCount)
    private val bubbleSpeed = FloatArray(bubbleCount)
    private val bubblePhase = FloatArray(bubbleCount)

    private var cardRadius = 0f
    private var panelRadius = 0f
    private var logoWidth = 0f
    private var logoHeight = 0f
    private var titleSize = 0f
    private var microSize = 0f
    private var detailSize = 0f
    private var shortSideCache = 0f

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
        contentDescription = "JARVIS opening animation"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val wf = w.toFloat()
        val hf = h.toFloat()
        val shortSide = min(wf, hf)
        shortSideCache = shortSide
        val landscape = wf > hf

        // Midnight backdrop.
        backgroundPaint.shader = LinearGradient(
            0f, 0f, wf * 0.4f, hf,
            intArrayOf(Color.rgb(5, 8, 22), Color.rgb(9, 12, 36), Color.rgb(4, 6, 18)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )

        // Aurora lights are built around the origin and positioned with canvas.translate each frame.
        for (i in blobShaders.indices) {
            val r = shortSide * (if (i % 2 == 0) 0.82f else 0.68f)
            blobRadius[i] = r
            val c = blobColors[i]
            blobShaders[i] = RadialGradient(
                0f, 0f, r,
                intArrayOf(
                    Color.argb(150, Color.red(c), Color.green(c), Color.blue(c)),
                    Color.argb(58, Color.red(c), Color.green(c), Color.blue(c)),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }

        // Deterministic glass bubbles.
        for (i in 0 until bubbleCount) {
            bubbleX[i] = (((i * 7919) % 997) / 997f) * wf
            bubbleY[i] = (((i * 104729) % 991) / 991f) * hf
            bubbleR[i] = dp(5f + ((i * 13) % 11) * 2.4f)
            bubbleSpeed[i] = 0.05f + (i % 5) * 0.022f
            bubblePhase[i] = i * 0.9f
        }

        // Frosted card.
        val cardW = if (landscape) min(wf * 0.56f, hf * 1.25f) else wf * 0.88f
        val cardH = if (landscape) hf * 0.40f else min(hf * 0.235f, cardW * 0.58f)
        val cardCy = if (landscape) hf * 0.33f else hf * 0.335f
        cardRect.set(wf * 0.5f - cardW * 0.5f, cardCy - cardH * 0.5f, wf * 0.5f + cardW * 0.5f, cardCy + cardH * 0.5f)
        cardRadius = min(cardH * 0.22f, dp(34f))
        cardPath.reset()
        cardPath.addRoundRect(cardRect, cardRadius, cardRadius, Path.Direction.CW)

        glassFill.shader = LinearGradient(
            cardRect.left, cardRect.top, cardRect.right, cardRect.bottom,
            intArrayOf(Color.argb(238, 255, 255, 255), Color.argb(214, 238, 242, 255), Color.argb(196, 222, 230, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        glassTint.shader = LinearGradient(
            cardRect.left, cardRect.bottom, cardRect.right, cardRect.top,
            intArrayOf(Color.argb(70, 139, 92, 246), Color.argb(0, 255, 255, 255), Color.argb(60, 56, 189, 248)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        glassSheen.shader = LinearGradient(
            0f, cardRect.top, 0f, cardRect.top + cardH * 0.5f,
            intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
        glassRim.strokeWidth = dp(1.4f)
        glassRim.shader = LinearGradient(
            cardRect.left, cardRect.top, cardRect.right, cardRect.bottom,
            intArrayOf(Color.argb(255, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(200, 170, 215, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        sweepPaint.shader = LinearGradient(
            -cardH * 0.45f, 0f, cardH * 0.45f, 0f,
            intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(190, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )

        // Wordmark.
        val artwork = logo
        if (artwork != null && !artwork.isRecycled) {
            val maxW = cardW * 0.78f
            val maxH = cardH * 0.62f
            val scale = min(maxW / artwork.width, maxH / artwork.height)
            logoWidth = artwork.width * scale
            logoHeight = artwork.height * scale
        } else {
            logoWidth = 0f
            logoHeight = 0f
        }
        logoRect.set(
            cardRect.centerX() - logoWidth * 0.5f, cardRect.centerY() - logoHeight * 0.5f,
            cardRect.centerX() + logoWidth * 0.5f, cardRect.centerY() + logoHeight * 0.5f
        )

        // Lower frosted panel.
        val panelW = if (landscape) cardW else wf * 0.82f
        val panelTop = if (landscape) hf * 0.60f else hf * 0.545f
        val panelBottom = if (landscape) hf * 0.80f else hf * 0.775f
        panelRect.set(wf * 0.5f - panelW * 0.5f, panelTop, wf * 0.5f + panelW * 0.5f, panelBottom)
        panelRadius = min(panelRect.height() * 0.17f, dp(26f))
        panelFill.shader = LinearGradient(
            0f, panelRect.top, 0f, panelRect.bottom,
            intArrayOf(Color.argb(46, 255, 255, 255), Color.argb(18, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
        panelRim.strokeWidth = dp(1f)
        panelRim.shader = LinearGradient(
            panelRect.left, panelRect.top, panelRect.right, panelRect.bottom,
            intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(26, 255, 255, 255), Color.argb(90, 150, 200, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )

        // Welcome pill.
        val pillW = min(wf * 0.74f, dp(330f))
        val pillH = dp(52f)
        val pillCy = if (landscape) hf * 0.905f else hf * 0.865f
        pillRect.set(wf * 0.5f - pillW * 0.5f, pillCy - pillH * 0.5f, wf * 0.5f + pillW * 0.5f, pillCy + pillH * 0.5f)
        pillFill.shader = LinearGradient(
            pillRect.left, 0f, pillRect.right, 0f,
            intArrayOf(Color.argb(96, 56, 189, 248), Color.argb(70, 139, 92, 246), Color.argb(96, 236, 72, 153)),
            null, Shader.TileMode.CLAMP
        )
        pillRim.strokeWidth = dp(1.2f)
        pillRim.shader = LinearGradient(
            pillRect.left, 0f, pillRect.right, 0f,
            intArrayOf(Color.argb(230, 125, 215, 255), Color.argb(200, 186, 150, 255), Color.argb(230, 255, 140, 200)),
            null, Shader.TileMode.CLAMP
        )
        pillGlow.shader = RadialGradient(
            0f, 0f, pillW * 0.62f,
            intArrayOf(Color.argb(90, 120, 130, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )

        titleSize = (shortSide * 0.040f).coerceIn(sp(13f), sp(22f))
        microSize = (shortSide * 0.021f).coerceIn(sp(8.5f), sp(11.5f))
        detailSize = (shortSide * 0.024f).coerceIn(sp(9.5f), sp(13.5f))
        rulePaint.strokeWidth = dp(1.2f)
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
                    alpha = 1f - segment(progress, 0.968f, 1f, EASE_IN)
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

    // ---- drawing --------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val p = progress

        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val auroraIn = segment(p, 0f, 0.26f, EASE_OUT)
        drawAurora(canvas, w, h, p, auroraIn)
        drawBubbles(canvas, h, p, auroraIn)

        drawGlassCard(canvas, p)
        drawInfoPanel(canvas, p)
        drawWelcomePill(canvas, p)
    }

    /** Four soft lights drifting on slow Lissajous paths; the colour mix behind the glass is what sells the frost. */
    private fun drawAurora(canvas: Canvas, w: Float, h: Float, p: Float, reveal: Float) {
        if (reveal <= 0f) return
        val t = p * 6.28318f
        val alphaInt = (255f * reveal).toInt().coerceIn(0, 255)
        blobPaint.alpha = alphaInt
        for (i in 0 until 4) {
            val bx: Float
            val by: Float
            when (i) {
                0 -> { bx = w * (0.18f + 0.10f * sin(t * 0.9f)); by = h * (0.20f + 0.07f * cos(t * 0.7f)) }
                1 -> { bx = w * (0.86f + 0.08f * cos(t * 0.8f)); by = h * (0.38f + 0.09f * sin(t * 1.1f)) }
                2 -> { bx = w * (0.30f + 0.12f * cos(t * 0.6f)); by = h * (0.72f + 0.07f * sin(t * 0.9f)) }
                else -> { bx = w * (0.78f + 0.09f * sin(t * 0.7f)); by = h * (0.90f + 0.05f * cos(t * 1.2f)) }
            }
            canvas.save()
            canvas.translate(bx, by)
            blobPaint.shader = blobShaders[i]
            val r = blobRadius[i]
            canvas.drawCircle(0f, 0f, r, blobPaint)
            canvas.restore()
        }
        blobPaint.shader = null
    }

    private fun drawBubbles(canvas: Canvas, h: Float, p: Float, reveal: Float) {
        if (reveal <= 0f) return
        for (i in 0 until bubbleCount) {
            val y = ((bubbleY[i] - p * h * bubbleSpeed[i] * 2.2f) % h + h) % h
            val x = bubbleX[i] + sin(p * 6.28318f + bubblePhase[i]) * dp(9f)
            val r = bubbleR[i]
            val pulse = 0.65f + 0.35f * abs(sin(p * 9.42f + bubblePhase[i]))
            bubbleFill.color = Color.argb((26f * reveal * pulse).toInt().coerceIn(0, 40), 190, 225, 255)
            canvas.drawCircle(x, y, r, bubbleFill)
            bubbleRim.strokeWidth = dp(0.8f)
            bubbleRim.color = Color.argb((70f * reveal * pulse).toInt().coerceIn(0, 90), 215, 235, 255)
            canvas.drawCircle(x, y, r, bubbleRim)
        }
    }

    private fun drawGlassCard(canvas: Canvas, p: Float) {
        val rise = segment(p, 0.10f, 0.40f, EASE_OUT_BACK)
        val fade = segment(p, 0.10f, 0.30f, EASE_OUT)
        if (fade <= 0f) return

        val scale = 0.90f + 0.10f * rise
        val lift = (1f - rise) * dp(30f)
        val cxp = cardRect.centerX()
        val cyp = cardRect.centerY()
        val a = (255f * fade).toInt().coerceIn(0, 255)

        canvas.save()
        canvas.translate(0f, lift)
        canvas.scale(scale, scale, cxp, cyp)

        // Layered depth shadow (shadow layers are unavailable for shapes on older hardware renderers).
        for (i in 3 downTo 1) {
            val grow = dp(7f * i)
            shadowRect.set(cardRect.left - grow, cardRect.top - grow * 0.4f + dp(8f), cardRect.right + grow, cardRect.bottom + grow + dp(8f))
            shadowPaint.color = Color.argb((34f * fade / i).toInt().coerceIn(0, 40), 2, 4, 20)
            canvas.drawRoundRect(shadowRect, cardRadius + grow, cardRadius + grow, shadowPaint)
        }

        glassFill.alpha = a
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, glassFill)
        glassTint.alpha = a
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, glassTint)

        canvas.save()
        canvas.clipPath(cardPath)

        // Top sheen: the soft light edge that makes the surface read as glass.
        glassSheen.alpha = a
        sheenRect.set(cardRect.left, cardRect.top, cardRect.right, cardRect.top + cardRect.height() * 0.5f)
        canvas.drawRect(sheenRect, glassSheen)

        // Wordmark: soft wipe + settle, original colours, full contrast on the light glass.
        val logoIn = segment(p, 0.30f, 0.56f, EASE_OUT)
        if (logoWidth > 0f && logoIn > 0f) {
            val settle = 1.045f - 0.045f * segment(p, 0.30f, 0.60f, EASE_OUT)
            val dw = logoWidth * settle
            val dh = logoHeight * settle
            val lcx = cardRect.centerX()
            val lcy = cardRect.centerY()
            val reveal = segment(p, 0.30f, 0.58f, EASE_IN_OUT)
            val clipHalf = (dw * 0.5f + dp(10f)) * reveal
            canvas.save()
            canvas.clipRect(lcx - clipHalf, lcy - dh, lcx + clipHalf, lcy + dh)
            logoPaint.alpha = (255f * min(1f, logoIn * 1.2f)).toInt().coerceIn(0, 255)
            logoRect.set(lcx - dw * 0.5f, lcy - dh * 0.5f, lcx + dw * 0.5f, lcy + dh * 0.5f)
            canvas.drawBitmap(logo!!, null, logoRect, logoPaint)
            canvas.restore()
        }

        // A single light sweep crosses the glass once.
        val sweep = segment(p, 0.54f, 0.74f, EASE_IN_OUT)
        if (sweep > 0f && sweep < 1f) {
            val travel = cardRect.width() + cardRect.height()
            val sx = cardRect.left - cardRect.height() * 0.5f + travel * sweep
            sweepMatrix.reset()
            sweepMatrix.setSkew(-0.35f, 0f)
            sweepMatrix.postTranslate(sx, cardRect.centerY())
            sweepPaint.shader.setLocalMatrix(sweepMatrix)
            sweepPaint.alpha = (255f * (1f - abs(sweep - 0.5f) * 0.9f)).toInt().coerceIn(0, 255)
            canvas.drawRect(cardRect, sweepPaint)
        }
        canvas.restore()

        // Rim light drawn last so it sits crisp over the sheen and sweep.
        glassRim.alpha = a
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, glassRim)
        canvas.restore()
    }

    private fun drawInfoPanel(canvas: Canvas, p: Float) {
        val panelIn = segment(p, 0.56f, 0.74f, EASE_OUT)
        if (panelIn <= 0f) return
        val a = (255f * panelIn).toInt().coerceIn(0, 255)
        val lift = (1f - panelIn) * dp(18f)

        canvas.save()
        canvas.translate(0f, lift)
        panelFill.alpha = a
        canvas.drawRoundRect(panelRect, panelRadius, panelRadius, panelFill)
        panelRim.alpha = a
        canvas.drawRoundRect(panelRect, panelRadius, panelRadius, panelRim)

        val cx = panelRect.centerX()
        val top = panelRect.top
        val hgt = panelRect.height()

        val descIn = segment(p, 0.60f, 0.74f, EASE_OUT)
        if (descIn > 0f) {
            microPaint.textSize = microSize
            microPaint.color = Color.argb((190f * descIn).toInt(), 150, 214, 255)
            drawTrackedText(canvas, "PERSONAL INTELLIGENCE SYSTEM", cx, top + hgt * 0.20f, microPaint, 0.2f)
            val ruleHalf = dp(30f) * descIn
            rulePaint.color = Color.argb((120f * descIn).toInt(), 150, 214, 255)
            canvas.drawLine(cx - ruleHalf, top + hgt * 0.29f, cx + ruleHalf, top + hgt * 0.29f, rulePaint)
        }

        val creditIn = segment(p, 0.68f, 0.82f, EASE_OUT)
        if (creditIn > 0f) {
            microPaint.textSize = microSize
            microPaint.color = Color.argb((175f * creditIn).toInt(), 190, 206, 230)
            drawTrackedText(canvas, "CREATED AND DESIGNED BY", cx, top + hgt * 0.47f, microPaint, 0.2f)
            textPaint.textSize = titleSize
            textPaint.color = Color.argb((250f * creditIn).toInt(), 247, 250, 255)
            drawTrackedText(canvas, "SUKANTA BHATTACHARYA", cx, top + hgt * 0.67f, textPaint, 0.07f)
        }

        val purposeIn = segment(p, 0.78f, 0.90f, EASE_OUT)
        if (purposeIn > 0f) {
            microPaint.textSize = detailSize * 0.92f
            microPaint.color = Color.argb((185f * purposeIn).toInt(), 178, 196, 222)
            drawTrackedText(canvas, "TO HANDLE ALL YOUR NECESSARY WORKS", cx, top + hgt * 0.88f, microPaint, 0.09f)
        }
        canvas.restore()
    }

    private fun drawWelcomePill(canvas: Canvas, p: Float) {
        val pillIn = segment(p, 0.88f, 0.975f, EASE_OUT_BACK)
        val fade = segment(p, 0.88f, 0.95f, EASE_OUT)
        if (fade <= 0f) return
        val a = (255f * fade).toInt().coerceIn(0, 255)
        val scale = 0.88f + 0.12f * pillIn
        val radius = pillRect.height() * 0.5f

        canvas.save()
        canvas.scale(scale, scale, pillRect.centerX(), pillRect.centerY())

        canvas.save()
        canvas.translate(pillRect.centerX(), pillRect.centerY())
        pillGlow.alpha = a
        canvas.drawCircle(0f, 0f, pillRect.width() * 0.62f, pillGlow)
        canvas.restore()

        pillFill.alpha = a
        canvas.drawRoundRect(pillRect, radius, radius, pillFill)
        pillRim.alpha = a
        canvas.drawRoundRect(pillRect, radius, radius, pillRim)

        textPaint.textSize = (titleSize * 0.86f).coerceAtLeast(sp(12f))
        textPaint.color = Color.argb((252f * fade).toInt(), 255, 255, 255)
        val baseline = pillRect.centerY() + textPaint.textSize * 0.34f
        drawTrackedText(canvas, "WELCOME TO JARVIS", pillRect.centerX(), baseline, textPaint, 0.12f)
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
        val maxWidth = width * 0.80f
        val scale = if (total > maxWidth && total > 0f) maxWidth / total else 1f
        var cursor = x - total * 0.5f
        canvas.save()
        canvas.scale(scale, 1f, x, baseline)
        for (ch in value) {
            val glyph = ch.toString()
            canvas.drawText(glyph, cursor + paint.measureText(glyph) * 0.5f, baseline, paint)
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
        const val DURATION_MS = 6800L
        const val SKIP_FADE_MS = 220L
        val EASE_OUT: Interpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val EASE_OUT_BACK: Interpolator = PathInterpolator(0.18f, 1.12f, 0.28f, 1f)
        val EASE_IN_OUT: Interpolator = PathInterpolator(0.65f, 0f, 0.35f, 1f)
        val EASE_IN: Interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
    }
}
