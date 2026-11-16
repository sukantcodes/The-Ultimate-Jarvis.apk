package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "Replying" indicator: a breathing, slowly rotating spoke spinner followed by a shimmering status word
 * and elapsed seconds, e.g. `Thinking…  4s`. One canvas view, no allocation per frame.
 * Honors the system "remove animations" setting (draws a single static frame).
 */
class ThinkingIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Epoch millis the reply started; the elapsed counter is derived from it so it survives view recycling. */
    var startTimeMs: Long = System.currentTimeMillis()

    private val density = resources.displayMetrics.density
    private val accent = resolveColor(R.attr.themeAccent, Color.rgb(64, 160, 255))
    private val baseText = resolveColor(R.attr.themeTextSecondary, Color.GRAY)
    private val verbs = arrayOf("Thinking", "Reasoning", "Working it out", "Drafting", "Polishing")

    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = accent
        strokeWidth = 2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = baseText
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var halo: RadialGradient? = null
    private var haloKey = 0f
    private val shimmerMatrix = Matrix()
    private var shimmer: LinearGradient? = null
    private var running = false
    private var reduced = false
    private var t0 = 0L
    private var now = 0L

    init {
        contentDescription = "JARVIS is thinking"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun resolveColor(attr: Int, fallback: Int): Int {
        val v = TypedValue()
        if (!context.theme.resolveAttribute(attr, v, true)) return fallback
        return if (v.resourceId != 0) {
            try { ContextCompat.getColor(context, v.resourceId) } catch (_: Exception) { v.data }
        } else v.data
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            now = System.currentTimeMillis()
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reduced = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) { false }
        t0 = System.currentTimeMillis()
        now = t0
        if (reduced) { invalidate(); return }
        running = true
        postOnAnimation(frame)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (30f * density).toInt()
        val w = (260f * density).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val t = (now - t0).toFloat()
        val cy = height / 2f
        val radius = 9f * density
        val cx = radius + 2f * density

        // Spoke spinner: 8 round-capped spokes whose lengths ripple around the ring while it turns.
        val rot = t / 2400f * 6.2832f
        val inner = radius * 0.28f

        // Soft breathing glow behind the spinner, so it reads as glass lit from inside.
        if (!reduced) {
            val hr = radius * 2.1f
            if (halo == null || haloKey != hr) {
                haloKey = hr
                halo = RadialGradient(0f, 0f, hr, intArrayOf(accent, (accent and 0x00FFFFFF)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                haloPaint.shader = halo
            }
            haloPaint.alpha = (255 * (0.10f + 0.12f * (0.5f + 0.5f * sin(t / 520f)))).toInt()
            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawCircle(0f, 0f, hr, haloPaint)
            canvas.restore()
        }

        // Grow-in: the spokes unfurl with a small overshoot when the indicator appears.
        val intro = if (reduced) 1f else easeOutBack((t / 480f).coerceIn(0f, 1f))
        for (i in 0 until 8) {
            val a = rot + i * (6.2832f / 8f)
            val len = intro * if (reduced) 1f else 0.55f + 0.45f * (0.5f + 0.5f * sin(t / 260f - i * 0.9f))
            val outer = inner + (radius - inner) * len
            canvas.drawLine(
                cx + cos(a) * inner, cy + sin(a) * inner,
                cx + cos(a) * outer, cy + sin(a) * outer, spokePaint
            )
        }

        // Status word with a light band sweeping across it.
        val verb = verbs[((t / 2400f).toInt()) % verbs.size]
        // Each word fades and slides in, holds, then fades out before the next one, instead of switching abruptly.
        val phase = t % 2400f
        val wordAlpha = if (reduced) 1f else min(1f, min(phase / 280f, (2400f - phase) / 280f)).coerceIn(0f, 1f)
        val wordDrop = (1f - wordAlpha) * 5f * density * (if (phase < 1200f) 1f else -1f)
        val intro2 = if (reduced) 1f else (t / 320f).coerceIn(0f, 1f)
        val secs = max(0L, (System.currentTimeMillis() - startTimeMs) / 1000L)
        val label = "$verb…  ${secs}s"
        val x = cx + radius + 10f * density
        val y = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        val tw = textPaint.measureText(label)
        if (!reduced && tw > 0f) {
            val bw = max(1f, tw * 0.45f)
            if (shimmer == null || shimmerWidth != bw) {
                shimmerWidth = bw
                shimmer = LinearGradient(
                    0f, 0f, bw, 0f,
                    intArrayOf(baseText, accent, baseText), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
                )
            }
            val travel = tw + bw
            val p = (t % 1800f) / 1800f
            shimmerMatrix.setTranslate(x - bw + travel * p, 0f)
            shimmer?.setLocalMatrix(shimmerMatrix)
            textPaint.shader = shimmer
        } else {
            textPaint.shader = null
            textPaint.color = baseText
        }
        textPaint.alpha = (255 * wordAlpha * intro2).toInt()
        canvas.drawText(label, x, min(y, height.toFloat()) + wordDrop, textPaint)
    }

    private var shimmerWidth = 0f

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val p = x - 1f
        return 1f + c3 * p * p * p + c1 * p * p
    }
}
