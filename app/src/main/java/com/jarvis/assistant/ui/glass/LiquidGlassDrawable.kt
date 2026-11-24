package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
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
 * Clear "liquid glass" for cards, inputs, sheets and dialogs.
 *
 * The pane is TRANSPARENT glass: no milky frosting, no colour fringes, no tinted glows. What it adds is only what real glass has:
 *  - a lens: on the Transparent (Crystal Mirror) theme the part of the picture under the pane is shown magnified, like looking
 *    through a convex lens (see [CrystalBackdrop.paintLens]);
 *  - a thin refraction band just inside the edge (light on top, a little shade below) that gives the pane its thickness;
 *  - one crisp rim, brightest at the top-left where the light hits, and a soft specular line along the top.
 * It is static and cheap: cached shaders, no clipping of children, no per-frame allocation.
 */
class LiquidGlassDrawable(
    private val host: View,
    private val radiusPx: Float,
    private val tint: Int = 0,
    private val soft: Boolean = false,
    private val topOnly: Boolean = false
) : Drawable() {

    private val density = host.resources.displayMetrics.density

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val patch = Paint(Paint.FILTER_BITMAP_FLAG)

    private val outer = RectF()
    private val outerPath = Path()
    private val rimRect = RectF()
    private val bandRect = RectF()
    private var bandRadius = 0f

    private val edgeW = (if (soft) 1.0f else 1.15f) * density

    private var alphaValue = 255
    private var cachedAt = 0L
    private var cachedLight = false
    private var cachedStrength = 0.6f
    private var builtLight: Boolean? = null
    private var builtStrength = -1f
    private var builtLens = false

    init { CrystalBackdrop.registerGlass(this) }

    private fun refreshThemeState() {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt < 500L && builtLight != null) return
        cachedAt = now
        cachedLight = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        cachedStrength = try { (ThemeManager.getLiquidGlassAlpha() / 255f).coerceIn(0f, 1f) } catch (_: Throwable) { 0.6f }
    }

    private fun rebuild(lens: Boolean) {
        val light = cachedLight
        val soften = if (soft) 0.8f else 1f
        val k = (0.6f + 0.4f * cachedStrength) * soften
        val top = outer.top
        val bottom = outer.bottom
        val w = outer.width()
        val h = outer.height()

        // Body: almost nothing. Just enough tone that a pane is still a pane over a very flat background.
        val lensCut = if (lens) 0.55f else 1f
        val bodyTop = (if (light) 0.30f else 0.050f) * k * lensCut
        val bodyBottom = (if (light) 0.14f else 0.018f) * k * lensCut
        body.shader = LinearGradient(
            0f, top, 0f, bottom,
            ColorUtils.setAlphaComponent(Color.WHITE, (255 * bodyTop).toInt()),
            ColorUtils.setAlphaComponent(Color.WHITE, (255 * bodyBottom).toInt()),
            Shader.TileMode.CLAMP
        )

        // Refraction band inside the edge: light gathers on top, a hint of shade collects below. Neutral, never coloured.
        val bandW = min(6f * density, min(w, h) * 0.20f)
        band.strokeWidth = bandW
        bandRect.set(outer); bandRect.inset(bandW / 2f + edgeW, bandW / 2f + edgeW)
        bandRadius = max(0f, radiusPx - bandW / 2f - edgeW)
        band.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, ((if (light) 0.50f else 0.17f) * k * 255).toInt()),
                0x00FFFFFF,
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )

        // Rim: one clean line, brightest where the light hits (top-left), softer on the far side.
        fun a(f: Float) = ColorUtils.setAlphaComponent(Color.WHITE, (255f * f * soften * (if (light) 1f else 0.85f)).toInt().coerceIn(0, 255))
        rim.strokeWidth = edgeW
        rim.shader = SweepGradient(
            outer.centerX(), outer.centerY(),
            intArrayOf(a(0.30f), a(0.42f), a(0.50f), a(0.95f), a(0.60f), a(0.30f)),
            floatArrayOf(0f, 0.25f, 0.5f, 0.625f, 0.75f, 1f)
        )

        // Soft specular line along the top edge.
        shine.strokeWidth = edgeW * 1.2f
        val sh = ColorUtils.setAlphaComponent(Color.WHITE, if (light) 200 else 95)
        shine.shader = LinearGradient(
            outer.left + radiusPx, 0f, outer.right - radiusPx, 0f,
            intArrayOf(Color.TRANSPARENT, sh, sh, Color.TRANSPARENT), floatArrayOf(0f, 0.25f, 0.75f, 1f), Shader.TileMode.CLAMP
        )

        builtLight = light
        builtStrength = cachedStrength
        builtLens = lens
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
        builtLight = null
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        refreshThemeState()
        val lens = CrystalBackdrop.lensActive()
        if (builtLight != cachedLight || builtStrength != cachedStrength || builtLens != lens) rebuild(lens)

        val layer = if (alphaValue < 255) canvas.saveLayerAlpha(outer, alphaValue) else canvas.save()

        if (lens) {
            // Small controls magnify more, big cards less, so a lens always reads as a lens without wrecking the layout behind text.
            val small = min(outer.width(), outer.height()) < 64f * density
            CrystalBackdrop.paintLens(canvas, host, outer, outerPath, GlassStyle.ZOOM, patch)
        }
        canvas.drawPath(outerPath, body)
        canvas.drawRoundRect(bandRect, bandRadius, bandRadius, band)
        canvas.drawRoundRect(rimRect, radiusPx, radiusPx, rim)
        val y = outer.top + edgeW * 1.7f
        canvas.drawLine(outer.left + radiusPx, y, outer.right - radiusPx, y, shine)

        canvas.restoreToCount(layer)
    }

    override fun getOutline(outline: Outline) {
        if (topOnly) {
            outline.setRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom + radiusPx.toInt(), radiusPx)
        } else {
            outline.setRoundRect(bounds, radiusPx)
        }
        // Fully opaque outline: the system then clips any shadow to OUTSIDE the pane, so it never shows through the clear glass.
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
