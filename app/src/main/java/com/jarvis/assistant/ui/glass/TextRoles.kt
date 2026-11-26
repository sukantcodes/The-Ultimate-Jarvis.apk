package com.jarvis.assistant.ui.glass

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.util.JarvisFonts
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Colourful text across the whole app.
 *
 * Text that only has the theme's neutral text colours (primary, secondary or muted) is given a colour for the job it does:
 * headings in the accent colour, sub-headings in the secondary colour, small CAPS labels in a warm neighbour, numbers and
 * values in mint, captions in a soft cool tint, helper text in a faint accent tint. Body text stays the theme's own
 * primary colour so long reading never tires the eye.
 *
 * Every colour starts from the theme's accent and is adjusted until it reads at 5:1 or better on whatever it really sits on
 * (ContrastGuard works that out), so it stays readable on every theme, glass, card and sheet. Anything that already has a
 * deliberate colour (a status red, a coloured button label, the drawer's ink) is left alone. It runs from the same scan as
 * the liquid effects (and on its own when Liquid Glass is off), reaches dialogs and sheets, and can be switched off in
 * Settings -> Appearance, which restores every original colour.
 */
internal object TextRoles {

    private const val ORIG_TAG = 0x4A545231     // the colour the text had before
    private const val ASSIGNED_TAG = 0x4A545232 // the colour this object gave it
    private const val BOLD_TAG = 0x4A545233     // was it bold when first seen (later passes set a bold font everywhere)
    private const val PLAIN_TAG = 0x4A545234    // decor already has its own scan

    private enum class Role { HEADING, SUBHEADING, LABEL, VALUE, CAPTION, SECONDARY, BODY }
    private enum class Kind { PRIMARY, SECONDARY, MUTED }

    private fun enabled(): Boolean = try { JarvisApp.instance.preferences.coloredText } catch (_: Throwable) { true }

    fun styleTree(view: View) {
        if (view is LiquidRippleOverlay) return
        if (view.id == R.id.bottomDock) return // the dock is locked
        if (view is TextView) { AppFonts.apply(view); apply(view) }
        if (view is ViewGroup) for (i in 0 until view.childCount) styleTree(view.getChildAt(i))
    }

    /** Colours (or, if switched off, restores) one label. Cheap to call repeatedly. */
    fun apply(tv: TextView) {
        if (tv is EditText || tv is Button || tv is CompoundButton) return
        if (tv.getTag(LiquidTouch.SKIP_TAG_KEY) == true || ContrastGuard.onWhiteGlass(tv)) return
        if (isMono(tv)) return

        val cur = tv.currentTextColor
        val assigned = tv.getTag(ASSIGNED_TAG) as? Int
        val orig = tv.getTag(ORIG_TAG) as? Int
        val ours = assigned != null && orig != null && assigned == cur

        if (!enabled()) {
            if (ours) { tv.setTextColor(orig!!); tv.setTag(ASSIGNED_TAG, null) }
            return
        }
        if (tv.visibility != View.VISIBLE || tv.text.isNullOrEmpty()) return
        if (tv.textColors?.isStateful == true) return

        val base = if (ours) orig!! else cur
        val kind = neutralKind(tv, base) ?: return
        if (tv.getTag(BOLD_TAG) == null) tv.setTag(BOLD_TAG, boldNow(tv))

        val role = roleFor(tv, kind)
        if (role == Role.BODY) {
            if (ours) { tv.setTextColor(orig!!); tv.setTag(ASSIGNED_TAG, null) }
            return
        }
        val color = fit(tv, role) ?: return
        if (ours && color == cur) return
        tv.setTag(ORIG_TAG, base)
        tv.setTag(ASSIGNED_TAG, color)
        tv.setTextColor(color)
    }

    /** Colours a window right away (and again shortly after, once its data has arrived). */
    fun refreshNow(window: Window) {
        val decor = window.decorView as? ViewGroup ?: return
        decor.post { try { styleTree(decor) } catch (_: Throwable) {} }
        decor.postDelayed({ try { styleTree(decor) } catch (_: Throwable) {} }, 350L)
    }

    /** Liquid Glass off: the glass scan is not installed, so this window gets its own light scan. */
    fun attachPlain(window: Window) {
        val decor = window.decorView as? ViewGroup ?: return
        if (decor.getTag(PLAIN_TAG) != null) return
        decor.setTag(PLAIN_TAG, true)
        var scheduled = false
        val scan = Runnable {
            scheduled = false
            try { styleTree(decor) } catch (_: Throwable) {}
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            if (!scheduled) { scheduled = true; decor.postDelayed(scan, 500L) }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Which kind of text is it, and what does it do?
    // ------------------------------------------------------------------------------------------

    private fun neutralKind(tv: TextView, color: Int): Kind? {
        val theme = tv.context.theme
        val tvOut = android.util.TypedValue()
        fun resolve(attr: Int): Int? = if (theme.resolveAttribute(attr, tvOut, true) && tvOut.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && tvOut.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) tvOut.data else null
        val candidates = listOf(
            Kind.PRIMARY to resolve(R.attr.themeTextPrimary),
            Kind.SECONDARY to resolve(R.attr.themeTextSecondary),
            Kind.MUTED to resolve(R.attr.themeTextMuted)
        )
        candidates.firstOrNull { it.second == color }?.let { return it.first }
        val rgb = color and 0xFFFFFF
        return candidates
            .filter { it.second != null && (it.second!! and 0xFFFFFF) == rgb }
            .minByOrNull { abs(android.graphics.Color.alpha(it.second!!) - android.graphics.Color.alpha(color)) }
            ?.first
    }

    private fun roleFor(tv: TextView, kind: Kind): Role {
        val dm = tv.resources.displayMetrics
        val sp = tv.textSize / (dm.density * tv.resources.configuration.fontScale)
        val bold = tv.getTag(BOLD_TAG) == true
        val text = tv.text.toString().trim()
        val caps = looksAllCaps(tv, text)

        if (kind == Kind.MUTED) return if (caps && sp <= 13f && text.length <= 28) Role.LABEL else Role.CAPTION
        if (kind == Kind.SECONDARY) return if (bold && sp >= 15f && text.length <= 40) Role.SUBHEADING else Role.SECONDARY
        // primary text: long text is reading text, whatever its size
        if (text.length > 70) return Role.BODY
        val id = idName(tv)
        return when {
            sp >= 19f -> Role.HEADING
            looksNumeric(text) && (bold || sp >= 14f) -> Role.VALUE
            caps && sp <= 13f && text.length <= 28 -> Role.LABEL
            sp >= 15f && bold -> Role.HEADING
            sp >= 12f && bold -> Role.SUBHEADING
            id.contains("title") || id.contains("heading") || id.contains("header") -> if (sp >= 14f) Role.HEADING else Role.SUBHEADING
            id.contains("subtitle") || id.contains("summary") -> Role.SUBHEADING
            else -> Role.BODY
        }
    }

    private fun looksAllCaps(tv: TextView, text: String): Boolean {
        if (text.length < 3) return false
        if (tv.transformationMethod?.javaClass?.simpleName?.contains("AllCaps") == true) return true
        if (tv.letterSpacing >= 0.08f && text == text.uppercase() && text.any { it.isLetter() }) return true
        return text == text.uppercase() && text.count { it.isLetter() } >= 3
    }

    private fun looksNumeric(text: String): Boolean {
        if (text.isEmpty() || text.length > 14) return false
        val t = text.trimStart('~', '≈', '<', '>', '+', '-', '−', ' ')
        if (t.isEmpty() || !t[0].isDigit()) return false
        val digits = t.count { it.isDigit() }
        return digits.toFloat() / t.length >= 0.4f
    }

    private fun boldNow(tv: TextView): Boolean {
        val tf = tv.typeface ?: return false
        if (tf.isBold || tv.paint.isFakeBoldText) return true
        return android.os.Build.VERSION.SDK_INT >= 28 && tf.weight >= 600
    }

    private fun idName(tv: TextView): String =
        if (tv.id == View.NO_ID) "" else try { tv.resources.getResourceEntryName(tv.id).lowercase() } catch (_: Throwable) { "" }

    private fun isMono(tv: TextView): Boolean {
        val tf = tv.typeface ?: return false
        return tf == JarvisFonts.mono || tf == JarvisFonts.monoBold || tf == Typeface.MONOSPACE
    }

    // ------------------------------------------------------------------------------------------
    // Colours
    // ------------------------------------------------------------------------------------------

    private var paletteKey = 0L
    private val palette = HashMap<Int, FloatArray>()

    private fun hueOf(color: Int): Pair<Float, Float> {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        return hsl[0] to hsl[1]
    }

    // Hue steps that keep neighbours clearly apart; each text picks one from its own wording, so it never changes.
    private val spread = floatArrayOf(0f, 72f, 144f, 216f, 288f)

    private fun variantOf(tv: TextView, role: Role): Int =
        if (role == Role.HEADING || role == Role.VALUE) 0
        else (tv.text.toString().trim().lowercase().hashCode() and 0x7FFFFFFF) % spread.size

    private fun base(role: Role, variant: Int): FloatArray {
        val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { 0xFF4FA3FF.toInt() }
        val secondary = try { ThemeManager.getThemeSecondaryColor() } catch (_: Throwable) { accent }
        val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        val key = accent.toLong() * 31L + secondary.toLong() * 17L + (if (light) 1L else 0L)
        if (key != paletteKey) {
            paletteKey = key
            palette.clear()
        }
        return palette.getOrPut(role.ordinal * 8 + variant) {
            var (accH, accS) = hueOf(accent)
            if (accS < 0.15f) accH = 215f // neutral accents still get a real colour
            var secH = hueOf(secondary).first
            if (abs(((secH - accH + 540f) % 360f) - 180f) > 155f || hueOf(secondary).second < 0.15f) secH = (accH + 60f) % 360f
            val s = if (light) 0.78f else 0.86f
            val l = if (light) 0.30f else 0.72f
            val off = spread[variant]
            when (role) {
                Role.HEADING -> floatArrayOf(accH, s, l)
                Role.SUBHEADING -> floatArrayOf((secH + off) % 360f, s, if (light) l + 0.04f else l - 0.02f)
                Role.LABEL -> floatArrayOf((accH + 38f + off) % 360f, s, l + 0.02f)
                Role.VALUE -> floatArrayOf(160f, 0.74f, if (light) 0.27f else 0.68f)
                Role.CAPTION -> floatArrayOf((accH + 195f + off) % 360f, 0.55f, if (light) 0.34f else 0.74f)
                Role.SECONDARY -> floatArrayOf((accH + 20f + off) % 360f, 0.50f, if (light) 0.30f else 0.80f)
                else -> floatArrayOf(0f, 0f, 0.5f)
            }
        }
    }

    /** The role's colour, nudged lighter or darker until it reads at 5:1 on what it sits on; null when that can't be judged. */
    private fun fit(tv: TextView, role: Role): Int? {
        val hsl = base(role, variantOf(tv, role))
        val light = try { ThemeManager.isCurrentThemeLight() } catch (_: Throwable) { false }
        for (direction in intArrayOf(if (light) -1 else 1, if (light) 1 else -1)) {
            var l = hsl[2]
            repeat(10) {
                val c = ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], l))
                val ratio = ContrastGuard.contrastOn(tv, c) ?: return null
                if (ratio >= 5.2) return c
                l = if (direction > 0) min(0.93f, l + 0.04f) else max(0.1f, l - 0.04f)
            }
        }
        return null
    }
}
