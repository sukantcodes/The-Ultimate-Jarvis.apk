package com.jarvis.assistant.ui.studyboard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.glass.CrystalTheme
import com.jarvis.assistant.ui.glass.GlassBackdropView
import com.jarvis.assistant.ui.glass.GlassMode
import com.jarvis.assistant.ui.glass.LiquidGlass
import com.jarvis.assistant.ui.glass.ViewSprings
import com.jarvis.assistant.util.JarvisFonts
import com.jarvis.assistant.util.ThemeManager

/**
 * Shared dynamic theme palette, glass styling, touch feedback, and edge-to-edge backdrop wiring for Study Board UI.
 * All colours resolve dynamically from [ThemeManager] and active theme attributes with zero static hex codes.
 */
internal object StudyTheme {

    data class Palette(
        val light: Boolean,
        val crystal: Boolean,
        val accent: Int,
        val secondary: Int,
        val success: Int,
        val warning: Int,
        val error: Int,
        val errorContainer: Int,
        val baseBg: Int,
        val surface: Int,
        val cardBg: Int,
        val cardBorder: Int,
        val chipBg: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val onAccent: Int,
        val solidPanel: Int,
        val floatPanelBg: Int,
        val floatPanelBorder: Int,
        val floatPanelInk: Int
    )

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    fun alpha(color: Int, a: Float): Int = ColorUtils.setAlphaComponent(color, (a * 255f).toInt().coerceIn(0, 255))

