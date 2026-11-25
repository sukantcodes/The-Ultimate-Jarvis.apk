package com.jarvis.assistant.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The glass pane used by every card, input, sheet and dialog in the app. It is the same glass as the Settings page:
 * clear white glass that BENDS what is behind it at the edge (refraction, with the red/green/blue channels differing a
 * hair) and never magnifies it (zoom is 1). It is a background Drawable, so any view can carry it without its layout
 * changing: the view keeps its children, ids and constraints.
 *
 * What is "behind" is the screen's [GlassBackdropView] (or, on screens without one, the root view's own background).
 * Only that scene is copied, never the other views, so a pane never shows its own text blurred underneath.
 * Below Android 13 (no shader) the pane is a frosted copy of the same scene.
 */
class LensGlassDrawable(
    private val host: View,
    private val radiusPx: Float,
    private val soft: Boolean = false,
    private val topOnly: Boolean = false
) : Drawable() {

    private val density = host.resources.displayMetrics.density

    private val bmps = arrayOfNulls<Bitmap>(BUFFERS)
    private val canvases = arrayOfNulls<Canvas>(BUFFERS)
    private val shaders = arrayOfNulls<Shader>(BUFFERS)
    private var cur = 0
    private var hasShader = false
    private var hasFrame = false

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val outer = RectF()
    private val rimRect = RectF()
    private val clip = Path()
    private val mine = IntArray(2)
    private val theirs = IntArray(2)
    private var alphaValue = 255

    // The backdrop drifts slowly, so the glass refreshes at about 10 fps (every second clock beat): same look, half the work.
    private var beat = 0
    private val tick = Runnable { if ((beat++ and 1) == 0 && host.isShown && !AuroraClock.quiet) host.invalidate() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = AuroraClock.register(tick)
        override fun onViewDetachedFromWindow(v: View) = AuroraClock.unregister(tick)
    }

    init {
        host.addOnAttachStateChangeListener(attachListener)
        if (host.isAttachedToWindow) AuroraClock.register(tick)
    }

    /** Stops the refresh and frees the copies. Call before this drawable is replaced. */
    fun release() {
        host.removeOnAttachStateChangeListener(attachListener)
        AuroraClock.unregister(tick)
        for (i in 0 until BUFFERS) {
            bmps[i] = null; canvases[i] = null; shaders[i] = null
        }
    }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        if (b.isEmpty) return
        outer.set(0f, 0f, b.width().toFloat(), b.height().toFloat())
        // A sheet that only rounds its top corners pushes the bottom corners below its own bounds, where they are clipped.
        if (topOnly) outer.bottom += radiusPx
        val w = outer.width()
        val h = outer.height()
        val r = min(radiusPx, min(w, h) / 2f)
        clip.reset()
        clip.addRoundRect(outer, r, r, Path.Direction.CW)
        val inset = 0.6f * density
        rimRect.set(outer); rimRect.inset(inset, inset)

        val scale = sqrt(60_000f / (w * h)).coerceIn(0.10f, 0.35f)
        for (i in 0 until BUFFERS) {
            try {
                val nb = Bitmap.createBitmap(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), Bitmap.Config.ARGB_8888)
                bmps[i] = nb
                canvases[i] = Canvas(nb)
            } catch (_: OutOfMemoryError) {
                bmps[i] = null; canvases[i] = null
            }
            shaders[i] = null
        }
        cur = 0
        hasFrame = false

        val frost = GlassStyle.frost() * (if (soft) 0.7f else 1f)
        veilPaint.color = Color.argb((frost * 255).toInt().coerceIn(0, 255), 255, 255, 255)
        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.5f,
            intArrayOf(0x1CFFFFFF, 0x08FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        edgePaint.strokeWidth = (if (soft) 1.0f else 1.15f) * density
        edgePaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xD8FFFFFF.toInt(), 0x22FFFFFF, 0x10FFFFFF, 0x70FFFFFF),
            floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP
        )

        hasShader = false
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                for (i in 0 until BUFFERS) shaders[i] = buildShader(bmps[i] ?: return, w.toInt(), h.toInt(), r)
                hasShader = true
            } catch (_: Throwable) {
                hasShader = false
            }
        }
    }

    @androidx.annotation.RequiresApi(33)
    private fun buildShader(b: Bitmap, w: Int, h: Int, r: Float): Shader {
        val content = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(w / b.width.toFloat(), h / b.height.toFloat()) })
        }
        val shader = RuntimeShader(GlassAgsl.SRC)
        shader.setInputShader("content", content)
        shader.setFloatUniform("size", w.toFloat(), h.toFloat())
        shader.setFloatUniform("radius", r)
        shader.setFloatUniform("bezel", min(GlassStyle.BEZEL_DP * density, min(w, h) / 2f))
        shader.setFloatUniform("strength", GlassStyle.REFRACTION_DP * density * 1.3f * (if (soft) 0.6f else 1f))
        shader.setFloatUniform("blur", 0.7f * density)
        shader.setFloatUniform("chroma", 1.0f, 0.97f, 0.94f)
        shader.setFloatUniform("veil", GlassStyle.frost() * (if (soft) 0.7f else 1f))
        shader.setFloatUniform("zoom", GlassStyle.ZOOM)
        return shader
    }

    /** Copies the scene behind this pane into [b]; false when the screen has no scene to copy. */
    private fun captureScene(b: Bitmap, bc: Canvas): Boolean {
        val w = outer.width()
        val h = outer.height()
        host.getLocationOnScreen(mine)
        b.eraseColor(Color.TRANSPARENT)
        bc.save()
        bc.scale(b.width / w, b.height / h)
        var drew = false
        try {
            val backdrop = GlassBackdropView.forView(host)
            if (backdrop != null) {
                backdrop.getLocationOnScreen(theirs)
                bc.translate((theirs[0] - mine[0]).toFloat(), (theirs[1] - mine[1]).toFloat())
                backdrop.draw(bc)
                drew = true
            } else {
                // A screen without a backdrop (the main tabs): use the background its root view already paints.
                val root = GlassScreens.activityOf(host.context)?.findViewById<ViewGroup>(android.R.id.content)?.let { sceneRoot(it) }
                val bg = root?.background
                if (root != null && bg != null && root.width > 0) {
                    root.getLocationOnScreen(theirs)
                    bc.translate((theirs[0] - mine[0]).toFloat(), (theirs[1] - mine[1]).toFloat())
                    val saved = bg.copyBounds()
                    bg.setBounds(0, 0, root.width, root.height)
                    bg.draw(bc)
                    bg.setBounds(saved.left, saved.top, saved.right, saved.bottom)
                    drew = true
                }
            }
        } catch (_: Throwable) {
        }
        bc.restore()
        return drew
    }

    private fun sceneRoot(content: ViewGroup): View? = if (content.childCount > 0) content.getChildAt(0) else null

    override fun draw(canvas: Canvas) {
        if (outer.isEmpty) return
        val w = outer.width()
        val h = outer.height()
        val r = min(radiusPx, min(w, h) / 2f)
        val layer = if (alphaValue < 255) canvas.saveLayerAlpha(outer, alphaValue) else canvas.save()

        val next = (cur + 1) % BUFFERS
        val b = bmps[next]
        val bc = canvases[next]
        var painted = false
        if (b != null && bc != null && !LiquidGlassLayoutGate.capturing() && !(AuroraClock.quiet && hasFrame)) {
            if (captureScene(b, bc)) {
                cur = next
                hasFrame = true
            }
        }
        val frame = bmps[cur]
        if (hasFrame && frame != null) {
            if (Build.VERSION.SDK_INT >= 33 && hasShader && shaders[cur] != null) {
                glassPaint.shader = shaders[cur]
                canvas.drawRect(outer, glassPaint)
            } else {
                canvas.save()
                canvas.clipPath(clip)
                canvas.drawBitmap(frame, null, outer, bmpPaint)
                canvas.restore()
                canvas.drawPath(clip, veilPaint)
            }
            painted = true
        }
        if (!painted) canvas.drawPath(clip, veilPaint) // no scene: still a white frosted pane, never an empty hole

        canvas.drawRoundRect(rimRect, r, r, sheenPaint)
        canvas.drawRoundRect(rimRect, r, r, edgePaint)
        canvas.restoreToCount(layer)
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        if (topOnly) outline.setRoundRect(b.left, b.top, b.right, b.bottom + radiusPx.toInt(), radiusPx)
        else outline.setRoundRect(b, radiusPx)
        // Glass casts only a faint, soft shadow (never a dark one) and none shows through the pane itself.
        outline.alpha = 0.35f
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val BUFFERS = 2
    }
}

/** Lets a drawable pane stay out of a copy that a LiquidGlassLayout is taking of the screen. */
internal object LiquidGlassLayoutGate {
    fun capturing(): Boolean = LiquidGlassLayout.isCapturing()
}
