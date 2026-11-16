package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
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

/**
 * Frosted "liquid glass" surface for cards, inputs, sheets and dialogs.
 *
 * It is deliberately STATIC and cheap: the card is a translucent frosted body, so the animated aurora backdrop of the
 * screen shows through it naturally (that is what makes it read as glass). The card itself only adds
 *  - a soft top sheen and a faint accent glow in the lower corner,
 *  - a bright edge light, a two-colour prism fringe (magenta / cyan, the ReactBits "prism" dispersion) and a hairline,
 *  - a crisp top shine.
 * No clipping, no per-frame allocation, no per-frame invalidation, no aurora re-draw per card.
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
    private val fringeOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fringeInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // 3D bubble layers: convex shade, bottom caustic, glossy dome, bevel and corner glint.
    private val convex = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caustic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dome = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bevel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val domeRect = RectF()
    private val bevelRect = RectF()
    private val unitMatrix = android.graphics.Matrix()
    private var domeRadius = 0f
    private var bevelRadius = 0f
    private var glintCx = 0f
    private var glintCy = 0f
    private var glintRx = 0f
    private var glintRy = 0f
    private var glintAlpha = 0
    private var cachedBubble = true
    private var builtBubble: Boolean? = null

    private val outer = RectF()
    private val outerPath = Path()
    private val rimRect = RectF()
    private val fringeRectA = RectF()
    private val fringeRectB = RectF()
    private val hairRect = RectF()

    private val edgeW = (if (soft) 1.0f else 1.25f) * density
    private val hairR = max(0f, radiusPx - edgeW * 2.4f)
    private val fringeRA = max(0f, radiusPx - 0.7f * density)
    private val fringeRB = max(0f, radiusPx - 1.5f * density)

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
        val soften = if (soft) 0.65f else 1f
        val k = (0.55f + 0.45f * cachedStrength) * soften
        val top = outer.top
        val bottom = outer.bottom
        val w = outer.width()
        val h = outer.height()

        // Frosted body: a calm white veil, denser at the top, so text stays readable on every theme.
        val bodyTop = if (light) 0.42f * k else 0.15f * k
        val bodyBottom = if (light) 0.20f * k else 0.05f * k
        body.shader = LinearGradient(
            0f, top, 0f, bottom,
            ColorUtils.setAlphaComponent(Color.WHITE, (255 * bodyTop).toInt()),
            ColorUtils.setAlphaComponent(Color.WHITE, (255 * bodyBottom).toInt()),
            Shader.TileMode.CLAMP
        )

        // Soft light falling across the upper half of the pane.
        val sheenAlpha = if (light) 0.55f * k else 0.20f * k
        sheen.shader = LinearGradient(
            0f, top, 0f, top + h * 0.55f,
            ColorUtils.setAlphaComponent(Color.WHITE, (255 * sheenAlpha).toInt()),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        val glowColor = if (tint != 0) tint else try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { Color.WHITE }
        val glowAlpha = (if (light) 0.20f else 0.16f) * k
        glow.shader = RadialGradient(
            outer.right, bottom, max(w, h) * 0.8f,
            ColorUtils.setAlphaComponent(glowColor, (255 * glowAlpha).toInt()),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        val rimStrong = ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.95f else 0.75f) * 255 * soften).toInt())
        val rimSoft = ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.40f else 0.12f) * 255 * soften).toInt())
        val rimTint = ColorUtils.setAlphaComponent(glowColor, ((if (light) 0.50f else 0.45f) * 255 * soften).toInt())
        edgeLight.shader = SweepGradient(
            outer.centerX(), outer.centerY(),
            intArrayOf(rimStrong, rimSoft, rimTint, rimSoft, rimStrong),
            floatArrayOf(0f, 0.28f, 0.62f, 0.86f, 1f)
        )

        val shine = ColorUtils.setAlphaComponent(Color.WHITE, if (light) 235 else 140)
        topShine.shader = LinearGradient(
            outer.left + radiusPx, 0f, outer.right - radiusPx, 0f,
            intArrayOf(Color.TRANSPARENT, shine, shine, Color.TRANSPARENT),
            floatArrayOf(0f, 0.22f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )

        hairline.color = ColorUtils.setAlphaComponent(Color.WHITE, if (light) 120 else 30)

        // Prism dispersion: a magenta fringe on the edge and a cyan one just inside it.
        val fringeAlpha = ((if (light) 0.22f else 0.30f) * soften * 255).toInt()
        fringeOuter.color = Color.argb(fringeAlpha, 255, 70, 205)
        fringeInner.color = Color.argb((fringeAlpha * 0.55f).toInt(), 40, 215, 255)

        edgeLight.strokeWidth = edgeW
        hairline.strokeWidth = 0.6f * density
        topShine.strokeWidth = edgeW * 1.3f
        fringeOuter.strokeWidth = 1.1f * density
        fringeInner.strokeWidth = 1.1f * density

        // ---- 3D bubble ----
        val c3 = (if (soft) 0.62f else 1f) * (0.6f + 0.4f * cachedStrength)
        val shadeA = ((if (light) 0.13f else 0.26f) * c3 * 255).toInt()
        convex.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x00000000, 0x00000000, shadeA shl 24), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        val cw = w * 0.5f
        val ch = h * 0.6f
        unitMatrix.reset(); unitMatrix.postScale(cw, ch); unitMatrix.postTranslate(outer.centerX(), bottom + ch * 0.1f)
        val causticShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.42f else 0.26f) * c3 * 255).toInt()),
                ColorUtils.setAlphaComponent(Color.WHITE, (0.08f * c3 * 255).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        causticShader.setLocalMatrix(unitMatrix)
        caustic.shader = causticShader

        val domeInset = max(radiusPx * 0.55f, 4f * density)
        val domeH = kotlin.math.min(h * 0.34f, 26f * density)
        domeRect.set(outer.left + domeInset, top + 1.8f * density, outer.right - domeInset, top + 1.8f * density + domeH)
        domeRadius = kotlin.math.min(domeH, domeRect.width()) / 2f
        dome.shader = LinearGradient(
            0f, domeRect.top, 0f, domeRect.bottom,
            intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.62f else 0.34f) * c3 * 255).toInt()), 0x04FFFFFF),
            null, Shader.TileMode.CLAMP
        )

        val bw = 2.2f * density
        bevel.strokeWidth = bw
        bevelRect.set(outer); bevelRect.inset(edgeW + bw / 2f, edgeW + bw / 2f)
        bevelRadius = max(0f, radiusPx - edgeW - bw / 2f)
        bevel.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.5f else 0.22f) * c3 * 255).toInt()),
                0x00FFFFFF, ColorUtils.setAlphaComponent(Color.BLACK, ((if (light) 0.10f else 0.20f) * c3 * 255).toInt())),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )

        val g = kotlin.math.min(radiusPx * 0.6f, 11f * density)
        glintCx = outer.left + max(radiusPx * 0.95f, g * 1.7f)
        glintCy = top + max(h * 0.16f, g * 0.9f)
        glintRx = g * 1.6f
        glintRy = g * 0.62f
        // On large cards the glint stays a quiet detail; on buttons and pills it is the bubble's catch-light.
        val sizeFactor = ((radiusPx * 2f) / kotlin.math.min(w, h)).coerceIn(0.3f, 1f)
        glintAlpha = (((if (light) 0.8f else 0.55f) * c3 * sizeFactor) * 255).toInt().coerceIn(0, 255)
        val glintShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        unitMatrix.reset(); unitMatrix.postScale(glintRx, glintRy); unitMatrix.postTranslate(glintCx, glintCy)
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
        // A sheet that only rounds its top corners pushes the bottom corners below its own bounds, where they are clipped.
        if (topOnly) outer.bottom += radiusPx
        outerPath.reset()
        outerPath.addRoundRect(outer, radiusPx, radiusPx, Path.Direction.CW)
        rimRect.set(outer); rimRect.inset(edgeW * 0.5f, edgeW * 0.5f)
        fringeRectA.set(outer); fringeRectA.inset(0.7f * density, 0.7f * density)
        fringeRectB.set(outer); fringeRectB.inset(1.5f * density, 1.5f * density)
        hairRect.set(outer); hairRect.inset(edgeW * 2.4f, edgeW * 2.4f)
        builtLight = null
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        refreshThemeState()
        if (builtLight != cachedLight || builtStrength != cachedStrength || builtTint != tint || builtBubble != cachedBubble) rebuildShaders()

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

        canvas.drawRoundRect(fringeRectA, fringeRA, fringeRA, fringeOuter)
        canvas.drawRoundRect(fringeRectB, fringeRB, fringeRB, fringeInner)
        canvas.drawRoundRect(rimRect, radiusPx, radiusPx, edgeLight)
        canvas.drawRoundRect(hairRect, hairR, hairR, hairline)
        val y = outer.top + edgeW * 1.6f
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
