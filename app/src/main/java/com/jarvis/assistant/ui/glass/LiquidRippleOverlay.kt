package com.jarvis.assistant.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Window-level iOS 26/27 Liquid Glass touch illumination layer:
 *  - Renders a clean luminous optical caustic glow & delicate refractive meniscus wave at touch
 *    coordinates (no noisy neon pink/cyan rings).
 *  - Feeds live touch coordinates to any LiquidGlassLayout beneath the finger so its curved glass
 *    rim gleams dynamically toward the touch.
 *  - Drives smooth elastic spring feedback on tappable controls without intercepting events.
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
        val px = SpringF(x, x, 290f, SpringF.damping(290f, 0.78f))
        val py = SpringF(y, y, 290f, SpringF.damping(290f, 0.78f))
        val radius = SpringF(0f, maxR, 96f, SpringF.damping(96f, 0.56f))
        var fadeAge = 0f
        fun alive() = held || fadeAge < fade
        fun alpha(): Float = if (held) 1f else (1f - fadeAge / fade).coerceIn(0f, 1f)
    }

    private class Squish(val view: View, pressedX: Float, pressedY: Float) {
        val sx = SpringF(1f, pressedX, 760f, SpringF.damping(760f, 0.74f))
        val sy = SpringF(1f, pressedY, 760f, SpringF.damping(760f, 0.74f))
        var released = false
        fun release() {
            released = true
            sx.target = 1f
            sy.target = 1f
        }
    }

    private val drops = ArrayList<Drop>()
    private val held = HashMap<Int, Drop>()
    private val lastTrail = HashMap<Int, FloatArray>()
    private val squishes = ArrayList<Squish>()
    private var pressed: Squish? = null
    private var pressedPod: DockPodDrawable? = null
    private var litGlassLayout: LiquidGlassLayout? = null
    private val tmpLoc = IntArray(2)

    private var lastFrame = 0L
    private var lastTrailAt = 0L
    private var light = false
    private var accent = Color.WHITE

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowMatrix = Matrix()
    private val unitCaustic = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0x66FFFFFF, 0x24FFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.48f, 1f),
        Shader.TileMode.CLAMP
    )
    private val rect = Rect()

    private var lowQuality = false
    private var slowFrames = 0

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        glowPaint.shader = unitCaustic
    }

    fun onTouch(e: MotionEvent, pressEffects: Boolean) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                refreshTheme()
                spawnHeld(e, e.actionIndex)
                if (pressEffects) pressUnder(e.rawX, e.rawY)
                updateGlassTouchLight(e.rawX, e.rawY, true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> spawnHeld(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) drag(e, i)
                updateGlassTouchLight(e.rawX, e.rawY, true)
            }
            MotionEvent.ACTION_POINTER_UP -> releasePointer(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP -> {
                releasePointer(e.getPointerId(e.actionIndex))
                updateGlassTouchLight(e.rawX, e.rawY, false)
                releaseAll()
            }
            MotionEvent.ACTION_CANCEL -> {
                updateGlassTouchLight(e.rawX, e.rawY, false)
                releaseAll()
            }
        }
        kick()
    }

    private fun refreshTheme() {
        light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { Color.WHITE }
    }

    private fun updateGlassTouchLight(rawX: Float, rawY: Float, active: Boolean) {
        if (!active) {
            litGlassLayout?.updateTouchLight(0f, 0f, false)
            litGlassLayout = null
            return
        }
        val root = parent as? ViewGroup ?: return
        val found = findGlassLayout(root, rawX.toInt(), rawY.toInt())
        if (litGlassLayout != null && litGlassLayout !== found) {
            litGlassLayout?.updateTouchLight(0f, 0f, false)
        }
        litGlassLayout = found
        if (found != null) {
            found.getLocationOnScreen(tmpLoc)
            found.updateTouchLight(rawX - tmpLoc[0], rawY - tmpLoc[1], true)
        }
    }

    private fun findGlassLayout(v: View, x: Int, y: Int): LiquidGlassLayout? {
        if (v === this || v.visibility != VISIBLE) return null
        if (!v.getGlobalVisibleRect(rect) || !rect.contains(x, y)) return null
        if (v is LiquidGlassLayout) return v
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                findGlassLayout(v.getChildAt(i), x, y)?.let { return it }
            }
        }
        return null
    }

    private fun spawnHeld(e: MotionEvent, index: Int) {
        val id = e.getPointerId(index)
        val x = e.getX(index)
        val y = e.getY(index)
        held.remove(id)?.held = false
        val drop = Drop(x, y, 22f * density, 1f, 0.55f, true)
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
        val now = SystemClock.uptimeMillis()
        if (hypot(x - last[0], y - last[1]) >= 26f * density && now - lastTrailAt >= 55L && liveTrails() < MAX_TRAILS) {
            last[0] = x; last[1] = y
            lastTrailAt = now
            addDrop(Drop(x, y, 10f * density, 0.45f, 0.34f, false))
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
        pressedPod?.release()
        pressedPod = null
    }

    private fun liveTrails(): Int {
        var n = 0
        for (d in drops) if (!d.held) n++
        return n
    }

    private fun addDrop(d: Drop) {
        if (drops.size >= MAX_DROPS) drops.removeAt(0)
        drops.add(d)
    }

    private fun pressUnder(rawX: Float, rawY: Float) {
        val root = parent as? ViewGroup ?: return
        val target = findPressable(root, rawX.toInt(), rawY.toInt()) ?: return
        DockPods.autoPod(target)?.let { pod ->
            target.getLocationOnScreen(tmpLoc)
            pod.press(rawX - tmpLoc[0], rawY - tmpLoc[1])
            pressedPod = pod
            return
        }
        if (abs(target.scaleX - 1f) > 0.02f || abs(target.scaleY - 1f) > 0.02f) return
        val small = max(target.width, target.height) < 56f * density
        val sq = Squish(target, if (small) 0.93f else 0.968f, if (small) 0.92f else 0.955f)
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

    override fun draw(canvas: Canvas) {
        if (LiquidGlassLayout.isCapturing()) return
        super.draw(canvas)
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
        if (needsFrames()) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun needsFrames(): Boolean {
        if (squishes.isNotEmpty()) return true
        for (d in drops) {
            if (!d.held) return true
            if (!d.radius.atRest(0.5f) || !d.px.atRest(0.5f) || !d.py.atRest(0.5f)) return true
        }
        return false
    }

    fun isBusy(): Boolean = held.isNotEmpty()

    private fun drawDrop(c: Canvas, d: Drop) {
        val r = d.radius.value
        if (r < 1f) return
        val a = d.alpha() * d.strength
        if (a <= 0.01f) return
        val x = d.px.value
        val y = d.py.value
        val progress = (r / d.maxR).coerceIn(0f, 1.25f)

        // Luminous optical glass caustic pool under touch (subtle, premium Apple-style glow)
        val glowR = r * 1.45f
        glowMatrix.reset()
        glowMatrix.postScale(glowR, glowR)
        glowMatrix.postTranslate(x, y)
        unitCaustic.setLocalMatrix(glowMatrix)
        glowPaint.alpha = (255 * a * (if (light) 0.34f else 0.44f)).toInt().coerceIn(0, 255)
        c.drawCircle(x, y, glowR, glowPaint)

        // Crisp delicate meniscus wave crest (clean neutral glass highlight, no neon stripes)
        val tint = ColorUtils.blendARGB(Color.WHITE, accent, 0.14f)
        val rings = if (lowQuality) 1 else 2
        for (i in 0 until rings) {
            val ri = r * (1f - 0.28f * i)
            if (ri < 2f) continue
            val ringA = a * (0.42f - 0.18f * i) * (1.1f - 0.55f * progress.coerceAtMost(1f))
            if (ringA <= 0.01f) continue
            ringPaint.strokeWidth = (1.1f - 0.35f * i) * density
            ringPaint.color = ColorUtils.setAlphaComponent(tint, (255 * ringA).toInt().coerceIn(0, 255))
            c.drawCircle(x, y, ri, ringPaint)
        }
    }

    override fun onDetachedFromWindow() {
        for (s in squishes) { s.view.scaleX = 1f; s.view.scaleY = 1f }
        litGlassLayout?.updateTouchLight(0f, 0f, false)
        litGlassLayout = null
        squishes.clear(); drops.clear(); held.clear(); lastTrail.clear(); pressed = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_DROPS = 22
        const val MAX_TRAILS = 5
    }
}
