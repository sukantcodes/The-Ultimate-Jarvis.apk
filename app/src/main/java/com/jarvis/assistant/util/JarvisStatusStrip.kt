package com.jarvis.assistant.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import com.jarvis.assistant.R

/** Marker for activities that draw [JarvisStatusStrip] themselves, so the generic inset padding skips the top edge. */
interface JarvisStatusStripHost

/**
 * Jarvis's own status strip. The phone's system status bar (clock, battery, signal) is hidden inside the app, so this strip
 * takes over that top area and shows Jarvis' state, the time and the battery. Its height follows the camera cut-out, and it
 * never goes under the cut-out sides, so nothing is hidden on any phone shape.
 */
class JarvisStatusStrip @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val label = TextView(context)
    private val clock = TextClock(context)
    private val battery = TextView(context)
    private var minStripPx = dp(26f)

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { showBattery(i) }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14f), 0, dp(14f), 0)
        val color = themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt())
        val accent = themeColor(R.attr.themeAccent, color)

        label.apply {
            text = "● JARVIS ONLINE"; setTextColor(accent); maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = com.jarvis.assistant.util.JarvisFonts.bold; letterSpacing = 0.08f
        }
        clock.apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; setTextColor(color); maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = com.jarvis.assistant.util.JarvisFonts.bold
        }
        battery.apply {
            setTextColor(color); maxLines = 1; setPadding(dp(10f), 0, 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(battery, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        minimumHeight = minStripPx
    }

    fun setStatusText(text: String) { label.text = "● " + text.uppercase() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showBattery(context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Throwable) { }
        super.onDetachedFromWindow()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cutout = insets.displayCutout
        val top = cutout?.safeInsetTop ?: 0
        val left = cutout?.safeInsetLeft ?: 0
        val right = cutout?.safeInsetRight ?: 0
        // Taller than the camera hole, so the strip fills that band; text sits at the sides of the hole.
        minimumHeight = maxOf(minStripPx, top)
        setPadding(dp(14f) + left, 0, dp(14f) + right, 0)
        return insets
    }

    private fun showBattery(i: Intent?) {
        i ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val charging = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        battery.text = (if (charging) "⚡" else "") + (level * 100 / scale) + "%"
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }
}
