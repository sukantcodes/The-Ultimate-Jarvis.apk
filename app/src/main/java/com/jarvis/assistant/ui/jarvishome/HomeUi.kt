package com.jarvis.assistant.ui.jarvishome

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import com.jarvis.assistant.home.DeviceStatus
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.ui.glass.LiquidGlass
import com.jarvis.assistant.util.ThemeManager

/**
 * View kit for JARVIS Home: LiquidGlass surfaces with a 3D finish (raised, specular edge, depth shade), per-page gradient
 * colour, Orbitron/Rajdhani type, bubble switches and tilt-on-touch. Colours that must follow the theme (text) still do.
 */
class HomeUi(private val context: Context) {

    /** The hue of the page being shown; set by the activity on every page change. */
    var hue: HomeTheme.Hue = HomeTheme.CYAN

    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * context.resources.displayMetrics.density

    fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private val light: Boolean get() = runCatching { ThemeManager.isCurrentThemeLight() }.getOrDefault(false)
    val primary: Int get() = themeColor(R.attr.themeTextPrimary, if (light) Color.BLACK else Color.WHITE)
    val muted: Int get() = themeColor(R.attr.themeTextMuted, ColorUtils.setAlphaComponent(primary, 175))
    val accent: Int get() = hue.a
    val success: Int get() = HomeTheme.MINT.a
    val warning: Int get() = HomeTheme.AMBER.a
    val danger: Int get() = HomeTheme.CORAL.a
    val chipBg: Int get() = themeColor(R.attr.themeChipBg, ColorUtils.setAlphaComponent(primary, 40))

    // ---- type -------------------------------------------------------------------------------------

