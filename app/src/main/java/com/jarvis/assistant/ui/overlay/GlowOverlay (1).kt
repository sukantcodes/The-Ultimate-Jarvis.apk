package com.jarvis.assistant.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * A rich, dense neon glow that hugs the real edges of the screen, filled with hundreds of tiny glowing dots in
 * different neon colors that drift and wave along the border, shown briefly when Jarvis wakes up.
 *
 * It covers the whole physical display on any phone: behind the status bar and navigation bar, into the notch or
 * punch-hole area, and it follows the screen's rounded corners. It also refits itself when the screen rotates or a
 * foldable changes size. Needs "Display over other apps"; if that permission is off it does nothing.
 */
object GlowOverlay {
    private const val TAG = "GlowOverlay"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var glowView: GlowView? = null
    private var windowManager: WindowManager? = null
    private var animator: ValueAnimator? = null
    private val hideRunnable = Runnable { removeNow() }

    fun show(context: Context, durationMs: Long = 5_000L) {
        val appContext = context.applicationContext
        mainHandler.post {
            try {
                if (!Settings.canDrawOverlays(appContext)) return@post
                removeNow()
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val view = GlowView(appContext)
                view.durationSeconds = durationMs / 1000f

                @Suppress("DEPRECATION")
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val size = realScreenSize(wm)
                val params = WindowManager.LayoutParams(
                    size?.first ?: WindowManager.LayoutParams.MATCH_PARENT,
                    size?.second ?: WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        setFitInsetsTypes(0)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }

                // Refit when the screen rotates or a foldable opens or closes.
                view.onRefit = {
                    try {
                        val newSize = realScreenSize(wm)
                        if (newSize != null) {
                            params.width = newSize.first
                            params.height = newSize.second
                            wm.updateViewLayout(view, params)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not refit the glow: ${e.message}")
                    }
                }

                wm.addView(view, params)
                windowManager = wm
                glowView = view
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = durationMs
                    interpolator = LinearInterpolator()
                    addUpdateListener { view.progress = it.animatedFraction }
                    start()
                }
                mainHandler.postDelayed(hideRunnable, durationMs + 100L)
            } catch (e: Exception) {
                Log.w(TAG, "Could not show glow: ${e.message}")
            }
        }
    }

    /** The full physical display size in pixels, including the areas under the system bars and the cutout. */
    private fun realScreenSize(wm: WindowManager): Pair<Int, Int>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                Pair(metrics.widthPixels, metrics.heightPixels)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun removeNow() {
        mainHandler.removeCallbacks(hideRunnable)
        animator?.cancel()
        animator = null
        val view = glowView
        val wm = windowManager
        if (view != null && wm != null) {
            try {
                wm.removeViewImmediate(view)
            } catch (_: Exception) {
            }
        }
        glowView = null
    }

    private class Dot(
        val baseFraction: Float,
        val speed: Float,
        val depthDp: Float,
        val amplitudeDp: Float,
        val radiusDp: Float,
        val color: Int,
        val phase: Float,
        val twinkleHz: Float
    )

    private class GlowView(context: Context) : View(context) {
        var onRefit: (() -> Unit)? = null
        var durationSeconds = 5f

        var progress = 0f
            set(value) {
                field = value
                invalidate()
            }

        private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // Rich neon colors: electric blue, violet, hot pink, orange, amber, green, cyan.
        private val neon = intArrayOf(
            0xFF2979FF.toInt(), 0xFF7C4DFF.toInt(), 0xFFFF2D95.toInt(), 0xFFFF6A3D.toInt(),
            0xFFFFC400.toInt(), 0xFF00E676.toInt(), 0xFF00E5FF.toInt()
        )
        private val sweepColors = intArrayOf(
            neon[0], neon[1], neon[2], neon[3], neon[4], neon[5], neon[6], neon[0]
        )

        private val shaderMatrix = Matrix()
        private var sweep: SweepGradient? = null
        private var cornerRadius = 0f
        private var cornerResolved = false

        private val edgePath = Path()
        private val measure = PathMeasure()
        private var perimeter = 0f
        private var dots: List<Dot> = emptyList()
        private val pos = FloatArray(2)
        private val tan = FloatArray(2)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            sweep = SweepGradient(w / 2f, h / 2f, sweepColors, null)
            cornerResolved = false
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            onRefit?.invoke()
        }

