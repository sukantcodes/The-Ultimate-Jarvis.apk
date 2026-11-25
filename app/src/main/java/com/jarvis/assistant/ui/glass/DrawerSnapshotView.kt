package com.jarvis.assistant.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * A frozen, blurred picture of the app screen that sits behind the drawer's glass. The drawer glass reads this instead of the
 * live screen, so nothing behind the drawer animates, and it costs one small capture each time the drawer opens.
 * The picture is taken at a low resolution, which is also what blurs it.
 */
class DrawerSnapshotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val edge = Rect()
    private var bleedPx = 0f
    private val frost = Paint().apply { color = Color.argb(176, 255, 255, 255) }
    private val full = Paint().apply { color = Color.argb(238, 246, 248, 252) }

    /** [bleedPx] is how far this view reaches past the drawer's visible left edge (off screen). */
    fun setBleed(px: Float) { bleedPx = px; invalidate() }

    /** Takes the picture: the left [regionWidthPx] of [src], at about 1/10 size. */
    fun capture(src: View, regionWidthPx: Int) {
        if (src.width <= 0 || src.height <= 0) return
        val scale = 0.10f
        val w = max(1, (regionWidthPx * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        try {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.scale(scale, scale)
            LiquidGlassLayout.whileCapturing { src.draw(c) }
            bmp = b
            invalidate()
        } catch (_: Throwable) { }
    }

    fun release() { bmp = null }

    override fun onDraw(canvas: Canvas) {
        val b = bmp
        if (b == null) {
            // Before the picture exists (the first frames of opening) the pane is already light, so options never flash unreadable.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), full)
            return
        }
        dst.set(bleedPx, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(b, null, dst, paint)
        if (bleedPx > 0f) {
            edge.set(0, 0, 1, b.height)
            canvas.drawBitmap(b, edge, RectF(0f, 0f, bleedPx, height.toFloat()), paint)
        }
        // The options use dark ink, so the picture is frosted to a light tone on every theme (a dark theme would otherwise
        // give dark text on dark glass). The glass above still bends and magnifies this picture.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), frost)
    }
}
