package com.jarvis.assistant.ui.glass

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.MaterialShapeDrawable
import com.jarvis.assistant.R

/**
 * Keeps text and icons readable on glass in every theme colour.
 *
 * Glass, pods and cards are translucent, so what sits behind a label is the theme's own background. Many labels and icons in
 * the app were written with a fixed white (or fixed dark) colour for one particular theme; change the theme and they vanish.
 * This looks at the colour a text or icon really has, works out what it is drawn on (walking up through the solid backgrounds
 * of its parents; glass counts as the theme background) and, if the contrast is too low, switches it to the theme's own text
 * colour. It leaves alone anything on an accent-filled button (white on a coloured button is meant), on an image or any
 * background it can't read, and anything that already reads well.
 */
internal object ContrastGuard {

    private const val FIXED_TAG = 0x4A4C4347 // "JLCG": holds the colour this guard set, so it is not undone or fought over
    private const val MIN_RATIO = 3.0
    /** Text must read better than icons: this is the default minimum for labels. */
    private const val TEXT_RATIO = 4.5

    fun check(v: View) {
        when {
            v is EditText -> return
            v is TextView -> checkText(v)
            v is ImageView && v.imageTintList != null -> checkIcon(v)
        }
    }

    fun checkText(tv: TextView, minRatio: Double = TEXT_RATIO) {
        if (tv.getTag(LiquidTouch.SKIP_TAG_KEY) == true || tv.visibility != View.VISIBLE || tv.text.isNullOrEmpty()) return
        val color = tv.currentTextColor
        if ((tv.getTag(FIXED_TAG) as? Int) == color) return
        val fixed = fixedColor(tv, color, minRatio) ?: return
        tv.setTextColor(fixed)
        tv.setTag(FIXED_TAG, fixed)
    }

    private fun checkIcon(iv: ImageView) {
        if (iv.getTag(LiquidTouch.SKIP_TAG_KEY) == true || iv.visibility != View.VISIBLE) return
        val tint = iv.imageTintList ?: return
        val color = tint.defaultColor
        if ((iv.getTag(FIXED_TAG) as? Int) == color) return
        val fixed = fixedColor(iv, color) ?: return
        iv.imageTintList = ColorStateList.valueOf(fixed)
        iv.setTag(FIXED_TAG, fixed)
    }

    /** The colour to use instead, or null when the current one is fine (or can't be judged). */
    private fun fixedColor(v: View, color: Int, minRatio: Double = MIN_RATIO): Int? {
        val themeBg = themeColor(v, R.attr.themeBg) ?: return null
        val bg = backgroundBehind(v, themeBg) ?: return null
        val fg = ColorUtils.compositeColors(color, bg)
        val ratio = ColorUtils.calculateContrast(fg, bg)
        if (ratio >= minRatio) return null
        val primary = themeColor(v, R.attr.themeTextPrimary)
        val muted = themeColor(v, R.attr.themeTextMuted)
        val wantsMuted = Color.alpha(color) < 200
        val order = if (wantsMuted) listOf(muted, primary) else listOf(primary, muted)
        for (c in order) {
            if (c == null) continue
            if (ColorUtils.calculateContrast(ColorUtils.compositeColors(c, bg), bg) >= minRatio) return c
        }
        // neither theme text colour reads: fall back to plain black or white, whichever is clearer
        return if (ColorUtils.calculateLuminance(bg) > 0.5) Color.BLACK else Color.WHITE
    }

    private fun themeColor(v: View, attr: Int): Int? {
        val tv = TypedValue()
        return if (v.context.theme.resolveAttribute(attr, tv, true) && tv.data != 0) tv.data else null
    }

    /**
     * The colour a view is drawn on: the first solid background found going up, composited over the theme background.
     * Glass counts as the theme background. Returns null when something unreadable (an image, a gradient with an accent hero look)
     * is in the way, so the guard leaves that view alone.
     */
    private fun backgroundBehind(start: View, themeBg: Int): Int? {
        var v: View? = start
        var depth = 0
        while (v != null && depth < 14) {
            if (v is MaterialButton) {
                val tint = v.backgroundTintList
                if (tint != null) return solidOrNull(tint.defaultColor, themeBg) ?: themeBg
            }
            if (v is MaterialCardView) {
                solidOrNull(v.cardBackgroundColor.defaultColor, themeBg)?.let { return it }
            }
            when (val result = describe(v.background, themeBg)) {
                is Found -> return result.color
                Unknown -> return null
                None -> Unit
            }
            v = v.parent as? View
            depth++
        }
        return themeBg
    }

    private sealed interface Result
    private class Found(val color: Int) : Result
    private object Unknown : Result
    private object None : Result

    private fun solidOrNull(color: Int, themeBg: Int): Int? =
        if (Color.alpha(color) >= 140) ColorUtils.compositeColors(color, themeBg) else null

    private fun describe(d: Drawable?, themeBg: Int): Result {
        when (d) {
            null -> return None
            is LiquidGlassDrawable -> return Found(themeBg)
            is DockPodDrawable -> return if (d.isHero) Unknown else Found(themeBg)
            is GlassIconDrawable -> return Found(themeBg)
            is ColorDrawable -> return solidOrNull(d.color, themeBg)?.let { Found(it) } ?: None
            is GradientDrawable -> {
                val c = d.color?.defaultColor ?: d.colors?.firstOrNull()
                return if (c == null) Unknown else solidOrNull(c, themeBg)?.let { Found(it) } ?: None
            }
            is MaterialShapeDrawable -> {
                val c = d.fillColor?.defaultColor ?: return Unknown
                return solidOrNull(c, themeBg)?.let { Found(it) } ?: None
            }
            is RippleDrawable -> return if (d.numberOfLayers > 0) describe(d.getDrawable(0), themeBg) else None
            is LayerDrawable -> {
                for (i in d.numberOfLayers - 1 downTo 0) {
                    when (val r = describe(d.getDrawable(i), themeBg)) {
                        is Found -> return r
                        Unknown -> return Unknown
                        None -> Unit
                    }
                }
                return None
            }
            else -> return Unknown // bitmaps, vectors, animated drawables: not judged
        }
    }
}
