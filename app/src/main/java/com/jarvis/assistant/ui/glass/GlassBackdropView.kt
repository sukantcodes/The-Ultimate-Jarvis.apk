package com.jarvis.assistant.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The scene that lives BEHIND every glass surface of a screen. A clear lens only looks like glass when there is
 * something detailed to bend, so this view paints three layers: the theme colour, the drifting aurora of coloured
 * light, and crisp detail (a dot lattice plus concentric rings) that visibly zooms and refracts inside any
 * [LiquidGlassLayout] whose `lgSource` points at it. It is cheap: it draws a few gradients and about 20 strokes,
 * and it animates on the shared [AuroraClock] heartbeat, which pauses while scrolling.
 *
 * All colours come from the active theme at draw time; nothing is hard-coded.
 */
class GlassBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val screenW = resources.displayMetrics.widthPixels.toFloat()
    private val screenH = resources.displayMetrics.heightPixels.toFloat()
    private val loc = IntArray(2)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.6f * density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
    }
    private var dots = FloatArray(0)

    private val heartbeat = Runnable { if (isShown) invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!GlassMode.plain()) AuroraClock.register(heartbeat)
    }

    override fun onDetachedFromWindow() {
        AuroraClock.unregister(heartbeat)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val step = 30f * density
        val cols = (w / step).toInt() + 2
        val rows = (h / step).toInt() + 2
        val pts = FloatArray(cols * rows * 2)
        var i = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                pts[i++] = c * step
                pts[i++] = r * step
            }
        }
        dots = pts
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true) &&
            tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) tv.data else fallback
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        val crystal = CrystalTheme.active()

        // Transparent theme: the window already paints the chosen picture, so only the glass detail is added on top.
        if (!crystal) canvas.drawColor(themeColor(R.attr.themeBg, if (light) Color.WHITE else Color.BLACK))
        if (GlassMode.plain()) return

        // 1. Aurora, drawn in screen coordinates so every glass pane shows the part of it that is really behind it.
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        AuroraField.draw(canvas, screenW, screenH, light, if (crystal) 0.35f else 0.9f)
        canvas.restore()

        // 2. Crisp detail the lens can visibly bend: a dot lattice and slowly drifting concentric rings.
        val ink = themeColor(R.attr.themeTextPrimary, if (light) Color.BLACK else Color.WHITE)
        val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { ink }
        val t = AuroraField.phase
        val step = 30f * density
        val shiftX = (t * 6f * density) % step
        val shiftY = (t * 4f * density) % step
        dotPaint.color = ColorUtils.setAlphaComponent(ink, if (light) 46 else 54)
        canvas.save()
        canvas.translate(-shiftX, -shiftY)
        canvas.drawPoints(dots, dotPaint)
        canvas.restore()

        val ringStep = 34f * density
        val rings = (max(w, h) / ringStep).toInt() + 3
        ringPaint.color = ColorUtils.setAlphaComponent(accent, if (light) 70 else 78)
        val c1x = w * (0.22f + 0.08f * sin(t * 0.21f))
        val c1y = h * (0.28f + 0.06f * cos(t * 0.17f))
        for (i in 1..rings) canvas.drawCircle(c1x, c1y, i * ringStep, ringPaint)
        val c2x = w * (0.82f + 0.07f * cos(t * 0.19f))
        val c2y = h * (0.74f + 0.06f * sin(t * 0.23f))
        ringPaint.color = ColorUtils.setAlphaComponent(ink, if (light) 40 else 44)
        for (i in 1..rings) canvas.drawCircle(c2x, c2y, i * ringStep, ringPaint)
    }
}
