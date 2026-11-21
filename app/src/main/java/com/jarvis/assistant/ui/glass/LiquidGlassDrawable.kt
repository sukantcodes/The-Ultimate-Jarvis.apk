package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.max
import kotlin.math.min

/**
 * Authentic iOS 26/27 Liquid Glass surface for cards, inputs, sheets and dialogs.
 *
 * Uses physically inspired optical layering (no synthetic neon magenta/cyan outlines):
 *  - Luminous adaptive crystal body that stays clear on dark backgrounds and crisp on light ones
 *  - Overhead sky reflection + subtle lower optical thickness depth + bottom caustic light pool
 *  - Dual-wall Fresnel rim (1px directional outer specular + soft inner glass-thickness bevel)
 *  - Crisp top catch-light arc and delicate corner glint
 *
 * Zero per-frame allocations; all shaders are cached per size and theme state.
 */
class LiquidGlassDrawable(
    @Suppress("unused") private val host: View,
    private val radiusPx: Float,
    private val tint: Int = 0,
    private val soft: Boolean = false,
    private val topOnly: Boolean = false
) : Drawable() {

    private val density = host.resources.displayMetrics.density

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val topShine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    // Optical thickness & caustic layers
    private val convex = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caustic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dome = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bevel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val domeRect = RectF()
    private val bevelRect = RectF()
    private val unitMatrix = Matrix()
    private var domeRadius = 0f
    private var bevelRadius = 0f
    private var glintCx = 0f
    private var glintCy = 0f
    private var glintRx = 0f
    private var glintRy = 0f
    private var cachedBubble = true
    private var builtBubble: Boolean? = null

    private val outer = RectF()
    private val outerPath = Path()
    private val rimRect = RectF()
    private val hairRect = RectF()

    private val edgeW = (if (soft) 0.95f else 1.15f) * density
    private val hairR = max(0f, radiusPx - edgeW * 2.2f)

    private var alphaValue = 255
    private var cachedAt = 0L
    private var cachedLight = false
    private var cachedStrength = 0.6f
    private var builtLight: Boolean? = null
    private var builtStrength = -1f
    private var builtTint = 0

    private fun refreshThemeState() {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt < 500L && builtLight != null) return
        cachedAt = now
        cachedLight = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        cachedStrength = try { (ThemeManager.getLiquidGlassAlpha() / 255f).coerceIn(0f, 1f) } catch (_: Throwable) { 0.6f }
        cachedBubble = try { com.jarvis.assistant.JarvisApp.instance.preferences.bubble3dEnabled } catch (_: Throwable) { true }
    }

    private fun rebuildShaders() {
        val light = cachedLight
        val soften = if (soft) 0.70f else 1f
        val k = (0.60f + 0.40f * cachedStrength) * soften
        val crystal = CrystalTheme.active()
        val clear = if (crystal) 0.50f else 1f
        val boost = if (crystal) 1.32f else 1f
        val top = outer.top
        val bottom = outer.bottom
        val w = outer.width()
        val h = outer.height()

        // Clean optical crystal body: luminous cool-white tint at top fading smoothly into clear depth
        val bodyTop = (if (light) 0.54f * k else 0.16f * k) * clear
        val bodyMid = (if (light) 0.34f * k else 0.08f * k) * clear
        val bodyBottom = (if (light) 0.42f * k else 0.11f * k) * clear
        val glassWhite = if (light) Color.WHITE else Color.rgb(240, 246, 255)
        body.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                ColorUtils.setAlphaComponent(glassWhite, (255 * bodyTop).toInt().coerceIn(0, 255)),
                ColorUtils.setAlphaComponent(glassWhite, (255 * bodyMid).toInt().coerceIn(0, 255)),
                ColorUtils.setAlphaComponent(glassWhite, (255 * bodyBottom).toInt().coerceIn(0, 255))
            ),
            floatArrayOf(0f, 0.54f, 1f),
            Shader.TileMode.CLAMP
        )

        // Overhead diffuse sky sheen
        val sheenAlpha = (if (light) 0.42f else 0.16f) * k
        sheen.shader = LinearGradient(
            0f, top, 0f, top + h * 0.48f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, (255 * sheenAlpha).toInt().coerceIn(0, 255)),
                ColorUtils.setAlphaComponent(Color.WHITE, (255 * sheenAlpha * 0.25f).toInt().coerceIn(0, 255)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        // Ambient theme refraction tint pooled gently in the lower-right optical corner
        val glowColor = if (tint != 0) tint else try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { Color.WHITE }
        val glowAlpha = (if (light) 0.14f else 0.12f) * k
        glow.shader = RadialGradient(
            outer.right - w * 0.12f, bottom, max(w, h) * 0.78f,
            ColorUtils.setAlphaComponent(glowColor, (255 * glowAlpha).toInt().coerceIn(0, 255)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        // Directional Fresnel glass rim (top-left bright specular, side transparent fade, bottom-right optical bounce)
        val rimPeak = ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.96f else 0.82f) * 255 * soften).toInt().coerceIn(0, 255))
        val rimFade = ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.28f else 0.10f) * 255 * soften).toInt().coerceIn(0, 255))
        val rimBounce = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(Color.WHITE, glowColor, 0.18f),
            ((if (light) 0.72f else 0.48f) * 255 * soften).toInt().coerceIn(0, 255)
        )
        edgeLight.shader = SweepGradient(
            outer.centerX(), outer.centerY(),
            intArrayOf(rimBounce, rimFade, rimPeak, rimPeak, rimFade, rimBounce),
            floatArrayOf(0f, 0.18f, 0.56f, 0.72f, 0.88f, 1f)
        )

        val shineA = ((if (light) 240 else 170) * soften).toInt().coerceIn(0, 255)
        topShine.shader = LinearGradient(
            outer.left + radiusPx * 0.75f, 0f, max(outer.left + radiusPx + 1f, outer.right - radiusPx * 0.75f), 0f,
            intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(Color.WHITE, shineA), ColorUtils.setAlphaComponent(Color.WHITE, shineA), Color.TRANSPARENT),
            floatArrayOf(0f, 0.22f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )

        hairline.color = ColorUtils.setAlphaComponent(Color.WHITE, if (light) 92 else 36)

        edgeLight.strokeWidth = edgeW
        hairline.strokeWidth = 0.65f * density
        topShine.strokeWidth = edgeW * 1.15f

        // Optical 3D depth & internal caustic (refined, clean, never heavy plastic)
        val c3 = (if (soft) 0.55f else 0.88f) * (0.65f + 0.35f * cachedStrength) * boost
        val shadeA = ((if (light) 0.08f else 0.20f) * c3 * 255).toInt().coerceIn(0, 255)
        convex.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x00000000, 0x00000000, shadeA shl 24),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )

        val cw = w * 0.48f
        val ch = h * 0.52f
        unitMatrix.reset()
        unitMatrix.postScale(cw, ch)
        unitMatrix.postTranslate(outer.centerX(), bottom + ch * 0.08f)
        val causticShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.34f else 0.20f) * c3 * 255).toInt().coerceIn(0, 255)),
                ColorUtils.setAlphaComponent(Color.WHITE, (0.06f * c3 * 255).toInt().coerceIn(0, 255)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        causticShader.setLocalMatrix(unitMatrix)
        caustic.shader = causticShader

        val domeInset = max(radiusPx * 0.50f, 4f * density)
        val domeH = min(h * 0.28f, 22f * density)
        domeRect.set(outer.left + domeInset, top + 1.5f * density, outer.right - domeInset, top + 1.5f * density + domeH)
        domeRadius = min(domeH, max(1f, domeRect.width())) / 2f
        dome.shader = LinearGradient(
            0f, domeRect.top, 0f, domeRect.bottom,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.48f else 0.24f) * c3 * 255).toInt().coerceIn(0, 255)),
                0x00FFFFFF
            ),
            null,
            Shader.TileMode.CLAMP
        )

        val bw = 1.8f * density
        bevel.strokeWidth = bw
        bevelRect.set(outer)
        bevelRect.inset(edgeW + bw * 0.5f, edgeW + bw * 0.5f)
        bevelRadius = max(0f, radiusPx - edgeW - bw * 0.5f)
        bevel.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.42f else 0.18f) * c3 * 255).toInt().coerceIn(0, 255)),
                0x00FFFFFF,
                ColorUtils.setAlphaComponent(Color.BLACK, ((if (light) 0.06f else 0.14f) * c3 * 255).toInt().coerceIn(0, 255))
            ),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )

        val g = min(radiusPx * 0.55f, 9f * density)
        glintCx = outer.left + max(radiusPx * 0.90f, g * 1.6f)
        glintCy = top + max(h * 0.15f, g * 0.85f)
        glintRx = g * 1.45f
        glintRy = g * 0.52f
        val sizeFactor = ((radiusPx * 2f) / max(1f, min(w, h))).coerceIn(0.25f, 1f)
        val glintAlpha = (((if (light) 0.65f else 0.42f) * c3 * sizeFactor) * 255).toInt().coerceIn(0, 255)
        val glintShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x55FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        unitMatrix.reset()
        unitMatrix.postScale(glintRx, glintRy)
        unitMatrix.postTranslate(glintCx, glintCy)
        glintShader.setLocalMatrix(unitMatrix)
        glint.shader = glintShader
        glint.alpha = glintAlpha

        builtBubble = cachedBubble
        builtLight = light
        builtStrength = cachedStrength
        builtTint = tint
    }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        if (b.isEmpty) return
        outer.set(b)
        if (topOnly) outer.bottom += radiusPx
        outerPath.reset()
        outerPath.addRoundRect(outer, radiusPx, radiusPx, Path.Direction.CW)
        rimRect.set(outer); rimRect.inset(edgeW * 0.5f, edgeW * 0.5f)
        hairRect.set(outer); hairRect.inset(edgeW * 2.2f, edgeW * 2.2f)
        builtLight = null
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        refreshThemeState()
        if (builtLight != cachedLight || builtStrength != cachedStrength || builtTint != tint || builtBubble != cachedBubble) {
            rebuildShaders()
        }

        val layer = if (alphaValue < 255) canvas.saveLayerAlpha(outer, alphaValue) else canvas.save()

        canvas.drawPath(outerPath, body)
        canvas.drawPath(outerPath, sheen)
        canvas.drawPath(outerPath, glow)
        if (cachedBubble) {
            canvas.drawPath(outerPath, convex)
            canvas.drawPath(outerPath, caustic)
            canvas.drawRoundRect(bevelRect, bevelRadius, bevelRadius, bevel)
            canvas.drawRoundRect(domeRect, domeRadius, domeRadius, dome)
            canvas.drawOval(glintCx - glintRx, glintCy - glintRy, glintCx + glintRx, glintCy + glintRy, glint)
        }

        canvas.drawRoundRect(rimRect, radiusPx, radiusPx, edgeLight)
        canvas.drawRoundRect(hairRect, hairR, hairR, hairline)
        if (outer.width() > radiusPx * 2f) {
            val y = outer.top + edgeW * 1.1f
            canvas.drawLine(outer.left + radiusPx * 0.75f, y, outer.right - radiusPx * 0.75f, y, topShine)
        }

        canvas.restoreToCount(layer)
    }

    override fun getOutline(outline: Outline) {
        if (topOnly) {
            outline.setRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom + radiusPx.toInt(), radiusPx)
        } else {
            outline.setRoundRect(bounds, radiusPx)
        }
        outline.alpha = 0.85f
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
