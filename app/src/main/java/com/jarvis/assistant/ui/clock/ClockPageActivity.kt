package com.jarvis.assistant.ui.clock

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.R
import com.jarvis.assistant.clock.AlarmNotifications
import com.jarvis.assistant.clock.ClockEngine
import com.jarvis.assistant.clock.ClockError
import com.jarvis.assistant.clock.ClockFormat
import com.jarvis.assistant.clock.ClockMessages
import com.jarvis.assistant.clock.ClockResult
import com.jarvis.assistant.clock.ClockServices
import com.jarvis.assistant.util.ThemeManager

/**
 * The look and shared behaviour of every Clock page: JARVIS theme and background, a heading with a back arrow, cards, rows,
 * permission notices, and a refresh whenever the alarms or settings change (from this screen or from the AI).
 */
abstract class ClockPageActivity : AppCompatActivity() {

    protected val engine: ClockEngine by lazy { ClockServices.engine(applicationContext) }

    private lateinit var content: LinearLayout
    private val changeListener: () -> Unit = { if (::content.isInitialized) content.post { if (!isFinishing && !isDestroyed) render() } }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) openAppNotificationSettings()
        if (::content.isInitialized) render()
    }

    @get:androidx.annotation.StringRes
    protected abstract val titleRes: Int

    @get:androidx.annotation.StringRes
    protected open val subtitleRes: Int = 0

    protected abstract fun buildContent(container: LinearLayout)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        AlarmNotifications.ensureChannels(this)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        ThemeManager.applyDynamicBackground(scroll)
        render()
    }

    override fun onStart() {
        super.onStart()
        engine.addChangeListener(changeListener)
        render()
    }

    override fun onStop() {
        engine.removeChangeListener(changeListener)
        super.onStop()
    }

    protected fun render() {
        content.removeAllViews()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = TextView(this).apply {
            text = "‹"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt()))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColor(R.attr.themeChipBg, 0x22FFFFFF))
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
            contentDescription = getString(R.string.clock_back)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        top.addView(back)
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(getString(titleRes), 25f, primary = true, bold = true))
            if (subtitleRes != 0) addView(text(getString(subtitleRes), 12f, primary = false).apply { setPadding(0, dp(2), 0, 0) })
        })
        content.addView(top)
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
        buildContent(content)
    }

    // ---- Notices about things Android needs ----------------------------------------------------

    /** Adds a card explaining anything that would stop an alarm from showing or being heard. Adds nothing when all is fine. */
    protected fun addNotices(container: LinearLayout) {
        val notices = ArrayList<Triple<String, String, () -> Unit>>()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            notices += Triple(getString(R.string.clock_perm_notifications), getString(R.string.clock_perm_allow)) {
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else openAppNotificationSettings()
            }
        } else if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(AlarmNotifications.CHANNEL_RING)?.importance == NotificationManager.IMPORTANCE_NONE) {
            notices += Triple(getString(R.string.clock_perm_channel), getString(R.string.clock_perm_open_settings)) {
                startSafely(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, AlarmNotifications.CHANNEL_RING))
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            notices += Triple(getString(R.string.clock_perm_fullscreen), getString(R.string.clock_perm_open_settings)) {
                startSafely(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            notices += Triple(getString(R.string.clock_perm_alarm_volume), getString(R.string.clock_perm_open_settings)) {
                startSafely(Intent(Settings.ACTION_SOUND_SETTINGS))
            }
        }
        if (notices.isEmpty()) return

        val card = card()
        card.addView(text(getString(R.string.clock_perm_title), 16f, primary = true, bold = true))
        notices.forEachIndexed { index, (message, action, onClick) ->
            if (index > 0) card.addView(divider())
            card.addView(text(message, 13f, primary = false).apply { setPadding(0, dp(8), 0, dp(6)) })
            card.addView(button(action, onClick).apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
        }
        container.addView(card)
    }

    private fun openAppNotificationSettings() {
        startSafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    protected fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.clock_cannot_open_settings, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Showing results -----------------------------------------------------------------------

    /** Tells the person why something did not happen. Never pretends it worked. */
    protected fun showFailure(failure: ClockResult.Failed) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clock_could_not_title)
            .setMessage(ClockMessages.forFailure(this, failure))
            .setPositiveButton(R.string.clock_ok, null)
        if (failure.error == ClockError.EXACT_ALARM_DENIED && Build.VERSION.SDK_INT >= 31) {
            builder.setNeutralButton(R.string.clock_perm_open_settings) { _, _ ->
                startSafely(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        builder.show()
    }

    /** "Alarm set for Tomorrow 06:00" using the time Android was really given. */
    protected fun toastScheduled(nextRingMillis: Long?) {
        val text = if (nextRingMillis != null) {
            getString(R.string.clock_alarm_set_for, ClockFormat.whenText(this, nextRingMillis, engine.settings()))
        } else getString(R.string.clock_alarm_saved_off)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    protected fun chooseOne(titleRes: Int, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton(R.string.clock_cancel, null)
            .show()
    }

    // ---- View helpers --------------------------------------------------------------------------

    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    protected fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    protected fun text(value: String, sizeSp: Float, primary: Boolean, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(themeColor(if (primary) R.attr.themeTextPrimary else R.attr.themeTextMuted, if (primary) 0xFFFFFFFF.toInt() else 0xFF9AA4B2.toInt()))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    protected fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(themeColor(R.attr.themeCardBg, 0x33FFFFFF))
            setStroke(dp(1), themeColor(R.attr.themeCardBorder, 0x33FFFFFF))
        }
        elevation = dp(3).toFloat()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
    }

    protected fun divider() = View(this).apply {
        setBackgroundColor(themeColor(R.attr.themeCardBorder, 0x22FFFFFF))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6) }
    }

    protected fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt()))
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minimumHeight = dp(40)
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(themeColor(R.attr.themeChipBg, 0x22FFFFFF))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A tappable row: title and a value or hint underneath, with a chevron. */
    protected fun actionRow(title: String, subtitle: String?, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(text(title, 16f, primary = true, bold = true))
        if (!subtitle.isNullOrBlank()) info.addView(text(subtitle, 13f, primary = false).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(info)
        row.addView(text("›", 22f, primary = false))
        return row
    }

    /** A row with an on/off switch. The switch is only a view of the stored value: [onChange] stores the new one. */
    protected fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(text(title, 16f, primary = true, bold = true))
        if (!subtitle.isNullOrBlank()) info.addView(text(subtitle, 13f, primary = false).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(info)
        val toggle = SwitchCompat(this).apply {
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(toggle)
        row.setOnClickListener { toggle.toggle() }
        return row
    }

    protected fun sectionTitle(value: String, subtitle: String? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(value, 18f, primary = true, bold = true))
        if (!subtitle.isNullOrBlank()) addView(text(subtitle, 12f, primary = false).apply { setPadding(0, dp(2), 0, dp(8)) })
    }
}
