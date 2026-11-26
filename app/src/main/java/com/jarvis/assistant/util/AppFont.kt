package com.jarvis.assistant.util

import androidx.annotation.FontRes
import androidx.annotation.StyleRes
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R

/**
 * The five fonts a person can pick for the whole app (Settings -> Appearance -> App font). All are SIL OFL and bundled.
 * One font covers every screen, dialog, sheet and chat message; weights (regular, medium, semibold, bold) come from the
 * same family, so headings still stand out. Terminal and code keep their monospace face and the status strip and clock
 * keep their HUD face, whatever is picked here.
 */
enum class AppFont(
    val key: String,
    val label: String,
    @FontRes val regular: Int,
    @FontRes val medium: Int,
    @FontRes val semibold: Int,
    @FontRes val bold: Int,
    @StyleRes val overlay: Int
) {
    /** Default: the most readable of the five at small sizes. */
    INTER("inter", "Inter", R.font.jarvis_sans_regular, R.font.jarvis_sans_medium, R.font.jarvis_sans_semibold, R.font.jarvis_sans_bold, R.style.ThemeOverlay_JarvisFont_Inter),
    SPACE("space", "Space Grotesk", R.font.jarvis_display_regular, R.font.jarvis_display_medium, R.font.jarvis_display_semibold, R.font.jarvis_display_bold, R.style.ThemeOverlay_JarvisFont_Space),
    SORA("sora", "Sora", R.font.jarvis_round_regular, R.font.jarvis_round_medium, R.font.jarvis_round_semibold, R.font.jarvis_round_bold, R.style.ThemeOverlay_JarvisFont_Sora),
    NEWSREADER("newsreader", "Newsreader", R.font.jarvis_serif_regular, R.font.jarvis_serif_medium, R.font.jarvis_serif_semibold, R.font.jarvis_serif_semibold, R.style.ThemeOverlay_JarvisFont_Newsreader),
    MONO("jetbrains", "JetBrains Mono", R.font.jarvis_mono_regular, R.font.jarvis_mono_medium, R.font.jarvis_mono_bold, R.font.jarvis_mono_bold, R.style.ThemeOverlay_JarvisFont_Mono);

    val displayName: String get() = if (this == DEFAULT) "$label (default)" else label

    companion object {
        val DEFAULT = INTER

        @Volatile private var cached: AppFont? = null

        fun fromKey(key: String?): AppFont = values().firstOrNull { it.key == key } ?: DEFAULT

        /** The font in use. Read from preferences once, then kept in memory until it is changed. */
        val current: AppFont
            get() {
                cached?.let { return it }
                val key = try { JarvisApp.instance.preferences.appFont } catch (_: Throwable) { return DEFAULT }
                return fromKey(key).also { cached = it }
            }

        fun select(font: AppFont) {
            cached = font
            try { JarvisApp.instance.preferences.appFont = font.key } catch (_: Throwable) {}
        }
    }
}
