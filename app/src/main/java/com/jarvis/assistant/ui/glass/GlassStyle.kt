package com.jarvis.assistant.ui.glass

import com.jarvis.assistant.util.ThemeManager

/**
 * The one look of every glass pane in the app (Settings screens, cards, dialogs, sheets, the dock): clear WHITE glass
 * that bends what is behind it at its edges (refraction) but never magnifies it (no zoom).
 */
internal object GlassStyle {
    /** How far the edge bends what is behind the glass. */
    const val REFRACTION_DP = 16f

    /** Width of the curved edge band, from the rim inward. */
    const val BEZEL_DP = 22f

    /** Zoom is always 1: the glass shows the scene behind it at its real size. */
    const val ZOOM = 1f

    fun isLight(): Boolean = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }

    /** White frost mixed into the glass: a little more on light themes so the pane still reads as white glass there. */
    fun frost(): Float = if (isLight()) 0.26f else 0.15f
}
