package com.jarvis.assistant.ui.chat.liquid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.ui.chat.GlassMotion
import kotlin.math.min
import kotlin.math.sin

/**
 * A glass sphere with liquid colour turning inside it. It breathes softly, turns slowly when idle and faster while
 * [busy] (JARVIS is thinking or writing). Used as JARVIS' avatar on every answer and as the welcome-screen orb.
 */
class LiquidOrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** True while JARVIS is working on something: the swirl speeds up and the glow pulses harder. */
    var busy: Boolean = false

    /** An optional letter in the middle of the orb (the welcome orb shows "J"). */
    var glyph: String? = null
        set(value) { field = value; invalidate() }

    private val tones = LiquidPalette.resolve(context)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swirl = Paint(Paint.ANTI_ALIAS_FLAG)
    private val under = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val swirlMatrix = Matrix()
    private val glossRect = RectF()
    private var swirlShader: SweepGradient? = null

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var haloRadius = 0f

    private var running = false
    private var angle = 0f
    private var phase = 0f
    private var last = 0L
    private var reduced = false

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = ((now - last) / 1000f).coerceIn(0f, 0.05f)
            last = now
            angle = (angle + dt * (if (busy) 150f else 26f)) % 360f
            phase += dt * (if (busy) 4.2f else 1.5f)
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun start() {
        if (running || reduced || !isAttachedToWindow || !isShown) return
        running = true
        last = SystemClock.uptimeMillis()
        postOnAnimation(frame)
    }

    private fun stop() {
        running = false
        removeCallbacks(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reduced = !GlassMotion.animationsOn(this)
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) start() else stop()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val t = tones
        cx = w / 2f
        cy = h / 2f
        haloRadius = min(w, h) / 2f
        radius = haloRadius * 0.74f
        val density = resources.displayMetrics.density

        fun a(color: Int, alpha: Float) = ColorUtils.setAlphaComponent(color, (255 * alpha.coerceIn(0f, 1f)).toInt())
        val deep = ColorUtils.blendARGB(t.secondary, Color.BLACK, 0.45f)

        halo.shader = RadialGradient(cx, cy, haloRadius, a(t.accent, 0.50f), a(t.accent, 0f), Shader.TileMode.CLAMP)
        body.shader = RadialGradient(
            cx - radius * 0.28f, cy - radius * 0.34f, radius * 1.35f,
            intArrayOf(ColorUtils.blendARGB(t.accent, t.specular, 0.35f), t.accent, deep),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        swirlShader = SweepGradient(
            cx, cy,
            intArrayOf(a(t.secondary, 0f), a(t.secondary, 0.70f), a(t.specular, 0.46f), a(t.accent, 0.60f), a(t.secondary, 0f)),
            floatArrayOf(0f, 0.30f, 0.52f, 0.76f, 1f)
        )
        swirl.shader = swirlShader
        under.shader = RadialGradient(cx, cy + radius * 0.92f, radius * 0.95f, a(t.secondary, 0.55f), a(t.secondary, 0f), Shader.TileMode.CLAMP)
        glossRect.set(cx - radius * 0.60f, cy - radius * 0.90f, cx + radius * 0.38f, cy - radius * 0.28f)
        gloss.shader = LinearGradient(0f, glossRect.top, 0f, glossRect.bottom, a(t.specular, 0.78f), a(t.specular, 0f), Shader.TileMode.CLAMP)
        rim.strokeWidth = (1.1f * density).coerceAtLeast(1f)
        rim.shader = SweepGradient(
            cx, cy,
            intArrayOf(a(t.specular, 0.90f), a(t.specular, 0.12f), a(t.specular, 0.55f), a(t.specular, 0.12f), a(t.specular, 0.90f)),
            floatArrayOf(0f, 0.28f, 0.55f, 0.80f, 1f)
        )
        text.color = t.specular
        text.textSize = radius * 1.05f
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        val amp = if (busy) 0.055f else 0.018f
        val breathe = 1f + amp * sin(phase)

        halo.alpha = (255 * (0.55f + 0.30f * sin(phase * 0.8f)).coerceIn(0f, 1f)).toInt()
        canvas.drawCircle(cx, cy, haloRadius, halo)

        canvas.save()
        canvas.scale(breathe, breathe, cx, cy)
        canvas.drawCircle(cx, cy, radius, body)
        swirlShader?.let {
            swirlMatrix.setRotate(angle, cx, cy)
            it.setLocalMatrix(swirlMatrix)
            canvas.drawCircle(cx, cy, radius * 0.96f, swirl)
        }
        canvas.drawCircle(cx, cy, radius, under)
        canvas.save()
        canvas.rotate(-26f, cx, cy)
        canvas.drawOval(glossRect, gloss)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius - rim.strokeWidth / 2f, rim)
        glyph?.let {
            val y = cy - (text.ascent() + text.descent()) / 2f
            canvas.drawText(it, cx, y, text)
        }
        canvas.restore()
    }
}

/** A suggestion chip: a liquid-glass capsule that presses in under a finger and springs back. */
class LiquidChipTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {
    init {
        LiquidPane.ensure(this, LiquidPane.Kind.PILL, 18f)
        LiquidMotion.pressFeel(this)
    }
}

/** The welcome column. Every time it becomes visible its children rise in one after another. */
class LiquidWelcomeLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private fun playEntrance() {
        post {
            if (!isAttachedToWindow || visibility != VISIBLE) return@post
            val kids = ArrayList<View>(childCount)
            for (i in 0 until childCount) kids += getChildAt(i)
            LiquidMotion.staggerIn(kids)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        playEntrance()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility == VISIBLE && isAttachedToWindow) playEntrance()
    }
}
