package com.jarvis.assistant.ui.jarvishome

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.home.DeviceCategory

/**
 * Colour and type for JARVIS Home. Every device category and every page has its own hue, so colour carries meaning
 * (amber lights, cyan plugs, violet TVs…) instead of one accent everywhere. Fonts are the app's own Orbitron (display)
 * and Rajdhani (text), loaded once.
 */
object HomeTheme {

    /** A two-stop gradient for one hue. [a] is the bright start, [b] the deeper end. */
    data class Hue(val a: Int, val b: Int) {
        val mid: Int get() = androidx.core.graphics.ColorUtils.blendARGB(a, b, 0.5f)
    }

    private fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v))
    fun hue(h: Float) = Hue(hsv(h, 0.55f, 1.0f), hsv(h + 32f, 0.85f, 0.92f))

    val CYAN = hue(190f)
    val BLUE = hue(215f)
    val VIOLET = hue(268f)
    val MAGENTA = hue(322f)
    val AMBER = hue(40f)
    val ORANGE = hue(18f)
    val MINT = hue(158f)
    val LIME = hue(92f)
    val INDIGO = hue(238f)
    val CORAL = hue(6f)

    fun forCategory(c: DeviceCategory): Hue = when (c) {
        DeviceCategory.LIGHT -> AMBER
        DeviceCategory.PLUG -> CYAN
        DeviceCategory.FAN -> MINT
        DeviceCategory.AC -> BLUE
        DeviceCategory.TV -> VIOLET
        DeviceCategory.SPEAKER -> MAGENTA
        DeviceCategory.SENSOR -> LIME
        DeviceCategory.MOTOR -> ORANGE
        DeviceCategory.CAMERA -> INDIGO
        DeviceCategory.OTHER -> BLUE
    }

    fun glyph(c: DeviceCategory): String = when (c) {
        DeviceCategory.LIGHT -> "💡"
        DeviceCategory.PLUG -> "🔌"
        DeviceCategory.FAN -> "🌀"
        DeviceCategory.AC -> "❄️"
        DeviceCategory.TV -> "📺"
        DeviceCategory.SPEAKER -> "🔊"
        DeviceCategory.SENSOR -> "📡"
        DeviceCategory.MOTOR -> "⚙️"
        DeviceCategory.CAMERA -> "📷"
        DeviceCategory.OTHER -> "◈"
    }

    /** The colour each page wears: tab, section titles and filled buttons all follow it. */
    fun forPage(p: HomePage): Hue = when (p) {
        HomePage.OVERVIEW -> CYAN
        HomePage.FAVORITES -> AMBER
        HomePage.ALL_DEVICES -> BLUE
        HomePage.ROOMS -> MINT
        HomePage.BLUETOOTH -> INDIGO
        HomePage.WIFI -> CYAN
        HomePage.DISCOVERY -> VIOLET
        HomePage.AUTOMATIONS -> ORANGE
        HomePage.SCENES -> MAGENTA
        HomePage.VOICE -> hue(280f)
        HomePage.ACTIVITY -> hue(172f)
        HomePage.SETTINGS -> hue(225f)
    }

    private var display: Typeface? = null
    private var body: Typeface? = null
    private var soft: Typeface? = null

    private fun load(context: Context, res: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(context.applicationContext, res) }.getOrNull() ?: fallback

    /** Orbitron: wide, techy, heavy. For titles and big numbers. */
    fun display(context: Context): Typeface = display ?: load(context, R.font.jarvis_serif_semibold, com.jarvis.assistant.util.JarvisFonts.bold).also { display = it }

    /** Rajdhani SemiBold: the main reading face; made thicker with a bold style where the screen needs weight. */
    fun body(context: Context): Typeface = body ?: load(context, R.font.jarvis_sans_semibold, com.jarvis.assistant.util.JarvisFonts.medium).also { body = it }

    /** Rajdhani Medium: quiet captions. */
    fun soft(context: Context): Typeface = soft ?: load(context, R.font.jarvis_sans_medium, com.jarvis.assistant.util.JarvisFonts.regular).also { soft = it }
}
