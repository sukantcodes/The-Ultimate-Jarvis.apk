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
 * Interactive iOS 26/27 Liquid Glass control pod for dock buttons and floating icon badges.
 *
 * Signature iOS Liquid Glass interaction:
 *  - At rest: crystal-clear optical dome with crisp Fresnel edge and delicate internal caustic.
 *  - On press: gently swells/elastic-compresses while illuminating from WITHIN at the exact touch
 *    point (luminous internal glass glow that brightens the rim, just like iOS 26/27 controls).
 *  - On release: springs back with smooth, damped liquid elasticity.
 */
internal class DockPodDrawable(
    private val host: View,
    private val heightDp: Float,
    private val maxWidthDp: Float,
    private val circle: Boolean,
    private val offsetYDp: Float,
    private val padDp: Float = 8f,
    private val hero: Boolean = false
) : Drawable() {

    private val density = host.resources.displayMetrics.density

    internal val isHero: Boolean get() = hero

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heroPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyLin = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyRad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val convex = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caustic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dome = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val touchBloom = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rippleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val pod = RectF()
    private val domeRect = RectF()
    private val rimRect = RectF()
    private val innerRimRect = RectF()
    private val shadowRect = RectF()
    private val clip = Path()
    private val matrix = Matrix()
    private val bloomMatrix = Matrix()
    private val unitBloom = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0x99FFFFFF.toInt(), 0x38FFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.48f, 1f),
        Shader.TileMode.CLAMP
    )

    private var radius = 0f
    private var domeRadius = 0f
    private var cx = 0f
    private var cy = 0f
    private var built = false
    private var light = false
    private var accent = Color.WHITE

    // Springs: fluid iOS elasticity + press illumination + active state glow
    private var sx = SpringF(1f, 1f, PRESS_K, SpringF.damping(PRESS_K, 0.78f))
    private var sy = SpringF(1f, 1f, PRESS_K, SpringF.damping(PRESS_K, 0.78f))
    private var depth = SpringF(0f, 0f, 620f, SpringF.damping(620f, 0.82f))
    private var glowSpring = SpringF(0f, 0f, 180f, SpringF.damping(180f, 0.88f))

    // Internal touch-point caustic
    private var rx = 0f
    private var ry = 0f
    private var rRadius = SpringF(0f, 0f, 110f, SpringF.damping(110f, 0.55f))
    private var rAlpha = 0f
    private var rHeld = false

    private var running = false
    private var last = 0L
    private val tick = Runnable { advance() }

    init {
        touchBloom.shader = unitBloom
    }

    fun press(x: Float, y: Float) {
        refreshTheme()
        val ts = PRESS_K
        // Hero button subtly swells like a glass bead under touch; standard pods tuck smoothly
        val targetX = if (hero) 1.05f else 0.94f
        val targetY = if (hero) 1.02f else 0.92f
        sx = SpringF(sx.value, targetX, ts, SpringF.damping(ts, 0.80f)).also { it.velocity = sx.velocity }
        sy = SpringF(sy.value, targetY, ts, SpringF.damping(ts, 0.80f)).also { it.velocity = sy.velocity }
        depth.target = 1f
        rx = (x - cx).coerceIn(-pod.width() / 2f, pod.width() / 2f)
        ry = (y - cy).coerceIn(-pod.height() / 2f, pod.height() / 2f)
        rRadius = SpringF(0f, max(pod.width(), pod.height()) * 0.65f, 110f, SpringF.damping(110f, 0.56f))
        rAlpha = 1f
        rHeld = true
        kick()
    }

    fun release() {
        val kx = 420f
        val ky = 370f
        sx = SpringF(sx.value, 1f, kx, SpringF.damping(kx, 0.36f)).also { it.velocity = sx.velocity }
        sy = SpringF(sy.value, 1f, ky, SpringF.damping(ky, 0.34f)).also { it.velocity = sy.velocity }
        depth.target = 0f
        rHeld = false
        kick()
    }

    fun setActive(active: Boolean) {
        glowSpring.target = if (active) 1f else 0f
        kick()
    }

    private fun refreshTheme() {
        val l = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        val a = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { Color.WHITE }
        if (!built || l != light || a != accent) {
            light = l
            accent = a
            if (!bounds.isEmpty) build(bounds)
        }
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
        clip.reset()
        clip.addRoundRect(pod, radius, radius, Path.Direction.CW)

        val k = if (light) 1.18f else 1f
        fun white(a: Float) = ColorUtils.setAlphaComponent(Color.WHITE, (255 * a).toInt().coerceIn(0, 255))

        if (hero) {
            val tv = android.util.TypedValue()
            fun attr(id: Int): Int = if (host.context.theme.resolveAttribute(id, tv, true) && tv.data != 0) tv.data else accent
            val c1 = attr(com.jarvis.assistant.R.attr.themeAccent1)
            val c2 = attr(com.jarvis.assistant.R.attr.themeAccent2)
            heroPaint.shader = LinearGradient(
                pod.left, pod.top, pod.right, pod.bottom,
                ColorUtils.setAlphaComponent(c1, 224),
                ColorUtils.setAlphaComponent(c2, 236),
                Shader.TileMode.CLAMP
            )
        }

        // Crystal-clear glass pod gradient (subtle, never opaque gray)
        bodyLin.shader = LinearGradient(
            0f, pod.top, 0f, pod.bottom,
            intArrayOf(white(0.20f * k), white(0.05f * k), white(0.10f * k)),
            floatArrayOf(0f, 0.54f, 1f),
            Shader.TileMode.CLAMP
        )
        bodyRad.shader = RadialGradient(
            -w * 0.22f, -h * 0.34f, max(w, h) * 0.88f,
            intArrayOf(white(0.24f * k), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )

        convex.shader = LinearGradient(
            0f, pod.top, 0f, pod.bottom,
            intArrayOf(0x00000000, 0x00000000, (if (light) 0x22 else 0x3A) shl 24),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )

        matrix.reset()
        matrix.postScale(w * 0.44f, h * 0.48f)
        matrix.postTranslate(0f, pod.bottom + h * 0.08f)
        val causticShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(white(if (light) 0.48f else 0.34f), white(0.08f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        causticShader.setLocalMatrix(matrix)
        caustic.shader = causticShader

        val domeInset = max(radius * 0.48f, 3.5f * density)
        val domeH = min(h * 0.34f, 18f * density)
        domeRect.set(pod.left + domeInset, pod.top + 1.8f * density, pod.right - domeInset, pod.top + 1.8f * density + domeH)
        domeRadius = min(domeH, max(1f, domeRect.width())) / 2f
        dome.shader = LinearGradient(
            0f, domeRect.top, 0f, domeRect.bottom,
            intArrayOf(white(if (light) 0.72f else 0.44f), 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )

        val gw = min(radius * 0.50f, 7.5f * density)
        val gx = pod.left + max(radius * 0.88f, gw * 1.6f)
        val gy = pod.top + max(h * 0.20f, gw * 0.85f)
        matrix.reset()
        matrix.postScale(gw * 1.45f, gw * 0.56f)
        matrix.postTranslate(gx, gy)
        val glintShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x55FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        glintShader.setLocalMatrix(matrix)
        glint.shader = glintShader

        // Clean dual Fresnel optical rim (no garish pink/cyan static lines)
        val rw = 1.15f * density
        rim.strokeWidth = rw
        rimRect.set(pod); rimRect.inset(rw / 2f, rw / 2f)
        rim.shader = LinearGradient(
            pod.left * 0.3f, pod.top, pod.right * 0.3f, pod.bottom,
            intArrayOf(white(if (hero) 0.95f else 0.86f), white(0.14f), white(if (hero) 0.62f else 0.48f)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )

        innerRim.strokeWidth = 0.9f * density
        innerRimRect.set(pod); innerRimRect.inset(rw + 1.1f * density, rw + 1.1f * density)
        innerRim.shader = LinearGradient(
            0f, pod.top, 0f, pod.bottom,
            intArrayOf(white(0.32f), 0x00FFFFFF, white(0.20f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        shadowRect.set(pod.left + w * 0.10f, pod.bottom - 1.2f * density, pod.right - w * 0.10f, pod.bottom + 4.2f * density)
        shadow.color = ColorUtils.setAlphaComponent(Color.BLACK, if (light) 28 else 56)
        built = true
    }

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
        if (!rHeld && rAlpha > 0f) rAlpha = max(0f, rAlpha - dt / 0.45f)
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

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty || !built) return
        val d = depth.value.coerceIn(0f, 1f)
        val g = glowSpring.value.coerceIn(0f, 1f)

        shadow.alpha = ((if (light) 28 else 56) * (1f - 0.40f * d)).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawOval(shadowRect, shadow)

        canvas.translate(0f, (if (hero) 0.5f else 1.1f) * density * d)
        canvas.scale(sx.value, sy.value)

        if (hero) canvas.drawRoundRect(pod, radius, radius, heroPaint)
        canvas.drawRoundRect(pod, radius, radius, bodyLin)
        canvas.drawRoundRect(pod, radius, radius, bodyRad)

        // Subtle accent presence when active
        if (g > 0.01f || !hero) {
            glow.color = ColorUtils.setAlphaComponent(accent, (255 * (0.04f + 0.14f * g)).toInt().coerceIn(0, 255))
            canvas.drawRoundRect(pod, radius, radius, glow)
        }

        canvas.drawRoundRect(pod, radius, radius, convex)
        canvas.drawRoundRect(pod, radius, radius, caustic)

        // iOS 26/27 interactive internal light illumination on press
        if (rAlpha > 0.01f && rRadius.value > 1f) {
            val saved = canvas.save()
            canvas.clipPath(clip)
            val r = rRadius.value
            val a = rAlpha
            bloomMatrix.reset()
            bloomMatrix.postScale(r, r)
            bloomMatrix.postTranslate(rx, ry)
            unitBloom.setLocalMatrix(bloomMatrix)
            touchBloom.alpha = (255 * a * (if (hero) 0.72f else 0.58f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(rx, ry, r, touchBloom)

            rippleRing.strokeWidth = 1.0f * density
            rippleRing.color = ColorUtils.setAlphaComponent(Color.WHITE, (255 * a * 0.42f).toInt().coerceIn(0, 255))
            canvas.drawCircle(rx, ry, r * 0.82f, rippleRing)
            canvas.restoreToCount(saved)
        }

        // Top gloss intensifies gently when pressed (real illuminated glass feel)
        dome.alpha = (255 * (1f + 0.25f * d)).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(domeRect, domeRadius, domeRadius, dome)
        glint.alpha = ((if (light) 185 else 145) * (1f + 0.2f * d)).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(pod, radius, radius, glint)

        val innerR = max(0f, radius - 2.2f * density)
        canvas.drawRoundRect(innerRimRect, innerR, innerR, innerRim)
        rim.alpha = (220 + 35 * d).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rimRect, max(0f, radius - 0.6f * density), max(0f, radius - 0.6f * density), rim)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val PRESS_K = 680f
    }
}
