package com.jarvis.assistant.ui.glass

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.jarvis.assistant.JarvisApp

/**
 * The iOS 27 style Liquid Glass intensity slider, from fully opaque to completely clear.
 *
 * The person's setting is the existing "Liquid glass strength" (0..100, default 60), read here as clarity: 0 is a solid,
 * opaque surface and 100 is clear glass with crisp refraction. At the default (60) every scale below is exactly 1 and
 * [Profile.opacity] is 0, so the glass looks the same as it did before the slider existed.
 *
 * Android has no system "Reduce Transparency" switch, so the closest equivalent is honoured: when the system's high
 * contrast text option is on, the glass is capped toward opaque for readability, the way iOS adapts the material.
 */
internal object GlassClarity {

    /** How each part of the glass responds at a given clarity. All values are multipliers except [opacity] (0..1). */
    class Profile(
        /** 0 = opaque, 1 = clear. */
        val clarity: Float,
        /** Frost over the backdrop: more at the opaque end, almost none when clear. */
        val veilScale: Float,
        /** Diffusion of what is behind the glass: strongest at the opaque end (readability), lighter when clear. */
        val blurScale: Float,
        /** How strongly the rim bends the backdrop: a thick, solid slab bends little, clear glass bends the most. */
        val refractScale: Float,
        /** Brightness of the rim light and the top sheen. */
        val rimScale: Float,
        /** How far the pane settles into a solid surface, 0 (glass) to about 0.9 (solid). */
        val opacity: Float
    ) {
        override fun equals(other: Any?): Boolean = other is Profile && other.clarity == clarity
        override fun hashCode(): Int = clarity.hashCode()
    }

    /** The value that must reproduce the existing look exactly. */
    const val DEFAULT_CLARITY = 0.6f

    /** Clarity cap while the system's high contrast option is on. */
    private const val HIGH_CONTRAST_CAP = 0.25f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Pure mapping from a clarity (0 opaque .. 1 clear) to the glass profile. Exactly neutral at [DEFAULT_CLARITY]. */
    fun forLevel(level: Float): Profile {
        val c = level.coerceIn(0f, 1f)
        val below = c <= DEFAULT_CLARITY
        val t = if (below) c / DEFAULT_CLARITY else (c - DEFAULT_CLARITY) / (1f - DEFAULT_CLARITY)
        val veil = if (below) lerp(2.6f, 1f, t) else lerp(1f, 0.25f, t)
        val blur = if (below) lerp(2.0f, 1f, t) else lerp(1f, 0.72f, t)
        val refract = if (below) lerp(0.35f, 1f, t) else lerp(1f, 1.18f, t)
        val rim = if (below) lerp(0.7f, 1f, t) else lerp(1f, 1.1f, t)
        val u = ((DEFAULT_CLARITY - c) / DEFAULT_CLARITY).coerceIn(0f, 1f)
        val opacity = 0.92f * u * u * (3f - 2f * u) // smoothstep: eases into the solid surface
        return Profile(c, veil, blur, refract, rim, opacity)
    }

    @Volatile private var cached: Profile = forLevel(DEFAULT_CLARITY)
    @Volatile private var cachedAt = 0L

    /**
     * The profile for the person's current setting. Cheap to call every frame: the preference and the accessibility
     * option are re-read at most four times a second.
     */
    fun current(context: Context): Profile {
        val now = SystemClock.uptimeMillis()
        if (now - cachedAt < 250L) return cached
        cachedAt = now
        var level = try {
            JarvisApp.instance.preferences.liquidGlassStrength / 100f
        } catch (_: Throwable) {
            DEFAULT_CLARITY
        }
        if (highContrast(context)) level = minOf(level, HIGH_CONTRAST_CAP)
        val next = forLevel(level)
        if (next != cached) cached = next
        return cached
    }

    private fun highContrast(context: Context): Boolean = try {
        // "High contrast text" in Accessibility settings. The key is not a public constant, hence the literal.
        Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
    } catch (_: Throwable) {
        false
    }
}
