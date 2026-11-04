package com.jarvis.assistant.util

import android.app.Activity
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

        val content = try { activity.findViewById<View>(android.R.id.content) } catch (_: Exception) { null }
        if (isCustom) {
            content?.background = null
        } else {
            content?.background = createThemeBackgroundDrawable(themeKey)
            activity.window.decorView.post {
                try {
                    activity.findViewById<View>(android.R.id.content)?.background = createThemeBackgroundDrawable(themeKey)
                } catch (_: Exception) {}
            }
        }
    }

    /** Alpha derived from the user's persisted Liquid Glass strength (0..100%). */
    fun getLiquidGlassAlpha(): Int {
        val strength = try { JarvisApp.instance.preferences.liquidGlassStrength } catch (_: Exception) { 60 }
        return (strength.coerceIn(0, 100) * 255 / 100)
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
                // Keep the accent readable on the canvas: darker on pale canvases, lighter on dark ones.
                val h = hsl(chosen)
                h[2] = if (light) h[2].coerceAtMost(0.46f) else h[2].coerceAtLeast(0.58f)
                if (h[1] < 0.10f) chosen else ColorUtils.HSLToColor(h)
            }
            else -> Color.parseColor("#2F6BFF")
        }
    }

    /** The two colours the Home orb should use for the custom theme (same colour twice for a single colour). */
    fun getCustomOrbColors(): Pair<String, String> {
        val (first, second) = customColors()
        fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
        return hex(first) to hex(second)
    }

    private fun createThemeBackgroundDrawable(themeKey: String): Drawable {
        if (themeKey != "custom") return ColorDrawable(getThemeBgColor(themeKey, null))
        val (first, second) = customColors()
        val light = customIsLight(first, second)
        val base = customBaseColor(accentSource(first, second, light), light)
        return AmbientGlowDrawable(base, first, second, light)
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

            val strongA = if (lightCanvas) 0x40 else 0x66
            val softA = if (lightCanvas) 0x2A else 0x44
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

    fun applyDynamicBackground(view: View) {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val themeKey = prefs?.appTheme ?: "sunset"
        // Custom-theme glow is drawn once, on the window. Any second glow or tinted fill drawn on a
        // smaller view would start and stop at that view's edges and show up as a coloured rectangle.
        view.background = if (themeKey == "custom") {
            ColorDrawable(Color.TRANSPARENT)
        } else {
            ColorDrawable(getThemeBgColor(themeKey, null))
        }
    }
}
