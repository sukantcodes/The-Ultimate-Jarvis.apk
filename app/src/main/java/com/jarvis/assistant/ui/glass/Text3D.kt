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
 * Makes every label in the glass design thick, readable in every theme colour and 3D.
 *
 * - Heavier fonts: headings get the black weight, everything else a bold medium weight, so thin text never disappears on glass.
 * - Contrast: text is pushed to at least a 4.5:1 ratio against what it sits on (ContrastGuard picks the theme's own text colour).
 * - 3D: a crisp offset shadow gives each letter a raised edge (dark under light text, light under dark text).
 * - Tiny intro / helper text is lifted to a readable size.
 *
 * It runs from the same debounced scan as the bubble effects, so it also reaches dialogs, bottom sheets and list rows, and it
 * never touches the locked bottom dock bar. Each view is styled once per text colour, so scrolling stays smooth.
 */
internal object Text3D {

    private const val DONE_TAG = 0x4A543344 // "JT3D": the text colour this view was last styled for

    fun styleTree(view: View) {
        if (view is LiquidRippleOverlay) return
        if (view.id == R.id.bottomDock) return // the dock is locked: it never changes
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
        // readable first: fix a low-contrast colour, then style for the colour the text really has
        if (tv !is EditText) ContrastGuard.checkText(tv, 4.5)
        val color = tv.currentTextColor
        if ((tv.getTag(DONE_TAG) as? Int) == color) return
        tv.setTag(DONE_TAG, color)

        val d = tv.resources.displayMetrics.density
        val sp = tv.textSize / tv.resources.displayMetrics.scaledDensity
        val heading = sp >= 17f || tv.typeface?.isBold == true
        tv.typeface = if (heading) Typeface.create("sans-serif-black", Typeface.NORMAL)
        else Typeface.create("sans-serif-medium", Typeface.BOLD)

        // tiny intro / helper text becomes readable (buttons and chips keep their size so layouts hold)
        if (sp < 12f && tv !is android.widget.Button && tv !is android.widget.CompoundButton && tv !is EditText) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        // 3D: a sharp raised edge. Glow text that already has its own shadow keeps it.
        if (tv.shadowRadius <= 0f) {
            val light = ColorUtils.calculateLuminance(ColorUtils.setAlphaComponent(color, 255)) > 0.5
            val shadow = if (light) Color.argb(150, 0, 0, 0) else Color.argb(170, 255, 255, 255)
            tv.setShadowLayer(0.8f * d, 0.6f * d, (if (light) 1.6f else 1.2f) * d, shadow)
        }
    }
}
