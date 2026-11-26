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
 * One app font for everything (Inter by default; Space Grotesk, Sora, Newsreader or JetBrains Mono when picked in Settings,
 * see [AppFont]). Status strip and clock: Orbitron (hud). Terminal and code: JetBrains Mono (mono). All SIL OFL.
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

    /**
     * The picked app font (see [AppFont]) at one weight: 0 regular, 1 medium, 2 semibold, 3 bold. Every text face in the
     * app (UI, titles, buttons, chat replies, headings) comes from here, so one choice in Settings changes all of it.
     */
    fun appFace(weight: Int): Typeface {
        val f = AppFont.current
        return when (weight) {
            0 -> load(f.regular, Typeface.SANS_SERIF)
            1 -> load(f.medium, Typeface.SANS_SERIF)
            2 -> load(f.semibold, Typeface.DEFAULT_BOLD)
            else -> load(f.bold, Typeface.DEFAULT_BOLD)
        }
    }

    val regular: Typeface get() = appFace(0)
    val medium: Typeface get() = appFace(1)
    val semibold: Typeface get() = appFace(2)
    val bold: Typeface get() = appFace(3)

    // Titles, drawer, buttons, chips and assistant replies used to have their own families; they now follow the picked font.
    val displayRegular: Typeface get() = appFace(0)
    val displayMedium: Typeface get() = appFace(1)
    val displaySemibold: Typeface get() = appFace(2)
    val displayBold: Typeface get() = appFace(3)

    val roundRegular: Typeface get() = appFace(0)
    val roundMedium: Typeface get() = appFace(1)
    val roundSemibold: Typeface get() = appFace(2)
    val roundBold: Typeface get() = appFace(3)

    val serifRegular: Typeface get() = appFace(0)
    val serifMedium: Typeface get() = appFace(1)
    val serifSemibold: Typeface get() = appFace(2)

    // Fixed faces: the status strip and clock (HUD), and the terminal and code (monospace).
    val hudMedium: Typeface get() = load(R.font.jarvis_hud_medium, Typeface.SANS_SERIF)
    val hudBold: Typeface get() = load(R.font.jarvis_hud_bold, Typeface.DEFAULT_BOLD)
    val hudBlack: Typeface get() = load(R.font.jarvis_hud_black, Typeface.DEFAULT_BOLD)

    val mono: Typeface get() = load(R.font.jarvis_mono_regular, Typeface.MONOSPACE)
    val monoMedium: Typeface get() = load(R.font.jarvis_mono_medium, Typeface.MONOSPACE)
    val monoBold: Typeface get() = load(R.font.jarvis_mono_bold, Typeface.MONOSPACE)
}

/** Applies one typeface to a span of text (TypefaceSpan(Typeface) needs API 28; the app supports 26). */
class JarvisTypefaceSpan(private val face: Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(ds: android.text.TextPaint) { ds.typeface = face }
    override fun updateMeasureState(paint: android.text.TextPaint) { paint.typeface = face }
}
