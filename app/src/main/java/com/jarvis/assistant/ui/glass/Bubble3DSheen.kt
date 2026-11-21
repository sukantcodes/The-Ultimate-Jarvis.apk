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
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Clean iOS 26/27 Liquid Glass optical overlay applied to buttons, cards, chips, and inputs
 * via ViewOverlay (so existing theme tints, ripples, and click targets remain untouched).
 *
 * Adds:
 *  - Subtle optical curvature depth & lower caustic bounce
 *  - Crisp directional Fresnel rim (bright top-left catch-light + gentle bottom-right bounce)
 *  - Delicate overhead sky reflection & small corner glint
 */
internal class Bubble3DSheen(
    private val density: Float,
    private val light: Boolean,
    private val cornerPx: Float,
    kIn: Float = 1f,
    private val accent: Int = Color.WHITE
) : Drawable() {

    private val crystal = CrystalTheme.active()
    private val k: Float = kIn * (if (crystal) 1.32f else 0.90f)
    private val glare = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerRect = RectF()
    private var innerRadius = 0f
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caustic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val body = Path()
    private val glossRect = RectF()
    private val rimRect = RectF()
    private val matrix = Matrix()
    private var radius = 0f
    private var glossRadius = 0f
    private var rimRadius = 0f
    private var glintCx = 0f
    private var glintCy = 0f
    private var glintRx = 0f
    private var glintRy = 0f
    private var alphaValue = 255

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        radius = if (cornerPx <= 0f) min(w, h) / 2f else min(cornerPx, min(w, h) / 2f)
        body.reset()
        body.addRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), radius, radius, Path.Direction.CW)

        val top = b.top.toFloat()
        val bottom = b.bottom.toFloat()
        val shadeAlpha = ((if (light) 0x16 else 0x2B) * k).toInt().coerceIn(0, 255)
        fun wc(a: Float) = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(Color.WHITE, accent, 0.22f),
            (255 * a * k).toInt().coerceIn(0, 255)
        )
        fun white(a: Float) = ColorUtils.setAlphaComponent(Color.WHITE, (255 * a * k).toInt().coerceIn(0, 255))

        shade.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x00000000, 0x00000000, shadeAlpha shl 24),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )

        // Subtle optical caustic near the bottom wall
        val cw = w * 0.48f
        val ch = h * 0.50f
        matrix.reset()
        matrix.postScale(cw, ch)
        matrix.postTranslate(b.exactCenterX(), bottom + ch * 0.10f)
        val causticShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(wc(if (light) 0.30f else 0.22f), wc(0.07f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        causticShader.setLocalMatrix(matrix)
        caustic.shader = causticShader

        // Overhead specular reflection band
        val inset = max(radius * 0.46f, 3f * density)
        val glossH = min(h * 0.36f, 20f * density)
        glossRect.set(b.left + inset, top + 1.2f * density, b.right - inset, top + 1.2f * density + glossH)
        glossRadius = min(glossH, max(1f, glossRect.width())) / 2f
        gloss.shader = LinearGradient(
            0f, glossRect.top, 0f, glossRect.bottom,
            intArrayOf(white(if (light) 0.48f else 0.30f), 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )

        // Crisp 1px directional Fresnel bevel
        val rw = 1.05f * density
        rim.strokeWidth = rw
        rimRect.set(b.left + rw / 2f, top + rw / 2f, b.right - rw / 2f, bottom - rw / 2f)
        rimRadius = max(0f, radius - rw / 2f)
        rim.shader = LinearGradient(
            b.left.toFloat(), top, b.right * 0.7f, bottom,
            intArrayOf(white(0.86f), white(0.14f), white(0.46f)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )

        // Subtle top-left catch-light glint
        val g = min(radius * 0.48f, 8f * density)
        glintCx = b.left + max(radius * 0.90f, g * 1.5f)
        glintCy = top + max(h * 0.18f, g * 0.85f)
        glintRx = g * 1.35f
        glintRy = g * 0.50f
        val glintShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x55FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        matrix.reset()
        matrix.postScale(glintRx, glintRy)
        matrix.postTranslate(glintCx, glintCy)
        glintShader.setLocalMatrix(matrix)
        glint.shader = glintShader
        glint.alpha = ((if (light) 150 else 110) * k).toInt().coerceIn(0, 255)

        if (crystal) {
            val gr = max(w, h) * 0.62f
            matrix.reset()
            matrix.postTranslate(b.left + w * 0.28f, top + h * 0.28f)
            val glareShader = RadialGradient(
                0f, 0f, gr,
                intArrayOf(white(0.24f), white(0.06f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            glareShader.setLocalMatrix(matrix)
            glare.shader = glareShader
            val iw = 0.95f * density
            innerRim.strokeWidth = iw
            val inOffset = rw + 1.8f * density
            innerRect.set(b.left + inOffset, top + inOffset, b.right - inOffset, bottom - inOffset)
            innerRadius = max(0f, radius - inOffset)
            innerRim.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(white(0.50f), wc(0.08f), white(0.26f)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val layer = if (alphaValue < 255) canvas.saveLayerAlpha(RectF(bounds), alphaValue) else canvas.save()
        canvas.drawPath(body, shade)
        canvas.drawPath(body, caustic)
        if (crystal) {
            canvas.drawPath(body, glare)
            canvas.drawRoundRect(innerRect, innerRadius, innerRadius, innerRim)
        }
        canvas.drawRoundRect(glossRect, glossRadius, glossRadius, gloss)
        canvas.drawRoundRect(rimRect, rimRadius, rimRadius, rim)
        canvas.drawOval(glintCx - glintRx, glintCy - glintRy, glintCx + glintRx, glintCy + glintRy, glint)
        canvas.restoreToCount(layer)
    }

    override fun setAlpha(alpha: Int) { alphaValue = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
