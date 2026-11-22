package com.jarvis.assistant.ui.chat.liquid

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.chat.GlassMotion
import com.jarvis.assistant.ui.glass.GlassMode
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.max
import kotlin.math.min

/** The colours the ChatBox glass is made from. Every one of them comes from the active app theme, none is hard-coded. */
internal class LiquidTones(
    /** The colour of light on the glass: bright text colour on dark themes, the card colour on light themes. */
    val specular: Int,
    val accent: Int,
    val secondary: Int,
    /** Dark ink used for the faint edge on light themes. */
    val ink: Int,
    val backdrop: Int,
    val light: Boolean,
    /** The user's Liquid Glass strength setting, 0..1. */
    val strength: Float
)

internal object LiquidPalette {
    fun resolve(context: Context): LiquidTones {
        val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        val strength = try { (ThemeManager.getLiquidGlassAlpha() / 255f).coerceIn(0f, 1f) } catch (_: Throwable) { 0.6f }
        val themeAccent = GlassMode.attrColor(context, R.attr.themeAccent, Color.GRAY)
        val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { themeAccent }
        val secondary = try { ThemeManager.getThemeSecondaryColor() } catch (_: Throwable) { accent }
        val text = GlassMode.attrColor(context, R.attr.themeTextPrimary, Color.GRAY)
        val card = GlassMode.attrColor(context, R.attr.themeCardBg, Color.GRAY)
        val backdrop = GlassMode.attrColor(context, R.attr.themeBg, Color.DKGRAY)
        return LiquidTones(
            specular = ColorUtils.setAlphaComponent(if (light) card else text, 255),
            accent = accent,
            secondary = secondary,
            ink = ColorUtils.setAlphaComponent(text, 255),
            backdrop = ColorUtils.setAlphaComponent(backdrop, 255),
            light = light,
            strength = strength
        )
    }
}

/**
 * The ChatBox's liquid-glass material. It is drawn as shaders over one rounded shape (so every edge stays smooth):
 * a frosted body, a soft accent glow, a curved specular highlight along the top, a rim of light that is bright at the
 * top-left and bottom-right corners, a faint prism fringe, and an optional band of light that glides across the pane.
 *
 * It is deliberately a separate class from the shared [com.jarvis.assistant.ui.chat.Glass] so the message composer keeps its own look.
 */
