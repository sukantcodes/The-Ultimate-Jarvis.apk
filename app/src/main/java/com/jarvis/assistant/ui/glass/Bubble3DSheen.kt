package com.jarvis.assistant.ui.glass

import android.graphics.Canvas
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
import kotlin.math.max
import kotlin.math.min

/**
 * The 3D "glass bubble" shading that is laid over a button (through the view's overlay, so the button's own
 * background, colours and theme tinting are never touched):
 *  - a convex shade that darkens the lower part, so the button reads as a rounded dome,
 *  - a caustic: light gathered at the bottom edge, as it is in a real glass bead,
 *  - a glossy window highlight across the top and a small glint in the corner,
 *  - a bevelled rim, bright at the top and soft at the bottom.
 * Draw cost is five cached shaders; nothing is allocated while drawing.
 */
internal class Bubble3DSheen(
    private val density: Float,
    private val light: Boolean,
    private val cornerPx: Float
) : Drawable() {

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
        val shadeAlpha = if (light) 0x26 else 0x40
        shade.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x00000000, 0x00000000, shadeAlpha shl 24), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )

        // Caustic: a soft pool of light hugging the bottom edge.
        val cw = w * 0.5f
        val ch = h * 0.55f
        matrix.reset()
        matrix.postScale(cw, ch)
        matrix.postTranslate(b.exactCenterX(), bottom + ch * 0.12f)
        val causticShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(if (light) 0x66FFFFFF else 0x4DFFFFFF, 0x1AFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        causticShader.setLocalMatrix(matrix)
        caustic.shader = causticShader

        // Glossy window across the top.
        val inset = max(radius * 0.5f, 3f * density)
        val glossH = min(h * 0.46f, 24f * density)
        glossRect.set(b.left + inset, top + 1.6f * density, b.right - inset, top + 1.6f * density + glossH)
        glossRadius = min(glossH, glossRect.width()) / 2f
        gloss.shader = LinearGradient(
            0f, glossRect.top, 0f, glossRect.bottom,
            intArrayOf(if (light) 0xA6FFFFFF.toInt() else 0x7AFFFFFF, 0x05FFFFFF), null, Shader.TileMode.CLAMP
        )

        // Bevel.
        val rw = 1.2f * density
        rim.strokeWidth = rw
        rimRect.set(b.left + rw / 2f, top + rw / 2f, b.right - rw / 2f, bottom - rw / 2f)
        rimRadius = max(0f, radius - rw / 2f)
        rim.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xCCFFFFFF.toInt(), 0x1AFFFFFF, 0x55FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )

        // Glint in the top-left corner; smaller on large buttons so it stays a detail.
        val g = min(radius * 0.55f, 9f * density)
        glintCx = b.left + max(radius * 0.95f, g * 1.6f)
        glintCy = top + max(h * 0.2f, g * 0.9f)
        glintRx = g * 1.5f
        glintRy = g * 0.6f
        // Built once per size: changing a shader's matrix while drawing forces the GPU to rebuild it every frame.
        val glintShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        matrix.reset()
        matrix.postScale(glintRx, glintRy)
        matrix.postTranslate(glintCx, glintCy)
        glintShader.setLocalMatrix(matrix)
        glint.shader = glintShader
        glint.alpha = if (light) 190 else 150
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val layer = if (alphaValue < 255) canvas.saveLayerAlpha(RectF(bounds), alphaValue) else canvas.save()
        canvas.drawPath(body, shade)
        canvas.drawPath(body, caustic)
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
