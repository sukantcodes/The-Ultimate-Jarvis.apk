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

    fun getThemeBgColor(themeKey: String, customHex: String?): Int {
        return when (themeKey) {
            "dark" -> Color.parseColor("#070914")
            "red" -> Color.parseColor("#0E0307")
            "sunset" -> Color.parseColor("#FFF0EA")
            "custom" -> {
                val accent = try { Color.parseColor(customHex ?: "#FFFFFF") } catch (_: Exception) { Color.WHITE }
                // Keep the canvas neutral; the selected colour is rendered as a soft ambient glow,
                // rather than a flat, hard-edged colour patch.
                if (isLight(accent)) Color.parseColor("#F7F8FC") else Color.parseColor("#070914")
            }
            else -> Color.parseColor("#EEF2F9")
        }
    }

    fun applyTheme(activity: Activity) {
        val prefs = try { JarvisApp.instance.preferences } catch (_: Exception) { null }
        val themeKey = prefs?.appTheme ?: "sunset"

        val (themeRes, isLightStatusBar, bgColor) = when (themeKey) {
            "dark" -> Triple(R.style.Theme_JarvisAssistant_Dark, false, Color.parseColor("#070914"))
            "red" -> Triple(R.style.Theme_JarvisAssistant_Red, false, Color.parseColor("#0E0307"))
            "sunset" -> Triple(R.style.Theme_JarvisAssistant_Sunset, true, Color.parseColor("#FFF0EA"))
            "custom" -> {
                val customHex = prefs?.customThemeColor ?: "#FFFFFF"
                val parsedColor = try { Color.parseColor(customHex) } catch (_: Exception) { Color.WHITE }
                val light = isLight(parsedColor)
                val res = if (light) R.style.Theme_JarvisAssistant_CustomLight else R.style.Theme_JarvisAssistant_CustomDark
                Triple(res, light, getThemeBgColor("custom", customHex))
            }
            else -> Triple(R.style.Theme_JarvisAssistant_Light, true, Color.parseColor("#EEF2F9"))
        }

        activity.setTheme(themeRes)
        fun background() = createThemeBackgroundDrawable(themeKey, prefs?.customThemeColor ?: "#FFFFFF")
        activity.window.setBackgroundDrawable(background())
        activity.window.decorView.background = background()
        activity.window.statusBarColor = bgColor
        activity.window.navigationBarColor = bgColor

        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = isLightStatusBar
        controller.isAppearanceLightNavigationBars = isLightStatusBar

        try {
            activity.findViewById<View>(android.R.id.content)?.background = background()
        } catch (_: Exception) {}
        activity.window.decorView.post {
            try {
                activity.findViewById<View>(android.R.id.content)?.background = background()
            } catch (_: Exception) {}
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
        val customHex = prefs?.customThemeColor ?: "#FFFFFF"
        return when (prefs?.appTheme ?: "sunset") {
            "dark" -> Color.parseColor("#5B8CFF")
            "red" -> Color.parseColor("#FF4D5E")
            "sunset" -> Color.parseColor("#FF6A3D")
            "custom" -> {
                val chosen = try { Color.parseColor(customHex) } catch (_: Exception) { Color.parseColor("#2F6BFF") }
                // Keep pale custom colours readable on the pale custom-theme canvas.
                if (isLight(chosen)) Color.rgb(
                    (Color.red(chosen) * 0.62f).toInt(),
                    (Color.green(chosen) * 0.62f).toInt(),
                    (Color.blue(chosen) * 0.62f).toInt()
                ) else chosen
            }
            else -> Color.parseColor("#2F6BFF")
        }
    }

    private fun createThemeBackgroundDrawable(themeKey: String, customHex: String): Drawable {
        if (themeKey != "custom") return ColorDrawable(getThemeBgColor(themeKey, customHex))
        val accent = try { Color.parseColor(customHex) } catch (_: Exception) { Color.parseColor("#2F6BFF") }
        val base = getThemeBgColor(themeKey, customHex)
        return AmbientGlowDrawable(base, accent)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /**
     * Paints the chosen custom colour into the background as a feathered ambient glow.
     * It intentionally avoids solid accent fills that appear as rectangular patches.
     */
    private class AmbientGlowDrawable(
        private val baseColor: Int,
        private val accentColor: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val area = bounds
            if (area.isEmpty) return
            canvas.drawColor(baseColor)
            val width = area.width().toFloat()
            val height = area.height().toFloat()
            val lightCanvas = ThemeManager.isLight(baseColor)
            val primaryAlpha = if (lightCanvas) 0x26 else 0x62
            val primaryRadius = maxOf(width, height) * 0.92f
            paint.shader = RadialGradient(
                area.left + width * 0.68f,
                area.top + height * 0.08f,
                primaryRadius.coerceAtLeast(1f),
                intArrayOf(withAlpha(accentColor, primaryAlpha), withAlpha(accentColor, 0)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(area, paint)

            paint.shader = RadialGradient(
                area.left + width * 0.12f,
                area.top + height * 0.76f,
                (maxOf(width, height) * 0.72f).coerceAtLeast(1f),
                intArrayOf(withAlpha(accentColor, if (lightCanvas) 0x10 else 0x24), withAlpha(accentColor, 0)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(area, paint)
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
        val customHex = prefs?.customThemeColor ?: "#FFFFFF"
        view.background = createThemeBackgroundDrawable(themeKey, customHex)
    }
}