class LiquidPaneDrawable(
    context: Context,
    val kind: LiquidPane.Kind,
    val radiusPx: Float
) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val tones = LiquidPalette.resolve(context)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specular = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fringeA = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fringeB = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val body = Path()
    private val rimPath = Path()
    private val rect = RectF()
    private val glintMatrix = Matrix()
    private var glintShader: LinearGradient? = null
    private var glintBand = 0f

    private var glintProgress = -1f
    private var glintLoop = false
    private var glintAnim: ValueAnimator? = null

    private fun k(): Float = 0.55f + 0.45f * tones.strength

    private fun corners(): FloatArray {
        val small = radiusPx * 0.3f
        return when (kind) {
            LiquidPane.Kind.ASSISTANT -> floatArrayOf(radiusPx, radiusPx, radiusPx, small)
            LiquidPane.Kind.USER -> floatArrayOf(radiusPx, radiusPx, small, radiusPx)
            else -> floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx)
        }
    }

    private fun addShape(out: Path, r: RectF, inset: Float) {
        val limit = min(r.width(), r.height()) / 2f
        val c = corners()
        val radii = FloatArray(8)
        for (i in 0 until 4) {
            val v = (min(c[i], limit) - inset).coerceAtLeast(0f)
            radii[i * 2] = v
            radii[i * 2 + 1] = v
        }
        val inner = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        out.reset()
        out.addRoundRect(inner, radii, Path.Direction.CW)
    }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        if (b.isEmpty) return
        rect.set(b)
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val k = k()
        val t = tones
        val rimWidth = 1.2f * density

        addShape(body, rect, 0f)
        addShape(rimPath, rect, rimWidth * 0.5f)

        fun spec(alpha: Float) = ColorUtils.setAlphaComponent(t.specular, (255 * alpha.coerceIn(0f, 1f)).toInt())
        fun accent(alpha: Float) = ColorUtils.setAlphaComponent(t.accent, (255 * alpha.coerceIn(0f, 1f)).toInt())
        fun second(alpha: Float) = ColorUtils.setAlphaComponent(t.secondary, (255 * alpha.coerceIn(0f, 1f)).toInt())
        val clear = ColorUtils.setAlphaComponent(t.specular, 0)

        // 1. Body
        when (kind) {
            LiquidPane.Kind.USER -> {
                val a = (if (t.light) 0.50f else 0.60f) * (0.72f + 0.28f * t.strength)
                fill.shader = LinearGradient(0f, 0f, w, h, accent(a), second(a * 0.68f), Shader.TileMode.CLAMP)
            }
            LiquidPane.Kind.WINDOW -> {
                val dark = ColorUtils.blendARGB(t.backdrop, Color.BLACK, 0.80f)
                fill.shader = null
                fill.color = ColorUtils.setAlphaComponent(dark, 238)
            }
            else -> {
                val top: Float
                val bottom: Float
                when (kind) {
                    LiquidPane.Kind.ASSISTANT -> { top = if (t.light) 0.64f else 0.14f; bottom = if (t.light) 0.34f else 0.055f }
                    LiquidPane.Kind.PILL -> { top = if (t.light) 0.56f else 0.13f; bottom = if (t.light) 0.32f else 0.05f }
                    else -> { top = if (t.light) 0.42f else 0.08f; bottom = if (t.light) 0.22f else 0.03f }
                }
                fill.shader = LinearGradient(0f, 0f, 0f, h, spec(top * k), spec(bottom * k), Shader.TileMode.CLAMP)
            }
        }

        // 2. Accent glow, from the corner that is furthest from the tail of the bubble
        val glowCx = if (kind == LiquidPane.Kind.USER) 0f else w
        val glowCy = if (kind == LiquidPane.Kind.USER) 0f else h
        val glowColor = if (kind == LiquidPane.Kind.USER) second(0.30f * k) else accent((if (t.light) 0.20f else 0.17f) * k)
        glow.shader = RadialGradient(glowCx, glowCy, max(w, h) * 0.85f, glowColor, ColorUtils.setAlphaComponent(glowColor, 0), Shader.TileMode.CLAMP)

        // 3. Curved specular highlight over the upper half
        val specTop = (if (t.light) 0.55f else 0.22f) * k
        specular.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, spec(specTop), clear, Shader.TileMode.CLAMP)

        // 4. Rim light: bright on the top-left and bottom-right, quiet in between
        rim.strokeWidth = rimWidth
        val rimBright = if (kind == LiquidPane.Kind.WINDOW) 0.34f else 0.92f
        rim.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(spec(rimBright * k), spec(0.10f * k), spec(0.08f * k), spec(rimBright * 0.58f * k)),
            floatArrayOf(0f, 0.34f, 0.66f, 1f), Shader.TileMode.CLAMP
        )
        edge.strokeWidth = rimWidth
        edge.color = ColorUtils.setAlphaComponent(t.ink, if (t.light) 34 else 0)

        // 5. Prism fringe
        val fringeOn = kind == LiquidPane.Kind.ASSISTANT || kind == LiquidPane.Kind.USER || kind == LiquidPane.Kind.PILL
        fringeA.strokeWidth = 1f * density
        fringeB.strokeWidth = 1f * density
        fringeA.color = accent(if (fringeOn) 0.24f * k else 0f)
        fringeB.color = second(if (fringeOn) 0.20f * k else 0f)

        // 6. Glint band (positioned per frame with a matrix)
        glintBand = max(w * 0.42f, 56f * density)
        val peak = ColorUtils.setAlphaComponent(if (t.light) t.accent else t.specular, if (t.light) 0x70 else 0x66)
        val none = ColorUtils.setAlphaComponent(peak, 0)
        glintShader = LinearGradient(0f, 0f, glintBand, 0f, intArrayOf(none, peak, none), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        glintPaint.shader = glintShader
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        canvas.drawPath(body, fill)
        if (kind != LiquidPane.Kind.WINDOW) {
            canvas.drawPath(body, glow)
            canvas.drawPath(body, specular)
        }
        drawGlint(canvas)
        if (edge.alpha > 0) canvas.drawPath(rimPath, edge)
        if (fringeA.alpha > 0) {
            val s = 0.7f * density
            canvas.save(); canvas.translate(-s, -s); canvas.drawPath(rimPath, fringeA); canvas.restore()
            canvas.save(); canvas.translate(s, s); canvas.drawPath(rimPath, fringeB); canvas.restore()
        }
        canvas.drawPath(rimPath, rim)
    }

    private fun drawGlint(canvas: Canvas) {
        val shader = glintShader ?: return
        if (glintProgress < 0f) return
        val p = if (glintLoop) glintProgress / 0.62f else glintProgress
        if (p <= 0f || p >= 1f) return
        val w = rect.width()
        val pos = LiquidMath.glintPosition(p)
        glintMatrix.setTranslate(-glintBand + (w + glintBand * 2f) * pos, 0f)
        glintMatrix.postRotate(-16f, rect.centerX(), rect.centerY())
        shader.setLocalMatrix(glintMatrix)
        glintPaint.alpha = (255 * LiquidMath.glintAlpha(p)).toInt().coerceIn(0, 255)
        canvas.drawPath(body, glintPaint)
    }

    /** Sends a band of light across the pane: once, or repeatedly with a pause between passes while something is being written. */
    fun playGlint(durationMs: Long, loop: Boolean) {
        val host = callback as? View ?: return
        if (!GlassMotion.animationsOn(host) || bounds.isEmpty) return
        stopGlint()
        glintLoop = loop
        glintProgress = 0f
        glintAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            if (loop) {
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
            }
            addUpdateListener {
                val v = callback as? View
                if (v == null || !v.isAttachedToWindow) { stopGlint(); return@addUpdateListener }
                glintProgress = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (!loop) { glintProgress = -1f; invalidateSelf() } }
            })
            start()
        }
    }

    /** True while the slow repeating band is running (a one-shot band must not interrupt it). */
    val looping: Boolean get() = glintAnim != null && glintLoop

    fun stopGlint() {
        glintAnim?.removeAllListeners()
        glintAnim?.cancel()
        glintAnim = null
        glintProgress = -1f
        invalidateSelf()
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radiusPx)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Builds ChatBox glass for any view, and falls back to the plain themed card when Liquid Glass is switched off in Settings. */
object LiquidPane {

