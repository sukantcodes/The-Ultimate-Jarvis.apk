package com.jarvis.assistant.ui.jarvishome

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import com.jarvis.assistant.home.DeviceStatus
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.ui.glass.LiquidGlass
import com.jarvis.assistant.util.ThemeManager

/** View helpers for JARVIS Home. All colours come from the active theme; glass surfaces come from the app's LiquidGlass system. */
class HomeUi(private val context: Context) {

    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private val light: Boolean get() = runCatching { ThemeManager.isCurrentThemeLight() }.getOrDefault(false)
    val primary: Int get() = themeColor(R.attr.themeTextPrimary, if (light) Color.BLACK else Color.WHITE)
    val muted: Int get() = themeColor(R.attr.themeTextMuted, ColorUtils.setAlphaComponent(primary, 160))
    val accent: Int get() = runCatching { ThemeManager.getThemeAccentColor() }.getOrDefault(primary)
    val success: Int get() = runCatching { ThemeManager.getThemeSuccessColor() }.getOrDefault(accent)
    val warning: Int get() = runCatching { ThemeManager.getThemeWarningColor() }.getOrDefault(accent)
    val chipBg: Int get() = themeColor(R.attr.themeChipBg, ColorUtils.setAlphaComponent(primary, 40))

    fun text(value: String, sizeSp: Float, color: Int = primary, bold: Boolean = false, maxLines: Int = 0) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        if (maxLines > 0) { this.maxLines = maxLines; ellipsize = android.text.TextUtils.TruncateAt.END }
    }

    fun space(h: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    /** A frosted LiquidGlass card. [tint] 0 uses the theme's own glass tint. */
    fun glassCard(radiusDp: Float = 22f, tint: Int = 0, padding: Int = 14) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        LiquidGlass.apply(this, radiusDp, tint)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    fun softPane(radiusDp: Float = 16f) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        LiquidGlass.applySoft(this, radiusDp)
    }

    fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(if (selected) primary else muted)
        setPadding(dp(14), 0, dp(14), 0)
        minimumHeight = dp(44)
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(if (selected) ColorUtils.setAlphaComponent(accent, 70) else chipBg)
            if (selected) setStroke(dp(1), accent)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(8) }
        isClickable = true; isFocusable = true
        contentDescription = label
        setOnClickListener { onClick() }
    }

    fun button(label: String, filled: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(primary)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minimumHeight = dp(48)
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(if (filled) ColorUtils.setAlphaComponent(accent, 110) else chipBg)
            if (filled) setStroke(dp(1), accent)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8); topMargin = dp(4) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    fun statusColor(status: DeviceStatus) = when (status) {
        DeviceStatus.ONLINE -> success
        DeviceStatus.OFFLINE -> warning
        DeviceStatus.UNKNOWN -> muted
    }

    fun statusLabel(d: HomeDevice): String = when (d.status) {
        DeviceStatus.ONLINE -> when (d.poweredOn) { true -> "On"; false -> "Off"; null -> "Online" }
        DeviceStatus.OFFLINE -> "Offline"
        DeviceStatus.UNKNOWN -> "Not checked"
    }

    fun dot(color: Int) = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(6) }
    }

    fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)); minimumHeight = dp(48) }
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        info.addView(text(title, 15f, bold = true))
        if (!subtitle.isNullOrBlank()) info.addView(text(subtitle, 12f, muted).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(info)
        val toggle = androidx.appcompat.widget.SwitchCompat(context).apply {
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(toggle)
        LiquidGlass.styleSwitches(row)
        row.setOnClickListener { toggle.toggle() }
        return row
    }

    fun actionRow(title: String, subtitle: String?, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)); minimumHeight = dp(48); isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        info.addView(text(title, 15f, bold = true))
        if (!subtitle.isNullOrBlank()) info.addView(text(subtitle, 12f, muted).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(info)
        row.addView(text("›", 22f, muted))
        return row
    }

    fun divider() = View(context).apply {
        setBackgroundColor(ColorUtils.setAlphaComponent(primary, 30))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(2); bottomMargin = dp(2) }
    }

    /** An honest empty/permission/error panel: says what is missing and offers the one action that fixes it. */
    fun notice(title: String, body: String, actionLabel: String? = null, action: (() -> Unit)? = null): View = glassCard().apply {
        addView(text(title, 16f, bold = true))
        addView(text(body, 13f, muted).apply { setPadding(0, dp(4), 0, dp(8)) })
        if (actionLabel != null && action != null) addView(button(actionLabel, true, action))
    }
}
