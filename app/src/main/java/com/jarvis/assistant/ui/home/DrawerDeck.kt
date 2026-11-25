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
     * Styles one drawer option (its own glass pane, see DrawerMenu): a coloured squircle badge behind the icon, the dark
     * ink on white glass, and when [checked] an accent wash, accent title and a small dot. Never changes what it does.
     */
    fun styleItem(root: View, itemId: Int, checked: Boolean) {
        val ctx = root.context
        val accent = ThemeManager.getThemeAccentColor()
        val tone = rotate(accent, toneOffsets[itemId] ?: 0f)
        val box = root.findViewById<View>(R.id.drawerItemIconBox)
        box?.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(alpha(tone, 0.26f), alpha(tone, 0.08f))).apply {
            cornerRadius = dp(ctx, 13f); setStroke(px(ctx, 1f), alpha(tone, 0.40f))
        }
        root.findViewById<android.widget.ImageView>(R.id.drawerItemIcon)?.imageTintList = ColorStateList.valueOf(ink(tone, 0.72f))
        root.findViewById<TextView>(R.id.drawerItemTitle)?.setTextColor(if (checked) ink(accent, 0.55f) else INK)
        root.findViewById<View>(R.id.drawerItemWash)?.apply {
            visibility = if (checked) View.VISIBLE else View.GONE
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(alpha(accent, 0.20f), alpha(accent, 0.06f))).apply {
                cornerRadius = dp(ctx, 22f); setStroke(px(ctx, 1f), alpha(accent, 0.55f))
            }
        }
        // The row's content sits on a light frosted backing (see DrawerMenu); the selected option's backing carries the accent.
        (root as? android.view.ViewGroup)?.takeIf { it.childCount > 1 }?.getChildAt(1)?.background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 22f)
            if (checked) {
                setColor(ColorUtils.setAlphaComponent(ColorUtils.blendARGB(Color.WHITE, accent, 0.16f), 215))
                setStroke(px(ctx, 1f), alpha(accent, 0.6f))
            } else {
                setColor(Color.argb(178, 255, 255, 255))
            }
        }
        root.findViewById<View>(R.id.drawerItemCheck)?.apply {
            visibility = if (checked) View.VISIBLE else View.GONE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink(accent, 0.6f)) }
        }
    }

    /** Fills the identity card from the account and the live network state. Safe to call repeatedly. */
    fun bindHeader(head: View, udn: UdnState, userName: String) {
        val ctx = head.context
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
