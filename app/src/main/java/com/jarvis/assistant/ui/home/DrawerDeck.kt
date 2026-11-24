package com.jarvis.assistant.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import com.google.android.material.navigation.NavigationView
import com.jarvis.assistant.R
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.udn.UdnState
import com.jarvis.assistant.util.ThemeManager

/**
 * The navigation drawer's look. A static themed canvas (base colour plus soft accent glows, drawn once), an identity card
 * with an initial ring and the live Device Network state, and a coloured squircle badge behind every menu icon. Colours are
 * the active theme's accent rotated around the colour wheel; nothing animates, so nothing can flicker.
 * It restyles the existing menu and never changes what an item does.
 */
object DrawerDeck {

    private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density
    private fun px(ctx: Context, v: Float) = (dp(ctx, v) + 0.5f).toInt().coerceAtLeast(1)
    private fun alpha(c: Int, a: Float) = ColorUtils.setAlphaComponent(c, (a * 255f).toInt().coerceIn(0, 255))

    private fun themeColor(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private fun rotate(color: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[0] = (hsv[0] + degrees) % 360f
        hsv[1] = hsv[1].coerceIn(0.5f, 0.9f)
        hsv[2] = hsv[2].coerceIn(0.75f, if (ThemeManager.isCurrentThemeLight()) 0.85f else 1f)
        return Color.HSVToColor(hsv)
    }

    private val toneOffsets = mapOf(
        R.id.drawerHome to 0f, R.id.drawerChat to 50f,
        R.id.drawerJarvisHome to 110f, R.id.drawerTerminal to 170f, R.id.drawerAutomation to 230f,
        R.id.drawerMemories to 290f, R.id.drawerScreenShare to 25f,
        R.id.drawerDeviceNetwork to 80f,
        R.id.drawerSettings to 140f, R.id.drawerUpdates to 200f,
        R.id.drawerAccount to 260f
    )

    private val INK = 0xFF111827.toInt()
    private val INK_MUTED = 0xFF5B6576.toInt()

    /** The accent made dark enough to read on white glass. */
    private fun ink(color: Int, value: Float = 0.62f): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceIn(0.55f, 1f)
        hsv[2] = hsv[2].coerceAtMost(value)
        return Color.HSVToColor(hsv)
    }

    /**
     * Paints the drawer as white glass. The lens glass itself (frozen, blurred screen picture, same glass as Settings, no zoom)
     * is the pane behind the menu; this adds the white frost over it, the dark text and the icon badges.
     */
    fun apply(nav: NavigationView) {
        val ctx = nav.context
        val accent = ThemeManager.getThemeAccentColor()
        val r = dp(ctx, 28f)
        val radii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)

        nav.background = LayerDrawable(arrayOf(
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD0FFFFFF.toInt(), 0xB8FFFFFF.toInt())).apply { cornerRadii = radii },
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(alpha(accent, 0.10f), alpha(accent, 0f))).apply { cornerRadii = radii },
            GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadii = radii; setStroke(px(ctx, 1f), 0x1A000000) }
        ))

        nav.itemTextColor = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ink(accent, 0.55f), INK)
        )
        nav.itemIconTintList = null // each icon carries its own colour
        nav.setItemIconSize(px(ctx, 40f))

        flatten(nav).forEach { item ->
            val icon = item.icon ?: return@forEach
            item.icon = badged(ctx, icon, rotate(accent, toneOffsets[item.itemId] ?: 0f))
        }

        // identity card
        nav.getHeaderView(0)?.findViewById<View>(R.id.drawerHeaderCard)?.let { card ->
            val cr = dp(ctx, 26f)
            card.background = LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(0xA6FFFFFF.toInt()); cornerRadius = cr },
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(alpha(accent, 0.16f), alpha(accent, 0.03f))).apply { cornerRadius = cr },
                GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = cr; setStroke(px(ctx, 1f), alpha(accent, 0.30f)) }
            ))
        }
    }

    private fun flatten(nav: NavigationView): List<android.view.MenuItem> {
        val out = ArrayList<android.view.MenuItem>()
        fun walk(menu: android.view.Menu) {
            for (i in 0 until menu.size()) {
                val it = menu.getItem(i)
                if (it.hasSubMenu()) walk(it.subMenu!!) else out += it
            }
        }
        walk(nav.menu)
        return out
    }

    private fun badged(ctx: Context, icon: Drawable, tone: Int): Drawable {
        val size = px(ctx, 40f)
        val badge = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(alpha(tone, 0.26f), alpha(tone, 0.08f))).apply {
            cornerRadius = dp(ctx, 13f); setStroke(px(ctx, 1f), alpha(tone, 0.40f)); setSize(size, size)
        }
        val tinted = DrawableCompat.wrap(icon.constantState?.newDrawable()?.mutate() ?: icon.mutate())
        DrawableCompat.setTint(tinted, ink(tone, 0.72f))
        val inset = InsetDrawable(tinted, px(ctx, 10f))
        return LayerDrawable(arrayOf(badge, inset)).apply { setBounds(0, 0, size, size) }
    }

    /** Fills the identity card from the account and the live network state. Safe to call repeatedly. */
    fun bindHeader(nav: NavigationView, udn: UdnState, userName: String) {
        val head = nav.getHeaderView(0) ?: return
        val ctx = nav.context
        val accent = ThemeManager.getThemeAccentColor()
        val email = AuthManager.email
        val signedIn = AuthManager.isSignedIn
        val display = userName.ifBlank { email?.substringBefore('@') ?: "J.A.R.V.I.S." }
        head.findViewById<TextView>(R.id.tvDrawerName)?.setTextColor(INK)
        head.findViewById<TextView>(R.id.tvDrawerEmail)?.setTextColor(INK_MUTED)
        head.findViewById<TextView>(R.id.tvDrawerAvatar)?.setTextColor(INK)
        head.findViewById<TextView>(R.id.tvDrawerName)?.text = if (signedIn || userName.isNotBlank()) display else "J.A.R.V.I.S."
        head.findViewById<TextView>(R.id.tvDrawerEmail)?.text = email ?: "Guest · personal AI assistant"
        head.findViewById<TextView>(R.id.tvDrawerAvatar)?.let { av ->
            av.text = (display.firstOrNull() ?: 'J').uppercaseChar().toString()
            av.background = LayerDrawable(arrayOf(
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(alpha(accent, 0.55f), alpha(rotate(accent, 60f), 0.35f))).apply { shape = GradientDrawable.OVAL },
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(px(ctx, 1.5f), alpha(accent, 0.8f)) }
            ))
        }
        val online = udn.devices.count { it.isAuthorized && it.online }
        val (label, tone) = when {
            !udn.configured || !signedIn -> "Sign in for Device Network" to themeColor(ctx, R.attr.themeTextMuted, accent)
            !udn.enrolled -> "Join the Device Network" to themeColor(ctx, R.attr.themeWarning, accent)
            !udn.authorized -> "Joining network…" to themeColor(ctx, R.attr.themeWarning, accent)
            else -> (if (udn.isParent) "Parent${udn.slot?.let { " $it" } ?: ""}" else "Child") + " · $online online" to themeColor(ctx, R.attr.themeSuccess, accent)
        }
        head.findViewById<TextView>(R.id.tvDrawerNetwork)?.apply { text = label; setTextColor(ink(tone, 0.5f)) }
        head.findViewById<View>(R.id.drawerNetworkDot)?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tone) }
        head.findViewById<View>(R.id.drawerNetworkChip)?.background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 100f); setColor(alpha(tone, 0.14f)); setStroke(px(ctx, 1f), alpha(tone, 0.4f))
        }
    }
}
