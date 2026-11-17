package com.jarvis.assistant.ui.glass

import android.view.Menu
import android.view.View
import com.google.android.material.navigation.NavigationView
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

/**
 * The navigation drawer in the app's liquid glass style: every menu icon sits in its own 3D glass bubble (it sinks when
 * pressed and glows when its row is selected), the header is a glass card with the logo in a bubble, and the selected row
 * is a glass pill (see bg_drawer_item_glass).
 */
object DrawerGlass {

    fun apply(nav: NavigationView) {
        val d = nav.resources.displayMetrics.density
        if (GlassMode.plain()) {
            // Original simple drawer: plain rows, standard icons, flat header.
            nav.itemBackground = null
            nav.itemIconSize = (24f * d).toInt()
            nav.itemIconPadding = (12f * d).toInt()
            nav.itemHorizontalPadding = (16f * d).toInt()
            val plainHeader = nav.getHeaderView(0) ?: return
            plainHeader.setBackgroundColor(GlassMode.attrColor(nav.context, R.attr.themeSurface, android.graphics.Color.TRANSPARENT))
            (plainHeader.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.setMargins(0, 0, 0, 0); plainHeader.layoutParams = it
            }
            plainHeader.findViewById<View>(R.id.ivDrawerLogo)?.let { logo ->
                logo.setPadding(0, 0, 0, 0)
                logo.layoutParams = logo.layoutParams.also { lp -> lp.width = (42f * d).toInt(); lp.height = (42f * d).toInt() }
            }
            return
        }
        val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { android.graphics.Color.WHITE }
        nav.itemIconTintList = null // the bubble carries its own tint; a tint list would colour the whole bubble
        wrap(nav, nav.menu, (40f * d).toInt(), accent)
        val header = nav.getHeaderView(0) ?: return
        header.post { LiquidTouch.decorate(header, 28f * d, 0.6f, accent) }
        header.findViewById<View>(R.id.ivDrawerLogo)?.let {
            DockPods.install(it, heightDp = 56f, circle = true, interactive = false, group = "drawer")
        }
    }

    private fun wrap(nav: NavigationView, menu: Menu, sizePx: Int, accent: Int) {
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val icon = item.icon
            if (icon != null && icon !is GlassIconDrawable) item.icon = GlassIconDrawable(nav, icon, sizePx, accent)
            item.subMenu?.let { wrap(nav, it, sizePx, accent) }
        }
    }
}
