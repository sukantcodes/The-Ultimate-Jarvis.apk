package com.jarvis.assistant.ui.chat

import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import com.jarvis.assistant.tools.server.Tunnel
import com.jarvis.assistant.tools.server.TunnelState
import com.jarvis.assistant.util.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live status of the public links above the composer: one card per link with a status dot, the address, and
 * Copy, Share and Stop. It shows nothing while there are no links. All colours come from the active theme.
 */
class PortForwardStripView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    /** Called with a link's id when the person presses Stop. */
    var onStop: (String) -> Unit = {}

    private class Row(val root: LinearLayout, val dot: View, val title: TextView, val status: TextView, val url: TextView,
                      val copy: TextView, val share: TextView, val stop: TextView, var pulse: ObjectAnimator? = null)

    private val rows = LinkedHashMap<String, Row>()

    init {
        orientation = VERTICAL
        visibility = GONE
        val h = dp(12)
        setPadding(h, 0, h, dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) tv.data else fallback
    }

    fun render(list: List<Tunnel>) {
        val ids = list.map { it.id }.toSet()
        rows.entries.removeAll { (id, r) -> if (id in ids) false else { r.pulse?.cancel(); removeView(r.root); true } }
        list.forEach { t -> bind(rows.getOrPut(t.id) { makeRow(t.id).also { addView(it.root) } }, t) }
        visibility = if (rows.isEmpty()) GONE else VISIBLE
    }

    private fun button(label: String): TextView = TextView(context).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(48); minWidth = dp(64); setPadding(dp(12), 0, dp(12), 0)
        isClickable = true; isFocusable = true
    }

    private fun makeRow(id: String): Row {
        val root = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(14), dp(10), dp(8), dp(2))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = View(context).apply { layoutParams = LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) } }
        val title = TextView(context).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val status = TextView(context).apply { textSize = 12f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val col = LinearLayout(context).apply { orientation = VERTICAL; layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); addView(title); addView(status) }
        head.addView(dot); head.addView(col)
        val url = TextView(context).apply {
            textSize = 13f; typeface = Typeface.MONOSPACE; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextIsSelectable(false); setPadding(dp(20), dp(4), dp(8), 0)
        }
        val copy = button(context.getString(R.string.port_forward_copy))
        val share = button(context.getString(R.string.port_forward_share))
        val stop = button(context.getString(R.string.port_forward_stop))
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.END; addView(copy); addView(share); addView(stop) }
        root.addView(head); root.addView(url); root.addView(actions)
        return Row(root, dot, title, status, url, copy, share, stop)
    }

    private fun bind(r: Row, t: Tunnel) {
        val accent = ThemeManager.getThemeAccentColor()
        val ok = ThemeManager.getThemeSuccessColor()
        val warn = ThemeManager.getThemeWarningColor()
        val textPrimary = themeColor(R.attr.themeTextPrimary, accent)
        val textSecondary = themeColor(R.attr.themeTextSecondary, textPrimary)
        r.root.background = ThemeManager.createDynamicCardDrawable(ColorUtils.setAlphaComponent(accent, 110), ColorUtils.setAlphaComponent(accent, 28), 16f, 1f, context)
        r.title.setTextColor(textPrimary); r.status.setTextColor(textSecondary); r.url.setTextColor(accent)
        r.title.text = context.getString(R.string.port_forward_title, t.label, t.port)

        val s = t.state
        val (dotColor, pulsing, statusText) = when (s) {
            is TunnelState.Live -> Triple(ok, false, context.getString(
                if (s.verified) R.string.port_forward_live else R.string.port_forward_live_unconfirmed, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t.expiresAt))))
            is TunnelState.Reconnecting -> Triple(warn, true, context.getString(R.string.port_forward_reconnecting))
            is TunnelState.Connecting -> Triple(warn, true, context.getString(R.string.port_forward_connecting))
            is TunnelState.Preparing -> Triple(warn, true, s.detail)
            is TunnelState.Stopped -> Triple(textSecondary, false, context.getString(R.string.port_forward_stopped, s.reason.text))
            is TunnelState.Failed -> Triple(warn, false, s.message)
        }
        r.dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
        r.status.text = if (t.note != null && t.isActive) "$statusText ${t.note}" else statusText
        setPulse(r, pulsing)

        val link = t.publicUrl
        r.url.visibility = if (link != null) VISIBLE else GONE
        r.url.text = link.orEmpty()
        r.copy.visibility = if (link != null) VISIBLE else GONE
        r.share.visibility = if (link != null) VISIBLE else GONE
        r.stop.visibility = if (t.isActive) VISIBLE else GONE
        for (b in listOf(r.copy, r.share)) b.setTextColor(accent)
        r.stop.setTextColor(warn)
        r.copy.setOnClickListener { link?.let { copyLink(it) } }
        r.share.setOnClickListener { link?.let { shareLink(it, t) } }
        r.stop.setOnClickListener { r.stop.isEnabled = false; onStop(t.id) }
        r.stop.isEnabled = t.isActive
    }

    private fun setPulse(r: Row, on: Boolean) {
        if (on && r.pulse == null) {
            r.pulse = ObjectAnimator.ofFloat(r.dot, View.ALPHA, 1f, 0.25f).apply {
                duration = 700; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator(); start()
            }
        } else if (!on && r.pulse != null) { r.pulse?.cancel(); r.pulse = null; r.dot.alpha = 1f }
    }

    private fun copyLink(url: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Public link", url))
        Toast.makeText(context, R.string.port_forward_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLink(url: String, t: Tunnel) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, t.label).putExtra(Intent.EXTRA_TEXT, url)
        runCatching { context.startActivity(Intent.createChooser(i, context.getString(R.string.port_forward_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, R.string.port_forward_share_failed, Toast.LENGTH_SHORT).show() }
    }

    override fun onDetachedFromWindow() {
        rows.values.forEach { it.pulse?.cancel(); it.pulse = null }
        super.onDetachedFromWindow()
    }
}
