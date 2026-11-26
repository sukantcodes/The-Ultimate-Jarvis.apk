package com.jarvis.assistant.ui.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.glass.LiquidGlassLayout

/**
 * The navigation drawer's options. Every option has its own layout file (drawer_item_*.xml) and is its own glass pane,
 * stacked on the drawer's base glass; this class puts them in order under the identity card, handles taps and keeps the
 * selected option marked. It replaces the stock list menu, so each option can have its own glass and design.
 */
class DrawerMenu(private val container: LinearLayout) {

    private class Entry(val id: Int, @LayoutRes val layout: Int, val checkable: Boolean)

    private val ctx = container.context
    private val views = LinkedHashMap<Int, View>()
    private val checkable = HashSet<Int>()
    private var checkedId = 0

    /** Called with the option's id when it is tapped; return value is unused, the drawer closes in the caller. */
    var onItemClick: ((Int) -> Unit)? = null

    /** The identity card (its own glass pane) at the top. */
    lateinit var header: View
        private set

    private fun dpf(v: Float) = v * ctx.resources.displayMetrics.density

    /** A light frosted backing so the dark ink is readable whatever the glass behind it shows (dark themes included). */
    private fun backing(radiusDp: Float, alpha: Int) = GradientDrawable().apply {
        setColor(Color.argb(alpha, 255, 255, 255))
        cornerRadius = dpf(radiusDp)
    }

    fun build() {
        val inflater = LayoutInflater.from(ctx)
        header = inflater.inflate(R.layout.nav_drawer_header, container, false)
        header.findViewById<View>(R.id.drawerHeaderCard)?.background = backing(26f, 190)
        container.addView(header)

        group(inflater, null, listOf(
            Entry(R.id.drawerHome, R.layout.drawer_item_home, true),
            Entry(R.id.drawerChat, R.layout.drawer_item_chat, true)
        ))
        group(inflater, "WORKSPACE", listOf(
            Entry(R.id.drawerJarvisHome, R.layout.drawer_item_jarvis_home, false),
            Entry(R.id.drawerStudyBoard, R.layout.drawer_item_study_board, false),
            Entry(R.id.drawerTerminal, R.layout.drawer_item_terminal, false),
            Entry(R.id.drawerAutomation, R.layout.drawer_item_automation, false),
            Entry(R.id.drawerMemories, R.layout.drawer_item_memories, false),
            Entry(R.id.drawerScreenShare, R.layout.drawer_item_screen_share, false)
        ))
        group(inflater, "NETWORK", listOf(
            Entry(R.id.drawerDeviceNetwork, R.layout.drawer_item_device_network, false)
        ))
        group(inflater, "SYSTEM", listOf(
            Entry(R.id.drawerSettings, R.layout.drawer_item_settings, true),
            Entry(R.id.drawerUpdates, R.layout.drawer_item_updates, true)
        ))
        group(inflater, "ACCOUNT", listOf(
            Entry(R.id.drawerAccount, R.layout.drawer_item_account, false)
        ))
        restyle()
    }

    private fun group(inflater: LayoutInflater, title: String?, entries: List<Entry>) {
        if (title != null) {
            val heading = inflater.inflate(R.layout.drawer_section_title, container, false) as TextView
            heading.text = title
            heading.setTextColor(0xFF3A4252.toInt())
            heading.background = backing(12f, 170)
            heading.setPadding(dpf(10f).toInt(), dpf(3f).toInt(), dpf(10f).toInt(), dpf(3f).toInt())
            heading.layoutParams = (heading.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT }
            container.addView(heading)
        }
        for (e in entries) {
            val v = inflater.inflate(e.layout, container, false)
            // Glass panes lift a little off the page by default; on the drawer's glass they sit flat, so none casts a shadow
            // over its neighbours.
            v.elevation = 0f
            // The row's content sits on the frosted backing; the glass pane shows around and through its edges.
            ((v as? android.view.ViewGroup)?.let { g -> if (g.childCount > 1) g.getChildAt(1) else null })?.background = backing(22f, 178)
            v.contentDescription = v.findViewById<TextView>(R.id.drawerItemTitle)?.text
            v.setOnClickListener { onItemClick?.invoke(e.id) }
            container.addView(v)
            views[e.id] = v
            if (e.checkable) checkable += e.id
        }
        header.elevation = 0f
    }

    /** Marks [id] as the selected option (only Home, Chat, Settings and Updates can be selected). */
    fun setChecked(id: Int) {
        checkedId = id
        restyle()
    }

    /** Changes the text of one option, e.g. the account row ("Sign in" / "Log out · email"). */
    fun setTitle(id: Int, text: CharSequence) {
        views[id]?.let {
            it.findViewById<TextView>(R.id.drawerItemTitle)?.text = text
            it.contentDescription = text
        }
    }

    /** Repaints every option in the current theme colours (also after a theme change). */
    fun restyle() {
        views.forEach { (id, v) -> DrawerDeck.styleItem(v, id, id in checkable && id == checkedId) }
    }

    /** Asks every glass pane to redraw, so they pick up a freshly taken screen picture. */
    fun invalidateGlass() {
        (header as? LiquidGlassLayout)?.invalidate()
        views.values.forEach { it.invalidate() }
    }
}
