package com.jarvis.assistant.ui.glass

import android.content.Context
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
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Authentic iOS 26/27 Liquid Glass container targeting every Android version from 10+ (minSdk 26+).
 *
 * Adaptive 3-tier optical rendering pipeline:
 *  - Tier 1 (Android 13+ / API 33+): Real-time AGSL RuntimeShader with convex meniscus Snell
 *    refraction, 13-tap Golden-Angle disc blur, subtle optical chromatic dispersion along the rim,
 *    luminance-adaptive vibrancy, dual Fresnel specular rim, and interactive touch caustic glow.
 *  - Tier 2 (Android 12 / API 31–32): Hardware RenderNode + GPU RenderEffect (Gaussian blur chained
 *    with vibrancy ColorFilter) + optical rim lens-magnification band + directional Fresnel speculars.
 *  - Tier 3 (Android 10–11 / API 26–30): Zero-allocation in-place StackBlur with vibrancy boost +
 *    optical rim lens-magnification band + inner depth bevel + crisp Fresnel specular highlights.
 *
 * Also shares its live triple-buffered backdrop with child GlassBubbleView lenses so sliding dock
 * pills optically magnify and bend what sits underneath them.
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

    /** True when Liquid Glass is off in Settings and this layout is not the locked dock. */
    private var plain = false

    /** The locked dock bar keeps its glass regardless of the toggle. */
    private var lockedGlass = false

    // Triple-buffered live backdrop (prevents flicker while RenderThread reads the previous frame).
    private val bmps = arrayOfNulls<Bitmap>(BUFFERS)
    private val canvases = arrayOfNulls<Canvas>(BUFFERS)
    private val runtimeShaders = arrayOfNulls<Shader>(BUFFERS)
    private var cur = 0

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val lensRimPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mine = IntArray(2)
    private val theirs = IntArray(2)
    private var hasShader = false
    private var captureCostMs = 0f

    // Tier 2 (API 31–32) hardware RenderNode blur
    private var blurNode: Any? = null
    // Tier 3 (API 26–30 & software fallback) fast StackBlur
    private val stackBlur = FastGlassBlur()
    private val vibrancyFilter: ColorFilter = FastGlassBlur.vibrancyFilter(1.32f, 4f)

    // Interactive touch-reactive specular illumination
    private var touchX = 0f
    private var touchY = 0f
    private var touchGlowSpring = SpringF(0f, 0f, 180f, SpringF.damping(180f, 0.85f))
    private var lastTouchStepMs = 0L

    // Cached theme state
    private var cachedLight = false
    private var cachedLightAt = 0L
    private var builtForLight: Boolean? = null

    // Pre-allocated geometry & optical decoration paints (zero draw-time allocations)
    private val fullRect = RectF()
    private val rimRect = RectF()
    private val innerRimRect = RectF()
    private val lensCropRect = RectF()
    private val clipPath = Path()
    private val innerClipPath = Path()
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerDepthPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val topArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val touchBloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchBloomMatrix = Matrix()
    private val unitRadial = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0x55FFFFFF, 0x1EFFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.48f, 1f),
        Shader.TileMode.CLAMP
    )

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
        val locked = a.getBoolean(R.styleable.LiquidGlassLayout_lgLocked, false)
        val plainBg = a.getResourceId(R.styleable.LiquidGlassLayout_lgPlainBackground, 0)
        val plainElevation = a.getDimension(R.styleable.LiquidGlassLayout_lgPlainElevation, 0f)
        a.recycle()
        lockedGlass = locked
        plain = !locked && GlassMode.plain()

        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        bmpPaint.colorFilter = vibrancyFilter
        lensRimPaint.colorFilter = vibrancyFilter
        touchBloomPaint.shader = unitRadial

        if (plain) {
            if (plainBg != 0) background = androidx.core.content.ContextCompat.getDrawable(context, plainBg)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = plainElevation
        } else {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = min(cornerRadiusPx, min(view.width, view.height) / 2f)
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            elevation = 10f * density
            if (Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = 0x50000000
                outlineAmbientShadowColor = 0x26000000
            }
        }
    }

    /** Exposes the latest captured backdrop bitmap so child GlassBubbleView lenses can refract it live. */
    internal fun currentBackdrop(): Bitmap? = if (plain) null else bmps[cur]

    /** Updates interactive touch illumination coordinates (in this view's local space). */
    internal fun updateTouchLight(localX: Float, localY: Float, active: Boolean) {
        if (plain) return
        touchX = localX.coerceIn(0f, max(1f, width.toFloat()))
        touchY = localY.coerceIn(0f, max(1f, height.toFloat()))
        touchGlowSpring.target = if (active) 1f else 0f
        if (lastTouchStepMs == 0L) lastTouchStepMs = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun refreshLightState(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedLightAt > 500L || builtForLight == null) {
            cachedLightAt = now
            cachedLight = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
            if (builtForLight != cachedLight && width > 0 && height > 0) {
                rebuildDecorPaints(width, height)
            }
        }
        return cachedLight
    }

    override fun draw(canvas: Canvas) {
        if (capturing) return
        super.draw(canvas)
    }

    /** Runs BEFORE children: renders optical refracted backdrop so icons/text sit cleanly on top. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (plain || capturing || width == 0 || height == 0) return

        val light = refreshLightState()
        stepTouchSpring()

        val next = (cur + 1) % BUFFERS
        val b = bmps[next]
        val bc = canvases[next]
        val src = source
        val r = min(cornerRadiusPx, min(width, height) / 2f)

        if (b != null && bc != null && src != null) {
            val t0 = System.nanoTime()
            captureBackdrop(src, b, bc)
            captureCostMs = captureCostMs * 0.7f + ((System.nanoTime() - t0) / 1_000_000f) * 0.3f
            cur = next

            if (Build.VERSION.SDK_INT >= 33 && hasShader) {
                val rs = runtimeShaders[next]
                if (rs != null) {
                    drawAgslBackdrop(canvas, rs, light)
                }
            } else {
                drawPreApi33Glass(canvas, b, r, light)
            }
        } else {
            // First-frame clean crystal fallback (never a transparent hole)
            canvas.drawRoundRect(fullRect, r, r, veilPaint)
            canvas.drawRoundRect(rimRect, r, r, sheenPaint)
        }

        // Subtle interactive touch caustic on pre-API 33 (API 33+ renders it inside AGSL)
        val glowVal = touchGlowSpring.value.coerceIn(0f, 1f)
        if (glowVal > 0.01f && (Build.VERSION.SDK_INT < 33 || !hasShader)) {
            val saved = canvas.save()
            canvas.clipPath(clipPath)
            val rad = max(height * 1.1f, 64f * density)
            touchBloomMatrix.reset()
            touchBloomMatrix.postScale(rad, rad)
            touchBloomMatrix.postTranslate(touchX, touchY)
            unitRadial.setLocalMatrix(touchBloomMatrix)
            touchBloomPaint.alpha = (255 * glowVal * (if (light) 0.65f else 0.50f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(touchX, touchY, rad, touchBloomPaint)
            canvas.restoreToCount(saved)
        }
    }

    /**
     * Tier 2 (API 31–32 Hardware RenderEffect) & Tier 3 (API 26–30 Fast StackBlur) rendering:
     * Combines optical blur + vibrancy with a magnified outer meniscus ring to simulate true
     * curved-edge Liquid Glass refraction on older Android versions.
     */
    private fun drawPreApi33Glass(canvas: Canvas, b: Bitmap, r: Float, light: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()

        val savedOuter = canvas.save()
        canvas.clipPath(clipPath)

        // 1. Optical meniscus rim band: draws a slightly magnified backdrop at the perimeter so
        //    background colors bend visibly along the curved glass wall (just like iOS bezel lensing).
        val zoomExpand = min(bezelDp * density * 0.22f, min(w, h) * 0.09f)
        lensCropRect.set(-zoomExpand, -zoomExpand, w + zoomExpand, h + zoomExpand)

        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated && blurNode != null) {
            drawWithRenderNode(canvas, b, w, h, lensCropRect)
        } else {
            val blurRadius = (blurDp * 0.55f).toInt().coerceIn(3, 14)
            stackBlur.blurInPlace(b, blurRadius)
            // Outer refracted meniscus ring
            canvas.drawBitmap(b, null, lensCropRect, lensRimPaint)
            // Inner crisp frosted body
            val savedInner = canvas.save()
            canvas.clipPath(innerClipPath)
            canvas.drawBitmap(b, null, fullRect, bmpPaint)
            canvas.restoreToCount(savedInner)
        }

        // 2. Adaptive glass body veil + subtle internal thickness shading + top sky sheen
        canvas.drawRoundRect(fullRect, r, r, veilPaint)
        canvas.drawRoundRect(fullRect, r, r, innerDepthPaint)
        canvas.drawRoundRect(fullRect, r, r, sheenPaint)

        canvas.restoreToCount(savedOuter)
    }

    @RequiresApi(33)
    private fun drawAgslBackdrop(canvas: Canvas, shader: Shader, light: Boolean) {
        val rs = shader as? RuntimeShader ?: return
        updateShaderDynamicUniforms(rs, light)
        glassPaint.shader = rs
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glassPaint)
    }

    @RequiresApi(31)
    private fun drawWithRenderNode(canvas: Canvas, b: Bitmap, w: Float, h: Float, lensRect: RectF) {
        val node = blurNode as? RenderNode ?: return
        try {
            node.setPosition(0, 0, w.toInt(), h.toInt())
            val rec = node.beginRecording(w.toInt(), h.toInt())
            rec.drawBitmap(b, null, lensRect, lensRimPaint)
            val innerSave = rec.save()
            rec.clipPath(innerClipPath)
            rec.drawBitmap(b, null, fullRect, bmpPaint)
            rec.restoreToCount(innerSave)
            node.endRecording()
            canvas.drawRenderNode(node)
        } catch (_: Throwable) {
            // Safe fallback if a vendor GPU rejects RenderNode recording
            stackBlur.blurInPlace(b, (blurDp * 0.55f).toInt().coerceIn(3, 12))
            canvas.drawBitmap(b, null, fullRect, bmpPaint)
        }
    }

    /** Runs AFTER children: razor-thin Fresnel specular rim strokes on the glass perimeter. */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (plain || width == 0 || height == 0) return
        val r = min(cornerRadiusPx, min(width, height) / 2f)
        val innerR = max(0f, r - 1.4f * density)

        // Inner soft Fresnel depth rim
        canvas.drawRoundRect(innerRimRect, innerR, innerR, innerEdgePaint)
        // Outer crisp 1px directional specular glass edge
        canvas.drawRoundRect(rimRect, r, r, edgePaint)
        // Top-edge luminous catch-light arc (signature Apple Liquid Glass highlight)
        if (width > r * 2.2f) {
            val y = rimRect.top + 0.7f * density
            canvas.drawLine(rimRect.left + r * 0.85f, y, rimRect.right - r * 0.85f, y, topArcPaint)
        }
    }

    private fun stepTouchSpring() {
        val now = SystemClock.uptimeMillis()
        if (lastTouchStepMs == 0L) return
        val dt = ((now - lastTouchStepMs) / 1000f).coerceIn(0.001f, 0.033f)
        lastTouchStepMs = now
        touchGlowSpring.step(dt)
        if (touchGlowSpring.atRest(0.004f)) {
            touchGlowSpring.value = touchGlowSpring.target
            if (touchGlowSpring.target == 0f) lastTouchStepMs = 0L
        } else {
            postInvalidateOnAnimation()
        }
    }

    private fun captureBackdrop(src: View, b: Bitmap, bc: Canvas) {
        getLocationOnScreen(mine)
        src.getLocationOnScreen(theirs)
        b.eraseColor(Color.TRANSPARENT)
        bc.save()
        bc.scale(b.width / width.toFloat(), b.height / height.toFloat())
        bc.translate((theirs[0] - mine[0]).toFloat(), (theirs[1] - mine[1]).toFloat())
        capturing = true
        try {
            src.draw(bc)
        } catch (_: Throwable) {
        } finally {
            capturing = false
        }
        bc.restore()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val visible = isShown
            if (autoRefresh && visible && !capturing) invalidate()
            val gap = if (visible) maxOf(33L, (captureCostMs * 5.5f).toLong()).coerceAtMost(120L) else 250L
            postDelayed(this, gap)
        }
    }

    private var frameStep = 1
    private var frameCount = 0
    private val frameTick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) return
            if (!isShown) {
                postDelayed({ if (isAttachedToWindow) startLockedRefresh() }, 250L)
                return
            }
            val step = maxOf(frameStep, Math.ceil((captureCostMs / 5.5f).toDouble()).toInt()).coerceAtMost(4 * frameStep)
            if (frameCount++ % step == 0 && autoRefresh && !capturing) invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startLockedRefresh() {
        val displayHz = display?.refreshRate ?: 60f
        frameStep = max(1, Math.ceil((displayHz / 90f - 0.05f).toDouble()).toInt())
        frameCount = 0
        Choreographer.getInstance().removeFrameCallback(frameTick)
        Choreographer.getInstance().postFrameCallback(frameTick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (plain) return
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
        if (plain || w <= 0 || h <= 0) return

        val wf = w.toFloat()
        val hf = h.toFloat()
        val r = min(cornerRadiusPx, min(wf, hf) / 2f)
        fullRect.set(0f, 0f, wf, hf)

        val inset = 0.65f * density
        rimRect.set(inset, inset, wf - inset, hf - inset)
        val innerInset = 1.9f * density
        innerRimRect.set(innerInset, innerInset, wf - innerInset, hf - innerInset)

        clipPath.reset()
        clipPath.addRoundRect(fullRect, r, r, Path.Direction.CW)

        val bevelInset = min(bezelDp * density * 0.48f, min(wf, hf) * 0.22f)
        val innerR = max(0f, r - bevelInset)
        innerClipPath.reset()
        innerClipPath.addRoundRect(
            bevelInset, bevelInset, wf - bevelInset, hf - bevelInset,
            innerR, innerR, Path.Direction.CW
        )

        // Higher-detail capture scale on API 33+ so AGSL optical rim bending looks razor-sharp.
        val scale = if (Build.VERSION.SDK_INT >= 33) BACKDROP_SCALE_AGSL else BACKDROP_SCALE_LEGACY
        for (i in 0 until BUFFERS) {
            try {
                val bw = max(2, (w * scale).toInt())
                val bh = max(2, (h * scale).toInt())
                val nb = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                bmps[i] = nb
                canvases[i] = Canvas(nb)
            } catch (_: OutOfMemoryError) {
                bmps[i] = null
                canvases[i] = null
            }
            runtimeShaders[i] = null
        }
        cur = 0

        rebuildDecorPaints(w, h)

        // Tier 2: Hardware RenderNode Gaussian Blur (API 31–32)
        blurNode = if (Build.VERSION.SDK_INT in 31..32) buildRenderNodeBlur() else null

        // Tier 1: Hardware AGSL RuntimeShader (API 33+)
        hasShader = false
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                for (i in 0 until BUFFERS) {
                    val bmp = bmps[i] ?: continue
                    runtimeShaders[i] = buildGlassShader(bmp, w, h)
                }
                hasShader = runtimeShaders[0] != null
            } catch (_: Throwable) {
                hasShader = false
            }
        }
    }

    private fun rebuildDecorPaints(w: Int, h: Int) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        builtForLight = light

        val r = min(cornerRadiusPx, min(wf, hf) / 2f)

        // Subtle sky-reflection sheen across upper glass surface
        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, hf * 0.56f,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 78 else 40),
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 22 else 10),
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )

        // Clean frosted glass body veil (never muddy)
        val veilA = ((if (light) max(veil, 0.22f) else max(veil, 0.09f)) * 255f).toInt().coerceIn(16, 110)
        veilPaint.shader = LinearGradient(
            0f, 0f, 0f, hf,
            ColorUtils.setAlphaComponent(if (light) 0xFFFFFFFF.toInt() else 0xFFF2F7FF.toInt(), veilA),
            ColorUtils.setAlphaComponent(if (light) 0xFFF4F8FF.toInt() else 0xFF0E1422.toInt(), (veilA * 0.55f).toInt()),
            Shader.TileMode.CLAMP
        )

        // Subtle lower-rim optical thickness depth
        innerDepthPaint.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(0x00000000, 0x00000000, Color.argb(if (light) 18 else 38, 0, 0, 0)),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )

        // Outer crisp 1px directional Fresnel specular rim (top-left bright, mid subtle, bottom-right bounce)
        edgePaint.strokeWidth = 1.05f * density
        edgePaint.shader = LinearGradient(
            0f, 0f, wf * 0.85f, hf,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 245 else 218),
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 95 else 54),
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 55 else 24),
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 185 else 132)
            ),
            floatArrayOf(0f, 0.34f, 0.66f, 1f),
            Shader.TileMode.CLAMP
        )

        // Soft inner refraction hairline (gives authentic 2-wall glass thickness)
        innerEdgePaint.strokeWidth = 1.15f * density
        innerEdgePaint.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 110 else 64),
                0x00FFFFFF,
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 75 else 36)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        // Signature top specular catch-light line
        topArcPaint.strokeWidth = 1.25f * density
        topArcPaint.shader = LinearGradient(
            r, 0f, max(r + 1f, wf - r), 0f,
            intArrayOf(
                0x00FFFFFF,
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 245 else 185),
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 245 else 185),
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.22f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    @RequiresApi(31)
    private fun buildRenderNodeBlur(): Any? = try {
        val node = RenderNode("JarvisLiquidGlass")
        val pxBlur = (blurDp * density * 0.72f).coerceIn(4f, 28f)
        node.setRenderEffect(RenderEffect.createBlurEffect(pxBlur, pxBlur, Shader.TileMode.CLAMP))
        node
    } catch (_: Throwable) {
        null
    }

    @RequiresApi(33)
    private fun buildGlassShader(b: Bitmap, w: Int, h: Int): Shader {
        val content = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(w / b.width.toFloat(), h / b.height.toFloat()) })
        }
        val shader = RuntimeShader(GlassAgsl.SRC)
        shader.setInputShader("content", content)
        val ds = if (distortion == 0f) -220f else distortion
        val norm = abs(ds)
        // Subtle, optical chromatic spread (keeps natural glass dispersion instead of garish color bands)
        val rScale = (ds + redOffset * 0.45f) / norm
        val gScale = (ds + greenOffset * 0.45f) / norm
        val bScale = (ds + blueOffset * 0.45f) / norm

        shader.setFloatUniform("size", w.toFloat(), h.toFloat())
        shader.setFloatUniform("radius", min(cornerRadiusPx, min(w, h) / 2f))
        shader.setFloatUniform("bezel", min(max(bezelDp, 14f) * density, min(w, h) * 0.48f))
        shader.setFloatUniform("strength", max(refractionDp, 12f) * density * 0.85f)
        shader.setFloatUniform("blur", max(blurDp, 6f) * density * 0.42f)
        shader.setFloatUniform("chroma", rScale, gScale, bScale)

        val crystalHere = !lockedGlass && CrystalTheme.active()
        shader.setFloatUniform("veil", if (crystalHere) veil * 0.45f else max(veil, 0.09f))
        shader.setFloatUniform("zoom", if (crystalHere) CrystalTheme.LENS_ZOOM else 1.04f)
        updateShaderDynamicUniforms(shader, cachedLight)
        return shader
    }

    @RequiresApi(33)
    private fun updateShaderDynamicUniforms(shader: RuntimeShader, light: Boolean) {
        shader.setFloatUniform("isLight", if (light) 1f else 0f)
        shader.setFloatUniform("touchPos", touchX, touchY)
        shader.setFloatUniform("touchGlow", touchGlowSpring.value.coerceIn(0f, 1f))
    }

    companion object {
        /** Higher resolution capture on API 33+ so optical rim refraction bends sharp real detail. */
        private const val BACKDROP_SCALE_AGSL = 0.34f
        /** Balanced fast capture scale on API 26–32 for smooth 60fps StackBlur / RenderNode blur. */
        private const val BACKDROP_SCALE_LEGACY = 0.25f

        private const val BUFFERS = 3

        @Volatile private var capturing = false

        internal fun isCapturing(): Boolean = capturing
    }
}
