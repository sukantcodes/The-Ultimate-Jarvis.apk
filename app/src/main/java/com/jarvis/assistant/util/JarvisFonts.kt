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
 * Interface: Inter (SIL OFL). Headings and assistant replies: Newsreader (SIL OFL), an editorial text serif.
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

    val serifRegular: Typeface get() = load(R.font.jarvis_serif_regular, Typeface.SERIF)
    val serifMedium: Typeface get() = load(R.font.jarvis_serif_medium, Typeface.SERIF)
    val serifSemibold: Typeface get() = load(R.font.jarvis_serif_semibold, Typeface.SERIF)
}
