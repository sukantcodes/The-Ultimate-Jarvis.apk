package com.jarvis.assistant.util

import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R

/**
 * The app's typefaces for UI that is built in code. XML uses the same fonts through @font/jarvis_sans and
 * @font/jarvis_serif.
 *
 * Interface text: Inter (SIL OFL). Assistant replies and markdown headings: Newsreader (SIL OFL), an editorial text serif.
 * Screen titles and numbers: Space Grotesk (display). Status strip, clock and small caps labels: Orbitron (hud).
 * Terminal and code: JetBrains Mono (mono). Drawer, buttons and chips: Sora (round). All SIL OFL.
 * A face that fails to load falls back to the system font and is retried next time, never cached as a failure.
 */
object JarvisFonts {
    private val cache = HashMap<Int, Typeface>()

    private fun load(@FontRes id: Int, fallback: Typeface): Typeface {
        cache[id]?.let { return it }
        val face = try {
            ResourcesCompat.getFont(JarvisApp.instance, id)
        } catch (_: Throwable) {
            null
        } ?: return fallback
        synchronized(cache) { cache[id] = face }
        return face
    }

    val regular: Typeface get() = load(R.font.jarvis_sans_regular, Typeface.SANS_SERIF)
    val medium: Typeface get() = load(R.font.jarvis_sans_medium, Typeface.SANS_SERIF)
    val semibold: Typeface get() = load(R.font.jarvis_sans_semibold, Typeface.DEFAULT_BOLD)
    val bold: Typeface get() = load(R.font.jarvis_sans_bold, Typeface.DEFAULT_BOLD)

    val displayRegular: Typeface get() = load(R.font.jarvis_display_regular, Typeface.SANS_SERIF)
    val displayMedium: Typeface get() = load(R.font.jarvis_display_medium, Typeface.SANS_SERIF)
    val displaySemibold: Typeface get() = load(R.font.jarvis_display_semibold, Typeface.DEFAULT_BOLD)
    val displayBold: Typeface get() = load(R.font.jarvis_display_bold, Typeface.DEFAULT_BOLD)

    val hudMedium: Typeface get() = load(R.font.jarvis_hud_medium, Typeface.SANS_SERIF)
    val hudBold: Typeface get() = load(R.font.jarvis_hud_bold, Typeface.DEFAULT_BOLD)
    val hudBlack: Typeface get() = load(R.font.jarvis_hud_black, Typeface.DEFAULT_BOLD)

    val mono: Typeface get() = load(R.font.jarvis_mono_regular, Typeface.MONOSPACE)
    val monoMedium: Typeface get() = load(R.font.jarvis_mono_medium, Typeface.MONOSPACE)
    val monoBold: Typeface get() = load(R.font.jarvis_mono_bold, Typeface.MONOSPACE)

    val roundRegular: Typeface get() = load(R.font.jarvis_round_regular, Typeface.SANS_SERIF)
    val roundMedium: Typeface get() = load(R.font.jarvis_round_medium, Typeface.SANS_SERIF)
    val roundSemibold: Typeface get() = load(R.font.jarvis_round_semibold, Typeface.DEFAULT_BOLD)
    val roundBold: Typeface get() = load(R.font.jarvis_round_bold, Typeface.DEFAULT_BOLD)

    val serifRegular: Typeface get() = load(R.font.jarvis_serif_regular, Typeface.SERIF)
    val serifMedium: Typeface get() = load(R.font.jarvis_serif_medium, Typeface.SERIF)
    val serifSemibold: Typeface get() = load(R.font.jarvis_serif_semibold, Typeface.SERIF)
}

/** Applies one typeface to a span of text (TypefaceSpan(Typeface) needs API 28; the app supports 26). */
class JarvisTypefaceSpan(private val face: Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(ds: android.text.TextPaint) { ds.typeface = face }
    override fun updateMeasureState(paint: android.text.TextPaint) { paint.typeface = face }
}