    enum class Kind {
        /** Everything JARVIS says for a turn. */
        ASSISTANT,
        /** The person's own message: glass tinted with the theme accent. */
        USER,
        /** A quieter sheet that sits inside a panel: a file card, the step list, a button. */
        INSET,
        /** Capsules: suggestion chips, badges. */
        PILL,
        /** The dark code window. */
        WINDOW
    }

    class GlintHandle internal constructor(private val drawable: LiquidPaneDrawable) {
        fun stop() = drawable.stopGlint()
    }

    fun make(context: Context, kind: Kind, radiusDp: Float): Drawable {
        val d = context.resources.displayMetrics.density
        if (GlassMode.plain()) return GlassMode.plainCardBackground(context, radiusDp * d)
        return LiquidPaneDrawable(context, kind, radiusDp * d)
    }

    /** Gives [view] this glass unless it already has the same one, so a running glint is not reset by a re-bind. */
    fun ensure(view: View, kind: Kind, radiusDp: Float) {
        val d = view.resources.displayMetrics.density
        val current = view.background
        if (GlassMode.plain()) {
            if (current !is GradientDrawable) view.background = make(view.context, kind, radiusDp)
            return
        }
        if (current is LiquidPaneDrawable && current.kind == kind && current.radiusPx == radiusDp * d) return
        view.background = make(view.context, kind, radiusDp)
    }

    /** One band of light across [view]'s glass. */
    fun glint(view: View, delayMs: Long = 0L) {
        val play = Runnable {
            val d = view.background as? LiquidPaneDrawable
            if (view.isAttachedToWindow && d != null && !d.looping) d.playGlint(1100L, false)
        }
        if (delayMs <= 0L) play.run() else view.postDelayed(play, delayMs)
    }

    /** A slow, repeating band of light while something is being written. Null when the view has no liquid glass or animations are off. */
    fun glintLoop(view: View): GlintHandle? {
        val drawable = view.background as? LiquidPaneDrawable ?: return null
        drawable.playGlint(2400L, true)
        return GlintHandle(drawable)
    }
}

/** A vertical LinearLayout that is a liquid-glass panel: the shape every JARVIS answer lives in. */
class LiquidPaneLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    init {
        orientation = VERTICAL
        background = LiquidPane.make(context, LiquidPane.Kind.ASSISTANT, 26f)
        val d = resources.displayMetrics.density
        setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
    }
}
