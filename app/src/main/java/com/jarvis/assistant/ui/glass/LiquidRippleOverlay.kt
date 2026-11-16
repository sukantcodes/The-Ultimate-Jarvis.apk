package com.jarvis.assistant.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * One transparent layer over a whole window that turns every touch into liquid:
 *  - a liquid drop spreads from the finger as spring-driven rings (they overshoot and rebound like a wave),
 *    with a refractive meniscus, a relief shadow and a prism fringe;
 *  - dragging leaves a trail of small drops, and the drop under a held finger follows it with a lag;
 *  - the button under the finger squishes like jelly and rebounds with a wobble on release.
 * It never takes part in touch dispatch (the window callback only lets it watch the events), so it cannot
 * change how any screen behaves.
 */
internal class LiquidRippleOverlay(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private class Drop(
        x: Float, y: Float,
        val maxR: Float,
        val strength: Float,
        val fade: Float,
        var held: Boolean
    ) {
        val px = SpringF(x, x, 260f, SpringF.damping(260f, 0.75f))
        val py = SpringF(y, y, 260f, SpringF.damping(260f, 0.75f))
        val radius = SpringF(0f, maxR, 78f, SpringF.damping(78f, 0.46f))
        var fadeAge = 0f
        fun alive() = held || fadeAge < fade
        fun alpha(): Float = if (held) 1f else (1f - fadeAge / fade).coerceIn(0f, 1f)
    }

    private class Squish(val view: View, pressedX: Float, pressedY: Float) {
        val sx = SpringF(1f, pressedX, 900f, SpringF.damping(900f, 0.72f))
        val sy = SpringF(1f, pressedY, 900f, SpringF.damping(900f, 0.72f))
        var released = false
        fun release() {
            released = true
            sx.target = 1f; sy.target = 1f
        }
    }

    private val drops = ArrayList<Drop>()
    private val held = HashMap<Int, Drop>()
    private val lastTrail = HashMap<Int, FloatArray>()
    private val squishes = ArrayList<Squish>()
    private var pressed: Squish? = null

    private var lastFrame = 0L
    private var light = false
    private var accent = Color.WHITE

    private val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val relief = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = Rect()

    /** Set when frames run slow: fewer rings and no fringe/relief, so the effect never costs smoothness. */
    private var lowQuality = false
    private var slowFrames = 0

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    // ---- touch feed (called by the window callback; never consumes anything) ----

    fun onTouch(e: MotionEvent, pressEffects: Boolean) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                refreshTheme()
                spawnHeld(e, e.actionIndex)
                if (pressEffects) pressUnder(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> spawnHeld(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) drag(e, i)
            }
            MotionEvent.ACTION_POINTER_UP -> releasePointer(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP -> {
                releasePointer(e.getPointerId(e.actionIndex))
                releaseAll()
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        kick()
    }

    private fun refreshTheme() {
        light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { Color.WHITE }
    }

    private fun spawnHeld(e: MotionEvent, index: Int) {
        val id = e.getPointerId(index)
        val x = e.getX(index)
        val y = e.getY(index)
        held.remove(id)?.held = false
        val drop = Drop(x, y, 120f * density, 1f, 0.95f, true)
        held[id] = drop
        addDrop(drop)
        lastTrail[id] = floatArrayOf(x, y)
    }

    private fun drag(e: MotionEvent, index: Int) {
        val id = e.getPointerId(index)
        val x = e.getX(index)
        val y = e.getY(index)
        held[id]?.let { it.px.target = x; it.py.target = y }
        val last = lastTrail[id] ?: return
        if (hypot(x - last[0], y - last[1]) >= 26f * density) {
            last[0] = x; last[1] = y
            addDrop(Drop(x, y, 54f * density, 0.55f, 0.55f, false))
        }
    }

    private fun releasePointer(id: Int) {
        held.remove(id)?.held = false
        lastTrail.remove(id)
    }

    private fun releaseAll() {
        for (d in held.values) d.held = false
        held.clear()
        lastTrail.clear()
        pressed?.release()
        pressed = null
    }

    private fun addDrop(d: Drop) {
        if (drops.size >= MAX_DROPS) drops.removeAt(0)
        drops.add(d)
    }

    // ---- jelly squish of the pressed button ----

    private fun pressUnder(rawX: Float, rawY: Float) {
        val root = parent as? ViewGroup ?: return
        val target = findPressable(root, rawX.toInt(), rawY.toInt()) ?: return
        if (kotlin.math.abs(target.scaleX - 1f) > 0.02f || kotlin.math.abs(target.scaleY - 1f) > 0.02f) return
        val small = max(target.width, target.height) < 56f * density
        val sq = Squish(target, if (small) 0.9f else 0.955f, if (small) 0.88f else 0.925f)
        squishes.firstOrNull { it.view === target }?.let { squishes.remove(it) }
        squishes.add(sq)
        pressed = sq
    }

    private fun findPressable(v: View, x: Int, y: Int): View? {
        if (v === this || v.visibility != VISIBLE || v is EditText) return null
        if (!v.getGlobalVisibleRect(rect) || !rect.contains(x, y)) return null
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                findPressable(v.getChildAt(i), x, y)?.let { return it }
            }
        }
        if (!v.isClickable || !v.isEnabled || v.getTag(LiquidTouch.SKIP_TAG_KEY) == true) return null
        val screen = resources.displayMetrics
        if (v.width > screen.widthPixels * 0.92f && v.height > screen.heightPixels * 0.25f) return null
        if (v.height > screen.heightPixels * 0.4f) return null
        return v
    }

    // ---- frame loop ----

    private fun kick() {
        if (lastFrame == 0L) lastFrame = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun step(dt: Float) {
        val di = drops.iterator()
        while (di.hasNext()) {
            val d = di.next()
            d.px.step(dt); d.py.step(dt); d.radius.step(dt)
            if (!d.held) d.fadeAge += dt
            if (!d.alive()) di.remove()
        }
        val si = squishes.iterator()
        while (si.hasNext()) {
            val s = si.next()
            s.sx.step(dt); s.sy.step(dt)
            s.view.scaleX = s.sx.value
            s.view.scaleY = s.sy.value
            if (s.released && s.sx.atRest(0.002f) && s.sy.atRest(0.002f)) {
                s.view.scaleX = 1f
                s.view.scaleY = 1f
                si.remove()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val raw = if (lastFrame == 0L) 0.016f else (now - lastFrame) / 1000f
        val dt = raw.coerceIn(0.001f, 0.033f)
        lastFrame = now
        if (raw > 0.026f) slowFrames++ else if (slowFrames > 0) slowFrames--
        lowQuality = slowFrames > 6
        step(dt)
        for (d in drops) drawDrop(canvas, d)
        if (drops.isNotEmpty() || squishes.isNotEmpty()) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawDrop(c: Canvas, d: Drop) {
        val r = d.radius.value
        if (r < 1f) return
        val a = d.alpha() * d.strength
        if (a <= 0.01f) return
        val x = d.px.value
        val y = d.py.value
        val progress = (r / d.maxR).coerceIn(0f, 1.3f)

        // Meniscus: a soft lens of liquid (stacked translucent discs, no shaders) with a brighter edge.
        val lensA = a * (if (light) 0.8f else 1f)
        lens.color = ColorUtils.setAlphaComponent(Color.WHITE, (255 * lensA * 0.035f).toInt().coerceIn(0, 255))
        c.drawCircle(x, y, r, lens)
        c.drawCircle(x, y, r * 0.78f, lens)
        c.drawCircle(x, y, r * 0.52f, lens)
        ring.strokeWidth = max(1f, r * 0.16f)
        ring.color = ColorUtils.setAlphaComponent(Color.WHITE, (255 * lensA * 0.10f).toInt().coerceIn(0, 255))
        c.drawCircle(x, y, r * 0.92f, ring)

        // Bright droplet right under the finger while it is down.
        if (d.held) {
            val cr = 16f * density * (1f - 0.35f * progress.coerceAtMost(1f))
            core.color = ColorUtils.setAlphaComponent(Color.WHITE, (255 * a * 0.22f).toInt().coerceIn(0, 255))
            c.drawCircle(x, y, cr, core)
            core.color = ColorUtils.setAlphaComponent(Color.WHITE, (255 * a * 0.55f).toInt().coerceIn(0, 255))
            c.drawCircle(x, y, cr * 0.45f, core)
        }

        // Three wave rings, each a little behind the one before.
        val tint = ColorUtils.blendARGB(Color.WHITE, accent, 0.22f)
        val ringCount = if (lowQuality) 1 else 3
        for (i in 0 until ringCount) {
            val ri = r * (1f - 0.2f * i)
            if (ri < 2f) continue
            val ringA = a * (0.62f - 0.17f * i) * (1.15f - 0.5f * progress.coerceAtMost(1f))
            if (ringA <= 0.01f) continue
            val w = (2.6f - 0.6f * i) * density
            if (lowQuality) {
                ring.strokeWidth = w
                ring.color = ColorUtils.setAlphaComponent(tint, (255 * ringA).toInt().coerceIn(0, 255))
                c.drawCircle(x, y, ri, ring)
                continue
            }
            // relief: a darker band just outside the crest gives the wave depth
            relief.strokeWidth = w * 2.2f
            relief.color = ColorUtils.setAlphaComponent(Color.BLACK, (255 * ringA * (if (light) 0.22f else 0.16f)).toInt().coerceIn(0, 255))
            c.drawCircle(x, y, ri + w * 1.6f, relief)
            // prism fringe
            ring.strokeWidth = w * 0.8f
            ring.color = Color.argb((255 * ringA * 0.5f).toInt().coerceIn(0, 255), 255, 70, 205)
            c.drawCircle(x, y, ri + w * 0.9f, ring)
            ring.color = Color.argb((255 * ringA * 0.45f).toInt().coerceIn(0, 255), 40, 215, 255)
            c.drawCircle(x, y, ri - w * 0.9f, ring)
            // the crest itself
            ring.strokeWidth = w
            ring.color = ColorUtils.setAlphaComponent(tint, (255 * ringA).toInt().coerceIn(0, 255))
            c.drawCircle(x, y, ri, ring)
        }
    }

    override fun onDetachedFromWindow() {
        for (s in squishes) { s.view.scaleX = 1f; s.view.scaleY = 1f }
        squishes.clear(); drops.clear(); held.clear(); lastTrail.clear(); pressed = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_DROPS = 28
    }
}