        /** Uses the phone's real corner radius when Android reports it (Android 12+), otherwise a typical value. */
        private fun resolveCornerRadius() {
            cornerResolved = true
            var radius = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val insets = rootWindowInsets
                if (insets != null) {
                    val positions = intArrayOf(
                        RoundedCorner.POSITION_TOP_LEFT,
                        RoundedCorner.POSITION_TOP_RIGHT,
                        RoundedCorner.POSITION_BOTTOM_LEFT,
                        RoundedCorner.POSITION_BOTTOM_RIGHT
                    )
                    for (position in positions) {
                        val corner = insets.getRoundedCorner(position)
                        if (corner != null) radius = max(radius, corner.radius.toFloat())
                    }
                }
            }
            val density = resources.displayMetrics.density
            cornerRadius = if (radius > 0f) radius else 32f * density
            rebuildEdge(density)
        }

        /** Builds the path around the screen edge and scatters the glowing dots along it. */
        private fun rebuildEdge(density: Float) {
            edgePath.reset()
            edgePath.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), cornerRadius, cornerRadius, Path.Direction.CW)
            measure.setPath(edgePath, true)
            perimeter = measure.length

            val perimeterDp = perimeter / density
            val count = (perimeterDp / 9f).toInt().coerceIn(100, 170)
            val random = Random(7)
            dots = List(count) { index ->
                val big = random.nextFloat() < 0.08f
                Dot(
                    baseFraction = (index + random.nextFloat()) / count,
                    speed = 0.012f + random.nextFloat() * 0.04f,
                    depthDp = random.nextFloat() * random.nextFloat() * 70f,
                    amplitudeDp = 4f + random.nextFloat() * 10f,
                    radiusDp = if (big) 4.5f + random.nextFloat() * 2f else 1.6f + random.nextFloat() * 2f,
                    color = neon[random.nextInt(neon.size)],
                    phase = random.nextFloat(),
                    twinkleHz = 0.5f + random.nextFloat() * 1.5f
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            val shader = sweep ?: return
            val fadeIn = (progress / 0.15f).coerceIn(0f, 1f)
            val fadeOut = ((1f - progress) / 0.25f).coerceIn(0f, 1f)
            val visible = minOf(fadeIn, fadeOut)
            if (visible <= 0f) return
            if (!cornerResolved) resolveCornerRadius()

            val density = resources.displayMetrics.density
            val time = progress * durationSeconds
            val twoPi = 6.2831855f

            // 1. Dense neon band hugging the border, its colors slowly travelling around the screen.
            shaderMatrix.setRotate(progress * 540f, width / 2f, height / 2f)
            shader.setLocalMatrix(shaderMatrix)
            bandPaint.shader = shader
            val pulse = 1f + 0.12f * sin(time * twoPi * 0.9f)
            val layers = 10
            val w = width.toFloat()
            val h = height.toFloat()
            for (i in 0 until layers) {
                val fraction = i / layers.toFloat()
                bandPaint.strokeWidth = (5f + i * 6f) * density * pulse
                bandPaint.alpha = (visible * 255f * (1f - fraction) * 0.40f).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, bandPaint)
            }

            // 2. Hundreds of tiny neon dots drifting and waving along the edge, each with its own color.
            if (perimeter <= 0f) return
            val haloScale = floatArrayOf(6.0f, 3.8f, 2.2f, 1.0f)
            val haloAlpha = floatArrayOf(0.07f, 0.14f, 0.28f, 0.95f)
            for (dot in dots) {
                var fraction = dot.baseFraction + dot.speed * time
                fraction -= Math.floor(fraction.toDouble()).toFloat()
                if (!measure.getPosTan(fraction * perimeter, pos, tan)) continue

                val wave = sin((time * 0.6f + dot.phase) * twoPi)
                val inward = (dot.depthDp + dot.amplitudeDp * wave) * density
                val x = pos[0] + (-tan[1]) * inward
                val y = pos[1] + tan[0] * inward

                val twinkle = 0.65f + 0.35f * sin((time * dot.twinkleHz + dot.phase) * twoPi)
                val radius = dot.radiusDp * density
                val rgb = dot.color and 0x00FFFFFF
                for (layer in haloScale.indices) {
                    val a = (visible * twinkle * haloAlpha[layer] * 255f).toInt().coerceIn(0, 255)
                    dotPaint.color = (a shl 24) or rgb
                    canvas.drawCircle(x, y, radius * haloScale[layer], dotPaint)
                }
                val coreAlpha = (visible * twinkle * 0.85f * 255f).toInt().coerceIn(0, 255)
                dotPaint.color = (coreAlpha shl 24) or 0x00FFFFFF
                canvas.drawCircle(x, y, radius * 0.45f, dotPaint)
            }
        }
    }
}
