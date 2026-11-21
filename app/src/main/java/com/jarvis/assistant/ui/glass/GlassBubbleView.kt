package com.jarvis.assistant.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The iOS 26/27 Liquid Glass sliding tab lens inside the dock.
 *
 * Moves with fluid two-edge spring physics (leading edge runs ahead, trailing edge stretches &
 * thins conserving volume, then settles with a crisp liquid wobble).
 *
 * Optical lens rendering:
 *  - API 33+: Runs a dedicated AGSL Liquid Lens shader over the parent LiquidGlassLayout's live
 *    backdrop, genuinely magnifying (1.16x) and bending background detail through the gliding pill.
 *  - API 26–32: Crops and magnifies the parent LiquidGlassLayout's live backdrop inside the pill
 *    with a crisp multi-layer Fresnel specular rim, internal caustic, and top catch-light.
 */
class GlassBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val inset = 4.5f * density

    private var left = 0f
    private var right = 0f
    private var restWidth = 1f
    private var shown = false
    private var target: View? = null
    private var springL: SpringF? = null
    private var springR: SpringF? = null
    private var gliding = false
    private var lastFrame = 0L
    private val frame = Runnable { stepGlide() }
    private var tint = 0
    private var accentTint = 0

    // Pre-allocated paints & paths
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounce = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val innerRect = RectF()
    private val shadowRect = RectF()
    private val srcRect = RectF()
    private val clipPath = Path()
    private val m = Matrix()
    private val vibrancyFilter = FastGlassBlur.vibrancyFilter(1.26f, 6f)

    // API 33+ AGSL lens shader cache
    private var lensShader: Shader? = null
    private var lastBmpRef: Bitmap? = null
    private var lastBmpW = 0
    private var lastBmpH = 0

    private val unitRadial = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xFFFFFFFF.toInt(), 0x55FFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP
    )

    init {
        lensPaint.colorFilter = vibrancyFilter
    }

    /** Optional soft accent colour mixed delicately into the lens. */
    fun setTint(color: Int) {
        tint = color
        rebuild()
        invalidate()
    }

    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val t = target ?: return@OnLayoutChangeListener
        if (!gliding) snapTo(t)
    }

    /** Moves the lens to [tab]. First placement snaps without animation. */
    fun attachTo(tab: View?) {
        target?.removeOnLayoutChangeListener(layoutListener)
        target = tab
        if (tab == null) { hide(); return }
        tab.addOnLayoutChangeListener(layoutListener)
        if (tab.width == 0) return
        val l = edgeLeft(tab)
        val r = edgeRight(tab)
        if (!shown) snapTo(tab) else glideTo(l, r)
    }

    private var dragging = false

    fun currentTarget(): View? = target
    fun beginDrag() { dragging = true; invalidate() }
    fun endDrag() { dragging = false; invalidate() }

    fun dragTo(centerX: Float) {
        if (!shown) return
        val w = restWidth
        glideTo(centerX - w / 2f, centerX + w / 2f)
    }

    fun settle() {
        val t = target ?: return
        glideTo(edgeLeft(t), edgeRight(t))
    }

    fun hide() {
        shown = false
        animate().alpha(0f).setDuration(140).start()
    }

    private fun edgeLeft(t: View) = ((t.parent as? View)?.left ?: 0) + t.left + 3.5f * density
    private fun edgeRight(t: View) = ((t.parent as? View)?.left ?: 0) + t.right - 3.5f * density

    private fun snapTo(t: View) {
        left = edgeLeft(t)
        right = edgeRight(t)
        restWidth = max(1f, right - left)
        if (!shown) {
            shown = true
            animate().cancel()
            alpha = 1f
        }
        invalidate()
    }

    private fun glideTo(newL: Float, newR: Float) {
        val toRight = (newL + newR) > (left + right)
        restWidth = max(1f, newR - newL)
        // Fluid iOS 26/27 liquid tab droplet springs
        val kLead = 540f
        val kTrail = 240f
        val lead = SpringF.damping(kLead, 0.64f)
        val trail = SpringF.damping(kTrail, 0.78f)
        val oldVl = springL?.velocity ?: 0f
        val oldVr = springR?.velocity ?: 0f
        springL = SpringF(left, newL, if (toRight) kTrail else kLead, if (toRight) trail else lead).also { it.velocity = oldVl }
        springR = SpringF(right, newR, if (toRight) kLead else kTrail, if (toRight) lead else trail).also { it.velocity = oldVr }
        if (!gliding) {
            gliding = true
            lastFrame = SystemClock.uptimeMillis()
            postOnAnimation(frame)
        }
    }

    private fun stepGlide() {
        val l = springL ?: run { gliding = false; return }
        val r = springR ?: run { gliding = false; return }
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0.001f, 0.033f)
        lastFrame = now
        l.step(dt)
        r.step(dt)
        if (l.atRest(0.05f) && r.atRest(0.05f)) {
            left = l.target
            right = r.target
            gliding = false
        } else {
            left = l.value
            right = r.value
            postOnAnimation(frame)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        rebuild()
        target?.let { if (it.width > 0) snapTo(it) }
    }

    private fun rebuild() {
        val h = height.toFloat()
        if (h <= 0f) return
        val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        val top = inset
        val bottom = h - inset

        accentTint = if (tint != 0) ColorUtils.setAlphaComponent(tint, if (light) 26 else 34) else 0

        body.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 115 else 64),
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 48 else 22),
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 74 else 38)
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )

        shade.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x00000000, 0x00000000, Color.argb(if (light) 20 else 44, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        rim.strokeWidth = 1.25f * density
        rim.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 252 else 232),
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 85 else 46),
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 205 else 155)
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )

        innerRim.strokeWidth = 1.0f * density
        innerRim.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 110 else 68),
                0x00FFFFFF,
                ColorUtils.setAlphaComponent(Color.WHITE, if (light) 80 else 42)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        shadowPaint.color = Color.argb(if (light) 22 else 46, 0, 0, 0)
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown || right <= left || height == 0 || width == 0) return
        val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }

        val baseH = height - 2 * inset
        // Volume-preserving liquid stretch while gliding; gentle optical swell while dragging
        val stretch = ((right - left) / restWidth).coerceIn(0.84f, 1.95f)
        val dragBoost = if (dragging) 1.06f else 1f
        val vs = (1f / sqrt(stretch)) * dragBoost
        val hh = min(baseH * min(vs, 1.12f), height - 2f * density)
        val cy = height / 2f

        val halfExtraW = if (dragging) 3f * density else 0f
        rect.set(left - halfExtraW, cy - hh / 2f, right + halfExtraW, cy + hh / 2f)
        val r = hh / 2f

        // Soft floating contact shadow underneath the glass droplet
        shadowRect.set(rect.left + 2f * density, rect.top + 2f * density, rect.right - 2f * density, rect.bottom + 3.2f * density)
        canvas.drawRoundRect(shadowRect, r, r, shadowPaint)

        // Attempt live optical lens refraction of the parent LiquidGlassLayout backdrop
        val backdrop = (parent as? LiquidGlassLayout)?.currentBackdrop()
        var drewAgsl = false
        if (Build.VERSION.SDK_INT >= 33 && backdrop != null && !backdrop.isRecycled) {
            drewAgsl = drawAgslLens(canvas, backdrop, r, light)
        }

        if (!drewAgsl) {
            // Tier 2 & 3: Live magnified lens crop of backdrop + layered optical glass dome
            if (backdrop != null && !backdrop.isRecycled) {
                clipPath.reset()
                clipPath.addRoundRect(rect, r, r, Path.Direction.CW)
                val saved = canvas.save()
                canvas.clipPath(clipPath)
                val sx = backdrop.width / width.toFloat()
                val sy = backdrop.height / height.toFloat()
                val zoom = if (dragging) 1.22f else 1.15f
                val cxB = rect.centerX() * sx
                val cyB = rect.centerY() * sy
                val hwB = (rect.width() * sx) / (2f * zoom)
                val hhB = (rect.height() * sy) / (2f * zoom)
                srcRect.set(
                    (cxB - hwB).coerceIn(0f, backdrop.width.toFloat()),
                    (cyB - hhB).coerceIn(0f, backdrop.height.toFloat()),
                    (cxB + hwB).coerceIn(0f, backdrop.width.toFloat()),
                    (cyB + hhB).coerceIn(0f, backdrop.height.toFloat())
                )
                val ir = android.graphics.Rect(
                    srcRect.left.toInt(), srcRect.top.toInt(),
                    max(srcRect.left.toInt() + 1, srcRect.right.toInt()),
                    max(srcRect.top.toInt() + 1, srcRect.bottom.toInt())
                )
                canvas.drawBitmap(backdrop, ir, rect, lensPaint)
                canvas.restoreToCount(saved)
            }

            canvas.drawRoundRect(rect, r, r, body)
            canvas.drawRoundRect(rect, r, r, shade)
            if (accentTint != 0) {
                bounce.color = accentTint
                canvas.drawRoundRect(rect, r, r, bounce)
            }

            // Bottom internal caustic glow
            val w = rect.width()
            m.reset()
            m.postScale(w * 0.44f, hh * 0.48f)
            m.postTranslate(rect.centerX(), rect.bottom + hh * 0.06f)
            unitRadial.setLocalMatrix(m)
            spec.shader = unitRadial
            spec.alpha = if (light) 115 else 82
            canvas.drawRoundRect(rect, r, r, spec)

            // Crisp top-left catch-light
            m.reset()
            m.postScale(w * 0.40f, hh * 0.56f)
            m.postTranslate(rect.left + w * 0.30f, rect.top + hh * 0.20f)
            unitRadial.setLocalMatrix(m)
            spec.shader = unitRadial
            spec.alpha = if (light) 168 else 128
            canvas.drawRoundRect(rect, r, r, spec)
        }

        // Crisp double Fresnel rim (both AGSL & pre-33 benefit from vector-sharp AA edge)
        innerRect.set(rect)
        innerRect.inset(1.6f * density, 1.6f * density)
        canvas.drawRoundRect(innerRect, max(0f, r - 1.6f * density), max(0f, r - 1.6f * density), innerRim)
        canvas.drawRoundRect(rect, r, r, rim)
    }

    @RequiresApi(33)
    private fun drawAgslLens(canvas: Canvas, backdrop: Bitmap, r: Float, light: Boolean): Boolean {
        return try {
            var shader = lensShader as? RuntimeShader
            if (shader == null) {
                shader = RuntimeShader(GlassAgsl.LENS_SRC)
                lensShader = shader
            }
            if (lastBmpRef !== backdrop || lastBmpW != backdrop.width || lastBmpH != backdrop.height) {
                val bmpShader = BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply {
                        setScale(width / backdrop.width.toFloat(), height / backdrop.height.toFloat())
                    })
                }
                shader.setInputShader("content", bmpShader)
                lastBmpRef = backdrop
                lastBmpW = backdrop.width
                lastBmpH = backdrop.height
            }
            shader.setFloatUniform("viewSize", width.toFloat(), height.toFloat())
            shader.setFloatUniform("pillRect", rect.left, rect.top, rect.right, rect.bottom)
            shader.setFloatUniform("cornerR", r)
            shader.setFloatUniform("zoom", if (dragging) 1.24f else 1.16f)
            shader.setFloatUniform("strength", (if (dragging) 11f else 8.5f) * density)
            if (tint != 0) {
                shader.setFloatUniform("tintColor", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f)
                shader.setFloatUniform("tintAmount", if (light) 0.08f else 0.11f)
            } else {
                shader.setFloatUniform("tintColor", 1f, 1f, 1f)
                shader.setFloatUniform("tintAmount", 0f)
            }
            shader.setFloatUniform("isLight", if (light) 1f else 0f)
            lensPaint.shader = shader
            lensPaint.colorFilter = null
            canvas.drawRect(rect.left - 2f, rect.top - 2f, rect.right + 2f, rect.bottom + 2f, lensPaint)
            lensPaint.shader = null
            lensPaint.colorFilter = vibrancyFilter
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        gliding = false
        target?.removeOnLayoutChangeListener(layoutListener)
        lastBmpRef = null
        lensShader = null
        super.onDetachedFromWindow()
    }
}
