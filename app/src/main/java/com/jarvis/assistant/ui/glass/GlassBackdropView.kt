package com.jarvis.assistant.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The scene that lives BEHIND every glass surface of a Settings screen. A clear lens only looks like glass when there is
 * something detailed to bend, so this view paints either an animated scene (Aurora, Nebula, Liquid Waves, Constellation,
 * Prism, Silk, picked in Settings -> Appearance) or, in See-through mode, a picture of the screen underneath. Any
 * [LiquidGlassLayout] whose `lgSource` points at it magnifies and bends whatever is drawn here.
 *
 * In See-through mode nothing animates, so it is also the cheapest scene. All colours come from the active theme.
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
    private val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val veilPaint = Paint()
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val scenes = SceneRenderer(density)
    private var dots = FloatArray(0)

    private var scene = SettingsScene.AURORA
    private var registered = false

    private val heartbeat = Runnable { if (isShown) invalidate() }
    private val snapshotChanged = Runnable { refreshScene() }

    /** Re-reads the chosen scene and starts or stops the animation to match. */
    fun refreshScene() {
        var next = SettingsScene.effective()
        if (GlassMode.plain()) next = SettingsScene.AURORA
        if (next == SettingsScene.SEE_THROUGH && ScreenSnapshot.bitmap == null) next = SettingsScene.AURORA
        scene = next
        val animated = !GlassMode.plain() && scene != SettingsScene.SEE_THROUGH
        if (animated && !registered && isAttachedToWindow) { AuroraClock.register(heartbeat); registered = true }
        if (!animated && registered) { AuroraClock.unregister(heartbeat); registered = false }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        live.add(this)
        ScreenSnapshot.addListener(snapshotChanged)
        refreshScene()
    }

    override fun onDetachedFromWindow() {
        live.remove(this)
        ScreenSnapshot.removeListener(snapshotChanged)
        if (registered) { AuroraClock.unregister(heartbeat); registered = false }
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
        val bg = themeColor(R.attr.themeBg, if (light) Color.WHITE else Color.BLACK)

        if (scene == SettingsScene.SEE_THROUGH) { drawPicture(canvas, w, h, bg); return }

        // Transparent theme: the window already paints the chosen picture, so only the glass detail is added on top.
        if (!crystal) canvas.drawColor(bg)
        if (GlassMode.plain()) return

        val ink = themeColor(R.attr.themeTextPrimary, if (light) Color.BLACK else Color.WHITE)
        val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { ink }
        val secondary = try { ThemeManager.getThemeSecondaryColor() } catch (_: Throwable) { accent }
        val t = AuroraField.phase

        if (scene == SettingsScene.AURORA) {
            // Aurora, drawn in screen coordinates so every glass pane shows the part of it that is really behind it.
            getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            AuroraField.draw(canvas, screenW, screenH, light, if (crystal) 0.35f else 0.9f)
            canvas.restore()
            drawLatticeAndRings(canvas, w, h, ink, accent, light, t)
        } else {
            scenes.draw(scene, canvas, w, h, t, light, accent, secondary, ink)
        }
    }

    /** The picture of the screen underneath, centre-cropped, calmed with a thin theme veil so text stays readable. */
    private fun drawPicture(canvas: Canvas, w: Float, h: Float, bg: Int) {
        val bmp = ScreenSnapshot.bitmap ?: run { canvas.drawColor(bg); return }
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = max(w / bw, h / bh)
        val sw = (w / scale).toInt().coerceAtMost(bmp.width)
        val sh = (h / scale).toInt().coerceAtMost(bmp.height)
        val sx = ((bmp.width - sw) / 2).coerceAtLeast(0)
        val sy = ((bmp.height - sh) / 2).coerceAtLeast(0)
        srcRect.set(sx, sy, sx + sw, sy + sh)
        dstRect.set(0, 0, width, height)
        canvas.drawColor(bg)
        canvas.drawBitmap(bmp, srcRect, dstRect, picturePaint)
        veilPaint.color = ColorUtils.setAlphaComponent(bg, 70)
        canvas.drawRect(dstRect, veilPaint)
    }

    private fun drawLatticeAndRings(canvas: Canvas, w: Float, h: Float, ink: Int, accent: Int, light: Boolean, t: Float) {
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

    companion object {
        private val live: MutableSet<GlassBackdropView> = Collections.newSetFromMap(WeakHashMap())

        /** Tells every visible backdrop to pick up a changed scene right away. */
        fun refreshAll() { live.toList().forEach { it.refreshScene() } }
    }
}
