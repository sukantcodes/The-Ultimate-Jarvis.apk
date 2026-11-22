package com.jarvis.assistant.ui.udn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.glass.GlassMode
import com.jarvis.assistant.util.ThemeManager

/**
 * Design tokens and small view factories for the Device Network screen. Every colour comes from the active app theme's
 * own attributes (so it follows Dark, Light, Sunset, Red, Crystal and Custom exactly), and nothing here animates: glass is
 * drawn once as static gradients, so it can never flicker.
 */
class UdnTheme(val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Float) = v * density

    private fun attr(a: Int, fallback: Int) = GlassMode.attrColor(ctx, a, fallback)

    val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
    /** The theme's real base colour. The themeBg attribute is see-through on Crystal Mirror and custom themes, which painted this screen black. */
    val bg: Int = run {
        val fromAttr = attr(R.attr.themeBg, 0)
        if (Color.alpha(fromAttr) >= 0xF0) fromAttr
        else try { ThemeManager.getThemeBgColor(currentThemeKey(), null) } catch (_: Throwable) { Color.parseColor("#0A1220") }
    }
    val text1 = attr(R.attr.themeTextPrimary, if (light) Color.parseColor("#0F172A") else Color.WHITE)
    val text2 = attr(R.attr.themeTextSecondary, if (light) Color.parseColor("#475569") else Color.parseColor("#A9B4CC"))
    val muted = attr(R.attr.themeTextMuted, Color.parseColor("#64748B"))
    val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { attr(R.attr.themeAccent, Color.parseColor("#5B8CFF")) }
    val accent2 = try { ThemeManager.getThemeSecondaryColor() } catch (_: Throwable) { accent }
    val success = attr(R.attr.themeSuccess, Color.parseColor("#4ADE80"))
    val warning = attr(R.attr.themeWarning, Color.parseColor("#FBBF24"))
    val error = attr(R.attr.themeError, Color.parseColor("#FF6B6B"))
    val glassStart = attr(R.attr.themeCardGlassStart, if (light) Color.parseColor("#F0FFFFFF") else Color.parseColor("#2EFFFFFF"))
    val glassEnd = attr(R.attr.themeCardGlassEnd, if (light) Color.parseColor("#CCFFFFFF") else Color.parseColor("#0FFFFFFF"))
    val specular = attr(R.attr.themeGlassSpecular, Color.WHITE)
    val border = attr(R.attr.themeCardBorder, if (light) Color.parseColor("#1F0F172A") else Color.parseColor("#40FFFFFF"))
    val onAccent: Int get() = if (ThemeManager.isLight(accent)) Color.parseColor("#0B1020") else Color.WHITE

    fun alpha(c: Int, a: Int) = ColorUtils.setAlphaComponent(c, a)

    /** A stable, colourful tone per item (device, circle), derived from the active theme's accent by rotating its hue. */
    fun hue(seed: String): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        hsv[0] = (hsv[0] + (Math.abs(seed.hashCode()) % 7) * 51f) % 360f
        hsv[1] = hsv[1].coerceIn(0.55f, 0.9f)
        hsv[2] = hsv[2].coerceIn(0.75f, if (light) 0.85f else 1f)
        return Color.HSVToColor(hsv)
    }

    /** Subtle press feedback (scale) for tappable views. Returns false from the touch listener so clicks still fire. */
    fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    /** Static frosted glass: themed gradient, themed hairline border and a faint specular highlight along the top edge. */
    fun glass(radiusDp: Int = 22, tint: Int? = null): Drawable {
        val r = dpf(radiusDp.toFloat())
        val layers = ArrayList<Drawable>()
        layers += GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(glassStart, glassEnd)).apply {
            cornerRadius = r; setStroke(dp(1), border)
        }
        if (tint != null) layers += GradientDrawable().apply { cornerRadius = r; setColor(alpha(tint, 0x22)) }
        layers += GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, alpha(specular, if (light) 0xB0 else 0x70), Color.TRANSPARENT))
        val ld = LayerDrawable(layers.toTypedArray())
        val i = layers.size - 1
        ld.setLayerInset(i, dp(radiusDp), dp(1), dp(radiusDp), 0)
        ld.setLayerHeight(i, dp(1))
        ld.setLayerGravity(i, Gravity.TOP)
        return ld
    }

    fun pill(fill: Int, strokeColor: Int? = null, radiusDp: Int = 100): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpf(radiusDp.toFloat()); setColor(fill)
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    fun text(s: CharSequence, sizeSp: Float = 14f, color: Int = text1, weight: Weight = Weight.Regular): TextView = TextView(ctx).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp); setTextColor(color)
        typeface = when (weight) {
            Weight.Regular -> Typeface.DEFAULT
            Weight.Medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            Weight.Bold -> Typeface.create("sans-serif", Typeface.BOLD)
        }
        includeFontPadding = false
    }

    enum class Weight { Regular, Medium, Bold }

    fun icon(res: Int, sizeDp: Int, tint: Int): ImageView = ImageView(ctx).apply {
        setImageResource(res); setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** A rounded square holding an icon, softly tinted: the visual anchor of a row. */
    fun iconTile(res: Int, tint: Int, sizeDp: Int = 44, iconDp: Int = 22): FrameLayout = FrameLayout(ctx).apply {
        background = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(alpha(tint, 0x26)); setStroke(dp(1), alpha(tint, 0x40)) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        addView(ImageView(ctx).apply { setImageResource(res); setColorFilter(tint) },
            FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
    }

    fun chip(label: String, color: Int, filled: Boolean = false, dot: Boolean = false): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(if (dot) 8 else 10), dp(4), dp(10), dp(4))
        background = if (filled) pill(color) else pill(alpha(color, 0x1F), alpha(color, 0x55))
        if (dot) addView(View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }
            .also { it.layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(6) } })
        addView(text(label, 11f, if (filled) onAccent else color, Weight.Medium))
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom); marginStart = dp(start); marginEnd = dp(end) }

    fun hRule(): View = View(ctx).apply { setBackgroundColor(alpha(text2, 0x26)); layoutParams = lp(h = 1, top = 10, bottom = 10) }
}

/**
 * A static themed backdrop for the window: the theme's base colour plus two soft glows from its accents, painted once.
 * It replaces the animated window canvas on this screen only, so the glass behind it never shimmers in and out.
 */
class StaticBackdrop(private val base: Int, private val glow1: Int, private val glow2: Int, private val light: Boolean) : Drawable() {
    private val a = if (light) 0x55 else 0x6A
    private val g1 = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; setGradientCenter(0.08f, 0.02f); colors = intArrayOf(ColorUtils.setAlphaComponent(glow1, a), Color.TRANSPARENT) }
    private val g2 = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; setGradientCenter(0.95f, 0.92f); colors = intArrayOf(ColorUtils.setAlphaComponent(glow2, a), Color.TRANSPARENT) }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val r = maxOf(bounds.width(), bounds.height()) * 0.75f
        g1.setBounds(bounds); g2.setBounds(bounds)
        g1.gradientRadius = r; g2.gradientRadius = r
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(base)
        g1.draw(canvas); g2.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        fun forCurrentTheme(t: UdnTheme): StaticBackdrop = StaticBackdrop(t.bg, t.accent, t.accent2, t.light)
    }
}

internal fun currentThemeKey(): String = try { JarvisApp.instance.preferences.appTheme } catch (_: Throwable) { "sunset" }
