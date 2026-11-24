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
abstract class SettingsScreenActivity : AppCompatActivity() {

    protected val preferences by lazy { JarvisApp.instance.preferences }

    /** True while switches are being set from stored values, so their listeners do not fire. */
    protected var syncingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
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
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            column.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
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
        startActivity(Intent(this, target))
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
}
