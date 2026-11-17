package com.jarvis.assistant.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.jarvis.assistant.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Liquid glass container (native port of the ReactBits "Glass Surface" prism look).
 *
 * The glass is painted as THIS view's own background (onDraw runs before the children are drawn), so your content
 * (text, icons, buttons) is always on top of it and is never covered. Only thin rim strokes are drawn after the
 * children, and they sit on the very edge.
 *
 *  - live capture of what is behind the view, downscaled (= blur)
 *  - API 33+: AGSL shader adds a 5/9-tap blur, edge refraction and the per-channel (R/G/B) dispersion
 *  - below 33: frosted capture + veil
 */
class LiquidGlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var cornerRadiusPx = 28f * density
    private var blurDp = 10f
    private var distortion = -220f
    private var redOffset = 0f
    private var greenOffset = 28f
    private var blueOffset = 56f
    private var refractionDp = 14f
    private var bezelDp = 18f
    private var veil = 0.10f
    private var prism = true
    private var autoRefresh = true
    private var refreshFps = 30
    private var sourceId = View.NO_ID

    private var source: View? = null

    // capture: three rotating buffers. The render thread may still be reading the previous frame's capture while the next one is
    // being drawn; writing into the same bitmap (erase, then redraw) is what made the glass flash. Each capture goes to its own buffer.
    private val bmps = arrayOfNulls<Bitmap>(BUFFERS)
    private val canvases = arrayOfNulls<Canvas>(BUFFERS)
    private val glassShaders = arrayOfNulls<Shader>(BUFFERS)
    private var cur = 0
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mine = IntArray(2)
    private val theirs = IntArray(2)
    private var hasShader = false

    // decoration
    private val rimRect = RectF()
    private val fullRect = RectF()
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fringePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassLayout)
        cornerRadiusPx = a.getDimension(R.styleable.LiquidGlassLayout_lgCornerRadius, cornerRadiusPx)
        blurDp = a.getFloat(R.styleable.LiquidGlassLayout_lgBlur, blurDp)
        distortion = a.getFloat(R.styleable.LiquidGlassLayout_lgDistortionScale, distortion)
        redOffset = a.getFloat(R.styleable.LiquidGlassLayout_lgRedOffset, redOffset)
        greenOffset = a.getFloat(R.styleable.LiquidGlassLayout_lgGreenOffset, greenOffset)
        blueOffset = a.getFloat(R.styleable.LiquidGlassLayout_lgBlueOffset, blueOffset)
        refractionDp = a.getFloat(R.styleable.LiquidGlassLayout_lgRefraction, refractionDp)
        bezelDp = a.getFloat(R.styleable.LiquidGlassLayout_lgBezel, bezelDp)
        veil = a.getFloat(R.styleable.LiquidGlassLayout_lgVeil, veil)
        prism = a.getBoolean(R.styleable.LiquidGlassLayout_lgPrism, prism)
        autoRefresh = a.getBoolean(R.styleable.LiquidGlassLayout_lgAutoRefresh, autoRefresh)
        refreshFps = a.getInt(R.styleable.LiquidGlassLayout_lgRefreshFps, refreshFps)
        sourceId = a.getResourceId(R.styleable.LiquidGlassLayout_lgSource, View.NO_ID)
        a.recycle()

        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false

        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
        elevation = 8f * density
        if (Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = 0x55FF8A5C
            outlineAmbientShadowColor = 0x22B06AB3
        }
    }

    // While one glass layout captures the screen, no glass layout draws itself (avoids recursion).
    override fun draw(canvas: Canvas) {
        if (capturing) return
        super.draw(canvas)
    }

    /** Runs BEFORE the children: the glass is the background of the content, never in front of it. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (capturing || width == 0 || height == 0) return
        val next = (cur + 1) % BUFFERS
        val b = bmps[next]
        val bc = canvases[next]
        val src = source
        val r = min(cornerRadiusPx, min(width, height) / 2f)

        if (b != null && bc != null && src != null) {
            captureBackdrop(src, b, bc)
            cur = next
            if (Build.VERSION.SDK_INT >= 33 && hasShader) {
                glassPaint.shader = glassShaders[next]
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glassPaint)
            } else {
                val save = canvas.save()
                val clip = android.graphics.Path().apply { addRoundRect(fullRect, r, r, android.graphics.Path.Direction.CW) }
                canvas.clipPath(clip)
                canvas.drawBitmap(b, null, fullRect, bmpPaint)
                canvas.restoreToCount(save)
                canvas.drawRoundRect(fullRect, r, r, veilPaint)
            }
        } else {
            // No backdrop yet (first frame): a plain frosted pane, never an empty hole.
            canvas.drawRoundRect(fullRect, r, r, veilPaint.also { it.alpha = 60 })
        }
        // soft light across the upper half - still behind the content
        canvas.drawRoundRect(rimRect, r, r, sheenPaint)
    }

    /** Runs AFTER the children: only hairline strokes on the very edge. */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (width == 0 || height == 0) return
        val r = min(cornerRadiusPx, min(width, height) / 2f)
        if (prism) {
            val s = 0.8f * density
            fringePaint.color = 0x59FF4FD8
            canvas.save(); canvas.translate(-s, -s)
            canvas.drawRoundRect(rimRect, r, r, fringePaint); canvas.restore()
            fringePaint.color = 0x2633E1FF
            canvas.save(); canvas.translate(s, s)
            canvas.drawRoundRect(rimRect, r, r, fringePaint); canvas.restore()
        }
        canvas.drawRoundRect(rimRect, r, r, edgePaint)
    }

    private fun captureBackdrop(src: View, b: Bitmap, bc: Canvas) {
        getLocationOnScreen(mine)
        src.getLocationOnScreen(theirs)
        b.eraseColor(Color.TRANSPARENT)
        bc.save()
        bc.scale(b.width / width.toFloat(), b.height / height.toFloat())
        bc.translate((theirs[0] - mine[0]).toFloat(), (theirs[1] - mine[1]).toFloat())
        capturing = true
        try { src.draw(bc) } catch (_: Throwable) { } finally { capturing = false }
        bc.restore()
    }

    // Default: refresh the backdrop at ~30 fps from a timer (cheap; 90/120 Hz phones would otherwise capture twice as often).
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val visible = isShown
            if (autoRefresh && visible && !capturing) invalidate()
            postDelayed(this, if (visible) 33L else 250L)
        }
    }

    // Locked refresh (lgRefreshFps above 30, used by the dock): one capture per display frame on 60 and 90 Hz
    // screens, and every second frame on faster ones, so the glass always updates at a steady 60 or 90 and never at 30.
    private var frameStep = 1
    private var frameCount = 0
    private val frameTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) return
            if (!isShown) {
                // Off screen: stop spending frames, look again shortly.
                postDelayed({ if (isAttachedToWindow) startLockedRefresh() }, 250L)
                return
            }
            if (frameCount++ % frameStep == 0 && autoRefresh && !capturing) invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startLockedRefresh() {
        val displayHz = display?.refreshRate ?: 60f
        // 60 Hz -> every frame (60); 90 Hz -> every frame (90); 120 Hz -> every second frame (60); 144 Hz -> every second (72).
        frameStep = max(1, Math.ceil((displayHz / 90f - 0.05f).toDouble()).toInt())
        frameCount = 0
        Choreographer.getInstance().removeFrameCallback(frameTick)
        Choreographer.getInstance().postFrameCallback(frameTick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        source = if (sourceId != View.NO_ID) rootView.findViewById(sourceId) else rootView
        removeCallbacks(tick)
        if (refreshFps > 30) startLockedRefresh() else post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        Choreographer.getInstance().removeFrameCallback(frameTick)
        source = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        fullRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val inset = 0.6f * density
        rimRect.set(inset, inset, w - inset, h - inset)

        for (i in 0 until BUFFERS) {
            val nb = Bitmap.createBitmap(
                max(1, (w * BACKDROP_SCALE).toInt()), max(1, (h * BACKDROP_SCALE).toInt()), Bitmap.Config.ARGB_8888
            )
            bmps[i] = nb
            canvases[i] = Canvas(nb)
            glassShaders[i] = null
        }
        cur = 0

        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.5f,
            intArrayOf(0x33FFFFFF, 0x0DFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        veilPaint.color = Color.argb((veil * 255).toInt().coerceIn(0, 255), 255, 255, 255)
        edgePaint.strokeWidth = 1.1f * density
        edgePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xF2FFFFFF.toInt(), 0x1AFFFFFF, 0x00FFFFFF, 0x8CFFFFFF.toInt()),
            floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        fringePaint.strokeWidth = 1f * density

        hasShader = false
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                for (i in 0 until BUFFERS) glassShaders[i] = buildGlassShader(bmps[i]!!, w, h)
                hasShader = true
            } catch (_: Throwable) { hasShader = false }
        }
    }

    @RequiresApi(33)
    private fun buildGlassShader(b: Bitmap, w: Int, h: Int): Shader {
        val content = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(w / b.width.toFloat(), h / b.height.toFloat()) })
        }
        val shader = RuntimeShader(GlassAgsl.SRC)
        shader.setInputShader("content", content)
        val ds = if (distortion == 0f) -1f else distortion
        val norm = abs(ds)
        shader.setFloatUniform("size", w.toFloat(), h.toFloat())
        shader.setFloatUniform("radius", min(cornerRadiusPx, min(w, h) / 2f))
        shader.setFloatUniform("bezel", min(bezelDp * density, min(w, h) / 2f))
        shader.setFloatUniform("strength", refractionDp * density)
        shader.setFloatUniform("blur", blurDp * density * 0.35f)
        shader.setFloatUniform("chroma", (ds + redOffset) / norm, (ds + greenOffset) / norm, (ds + blueOffset) / norm)
        shader.setFloatUniform("veil", veil)
        return shader
    }

    companion object {
        /** Backdrop is captured at ~1/7 size: cheap, and already most of the blur. */
        private const val BACKDROP_SCALE = 0.15f

        private const val BUFFERS = 3

        @Volatile private var capturing = false

        /** True while a glass layout is copying the screen behind it; overlays use it to stay out of that copy. */
        internal fun isCapturing(): Boolean = capturing
    }
}
