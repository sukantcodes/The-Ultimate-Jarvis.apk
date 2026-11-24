package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.glass.CrystalTheme
import com.jarvis.assistant.util.ThemeManager

/**
 * Base for every Settings category screen. Each category (AI, Voice, Security, ...) is its own Activity with its own
 * standalone layout; this class holds only what they all share: theme, backdrop, system-bar insets and small helpers.
 */
abstract class SettingsScreenActivity : AppCompatActivity(), com.jarvis.assistant.util.JarvisHandlesInsets {

    protected val preferences by lazy { JarvisApp.instance.preferences }

    /** True while switches are being set from stored values, so their listeners do not fire. */
    protected var syncingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // Picks up a background chosen on another screen.
        com.jarvis.assistant.ui.glass.GlassBackdropView.refreshAll()
    }

    /** Paints the shared look: back button, backdrop, glass card, switches, icon tiles and system-bar padding. */
    protected fun applyChrome(root: View, card: ViewGroup, @ColorRes accentRes: Int, back: View, title: View) {
        val themeAccent = ThemeManager.getThemeAccentColor()
        back.setOnClickListener { finish() }
        ViewCompat.setAccessibilityHeading(title, true)
        // The card is a real lens-glass pane (LiquidGlassLayout over GlassBackdropView); it must keep no solid fill of its own.
        SettingsDeck.pressFeedback(card)
        SettingsDeck.styleSwitches(root, themeAccent)
        SettingsDeck.styleIcons(root, themeAccent, preferences.settingsIconShape)
        // Insets pad the content column only, so the backdrop still reaches the very edges of the screen.
        val column = root.findViewById<View>(R.id.contentColumn) ?: root
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            column.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        intent.getStringExtra(EXTRA_FOCUS_ROW)?.let { name -> root.post { focusRow(root, name) } }
    }

    /** Scrolls to a row found through Settings search and pulses it, so the person sees exactly where it is. */
    private fun focusRow(root: View, idName: String) {
        val id = resources.getIdentifier(idName, "id", packageName)
        val row = if (id != 0) root.findViewById<View>(id) else null
        if (row == null) return
        val scroll = root.findViewById<android.widget.ScrollView>(R.id.screenScroll)
        if (scroll != null) {
            var y = row.top
            var p = row.parent
            while (p is View && p !== scroll) { y += (p as View).top; p = (p as View).parent }
            scroll.postDelayed({ scroll.smoothScrollTo(0, (y - 140).coerceAtLeast(0)) }, 120L)
        }
        val pulse = GradientDrawable().apply {
            cornerRadius = 18f * resources.displayMetrics.density
            setColor(ColorUtils.setAlphaComponent(ThemeManager.getThemeAccentColor(), 255))
        }
        row.foreground = pulse
        android.animation.ValueAnimator.ofInt(150, 0, 150, 0).apply {
            duration = 1500L
            startDelay = 350L
            addUpdateListener { pulse.alpha = it.animatedValue as Int }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { row.foreground = null }
            })
            start()
        }
    }

    /** Repaints the icon tiles, used after the icon shape changes. */
    fun repaintIcons(root: View) {
        SettingsDeck.styleIcons(root, ThemeManager.getThemeAccentColor(), preferences.settingsIconShape)
    }

    /** Makes the whole row toggle its switch, and reports only changes made by the user. */
    protected fun bindSwitchRow(row: View, toggle: CompoundButton, onChanged: (Boolean) -> Unit) {
        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (!syncingUi) onChanged(isChecked)
        }
        row.setOnClickListener { toggle.toggle() }
    }

    protected fun open(target: Class<*>) {
        // In See-through mode the next screen shows this one underneath its glass, so take the picture first.
        com.jarvis.assistant.ui.glass.ScreenSnapshot.capture(this) { startActivity(Intent(this, target)) }
    }

    protected fun toast(message: String, long: Boolean = false) {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    protected fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("Unable to open link")
        }
    }

    companion object {
        /** Intent extra naming the id of a row (for example "rowVoiceGender") to scroll to and highlight. */
        const val EXTRA_FOCUS_ROW = "focus_row"
    }
}
