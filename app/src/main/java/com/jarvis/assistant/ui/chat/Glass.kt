package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.max

/**
 * Frosted "liquid glass": a translucent fill with a soft warm glow in the lower corner, a bright highlight along the
 * top edge, a thin iridescent rim (pink, cyan, violet, peach) and a fine inner hairline. It follows the app's
 * Liquid Glass strength setting and works on light and dark themes.
 */
class GlassDrawable(
    private val radiusPx: Float,
    private val widthPx: Float,
    private val light: Boolean,
    private val strength: Float,
    private val accent: Int,
    private val soft: Boolean = false
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val top = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val rimRect = RectF()
    private val hairRect = RectF()

    private fun k(): Float = (0.55f + 0.45f * strength) * (if (soft) 0.62f else 1f)

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        if (b.isEmpty) return
        rect.set(b)
        val inset = widthPx * 0.6f
        rimRect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        val hi = widthPx * 2.2f
        hairRect.set(b.left + hi, b.top + hi, b.right - hi, b.bottom - hi)

        val k = k()
        val topA = if (light) 0.46f * k else 0.15f * k
        val botA = if (light) 0.18f * k else 0.05f * k
        fill.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            ColorUtils.setAlphaComponent(Color.WHITE, (255 * topA).toInt()),
            ColorUtils.setAlphaComponent(Color.WHITE, (255 * botA).toInt()),
            Shader.TileMode.CLAMP
        )
        val glowA = if (light) 0.22f else 0.15f
        glow.shader = RadialGradient(
            b.right.toFloat(), b.bottom.toFloat(), max(b.width(), b.height()) * 0.8f,
            ColorUtils.setAlphaComponent(accent, (255 * glowA * k).toInt()), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        val rimA = ((if (light) 0.62f else 0.5f) * (if (soft) 0.7f else 1f) * 255).toInt()
        fun c(rgb: Int) = ColorUtils.setAlphaComponent(rgb, rimA)
        rim.strokeWidth = widthPx
        rim.shader = SweepGradient(
            b.exactCenterX(), b.exactCenterY(),
            intArrayOf(c(0xFFFF8FC7.toInt()), c(0xFF7FDCFF.toInt()), c(0xFFA98BFF.toInt()), c(0xFFFFB98A.toInt()), c(0xFFFF8FC7.toInt())),
            null
        )
        hair.strokeWidth = widthPx * 0.5f
        hair.color = ColorUtils.setAlphaComponent(Color.WHITE, if (light) 150 else 40)
        top.strokeWidth = widthPx * 1.4f
        val shine = ColorUtils.setAlphaComponent(Color.WHITE, if (light) 230 else 110)
        top.shader = LinearGradient(
            b.left + radiusPx, 0f, b.right - radiusPx, 0f,
            intArrayOf(Color.TRANSPARENT, shine, shine, Color.TRANSPARENT), floatArrayOf(0f, 0.25f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fill)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, glow)
        canvas.drawRoundRect(hairRect, max(0f, radiusPx - widthPx * 2f), max(0f, radiusPx - widthPx * 2f), hair)
        canvas.drawRoundRect(rimRect, radiusPx, radiusPx, rim)
        val y = rect.top + widthPx * 1.6f
        canvas.drawLine(rect.left + radiusPx, y, rect.right - radiusPx, y, top)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radiusPx)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Builds glass backgrounds that follow the current theme and the user's Liquid Glass strength. */
object Glass {
    private fun isLight(): Boolean = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
    private fun strength(): Float = try { (ThemeManager.getLiquidGlassAlpha() / 255f).coerceIn(0f, 1f) } catch (_: Throwable) { 0.6f }
    private fun accent(): Int = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { 0xFFFF7A45.toInt() }

    /** A large glass surface: message panels, the composer. */
    fun panel(context: Context, radiusDp: Float): Drawable {
        val d = context.resources.displayMetrics.density
        return GlassDrawable(radiusDp * d, 1.3f * d, isLight(), strength(), accent())
    }

    /** A quieter glass sheet for something that sits inside a panel: the step list, a file card. */
    fun inset(context: Context, radiusDp: Float): Drawable {
        val d = context.resources.displayMetrics.density
        return GlassDrawable(radiusDp * d, 1f * d, isLight(), strength(), accent(), soft = true)
    }
}

/** A vertical LinearLayout that is a glass panel: the shape every Jarvis answer lives in. */
class GlassPanelLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    init {
        orientation = VERTICAL
        background = Glass.panel(context, 24f)
        val d = resources.displayMetrics.density
        setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
    }
}

/** Applies [Glass.inset] to any view (the file card, small buttons). */
fun View.useInsetGlass(radiusDp: Float) {
    background = Glass.inset(context, radiusDp)
}
