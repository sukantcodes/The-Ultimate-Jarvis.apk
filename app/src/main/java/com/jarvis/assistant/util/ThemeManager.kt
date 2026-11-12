package com.jarvis.assistant.util

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R

object ThemeManager {

    fun isLight(colorInt: Int): Boolean {
        val r = Color.red(colorInt) / 255.0
        val g = Color.green(colorInt) / 255.0
        val b = Color.blue(colorInt) / 255.0
        val lum = 0.299 * r + 0.587 * g + 0.114 * b
        return lum > 0.55
    }

    // ---- Custom colours ----------------------------------------------------------------------

    private fun parseOr(hex: String?, fallback: Int): Int =
        try { Color.parseColor(hex?.trim().orEmpty()) } catch (_: Exception) { fallback }

    /** The two colours of the custom theme. A plain single-colour theme returns the same colour twice. */
    private fun customColors(): Pair<Int, Int> {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val first = parseOr(prefs?.customThemeColor, Color.parseColor("#2F6BFF"))
        val second = prefs?.customThemeColor2?.takeIf { it.isNotBlank() }?.let { parseOr(it, first) } ?: first
        return first to second
    }

    private fun hsl(color: Int): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }

    /** Dark or light UI for a custom theme, judged on the average of its colours. */
    private fun customIsLight(first: Int, second: Int): Boolean =
        isLight(ColorUtils.blendARGB(first, second, 0.5f))

    /**
     * The colour that drives buttons, icons and highlights. For a blend this is whichever of the two
     * colours stands out best, so a "black x blue" blend uses the blue rather than the near-black.
     */
    private fun accentSource(first: Int, second: Int, lightUi: Boolean): Int {
        fun score(c: Int): Float {
            val h = hsl(c)
            val target = if (lightUi) 0.42f else 0.62f
            return h[1] * (1f - kotlin.math.abs(h[2] - target))
        }
        return if (score(second) > score(first)) second else first
    }

    private fun customStyleRes(accent: Int, lightUi: Boolean): Int {
        val h = hsl(accent)
        // Greys and near-whites have no hue worth matching: use the neutral custom styles.
        if (h[1] < 0.10f) {
            return if (lightUi) R.style.Theme_JarvisAssistant_CustomLight else R.style.Theme_JarvisAssistant_CustomDark
        }
        val table = if (lightUi) CustomThemeStyles.LIGHT else CustomThemeStyles.DARK
        val index = (Math.round(h[0] / 15f)) % table.size
        return table[index]
    }

    /** Canvas colour under the glow: a very dark (or very pale) tint of the accent hue, never a flat patch. */
    private fun customBaseColor(accent: Int, lightUi: Boolean): Int {
        val h = hsl(accent)
        val sat = if (h[1] < 0.10f) 0f else 0.45f
        return ColorUtils.HSLToColor(floatArrayOf(h[0], sat, if (lightUi) 0.96f else 0.055f))
    }

    fun getThemeBgColor(themeKey: String, customHex: String?): Int {
        return when (themeKey) {
            "dark" -> Color.parseColor("#070914")
            "red" -> Color.parseColor("#0E0307")
            "sunset" -> Color.parseColor("#FFF0EA")
            "custom" -> {
                val (first, second) = customColors()
                val light = customIsLight(first, second)
                customBaseColor(accentSource(first, second, light), light)
            }
            else -> Color.parseColor("#EEF2F9")
        }
    }

    fun applyTheme(activity: Activity) {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val themeKey = prefs?.appTheme ?: "sunset"
        val isCustom = themeKey == "custom"

        val (themeRes, isLightStatusBar, bgColor) = when (themeKey) {
            "dark" -> Triple(R.style.Theme_JarvisAssistant_Dark, false, Color.parseColor("#070914"))
            "red" -> Triple(R.style.Theme_JarvisAssistant_Red, false, Color.parseColor("#0E0307"))
            "sunset" -> Triple(R.style.Theme_JarvisAssistant_Sunset, true, Color.parseColor("#FFF0EA"))
            "custom" -> {
                val (first, second) = customColors()
                val light = customIsLight(first, second)
                val accent = accentSource(first, second, light)
                Triple(customStyleRes(accent, light), light, customBaseColor(accent, light))
            }
            else -> Triple(R.style.Theme_JarvisAssistant_Light, true, Color.parseColor("#EEF2F9"))
        }

        activity.setTheme(themeRes)
        activity.window.setBackgroundDrawable(createThemeBackgroundDrawable(themeKey))
        // Keep the decor transparent so the window background stays continuous behind content.
        activity.window.decorView.background = ColorDrawable(Color.TRANSPARENT)

        if (isCustom) {
            // The glow is painted ONCE, on the window. Painting it again on the content view (which is a
            // different size) or putting solid bars over it is what made rectangle edges and corners show.
            activity.window.statusBarColor = Color.TRANSPARENT
            activity.window.navigationBarColor = Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                activity.window.isStatusBarContrastEnforced = false
                activity.window.isNavigationBarContrastEnforced = false
            }
        } else {
            activity.window.statusBarColor = bgColor
            activity.window.navigationBarColor = bgColor
        }

        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = isLightStatusBar
        controller.isAppearanceLightNavigationBars = isLightStatusBar

        // The window owns the continuous canvas. Keep the content host transparent so it
        // cannot paint a second, differently-sized copy over the same screen.
        val content = try { activity.findViewById<View>(android.R.id.content) } catch (_: Exception) { null }
        content?.background = ColorDrawable(Color.TRANSPARENT)
        activity.window.decorView.post {
            try {
                activity.findViewById<View>(android.R.id.content)?.background = ColorDrawable(Color.TRANSPARENT)
            } catch (_: Exception) {}
        }
        applyPreferredRefreshRate(activity)
    }

    /**
     * Refresh rates reported by the active physical display. The app can request only a rate the
     * display advertises; Android/ColorOS remains free to choose another rate for power or thermal
     * reasons.
     */
    @Suppress("DEPRECATION")
    fun supportedRefreshRates(activity: Activity): List<Int> = try {
        activity.windowManager.defaultDisplay.supportedModes
            .map { mode -> kotlin.math.round(mode.refreshRate).toInt() }
            .filter { it in 60..144 }
            .distinct()
            .sorted()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Apply the saved preference. A specific rate is requested as-is; "system" (the default) now means
     * "highest rate the display supports", so JARVIS runs at 120 Hz on 120 Hz panels. It no longer clears
     * the request, which used to let the system fall back to 60 Hz.
     */
    fun applyPreferredRefreshRate(activity: Activity) {
        val requested = try {
            JarvisApp.instance.preferences.uiRefreshRate.toIntOrNull()
        } catch (_: Exception) {
            null
        }
        DisplayRefreshRateController.apply(activity, requested)
    }

    /** Alpha derived from the user's persisted Liquid Glass strength (0..100%). */
    fun getLiquidGlassAlpha(): Int {
        val strength = try { JarvisApp.instance.preferences.liquidGlassStrength } catch (_: Exception) { 60 }
        return (strength.coerceIn(0, 100) * 255 / 100)
    }

    /**
     * What a WebView orb needs to match the app theme exactly: whether the UI is light, plus the two
     * theme accent colours as hex. (Guessing light/dark from a colour name made light themes such as
     * Sunset render with a dark-background palette and wash out.)
     */
    fun getOrbThemeSpec(themeKey: String? = null): Triple<Boolean, String, String> {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val key = themeKey ?: prefs?.appTheme ?: "sunset"
        val (first, second) = themeGlowColors(key)
        fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
        return Triple(isLight(getThemeBgColor(key, null)), hex(first), hex(second))
    }

    /** Accent used by runtime-tinted UI elements, including custom themes. */
    fun getThemeAccentColor(): Int {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        return when (prefs?.appTheme ?: "sunset") {
            "dark" -> Color.parseColor("#5B8CFF")
            "red" -> Color.parseColor("#FF4D5E")
            "sunset" -> Color.parseColor("#FF6A3D")
            "custom" -> {
                val (first, second) = customColors()
                val light = customIsLight(first, second)
                val chosen = accentSource(first, second, light)
                // Neutral custom swatches use the same readable fallback accent as their static theme
                // style, rather than tinting icons white-on-white or black-on-black.
                val h = hsl(chosen)
                if (h[1] < 0.10f) {
                    if (light) Color.parseColor("#2F6BFF") else Color.parseColor("#5B8CFF")
                } else {
                    h[2] = if (light) h[2].coerceAtMost(0.46f) else h[2].coerceAtLeast(0.58f)
                    ColorUtils.HSLToColor(h)
                }
            }
            else -> Color.parseColor("#2F6BFF")
        }
    }

    /** Secondary accent color used for dual-tone highlights, gradients, and tri-color progress bars. */
    fun getThemeSecondaryColor(): Int {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        return when (prefs?.appTheme ?: "sunset") {
            "dark" -> Color.parseColor("#A78BFA")
            "red" -> Color.parseColor("#FB7185")
            "sunset" -> Color.parseColor("#E879A8")
            "custom" -> {
                val (first, second) = customColors()
                if (first != second) second else {
                    val h = hsl(first)
                    h[0] = (h[0] + 35f) % 360f
                    ColorUtils.HSLToColor(h)
                }
            }
            else -> Color.parseColor("#8B5CF6")
        }
    }

    /** High-contrast success green harmonized with the active theme. */
    fun getThemeSuccessColor(): Int = Color.parseColor("#00E676")

    /** High-contrast warning amber harmonized with the active theme. */
    fun getThemeWarningColor(): Int = Color.parseColor("#FFD700")

    /** Whether the currently active app theme uses a light background canvas. */
    fun isCurrentThemeLight(): Boolean {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val key = prefs?.appTheme ?: "sunset"
        return isLight(getThemeBgColor(key, null))
    }

    /** The two colours the Home orb should use for the custom theme (same colour twice for a single colour). */
    fun getCustomOrbColors(): Pair<String, String> {
        val (first, second) = customColors()
        fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
        return hex(first) to hex(second)
    }

    /**
     * One shared, full-canvas ambient background for every built-in and custom palette.
     * A single renderer avoids small view-bound glow patches and duplicated gradients.
     */
    private fun themeGlowColors(themeKey: String): Pair<Int, Int> = when (themeKey) {
        "dark" -> Color.parseColor("#5B8CFF") to Color.parseColor("#A78BFA")
        "red" -> Color.parseColor("#FF4D5E") to Color.parseColor("#FB7185")
        "sunset" -> Color.parseColor("#FF6A3D") to Color.parseColor("#E879A8")
        "custom" -> customColors()
        else -> Color.parseColor("#2F6BFF") to Color.parseColor("#8B5CF6")
    }

    private fun createThemeBackgroundDrawable(themeKey: String): Drawable {
        val base = getThemeBgColor(themeKey, null)
        val (first, second) = themeGlowColors(themeKey)
        return AmbientGlowDrawable(base, first, second, isLight(base))
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /**
     * Paints the chosen colour(s) into the background as feathered glows. Both gradients fade to a
     * fully transparent copy of their own colour (not transparent black), so no grey rim or hard
     * edge can appear, and nothing here is a solid accent fill that could read as a patch.
     */
    private class AmbientGlowDrawable(
        private val baseColor: Int,
        private val firstColor: Int,
        private val secondColor: Int,
        private val lightCanvas: Boolean
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }

        override fun draw(canvas: Canvas) {
            val area = bounds
            if (area.isEmpty) return
            canvas.drawColor(baseColor)
            val width = area.width().toFloat()
            val height = area.height().toFloat()
            val big = maxOf(width, height)

            // Keep pearl palettes luminous but restrained; darker palettes retain richer colour.
            val strongA = if (lightCanvas) 0x22 else 0x48
            val softA = if (lightCanvas) 0x16 else 0x30
            drawGlow(canvas, area.left + width * 0.82f, area.top + height * 0.10f, big * 0.95f, firstColor, strongA)
            drawGlow(canvas, area.left + width * 0.10f, area.top + height * 0.88f, big * 0.85f, secondColor, strongA)
            drawGlow(canvas, area.left + width * 0.08f, area.top + height * 0.38f, big * 0.55f, secondColor, softA / 2)
            drawGlow(canvas, area.left + width * 0.92f, area.top + height * 0.66f, big * 0.55f, firstColor, softA / 2)
        }

        private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Int) {
            paint.shader = RadialGradient(
                cx, cy, radius.coerceAtLeast(1f),
                intArrayOf(
                    withAlpha(color, alpha),
                    withAlpha(color, (alpha * 0.55f).toInt()),
                    withAlpha(color, (alpha * 0.18f).toInt()),
                    withAlpha(color, 0)
                ),
                floatArrayOf(0f, 0.38f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(bounds, paint)
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    /**
     * Fragment roots share the activity's single screen-sized ambient canvas. Keeping these
     * roots transparent prevents each tab from restarting the gradient at a different height.
     */
    fun applyFragmentBackground(view: View) {
        view.background = ColorDrawable(Color.TRANSPARENT)
    }

    fun applyDynamicBackground(view: View) {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val themeKey = prefs?.appTheme ?: "sunset"
        // Use the same full-bounds renderer for every activity and fragment, including Updates.
        view.background = createThemeBackgroundDrawable(themeKey)
    }

    /**
     * Programmatically creates a card background with dynamic theme stroke and fill,
     * ensuring that changing the theme immediately updates all card containers.
     */
    fun createDynamicCardDrawable(
        strokeColor: Int,
        fillColor: Int = 0x1A101A2E,
        cornerRadiusDp: Float = 14f,
        strokeWidthDp: Float = 1.5f,
        context: Context
    ): Drawable {
        val density = context.resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp * density
            setColor(fillColor)
            setStroke((strokeWidthDp * density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    /**
     * Programmatically creates a dynamic gradient badge background bound to theme accents.
     */
    fun createDynamicBadgeDrawable(
        startColor: Int,
        endColor: Int,
        cornerRadiusDp: Float = 12f,
        context: Context
    ): Drawable {
        val density = context.resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(startColor, endColor)
        ).apply {
            cornerRadius = cornerRadiusDp * density
        }
    }

    /**
     * Tints an Android switch thumb and track dynamically using the active theme accent.
     */
    fun tintSwitch(switch: android.widget.CompoundButton, accentColor: Int = getThemeAccentColor()) {
        val trackStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val trackColors = intArrayOf(
            ColorUtils.setAlphaComponent(accentColor, 120),
            Color.parseColor("#424A60")
        )
        val trackColorList = android.content.res.ColorStateList(trackStates, trackColors)

        val thumbStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val thumbColors = intArrayOf(
            accentColor,
            Color.parseColor("#90A4AE")
        )
        val thumbColorList = android.content.res.ColorStateList(thumbStates, thumbColors)

        when (switch) {
            is androidx.appcompat.widget.SwitchCompat -> {
                switch.trackTintList = trackColorList
                switch.thumbTintList = thumbColorList
            }
            is android.widget.Switch -> {
                switch.trackTintList = trackColorList
                switch.thumbTintList = thumbColorList
            }
            else -> {
                androidx.core.widget.CompoundButtonCompat.setButtonTintList(switch, thumbColorList)
            }
        }
    }
}
