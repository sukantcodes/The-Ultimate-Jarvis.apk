package com.jarvis.assistant.ui.chat

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.abs

/**
 * Each answer gets its own look: a colour pair (drawn from the app theme and rotated around the colour wheel by the
 * answer's id) and one of three heading font families, so answers are easy to tell apart and never look the same.
 * The colours are kept readable: lighter on dark themes, deeper on light ones.
 */
object AnswerPalette {

    class Palette(val accent: Int, val accent2: Int, val emphasis: Int, val variant: Int)

    fun forKey(key: String): Palette {
        val base = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { 0xFF00E5FF.toInt() }
        val second = try { ThemeManager.getThemeSecondaryColor() } catch (_: Throwable) { 0xFFA78BFA.toInt() }
        val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        val h = abs(key.hashCode())
        val shift = (h % 6) * 46f            // six distinct colour families, always from the theme's own hues
        val variant = (h / 6) % 3
        val a = tune(base, shift, light)
        val b = tune(second, shift * 0.5f + 40f, light)
        val emphasis = tune(second, shift + 150f, light, boost = true)
        return Palette(a, b, emphasis, variant)
    }

    fun themed(accent: Int): Palette {
        val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        val second = try { ThemeManager.getThemeSecondaryColor() } catch (_: Throwable) { accent }
        return Palette(accent, tune(second, 0f, light), tune(second, 150f, light, boost = true), 0)
    }

    private fun tune(color: Int, shift: Float, light: Boolean, boost: Boolean = false): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[0] = (hsl[0] + shift) % 360f
        hsl[1] = hsl[1].coerceIn(if (boost) 0.6f else 0.5f, 0.95f)
        hsl[2] = if (light) 0.34f else if (boost) 0.78f else 0.66f
        return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), 255).let { if (Color.alpha(it) == 0) color else it }
    }
}
