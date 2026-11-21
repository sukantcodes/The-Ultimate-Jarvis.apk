package com.jarvis.assistant.ui.glass

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R

/**
 * Keeps typography crisp, balanced, and effortless to read over Liquid Glass across every theme:
 *  - Clean Apple-inspired weight hierarchy (semibold headings, clean medium body — never heavy/muddy).
 *  - WCAG contrast guarantee via ContrastGuard.
 *  - Subtle optical legibility halo so text floats clearly over refracted glass without heavy drop shadows.
 */
internal object Text3D {

    private const val DONE_TAG = 0x4A543344 // "JT3D"

    fun styleTree(view: View) {
        if (view is LiquidRippleOverlay) return
        if (view.id == R.id.bottomDock) return
        when (view) {
            is TextView -> style(view)
            is androidx.core.widget.NestedScrollView, is android.widget.ScrollView, is androidx.recyclerview.widget.RecyclerView ->
                SmoothScroll.install(view)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) styleTree(view.getChildAt(i))
    }

    private fun style(tv: TextView) {
        if (tv.getTag(LiquidTouch.SKIP_TAG_KEY) == true || tv.visibility != View.VISIBLE) return
        if (tv.typeface == Typeface.MONOSPACE || tv.typeface?.let { it == Typeface.create("monospace", Typeface.NORMAL) } == true) return
        if (tv !is EditText) ContrastGuard.checkText(tv, 4.5)
        val color = tv.currentTextColor
        if ((tv.getTag(DONE_TAG) as? Int) == color) return
        tv.setTag(DONE_TAG, color)

        val d = tv.resources.displayMetrics.density
        val sp = tv.textSize / tv.resources.displayMetrics.scaledDensity
        val heading = sp >= 17f || tv.typeface?.isBold == true
        tv.typeface = if (heading) {
            Typeface.create("sans-serif-medium", Typeface.BOLD)
        } else {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        if (sp < 11.5f && tv !is android.widget.Button && tv !is android.widget.CompoundButton && tv !is EditText) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }

        // Delicate optical legibility shadow (keeps glass text razor-sharp without heavy cartoon outline)
        if (tv.shadowRadius <= 0f) {
            val light = ColorUtils.calculateLuminance(ColorUtils.setAlphaComponent(color, 255)) > 0.5
            val shadow = if (light) Color.argb(96, 0, 0, 0) else Color.argb(110, 255, 255, 255)
            tv.setShadowLayer(1.6f * d, 0f, 0.7f * d, shadow)
        }
    }
}
