package com.jarvis.assistant.ui.glass

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R

/** True while the Crystal Mirror theme is the selected theme. Everything in [CrystalPolish] is gated by it. */
object CrystalTheme {
    fun active(): Boolean = try { JarvisApp.instance.preferences.appTheme == "crystal" } catch (_: Throwable) { false }
}

/**
 * The Crystal Mirror optimisation pass. It runs ONLY while the Crystal Mirror theme is selected and never touches the locked
 * dock bar, so every other theme keeps exactly its own look.
 *
 * On a see-through mirror every element has to be clear glass, not a dark opaque plate, so:
 *  - icons become bright ice-white (or a lifted version of their own colour when they carry meaning),
 *  - filled buttons, chips, cards and inputs turn into translucent crystal with a bright mirror edge,
 *  - sliders and progress bars glow in the ice accent, hairlines become fine light lines,
 *  - solid full-screen panels become transparent so the mirror shows through, and dialog dimming is lightened.
 * Each view is polished once, so it costs nothing while scrolling or streaming.
 */
internal object CrystalPolish {

    private const val DONE = 0x4A435259 // "JCRY"
    private val ICE = Color.parseColor("#F2F9FF")

    fun polishTree(root: View) {
        if (!CrystalTheme.active()) return
        walk(root)
    }

    /** Dialogs and sheets float over a mirror: a light dim keeps the glass bright instead of greying it. */
    fun window(window: Window) {
        if (!CrystalTheme.active()) return
        val lp = window.attributes
        if (lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0 && lp.dimAmount > 0.18f) window.setDimAmount(0.18f)
    }

    private fun walk(v: View) {
        if (v is LiquidRippleOverlay) return
        if (v.id == R.id.bottomDock) return // the dock is locked
        if (v.getTag(DONE) == null && v.visibility == View.VISIBLE && v.width > 0 && v.height > 0 && v.getTag(LiquidTouch.SKIP_TAG_KEY) != true) {
            try { polish(v) } catch (_: Throwable) {}
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    private fun polish(v: View) {
        val d = v.resources.displayMetrics.density
        when (v) {
            is MaterialButton -> button(v, d)
            is Chip -> chip(v)
            is MaterialCardView -> card(v)
            is TextInputLayout -> field(v)
            is EditText -> v.setHintTextColor(ColorUtils.setAlphaComponent(ICE, 170))
            is SeekBar -> bar(v)
            is ProgressBar -> bar(v)
            is ImageView -> icon(v, d)
            is ViewGroup -> panel(v, d)
            else -> hairline(v, d)
        }
        v.setTag(DONE, true)
    }

    // ---- icons: bright and readable on clear glass ----
    private fun icon(iv: ImageView, d: Float) {
        if (iv.width / d > 64f || iv.height / d > 64f) return          // pictures, avatars and logos stay as they are
        val tint = iv.imageTintList ?: return                           // untinted (multi-colour) artwork stays as designed
        if (tint.isStateful) return                                     // selectors keep their own states
        val c = tint.defaultColor
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(c, it) }
        val out = if (hsl[1] < 0.28f) {
            ColorUtils.setAlphaComponent(ICE, Color.alpha(c).coerceAtLeast(200))   // neutral icons: ice white
        } else {
            if (hsl[2] < 0.62f) hsl[2] = 0.68f                                      // meaningful colours: same hue, lifted
            ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), Color.alpha(c))
        }
        iv.imageTintList = ColorStateList.valueOf(out)
    }

    // ---- elements: clear crystal instead of dark plates ----
    private fun button(b: MaterialButton, d: Float) {
        val tint = b.backgroundTintList
        if (tint != null && !tint.isStateful && Color.alpha(tint.defaultColor) > 0xC0) {
            val base = tint.defaultColor
            b.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(base, 0x6E))
            b.strokeColor = ColorStateList.valueOf(Color.argb(0xA6, 255, 255, 255))
            b.strokeWidth = (1f * d).toInt().coerceAtLeast(1)
            if (ColorUtils.calculateLuminance(base) < 0.75) b.setTextColor(Color.WHITE)
            if (b.iconTint == null || !b.iconTint.isStateful) b.iconTint = ColorStateList.valueOf(if (ColorUtils.calculateLuminance(base) < 0.75) Color.WHITE else ICE)
        } else if (tint != null && Color.alpha(tint.defaultColor) < 0x40) {
            // outlined / text buttons: a bright mirror edge
            if (b.strokeWidth > 0 && !(b.strokeColor?.isStateful ?: true)) b.strokeColor = ColorStateList.valueOf(Color.argb(0xA6, 255, 255, 255))
        }
    }

    private fun chip(c: Chip) {
        val bg = c.chipBackgroundColor
        if (bg != null && !bg.isStateful && Color.alpha(bg.defaultColor) > 0xA0) {
            c.chipBackgroundColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(bg.defaultColor, 0x66))
        }
        c.chipStrokeColor = ColorStateList.valueOf(Color.argb(0x99, 255, 255, 255))
        if (c.chipStrokeWidth < 1f) c.chipStrokeWidth = c.resources.displayMetrics.density
    }

    private fun card(c: MaterialCardView) {
        // Never lower than alpha 40: the glass sheen pass treats fainter cards as "designed see-through" and skips them.
        if (Color.alpha(c.cardBackgroundColor.defaultColor) > 0x60) c.setCardBackgroundColor(Color.argb(0x2E, 255, 255, 255))
        c.strokeColor = Color.argb(0x73, 255, 255, 255)
        if (c.strokeWidth < 1) c.strokeWidth = c.resources.displayMetrics.density.toInt().coerceAtLeast(1)
        c.cardElevation = minOf(c.cardElevation, 2f * c.resources.displayMetrics.density)
    }

    private fun field(t: TextInputLayout) {
        t.boxBackgroundColor = Color.argb(0x26, 255, 255, 255)
        t.setBoxStrokeColor(Color.argb(0xB3, 255, 255, 255))
        t.defaultHintTextColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(ICE, 190))
    }

    private fun bar(v: ProgressBar) {
        val accent = com.jarvis.assistant.util.ThemeManager.getThemeAccentColor()
        v.progressTintList = ColorStateList.valueOf(accent)
        v.progressBackgroundTintList = ColorStateList.valueOf(Color.argb(0x40, 255, 255, 255))
        if (v is SeekBar) v.thumbTintList = ColorStateList.valueOf(Color.WHITE)
    }

    // ---- panels: solid plates become glass or disappear ----
    private fun panel(g: ViewGroup, d: Float) {
        val bg = g.background as? ColorDrawable ?: return
        if (Color.alpha(bg.color) < 0x60) return
        val dm = g.resources.displayMetrics
        val big = g.width >= dm.widthPixels * 0.9f && g.height >= dm.heightPixels * 0.6f
        if (big) {
            g.background = ColorDrawable(Color.TRANSPARENT)                 // full-screen panels let the mirror show through
        } else if (g.width >= 120f * d && g.height >= 56f * d && g.height <= 420f * d) {
            LiquidGlass.apply(g, 22f, 0, soft = g.height < 96f * d)         // a solid card becomes liquid glass
        }
    }

    private fun hairline(v: View, d: Float) {
        val bg = v.background as? ColorDrawable ?: return
        if (v.height <= 2f * d && v.width >= 40f * d) v.setBackgroundColor(Color.argb(0x38, 255, 255, 255))
    }
}
