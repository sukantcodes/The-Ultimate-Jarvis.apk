package com.jarvis.assistant.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.jarvis.assistant.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Native port of the ReactBits "Glass Surface" idea (prism preset):
 *  - live backdrop capture + blur            (CSS backdrop-filter)
 *  - edge refraction with per-channel split   (SVG feDisplacementMap x3: R/G/B offsets)
 *  - specular rim, dispersion fringe, sheen   (the bright border / highlight)
 *
 * API 33+  : full AGSL refraction + chromatic split + blur
 * API 31-32: blur + rim + sheen
 * API <31  : frosted capture + rim + sheen
 *
 * Put your existing views INSIDE this layout. Do not give it a background.
 */
class LiquidGlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ---- config (ReactBits prism defaults) ----
    private val density = resources.displayMetrics.density
    private var cornerRadiusPx = 28f * density
    private var blurDp = 10f
    private var distortion = -220f
    private var redOffset = 0f
    private var greenOffset = 28f
    private var blueOffset = 56f
    private var refractionDp = 16f
    private var bezelDp = 18f
    private var veil = 0.10f
    private var prism = true
    private var autoRefresh = true
    private var sourceId = View.NO_ID

    private var source: View? = null
    private val backdrop = BackdropView(context)

    // ---- rim paints ----
    private val rimRect = RectF()
    private val rimPath = Path()
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fringePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
        sourceId = a.getResourceId(R.styleable.LiquidGlassLayout_lgSource, View.NO_ID)
        a.recycle()

        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false

        val provider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
        outlineProvider = provider
        elevation = 10f * density
        if (Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = 0x66FF8A5C
            outlineAmbientShadowColor = 0x33B06AB3
        }
        backdrop.outlineProvider = provider
        backdrop.clipToOutline = true
        addView(backdrop, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // Skip drawing while some glass layout is capturing the screen (prevents recursion)
    override fun draw(canvas: Canvas) {
        if (capturing) return
        super.draw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawRim(canvas)
    }

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (autoRefresh && isShown && !capturing) backdrop.invalidate()
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        source = if (sourceId != View.NO_ID) rootView.findViewById(sourceId) else rootView
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        source = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = min(cornerRadiusPx, min(w, h) / 2f)
        val inset = 0.6f * density
        rimRect.set(inset, inset, w - inset, h - inset)
        rimPath.reset()
        rimPath.addRoundRect(rimRect, r, r, Path.Direction.CW)

        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.55f,
            intArrayOf(0x4DFFFFFF, 0x14FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        edgePaint.strokeWidth = 1.1f * density
        edgePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xF2FFFFFF.toInt(), 0x1AFFFFFF, 0x00FFFFFF, 0x8CFFFFFF.toInt()),
            floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        fringePaint.strokeWidth = 1f * density
        glowPaint.strokeWidth = 7f * density
        glowPaint.color = 0x0FFFFFFF
        veilPaint.color = Color.argb((veil * 255).toInt(), 255, 255, 255)
        applyEffects()
    }

    private fun drawRim(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val r = min(cornerRadiusPx, min(width, height) / 2f)
        val save = canvas.save()
        canvas.clipPath(rimPath)
        if (Build.VERSION.SDK_INT < 33) canvas.drawRoundRect(rimRect, r, r, veilPaint)
        canvas.drawRoundRect(rimRect, r, r, sheenPaint.also { })
        canvas.drawRoundRect(rimRect, r, r, glowPaint)
        canvas.restoreToCount(save)

        if (prism) {
            // dispersion fringe: magenta/cyan split like the R/G/B channel offsets
            val s = 0.8f * density
            fringePaint.color = 0x59FF4FD8
            canvas.save(); canvas.translate(-s, -s)
            canvas.drawRoundRect(rimRect, r, r, fringePaint); canvas.restore()
            fringePaint.color = 0x5933E1FF
            canvas.save(); canvas.translate(s, s)
            canvas.drawRoundRect(rimRect, r, r, fringePaint); canvas.restore()
        }
        canvas.drawRoundRect(rimRect, r, r, edgePaint)
    }

    // ---------------------------------------------------------------- effects

    private fun applyEffects() {
        if (Build.VERSION.SDK_INT < 31 || backdrop.width == 0) return
        val blurPx = max(0.1f, blurDp * density * BACKDROP_SCALE)
        var effect = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        if (Build.VERSION.SDK_INT >= 33) effect = chainWithGlassShader(effect)
        backdrop.setRenderEffect(effect)
    }

    @RequiresApi(33)
    private fun chainWithGlassShader(blur: RenderEffect): RenderEffect {
        val w = backdrop.width.toFloat()
        val h = backdrop.height.toFloat()
        val shader = RuntimeShader(GlassAgsl.SRC)
        val ds = if (distortion == 0f) -1f else distortion
        val norm = abs(ds)
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("radius", min(cornerRadiusPx, min(w, h) / 2f))
        shader.setFloatUniform("bezel", min(bezelDp * density, min(w, h) / 2f))
        shader.setFloatUniform("strength", refractionDp * density)
        // ReactBits: per-channel displacement = distortionScale + channelOffset
        shader.setFloatUniform(
            "chroma",
            (ds + redOffset) / norm, (ds + greenOffset) / norm, (ds + blueOffset) / norm
        )
        shader.setFloatUniform("veil", veil)
        return RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(shader, "content"), blur
        )
    }

    // ---------------------------------------------------------------- backdrop

    private inner class BackdropView(ctx: Context) : View(ctx) {
        private var bmp: Bitmap? = null
        private var bmpCanvas: Canvas? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dst = RectF()
        private val mine = IntArray(2)
        private val theirs = IntArray(2)

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            bmp?.recycle()
            if (w <= 0 || h <= 0) { bmp = null; bmpCanvas = null; return }
            val b = Bitmap.createBitmap(
                max(1, (w * BACKDROP_SCALE).toInt()), max(1, (h * BACKDROP_SCALE).toInt()),
                Bitmap.Config.ARGB_8888
            )
            bmp = b
            bmpCanvas = Canvas(b)
            applyEffects()
        }

        @SuppressLint("MissingSuperCall")
        override fun onDraw(canvas: Canvas) {
            val src = source ?: return
            val b = bmp ?: return
            val bc = bmpCanvas ?: return
            if (capturing) return
            getLocationOnScreen(mine)
            src.getLocationOnScreen(theirs)
            b.eraseColor(Color.TRANSPARENT)
            bc.save()
            bc.scale(b.width / width.toFloat(), b.height / height.toFloat())
            bc.translate((theirs[0] - mine[0]).toFloat(), (theirs[1] - mine[1]).toFloat())
            capturing = true
            try { src.draw(bc) } finally { capturing = false }
            bc.restore()
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(b, null, dst, paint)
        }
    }

    companion object {
        /** Backdrop is captured at 1/4 size: cheap, and already half the blur. */
        private const val BACKDROP_SCALE = 0.25f

        @Volatile private var capturing = false
    }
}