    /** Reading text in Rajdhani. [bold] thickens it (used for names and key values). */
    fun text(value: String, sizeSp: Float, color: Int = primary, bold: Boolean = false, maxLines: Int = 0) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp * 1.1f)
        setTextColor(color)
        typeface = if (bold) Typeface.create(HomeTheme.body(context), Typeface.BOLD) else HomeTheme.soft(context)
        if (bold) letterSpacing = 0.01f
        if (maxLines > 0) { this.maxLines = maxLines; ellipsize = android.text.TextUtils.TruncateAt.END }
    }

    /** Heavy Orbitron for titles and big numbers. */
    fun display(value: String, sizeSp: Float, color: Int = primary) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Typeface.create(HomeTheme.display(context), Typeface.BOLD)
        letterSpacing = 0.04f
    }

    /** A title filled with the page's gradient. */
    fun gradientText(value: String, sizeSp: Float, h: HomeTheme.Hue = hue, display: Boolean = true) = (if (display) display(value, sizeSp) else text(value, sizeSp, bold = true)).apply {
        val width = paint.measureText(value).coerceAtLeast(1f)
        paint.shader = LinearGradient(0f, 0f, width, 0f, intArrayOf(h.a, h.mid, h.b), null, Shader.TileMode.CLAMP)
    }

    fun space(h: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    // ---- surfaces ---------------------------------------------------------------------------------

    /** Gives a surface its 3D finish: lifted off the page, a bright specular edge and a shaded underside. */
    fun finish3d(view: View, radiusDp: Float, lift: Float = 6f) {
        val r = dpf(radiusDp)
        view.foreground = LayerDrawable(arrayOf(
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, 62), Color.TRANSPARENT, Color.TRANSPARENT, ColorUtils.setAlphaComponent(Color.BLACK, 44))).apply { cornerRadius = r },
            GradientDrawable().apply { cornerRadius = r; setStroke(dp(1), ColorUtils.setAlphaComponent(Color.WHITE, 96)); setColor(Color.TRANSPARENT) }
        ))
        view.elevation = dpf(lift)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) { outline.setRoundRect(0, 0, v.width, v.height, r) }
        }
        view.clipToOutline = false
    }

    /** A frosted LiquidGlass card. [tint] 0 uses the theme's own glass tint; a colour washes the glass with that hue. */
    fun glassCard(radiusDp: Float = 24f, tint: Int = 0, padding: Int = 16) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        LiquidGlass.apply(this, radiusDp, tint)
        finish3d(this, radiusDp)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
    }

    fun softPane(radiusDp: Float = 16f) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        LiquidGlass.applySoft(this, radiusDp)
    }

    private fun pill(h: HomeTheme.Hue, filled: Boolean, radiusPx: Float): LayerDrawable {
        val base = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, if (filled) intArrayOf(ColorUtils.setAlphaComponent(h.a, 235), ColorUtils.setAlphaComponent(h.b, 235)) else intArrayOf(chipBg, chipBg)).apply {
            cornerRadius = radiusPx
            if (!filled) setStroke(dp(1), ColorUtils.setAlphaComponent(h.a, 110))
        }
        val gloss = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE, if (filled) 120 else 50), Color.TRANSPARENT)).apply { cornerRadius = radiusPx }
        val edge = GradientDrawable().apply { cornerRadius = radiusPx; setStroke(dp(1), ColorUtils.setAlphaComponent(Color.WHITE, if (filled) 120 else 40)); setColor(Color.TRANSPARENT) }
        return LayerDrawable(arrayOf(base, gloss, edge)).also { it.setLayerInset(1, 0, 0, 0, dp(14)) }
    }

    fun chip(label: String, selected: Boolean, h: HomeTheme.Hue = hue, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        typeface = if (selected) Typeface.create(HomeTheme.body(context), Typeface.BOLD) else HomeTheme.body(context)
        setTextColor(if (selected) Color.WHITE else muted)
        if (selected) setShadowLayer(dpf(2f), 0f, dpf(1f), ColorUtils.setAlphaComponent(Color.BLACK, 110))
        setPadding(dp(16), 0, dp(16), 0)
        minimumHeight = dp(44)
        background = pill(h, selected, dpf(22f))
        if (selected) elevation = dpf(5f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8); topMargin = dp(4); bottomMargin = dp(6) }
        isClickable = true; isFocusable = true
        contentDescription = label
        Motion.pressable(this, 9f)
        setOnClickListener { onClick() }
    }

    fun button(label: String, filled: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create(HomeTheme.body(context), Typeface.BOLD)
        letterSpacing = 0.02f
        setTextColor(if (filled) Color.WHITE else primary)
        if (filled) setShadowLayer(dpf(2f), 0f, dpf(1f), ColorUtils.setAlphaComponent(Color.BLACK, 110))
        setPadding(dp(18), dp(10), dp(18), dp(10))
        minimumHeight = dp(48)
        background = pill(hue, filled, dpf(24f))
        elevation = dpf(if (filled) 6f else 2f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8); topMargin = dp(6); bottomMargin = dp(6) }
        isClickable = true; isFocusable = true
        Motion.pressable(this, 8f)
        setOnClickListener { onClick() }
    }

    fun statusColor(status: DeviceStatus) = when (status) {
        DeviceStatus.ONLINE -> success
        DeviceStatus.OFFLINE -> danger
        DeviceStatus.UNKNOWN -> muted
    }

    fun statusLabel(d: HomeDevice): String = when (d.status) {
        DeviceStatus.ONLINE -> when (d.poweredOn) { true -> "On"; false -> "Off"; null -> "Online" }
        DeviceStatus.OFFLINE -> "Offline"
        DeviceStatus.UNKNOWN -> "Not checked"
    }

    /** A status dot; online ones breathe so a live device reads as alive. */
    fun dot(color: Int, live: Boolean = false) = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(7) }
        if (live) Motion.breathe(this)
    }

    fun orb(glyph: String, h: HomeTheme.Hue, sizeDp: Int, lit: Boolean) = OrbView(context).configure(h, glyph).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setLit(lit, animate = false)
    }

    fun bubbleSwitch(checked: Boolean, h: HomeTheme.Hue = hue, onChange: (Boolean) -> Unit) = BubbleSwitch(context).apply {
        hue = h
        setOn(checked, animate = false)
        this.onChange = onChange
    }

    fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)); minimumHeight = dp(52) }
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        info.addView(text(title, 15.5f, bold = true))
        if (!subtitle.isNullOrBlank()) info.addView(text(subtitle, 12f, muted).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(info)
        val sw = bubbleSwitch(checked, hue, onChange).apply { contentDescription = title }
        row.addView(sw)
        row.setOnClickListener { sw.performClick(); sw.setOn(!sw.isOn, animate = true, notify = true) }
        return row
    }

    fun actionRow(title: String, subtitle: String?, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)); minimumHeight = dp(52); isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        info.addView(text(title, 15.5f, bold = true))
        if (!subtitle.isNullOrBlank()) info.addView(text(subtitle, 12f, muted).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(info)
        row.addView(display("›", 20f, hue.a))
        return row
    }

    fun divider() = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(hue.a, 110), Color.TRANSPARENT))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(4) }
    }

    /** An honest empty/permission/error panel: says what is missing and offers the one action that fixes it. */
    fun notice(title: String, body: String, actionLabel: String? = null, action: (() -> Unit)? = null): View = glassCard(26f, ColorUtils.setAlphaComponent(hue.a, 34)).apply {
        addView(text(title, 17f, bold = true))
        addView(text(body, 13.5f, muted).apply { setPadding(0, dp(4), 0, dp(8)) })
        if (actionLabel != null && action != null) addView(button(actionLabel, true, action))
    }
}
