package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Native Android take on Apple's iOS "liquid glass", modelled on the ReactBits GlassSurface "prism" look:
 *  - the aurora behind the surface is magnified like a lens, and compressed again along the rim (refraction);
 *  - the rim splits into red, green and blue fringes with growing offsets (the prism / chromatic dispersion);
 *  - a specular highlight slides across the top as the surface moves up and down the screen;
 *  - a bright edge light, a thin inner hairline and a faint accent glow finish the material.
 *
 * It only needs Canvas, so it works on every supported Android version (minSdk 26), and it follows the user's
 * Liquid Glass strength and the light or dark theme.
 */
class LiquidGlassDrawable(
    private val host: View,
    private val radiusPx: Float,
    private val tint: Int = 0,
    private val soft: Boolean = false,
    private val topOnly: Boolean = false
) : Drawable() {

    private val density = host.resources.displayMetrics.density
    private val screenW = host.resources.displayMetrics.widthPixels.toFloat()
    private val screenH = host.resources.displayMetrics.heightPixels.toFloat()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specular = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val topShine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fringeR = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val fringeG = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val fringeB = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }

    private val outer = RectF()
    private val outerPath = Path()
    private val innerPath = Path()
    private val ringInset = 15f * density
    private val loc = IntArray(2)
    private var alphaValue = 255

    private var cachedAt = 0L
    private var cachedLight = false
    private var cachedStrength = 0.6f
    private var shaderLight: Boolean? = null
    private var shaderStrength = -1f
    private var shaderTint = 0

    private val specularShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Color.argb(255, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP
    )

    private fun refreshThemeState() {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt < 400L && shaderLight != null) return
        cachedAt = now
        cachedLight = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        cachedStrength = try { (ThemeManager.getLiquidGlassAlpha() / 255f).coerceIn(0f, 1f) } catch (_: Throwable) { 0.6f }
    }

    private fun corners(): FloatArray = FloatArray(8) { radiusPx }

    private fun softness(): Float = if (soft) 0.62f else 1f

    private fun rebuildShaders(b: Rect) {
        val light = cachedLight
        val k = (0.55f + 0.45f * cachedStrength) * softness()
        val w = b.width().toFloat()
        val h = b.height().toFloat()

        val topAlpha = if (light) 0.50f * k else 0.17f * k
        val bottomAlpha = if (light) 0.20f * k else 0.05f * k
        fill.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            ColorUtils.setAlphaComponent(Color.WHITE, (255 * topAlpha).toInt()),
            ColorUtils.setAlphaComponent(Color.WHITE, (255 * bottomAlpha).toInt()),
            Shader.TileMode.CLAMP
        )

        val glowColor = if (tint != 0) tint else try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { Color.WHITE }
        val glowAlpha = (if (light) 0.26f else 0.20f) * k
        glow.shader = RadialGradient(
            b.right.toFloat(), b.bottom.toFloat(), max(w, h) * 0.85f,
            ColorUtils.setAlphaComponent(glowColor, (255 * glowAlpha).toInt()),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        val rimStrong = ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.95f else 0.72f) * 255 * softness()).toInt())
        val rimSoft = ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.45f else 0.14f) * 255 * softness()).toInt())
        val rimTint = ColorUtils.setAlphaComponent(glowColor, ((if (light) 0.55f else 0.50f) * 255 * softness()).toInt())
        edgeLight.shader = SweepGradient(
            b.exactCenterX(), b.exactCenterY(),
            intArrayOf(rimStrong, rimSoft, rimTint, rimSoft, rimStrong),
            floatArrayOf(0f, 0.28f, 0.62f, 0.86f, 1f)
        )

        val shine = ColorUtils.setAlphaComponent(Color.WHITE, if (light) 235 else 130)
        topShine.shader = LinearGradient(
            b.left + radiusPx, 0f, b.right - radiusPx, 0f,
            intArrayOf(Color.TRANSPARENT, shine, shine, Color.TRANSPARENT),
            floatArrayOf(0f, 0.22f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )

        hairline.color = ColorUtils.setAlphaComponent(Color.WHITE, if (light) 140 else 34)

        // Prism fringes: red sits on the edge, green and blue step inwards (the 28 and 56 offsets of the preset).
        val fringeAlpha = ((if (light) 0.34f else 0.42f) * softness() * 255).toInt()
        fringeR.color = Color.argb(fringeAlpha, 255, 0, 0)
        fringeG.color = Color.argb(fringeAlpha, 0, 255, 0)
        fringeB.color = Color.argb(fringeAlpha, 0, 0, 255)

        shaderLight = light
        shaderStrength = cachedStrength
        shaderTint = tint
    }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        if (b.isEmpty) return
        outer.set(b)
        // A sheet that only rounds its top corners pushes the bottom corners below its own bounds, where they are clipped.
        if (topOnly) outer.bottom += radiusPx
        outerPath.reset()
        outerPath.addRoundRect(outer, corners(), Path.Direction.CW)
        val inner = RectF(outer)
        inner.inset(ringInset, ringInset)
        innerPath.reset()
        val innerR = max(0f, radiusPx - ringInset)
        innerPath.addRoundRect(inner, FloatArray(8) { innerR }, Path.Direction.CW)
        shaderLight = null
    }

    @Suppress("DEPRECATION")
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        refreshThemeState()
        if (shaderLight != cachedLight || shaderStrength != cachedStrength || shaderTint != tint) rebuildShaders(b)

        val light = cachedLight
        val k = (0.55f + 0.45f * cachedStrength) * softness()
        val w = b.width().toFloat()
        val h = b.height().toFloat()

        host.getLocationOnScreen(loc)
        val originX = (loc[0] + b.left).toFloat()
        val originY = (loc[1] + b.top).toFloat()
        val yFraction = (originY / screenH).coerceIn(0f, 1f)

        val layer = if (alphaValue < 255) canvas.saveLayerAlpha(outer, alphaValue) else canvas.save()

        canvas.save()
        canvas.clipPath(outerPath)

        // 1. Lens: the aurora behind the glass, magnified a little and richer than the open backdrop.
        val cx = originX + w / 2f
        val cy = originY + h / 2f
        canvas.save()
        canvas.translate(-originX + b.left, -originY + b.top)
        canvas.translate(cx, cy)
        canvas.scale(1.08f, 1.08f)
        canvas.translate(-cx, -cy)
        AuroraField.draw(canvas, screenW, screenH, light, (if (light) 0.95f else 1.0f) * k.coerceAtLeast(0.6f))
        canvas.restore()

        // 2. Refraction along the rim: the same field squeezed inwards, only inside a ring at the edge.
        canvas.save()
        canvas.clipPath(innerPath, Region.Op.DIFFERENCE)
        canvas.translate(-originX + b.left, -originY + b.top)
        canvas.translate(cx, cy)
        canvas.scale(0.90f, 0.90f)
        canvas.translate(-cx, -cy)
        AuroraField.draw(canvas, screenW, screenH, light, 0.85f * k.coerceAtLeast(0.6f))
        canvas.restore()

        // 3. Frosted body and the warm accent glow in the lower corner.
        canvas.drawPath(outerPath, fill)
        canvas.drawPath(outerPath, glow)

        // 4. Specular highlight that drifts as the surface moves down the screen.
        val sway = sin(AuroraField.phase * 0.8f)
        val sx = b.left + w * (0.18f + 0.55f * yFraction + 0.05f * sway)
        val sy = b.top + 0f
        val specAlpha = ((if (light) 0.42f else 0.26f) * softness() * 255).toInt()
        specular.shader = specularShader
        specular.alpha = specAlpha
        canvas.save()
        canvas.translate(sx, sy)
        canvas.scale(max(w * 0.55f, 1f), max(min(h, 130f * density) * 0.8f, 1f))
        canvas.drawCircle(0f, 0f, 1f, specular)
        canvas.restore()

        canvas.restore() // end clip to the glass shape

        // 5. Rim: bright edge light, prism fringes, inner hairline, crisp top shine.
        val edge = (if (soft) 1.0f else 1.3f) * density
        edgeLight.strokeWidth = edge
        hairline.strokeWidth = 0.6f * density
        topShine.strokeWidth = edge * 1.3f
        fringeR.strokeWidth = 1.2f * density
        fringeG.strokeWidth = 1.2f * density
        fringeB.strokeWidth = 1.2f * density

        val rim = RectF(outer)
        rim.inset(edge * 0.5f, edge * 0.5f)
        canvas.drawRoundRect(rim, radiusPx, radiusPx, edgeLight)

        val fr = RectF(outer)
        fr.inset(0.7f * density, 0.7f * density)
        canvas.drawRoundRect(fr, radiusPx, radiusPx, fringeR)
        fr.inset(0.8f * density, 0.8f * density)
        canvas.drawRoundRect(fr, max(0f, radiusPx - 0.8f * density), max(0f, radiusPx - 0.8f * density), fringeG)
        fr.inset(0.8f * density, 0.8f * density)
        canvas.drawRoundRect(fr, max(0f, radiusPx - 1.6f * density), max(0f, radiusPx - 1.6f * density), fringeB)

        val hair = RectF(outer)
        hair.inset(edge * 2.4f, edge * 2.4f)
        canvas.drawRoundRect(hair, max(0f, radiusPx - edge * 2.4f), max(0f, radiusPx - edge * 2.4f), hairline)

        val y = outer.top + edge * 1.6f
        canvas.drawLine(outer.left + radiusPx, y, outer.right - radiusPx, y, topShine)

        canvas.restoreToCount(layer)
    }

    override fun getOutline(outline: Outline) {
        if (topOnly) {
            outline.setRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom + radiusPx.toInt(), radiusPx)
        } else {
            outline.setRoundRect(bounds, radiusPx)
        }
        outline.alpha = 0.9f
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
