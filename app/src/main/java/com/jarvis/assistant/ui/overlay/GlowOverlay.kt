package com.jarvis.assistant.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.SweepGradient
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * A soft, colorful glow around the screen edges that fades in and out, shown briefly when Jarvis wakes up.
 * Needs "Display over other apps"; if that permission is off it does nothing.
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
                @Suppress("DEPRECATION")
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
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

    private class GlowView(context: Context) : View(context) {
        var progress = 0f
            set(value) {
                field = value
                invalidate()
            }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val colors = intArrayOf(
            0xFF4285F4.toInt(), 0xFF9B72CB.toInt(), 0xFFD96570.toInt(),
            0xFFF9AB00.toInt(), 0xFF34A853.toInt(), 0xFF4285F4.toInt()
        )
        private val shaderMatrix = Matrix()
        private var sweep: SweepGradient? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            sweep = SweepGradient(w / 2f, h / 2f, colors, null)
        }

        override fun onDraw(canvas: Canvas) {
            val shader = sweep ?: return
            val fadeIn = (progress / 0.15f).coerceIn(0f, 1f)
            val fadeOut = ((1f - progress) / 0.25f).coerceIn(0f, 1f)
            val visible = minOf(fadeIn, fadeOut)
            if (visible <= 0f) return

            shaderMatrix.setRotate(progress * 720f, width / 2f, height / 2f)
            shader.setLocalMatrix(shaderMatrix)
            paint.shader = shader

            val density = resources.displayMetrics.density
            val pulse = 1f + 0.15f * sin(progress * 6.2831855f * 4f)
            val layers = 8
            for (i in 0 until layers) {
                val fraction = i / layers.toFloat()
                paint.strokeWidth = (4f + i * 5f) * density * pulse
                paint.alpha = (visible * 255f * (1f - fraction) * 0.30f).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }
    }
}
