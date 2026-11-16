package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.max
import kotlin.math.min

/**
 * One dock option's own 3D glass bubble, sitting inside the dock's big bubble.
 *
 * It is a lit, domed pod: a convex body, a pool of light at the bottom, a glossy window and glint on top, a bevelled rim
 * with a faint prism fringe, and a soft contact shadow underneath. All of it is built once per size; a frame only moves
 * a few springs.
 *
 * Touch physics (all springs, so it stays smooth at any frame rate):
 *  - press: the pod sinks like a pressed glass bead (squashes, the shading deepens, the gloss dims, the shadow tightens);
 *  - release: it springs back with a jelly wobble, with x and y out of phase;
 *  - a liquid ripple spreads inside the pod from the exact touch point, clipped to the pod;
 *  - the selected option gets a warm accent glow that eases in and out.
 */
internal class DockPodDrawable(
    private val host: View,
    private val heightDp: Float,
    private val maxWidthDp: Float,
    private val circle: Boolean,
    private val offsetYDp: Float,
    private val padDp: Float = 8f
) : Drawable() {

    private val density = host.resources.displayMetrics.density

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyLin = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyRad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val convex = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caustic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dome = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fringeA = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fringeB = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rippleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rippleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val pod = RectF()
    private val domeRect = RectF()
    private val rimRect = RectF()
    private val fringeRectA = RectF()
    private val fringeRectB = RectF()
    private val shadowRect = RectF()
    private val clip = Path()
    private val matrix = Matrix()
    private var radius = 0f
    private var domeRadius = 0f
    private var cx = 0f
    private var cy = 0f
    private var built = false
    private var light = false
    private var accent = Color.WHITE

    // springs: scale x/y, press depth (0..1), selected glow (0..1)
    private var sx = SpringF(1f, 1f, PRESS_K, SpringF.damping(PRESS_K, 0.8f))
    private var sy = SpringF(1f, 1f, PRESS_K, SpringF.damping(PRESS_K, 0.8f))
    private var depth = SpringF(0f, 0f, 700f, SpringF.damping(700f, 0.85f))
    private var glowSpring = SpringF(0f, 0f, 160f, SpringF.damping(160f, 0.9f))

    // liquid ripple inside the pod
    private var rx = 0f
    private var ry = 0f
    private var rRadius = SpringF(0f, 0f, 95f, SpringF.damping(95f, 0.5f))
    private var rAlpha = 0f
    private var rHeld = false

    private var running = false
    private var last = 0L
    private val tick = Runnable { advance() }

    // ---- public control ----

    fun press(x: Float, y: Float) {
        refreshTheme()
        val ts = PRESS_K
        sx = SpringF(sx.value, 0.93f, ts, SpringF.damping(ts, 0.85f)).also { it.velocity = sx.velocity }
        sy = SpringF(sy.value, 0.9f, ts, SpringF.damping(ts, 0.85f)).also { it.velocity = sy.velocity }
        depth.target = 1f
        rx = (x - cx).coerceIn(-pod.width() / 2f, pod.width() / 2f)
        ry = (y - cy).coerceIn(-pod.height() / 2f, pod.height() / 2f)
        rRadius = SpringF(0f, min(max(pod.width(), pod.height()) * 0.2f, 9f * density), 95f, SpringF.damping(95f, 0.5f))
        rAlpha = 1f
        rHeld = true
        kick()
    }

    fun release() {
        // Low damping on the way back: the pod overshoots and wobbles like a drop of liquid, x and y out of phase.
        val kx = 380f
        val ky = 330f
        sx = SpringF(sx.value, 1f, kx, SpringF.damping(kx, 0.26f)).also { it.velocity = sx.velocity }
        sy = SpringF(sy.value, 1f, ky, SpringF.damping(ky, 0.24f)).also { it.velocity = sy.velocity }
        depth.target = 0f
        rHeld = false
        kick()
    }

    fun setActive(active: Boolean) {
        glowSpring.target = if (active) 1f else 0f
        kick()
    }

    // ---- build ----

    private fun refreshTheme() {
        val l = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        val a = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { Color.WHITE }
        if (!built || l != light || a != accent) { light = l; accent = a; if (!bounds.isEmpty) build(bounds) }
    }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        if (b.isEmpty) return
        refreshTheme()
        build(b)
    }

    private fun build(b: Rect) {
        val h = heightDp * density
        val w = if (circle) h else min(maxWidthDp * density, max(h, b.width() - padDp * density))
        cx = b.exactCenterX()
        cy = b.exactCenterY() + offsetYDp * density
        pod.set(-w / 2f, -h / 2f, w / 2f, h / 2f)
        radius = h / 2f
        clip.reset(); clip.addRoundRect(pod, radius, radius, Path.Direction.CW)

        val k = if (light) 1.25f else 1f
        fun white(a: Float) = ColorUtils.setAlphaComponent(Color.WHITE, (255 * a).toInt().coerceIn(0, 255))

        bodyLin.shader = LinearGradient(
            0f, pod.top, 0f, pod.bottom,
            intArrayOf(white(0.24f * k), white(0.07f * k), white(0.12f * k)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        bodyRad.shader = RadialGradient(
            -w * 0.2f, -h * 0.32f, max(w, h) * 0.85f,
            intArrayOf(white(0.30f * k), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        convex.shader = LinearGradient(
            0f, pod.top, 0f, pod.bottom,
            intArrayOf(0x00000000, 0x00000000, ((if (light) 0x30 else 0x4D) shl 24)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        matrix.reset(); matrix.postScale(w * 0.42f, h * 0.5f); matrix.postTranslate(0f, pod.bottom + h * 0.08f)
        val causticShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(white(if (light) 0.55f else 0.42f), white(0.10f), Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        causticShader.setLocalMatrix(matrix)
        caustic.shader = causticShader

        val domeInset = max(radius * 0.5f, 4f * density)
        val domeH = min(h * 0.38f, 20f * density)
        domeRect.set(pod.left + domeInset, pod.top + 2f * density, pod.right - domeInset, pod.top + 2f * density + domeH)
        domeRadius = min(domeH, domeRect.width()) / 2f
        dome.shader = LinearGradient(
            0f, domeRect.top, 0f, domeRect.bottom,
            intArrayOf(white(if (light) 0.8f else 0.55f), 0x05FFFFFF), null, Shader.TileMode.CLAMP
        )

        val gw = min(radius * 0.55f, 8f * density)
        val gx = pod.left + max(radius * 0.9f, gw * 1.7f)
        val gy = pod.top + max(h * 0.2f, gw * 0.9f)
        matrix.reset(); matrix.postScale(gw * 1.5f, gw * 0.62f); matrix.postTranslate(gx, gy)
        val glintShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        glintShader.setLocalMatrix(matrix)
        glint.shader = glintShader

        val rw = 1.3f * density
        rim.strokeWidth = rw
        rimRect.set(pod); rimRect.inset(rw / 2f, rw / 2f)
        rim.shader = LinearGradient(
            0f, pod.top, 0f, pod.bottom,
            intArrayOf(white(0.9f), white(0.10f), white(0.5f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        fringeA.strokeWidth = 1f * density
        fringeA.color = Color.argb(if (light) 50 else 70, 255, 70, 205)
        fringeB.strokeWidth = 1f * density
        fringeB.color = Color.argb(if (light) 38 else 52, 40, 215, 255)
        fringeRectA.set(pod); fringeRectA.inset(0.8f * density, 0.8f * density)
        fringeRectB.set(pod); fringeRectB.inset(1.7f * density, 1.7f * density)

        // Contact shadow: a thin dark ellipse just under the pod.
        shadowRect.set(pod.left + w * 0.08f, pod.bottom - 1.5f * density, pod.right - w * 0.08f, pod.bottom + 4.5f * density)
        shadow.color = ColorUtils.setAlphaComponent(Color.BLACK, if (light) 40 else 70)
        built = true
    }

    // ---- animation ----

    private fun kick() {
        if (running) return
        running = true
        last = SystemClock.uptimeMillis()
        host.postOnAnimation(tick)
    }

    private fun advance() {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - last) / 1000f).coerceIn(0.001f, 0.033f)
        last = now
        sx.step(dt); sy.step(dt); depth.step(dt); glowSpring.step(dt)
        rRadius.step(dt)
        if (!rHeld && rAlpha > 0f) rAlpha = max(0f, rAlpha - dt / 0.6f)
        invalidateSelf()
        val settled = sx.atRest(0.0015f) && sy.atRest(0.0015f) && depth.atRest(0.002f) && glowSpring.atRest(0.002f) &&
            (rAlpha <= 0f) && !rHeld
        if (settled) {
            sx.value = 1f; sy.value = 1f
            running = false
            invalidateSelf()
        } else {
            host.postOnAnimation(tick)
        }
    }

    // ---- draw ----

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || !built) return
        val d = depth.value.coerceIn(0f, 1f)
        val g = glowSpring.value.coerceIn(0f, 1f)

        // contact shadow tightens as the pod is pressed
        shadow.alpha = ((if (light) 40 else 70) * (1f - 0.55f * d)).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawOval(shadowRect, shadow)

        canvas.translate(0f, 1.4f * density * d)
        canvas.scale(sx.value, sy.value)

        canvas.drawRoundRect(pod, radius, radius, bodyLin)
        canvas.drawRoundRect(pod, radius, radius, bodyRad)
        if (g > 0.01f) {
            glow.color = ColorUtils.setAlphaComponent(accent, (255 * 0.30f * g).toInt().coerceIn(0, 255))
            canvas.drawRoundRect(pod, radius, radius, glow)
        }
        convex.alpha = (255 * (1f + 0.5f * d)).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(pod, radius, radius, convex)
        canvas.drawRoundRect(pod, radius, radius, caustic)

        // liquid ripple inside the pod
        if (rAlpha > 0.01f && rRadius.value > 1f) {
            val saved = canvas.save()
            canvas.clipPath(clip)
            val r = rRadius.value
            val a = rAlpha
            rippleFill.color = ColorUtils.setAlphaComponent(Color.WHITE, (255 * a * 0.12f).toInt().coerceIn(0, 255))
            canvas.drawCircle(rx, ry, r, rippleFill)
            for (i in 0..1) {
                val ri = r * (1f - 0.28f * i)
                rippleRing.strokeWidth = (1.1f - 0.3f * i) * density
                rippleRing.color = ColorUtils.setAlphaComponent(Color.WHITE, (255 * a * (0.6f - 0.2f * i)).toInt().coerceIn(0, 255))
                canvas.drawCircle(rx, ry, ri, rippleRing)
            }
            canvas.restoreToCount(saved)
        }

        dome.alpha = (255 * (1f - 0.45f * d)).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(domeRect, domeRadius, domeRadius, dome)
        glint.alpha = (if (light) 200 else 160).let { (it * (1f - 0.5f * d)).toInt() }
        canvas.drawRoundRect(pod, radius, radius, glint)
        canvas.drawRoundRect(fringeRectA, radius, radius, fringeA)
        canvas.drawRoundRect(fringeRectB, radius, radius, fringeB)
        canvas.drawRoundRect(rimRect, max(0f, radius - 0.65f * density), max(0f, radius - 0.65f * density), rim)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val PRESS_K = 760f
    }
}