    fun attrColor(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!ctx.theme.resolveAttribute(attr, tv, true)) return fallback
        return when {
            tv.resourceId != 0 -> runCatching { ctx.getColor(tv.resourceId) }.getOrDefault(fallback)
            tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> tv.data
            else -> fallback
        }
    }

    fun onColor(bg: Int, primary: Int, baseBg: Int): Int {
        val opaqueBg = if (Color.alpha(bg) < 255) ColorUtils.compositeColors(bg, baseBg) else bg
        val cPrimary = ColorUtils.calculateContrast(primary, opaqueBg)
        val cBase = ColorUtils.calculateContrast(baseBg, opaqueBg)
        return when {
            cPrimary >= 4.5 -> primary
            cBase >= 4.5 -> baseBg
            else -> if (ColorUtils.calculateLuminance(opaqueBg) > 0.45) Color.BLACK else Color.WHITE
        }
    }

    fun palette(ctx: Context): Palette {
        val prefs = runCatching { JarvisApp.instance.preferences }.getOrNull()
        val light = runCatching { ThemeManager.isCurrentThemeLight() }.getOrDefault(false)
        val crystal = runCatching { CrystalTheme.active() }.getOrDefault(false)
        val accent = runCatching { ThemeManager.getThemeAccentColor() }.getOrDefault(attrColor(ctx, R.attr.themeAccent, Color.BLUE))
        val secondary = runCatching { ThemeManager.getThemeSecondaryColor() }.getOrDefault(accent)
        val success = runCatching { ThemeManager.getThemeSuccessColor() }.getOrDefault(attrColor(ctx, R.attr.themeSuccess, accent))
        val warning = runCatching { ThemeManager.getThemeWarningColor() }.getOrDefault(attrColor(ctx, R.attr.themeWarning, accent))
        val baseBg = runCatching {
            ThemeManager.getThemeBgColor(prefs?.appTheme ?: "sunset", prefs?.customThemeColor)
        }.getOrDefault(if (light) Color.WHITE else Color.BLACK)
        val cardBg = attrColor(ctx, R.attr.themeCardBg, alpha(baseBg, 0.85f))
        val surface = attrColor(ctx, R.attr.themeSurface, cardBg)
        val cardBorder = attrColor(ctx, R.attr.themeCardBorder, alpha(accent, 0.35f))
        val chipBg = attrColor(ctx, R.attr.themeChipBg, alpha(cardBg, 0.7f))
        val textPrimary = attrColor(ctx, R.attr.themeTextPrimary, if (light) Color.BLACK else Color.WHITE)
        val textSecondary = attrColor(ctx, R.attr.themeTextSecondary, alpha(textPrimary, 0.78f))
        val textMuted = attrColor(ctx, R.attr.themeTextMuted, alpha(textPrimary, 0.58f))
        val error = attrColor(ctx, R.attr.themeError, attrColor(ctx, android.R.attr.colorError, accent))
        val errorContainer = attrColor(ctx, R.attr.themeErrorContainer, alpha(error, 0.16f))
        val onAccent = onColor(accent, textPrimary, baseBg)
        val solidPanel = ColorUtils.compositeColors(alpha(cardBg, 0.86f), baseBg)
        val floatPanelBg = alpha(solidPanel, 0.93f)
        val floatPanelBorder = if (light) cardBorder else alpha(accent, 0.42f)
        val floatPanelInk = onColor(solidPanel, textPrimary, baseBg)
        return Palette(
            light = light, crystal = crystal, accent = accent, secondary = secondary,
            success = success, warning = warning, error = error, errorContainer = errorContainer,
            baseBg = baseBg, surface = surface, cardBg = cardBg, cardBorder = cardBorder, chipBg = chipBg,
            textPrimary = textPrimary, textSecondary = textSecondary, textMuted = textMuted,
            onAccent = onAccent, solidPanel = solidPanel, floatPanelBg = floatPanelBg,
            floatPanelBorder = floatPanelBorder, floatPanelInk = floatPanelInk
        )
    }

    /** Wraps [content] with an edge-to-edge [GlassBackdropView] and applies system-bar + IME insets to [content]. */
    fun wrapWithBackdrop(activity: Activity, content: View, extraPadDp: Int = 0): FrameLayout {
        val wrapper = FrameLayout(activity)
        val backdrop = GlassBackdropView(activity).apply {
            id = R.id.glassBackdrop
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        wrapper.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wrapper.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val extra = dp(activity, extraPadDp)
        ViewCompat.setOnApplyWindowInsetsListener(wrapper) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            content.setPadding(
                bars.left + extra,
                bars.top + extra,
                bars.right + extra,
                maxOf(bars.bottom, ime.bottom) + extra
            )
            insets
        }
        ViewCompat.requestApplyInsets(wrapper)
        return wrapper
    }

    fun applyGlass(view: View, radiusDp: Float, pal: Palette, soft: Boolean = false) {
        if (!GlassMode.plain()) {
            LiquidGlass.apply(view, radiusDp, pal.accent, soft = soft, elevationDp = if (soft) 1f else 4f)
        } else {
            view.background = ThemeManager.createDynamicCardDrawable(
                strokeColor = pal.cardBorder,
                fillColor = alpha(pal.solidPanel, 0.90f),
                cornerRadiusDp = radiusDp,
                strokeWidthDp = 1f,
                context = view.context
            )
        }
    }

    fun pillDrawable(ctx: Context, radiusDp: Float, fill: Int, stroke: Int, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dpf(ctx, radiusDp)
            setColor(fill)
            setStroke(dp(ctx, 1).coerceAtLeast((dpf(ctx, strokeDp) + 0.5f).toInt()), stroke)
        }

    fun gradientPillDrawable(ctx: Context, radiusDp: Float, startColor: Int, endColor: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(startColor, endColor)).apply {
            cornerRadius = dpf(ctx, radiusDp)
            if (stroke != null) setStroke(dp(ctx, 1), stroke)
        }

    @SuppressLint("ClickableViewAccessibility")
    fun applyPressSpring(view: View, pressedScale: Float = 0.93f) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(pressedScale).scaleY(pressedScale).setDuration(75L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ViewSprings.pop(v, v.scaleX, 1f)
            }
            false
        }
    }

    fun iconButton(
        ctx: Context,
        res: Int,
        desc: String,
        pal: Palette,
        sizeDp: Int = 44,
        padDp: Int = 10,
        tint: Int = pal.textPrimary,
        onClick: () -> Unit
    ): ImageButton = ImageButton(ctx).apply {
        val sz = dp(ctx, sizeDp.coerceAtLeast(44))
        val p = dp(ctx, padDp)
        setImageResource(res)
        setColorFilter(tint)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(sz, sz)
        minimumWidth = sz
        minimumHeight = sz
        setPadding(p, p, p, p)
        scaleType = ImageView.ScaleType.FIT_CENTER
        isFocusable = true
        applyPressSpring(this, 0.90f)
        setOnClickListener { onClick() }
    }

    fun label(
        ctx: Context,
        text: String,
        sp: Float,
        color: Int,
        bold: Boolean = false,
        hud: Boolean = false
    ): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        includeFontPadding = false
        typeface = when {
            hud -> JarvisFonts.hudBold
            bold -> JarvisFonts.semibold
            else -> JarvisFonts.regular
        }
    }

    fun chip(
        ctx: Context,
        text: String,
        pal: Palette,
        minHeightDp: Int = 44,
        onClick: () -> Unit
    ): TextView = label(ctx, text, 12.5f, pal.textPrimary, bold = true).apply {
        minHeight = dp(ctx, minHeightDp.coerceAtLeast(44))
        setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        background = pillDrawable(ctx, 18f, alpha(pal.cardBg, 0.65f), pal.cardBorder)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        applyPressSpring(this, 0.95f)
        setOnClickListener { onClick() }
    }
}
