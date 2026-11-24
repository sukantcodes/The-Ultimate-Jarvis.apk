package com.jarvis.assistant.ui.settings

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.R
import com.jarvis.assistant.updates.OtaFeed
import com.jarvis.assistant.updates.OtaFormat
import com.jarvis.assistant.updates.OtaManager
import com.jarvis.assistant.updates.OtaPromptPolicy
import com.jarvis.assistant.updates.OtaRelease
import com.jarvis.assistant.updates.OtaScheduler
import com.jarvis.assistant.updates.OtaSettings
import com.jarvis.assistant.updates.OtaState
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Settings → Privacy & About → Software Updates. Shows the live state of [OtaManager] (check, download progress and
 * speed, verification, install), the release notes of the offered build, the updater settings, and the update history.
 * Everything is drawn from theme attributes so it follows the JARVIS theme like the rest of the app.
 */
class SoftwareUpdatesActivity : AppCompatActivity() {

    private lateinit var settings: OtaSettings

    private lateinit var versionLine: TextView
    private lateinit var checkedLine: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressInfo: TextView
    private lateinit var primaryButton: TextView
    private lateinit var secondaryButton: TextView
    private lateinit var notesCard: LinearLayout
    private lateinit var notesHeading: TextView
    private lateinit var notesMeta: TextView
    private lateinit var notesList: LinearLayout
    private lateinit var historyList: LinearLayout

    private var lastState: OtaState = OtaState.Idle

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        settings = OtaSettings(this)
        OtaManager.restoreAsync(applicationContext)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        ThemeManager.applyDynamicBackground(scroll)

        buildHeader(content)
        buildStatusCard(content)
        buildNotesCard(content)
        buildSettingsCard(content)
        buildHistoryCard(content)
    }

    override fun onStart() {
        super.onStart()
        OtaManager.setVisible(OtaManager.SETTINGS_SCREEN, true)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { OtaManager.state.collect { renderState(it) } }
                launch { OtaManager.feed.collect { renderFeed(it) } }
            }
        }
        if (settings.autoCheck) {
            lifecycleScope.launch { OtaManager.check(applicationContext, manual = false) }
        }
    }

    override fun onStop() {
        OtaManager.setVisible(OtaManager.SETTINGS_SCREEN, false)
        super.onStop()
    }

    // ---- Sections -----------------------------------------------------------------------------

    private fun buildHeader(content: LinearLayout) {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = TextView(this).apply {
            text = "‹"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(themeColor(R.attr.themeTextPrimary))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColor(R.attr.themeChipBg))
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
            contentDescription = getString(R.string.ota_back)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        top.addView(back)
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(getString(R.string.ota_title), 25f, primary = true, bold = true))
            addView(text(getString(R.string.ota_subtitle), 12f, primary = false).apply { setPadding(0, dp(2), 0, 0) })
        })
        content.addView(top)
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
    }

    private fun buildStatusCard(content: LinearLayout) {
        val card = card()
        versionLine = text(
            getString(R.string.ota_installed, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), 13f, primary = false
        )
        checkedLine = text("", 12f, primary = false).apply { setPadding(0, dp(2), 0, dp(12)) }
        statusTitle = text("", 20f, primary = true, bold = true)
        statusDetail = text("", 13f, primary = false).apply { setPadding(0, dp(4), 0, 0) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(ThemeManager.getThemeAccentColor())
            indeterminateTintList = ColorStateList.valueOf(ThemeManager.getThemeAccentColor())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(14) }
        }
        progressInfo = text("", 12f, primary = false).apply {
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        primaryButton = button(emphasized = true) { onPrimary() }
        secondaryButton = button(emphasized = false) { onSecondary() }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
            addView(secondaryButton)
            addView(primaryButton)
        }
        card.addView(versionLine)
        card.addView(checkedLine)
        card.addView(statusTitle)
        card.addView(statusDetail)
        card.addView(progress)
        card.addView(progressInfo)
        card.addView(actions)
        content.addView(card)
    }

    private fun buildNotesCard(content: LinearLayout) {
        notesCard = card().apply { visibility = View.GONE }
        notesHeading = text(getString(R.string.ota_notes_title), 18f, primary = true, bold = true)
        notesMeta = text("", 12f, primary = false).apply { setPadding(0, dp(2), 0, dp(8)) }
        notesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        notesCard.addView(notesHeading)
        notesCard.addView(notesMeta)
        notesCard.addView(notesList)
        content.addView(notesCard)
    }

    private fun buildSettingsCard(content: LinearLayout) {
        content.addView(sectionTitle(getString(R.string.ota_settings_title)))
        val card = card()
        card.addView(switchRow(R.string.ota_set_autocheck_title, R.string.ota_set_autocheck_sub, settings.autoCheck) {
            settings.autoCheck = it
            OtaScheduler.ensure(applicationContext)
        })
        card.addView(divider())
        card.addView(switchRow(R.string.ota_set_autodownload_title, R.string.ota_set_autodownload_sub, settings.autoDownload) {
            settings.autoDownload = it
        })
        card.addView(divider())
        card.addView(switchRow(R.string.ota_set_ask_title, R.string.ota_set_ask_sub, settings.askBeforeInstall) {
            settings.askBeforeInstall = it
        })
        card.addView(divider())
        card.addView(switchRow(R.string.ota_set_wifi_title, R.string.ota_set_wifi_sub, settings.wifiOnly) {
            settings.wifiOnly = it
        })
        content.addView(card)
    }

    private fun buildHistoryCard(content: LinearLayout) {
        content.addView(sectionTitle(getString(R.string.ota_history_title)))
        val card = card()
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(historyList)
        content.addView(card)
        renderHistory()
    }

    // ---- Rendering ----------------------------------------------------------------------------

    private fun renderState(state: OtaState) {
        lastState = state
        checkedLine.text = if (settings.lastCheckAt > 0L) {
            getString(
                R.string.ota_last_check,
                DateUtils.getRelativeTimeSpanString(settings.lastCheckAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            )
        } else {
            getString(R.string.ota_never_checked)
        }
        progress.visibility = View.GONE
        progressInfo.visibility = View.GONE
        statusDetail.visibility = View.VISIBLE
        secondaryButton.visibility = View.GONE

        when (state) {
            OtaState.Idle -> {
                statusTitle.text = getString(R.string.ota_status_idle)
                statusDetail.visibility = View.GONE
                setPrimary(R.string.ota_btn_check, enabled = true)
            }
            OtaState.Checking -> {
                statusTitle.text = getString(R.string.ota_status_checking)
                statusDetail.visibility = View.GONE
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
                setPrimary(R.string.ota_btn_check, enabled = false)
            }
            is OtaState.UpToDate -> {
                statusTitle.text = getString(R.string.ota_status_uptodate)
                statusDetail.text = state.message
                statusDetail.visibility = if (state.message.isBlank()) View.GONE else View.VISIBLE
                setPrimary(R.string.ota_btn_check, enabled = true)
            }
            is OtaState.Available -> {
                statusTitle.text = getString(R.string.ota_status_available, state.release.versionName)
                statusDetail.text = OtaFormat.bytes(state.release.sizeBytes)
                setPrimary(R.string.ota_btn_download, enabled = true)
            }
            is OtaState.Downloading -> {
                statusTitle.text = getString(R.string.ota_status_downloading, state.release.versionName)
                statusDetail.text = state.note.orEmpty()
                statusDetail.visibility = if (state.note.isNullOrBlank()) View.GONE else View.VISIBLE
                val percent = OtaFormat.percent(state.bytes, state.total)
                progress.isIndeterminate = false
                progress.progress = percent
                progress.visibility = View.VISIBLE
                val remaining = (state.total - state.bytes).coerceAtLeast(0L)
                val eta = if (state.bytesPerSecond >= 1.0) OtaFormat.eta((remaining / state.bytesPerSecond).toLong()) else ""
                progressInfo.text = if (eta.isEmpty()) {
                    getString(
                        R.string.ota_progress_line, percent, OtaFormat.bytes(state.bytes),
                        OtaFormat.bytes(state.total), OtaFormat.speed(state.bytesPerSecond)
                    )
                } else {
                    getString(
                        R.string.ota_progress_line_eta, percent, OtaFormat.bytes(state.bytes),
                        OtaFormat.bytes(state.total), OtaFormat.speed(state.bytesPerSecond), eta
                    )
                }
                progressInfo.visibility = View.VISIBLE
                setPrimary(R.string.ota_btn_cancel, enabled = true)
            }
            is OtaState.Verifying -> {
                statusTitle.text = getString(R.string.ota_status_verifying)
                statusDetail.text = getString(R.string.ota_verifying_detail)
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
                setPrimary(R.string.ota_btn_install, enabled = false)
            }
            is OtaState.Ready -> {
                statusTitle.text = getString(R.string.ota_status_ready, state.release.versionName)
                statusDetail.text = getString(R.string.ota_ready_detail)
                setPrimary(R.string.ota_btn_install, enabled = true)
            }
            is OtaState.NeedsInstallPermission -> {
                statusTitle.text = getString(R.string.ota_status_permission)
                statusDetail.text = getString(R.string.ota_status_permission_detail)
                setPrimary(
                    if (packageManager.canRequestPackageInstalls()) R.string.ota_btn_install else R.string.ota_btn_permission,
                    enabled = true
                )
            }
            is OtaState.Installing -> {
                statusTitle.text = getString(R.string.ota_status_installing)
                statusDetail.text = getString(R.string.ota_installing_detail)
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
                setPrimary(R.string.ota_btn_install, enabled = false)
            }
            is OtaState.Failed -> {
                statusTitle.text = getString(R.string.ota_status_failed)
                statusDetail.text = state.message
                if (state.canRetry) {
                    setPrimary(R.string.ota_btn_retry, enabled = true)
                    secondaryButton.text = getString(R.string.ota_btn_check)
                    secondaryButton.visibility = View.VISIBLE
                } else {
                    setPrimary(R.string.ota_btn_check, enabled = true)
                }
            }
        }
        renderNotes(state.releaseOrNull() ?: OtaManager.feed.value?.release?.takeIf { it.versionCode > BuildConfig.VERSION_CODE })
        renderHistory()
    }

    private fun renderFeed(feed: OtaFeed?) {
        val offered = feed?.release?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        renderNotes(lastState.releaseOrNull() ?: offered)
        renderHistory()
    }

    private fun renderNotes(release: OtaRelease?) {
        if (release == null) {
            notesCard.visibility = View.GONE
            return
        }
        notesCard.visibility = View.VISIBLE
        notesHeading.text = getString(R.string.ota_notes_title) + " · v" + release.versionName
        notesMeta.text = getString(
            R.string.ota_release_meta, release.versionCode, OtaFormat.bytes(release.sizeBytes),
            OtaFormat.published(release.publishedAt)
        )
        renderWhatsNew(OtaPromptPolicy.conciseNotes(release.releaseNotes, maxLines = 12, maxLineChars = 160))
    }

    /** "What's new" as coloured bullet points: a bold, accent-coloured lead-in (text before a colon) and a light detail. */
    private fun renderWhatsNew(points: List<String>) {
        notesList.removeAllViews()
        if (points.isEmpty()) {
            notesList.addView(text(getString(R.string.ota_notes_empty), 14f, primary = false))
            return
        }
        val accents = intArrayOf(ThemeManager.getThemeAccentColor(), ThemeManager.getThemeSecondaryColor())
        points.forEachIndexed { index, point ->
            val tone = accents[index % accents.size]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(
                TextView(this).apply {
                    text = "●"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(tone)
                    setPadding(0, dp(4), dp(12), 0)
                }
            )
            row.addView(
                TextView(this).apply {
                    text = styledPoint(point, tone)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setLineSpacing(0f, 1.15f)
                    setTextColor(themeColor(R.attr.themeTextPrimary))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            notesList.addView(row)
        }
    }

    private fun styledPoint(point: String, tone: Int): CharSequence {
        val colon = point.indexOf(':')
        val out = SpannableStringBuilder()
        if (colon in 3..40 && colon < point.length - 1) {
            val lead = point.substring(0, colon + 1)
            out.append(lead)
            out.setSpan(StyleSpan(Typeface.BOLD), 0, lead.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(TypefaceSpan("sans-serif-medium"), 0, lead.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(tone), 0, lead.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val detail = point.substring(colon + 1)
            val start = out.length
            out.append(detail)
            out.setSpan(TypefaceSpan("sans-serif-light"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            out.append(point)
            out.setSpan(TypefaceSpan("sans-serif-medium"), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        val entries = settings.history().asReversed().take(12)
        if (entries.isEmpty()) {
            historyList.addView(text(getString(R.string.ota_history_empty), 13f, primary = false))
            return
        }
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        entries.forEachIndexed { index, entry ->
            if (index > 0) historyList.addView(divider())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(
                text(getString(R.string.ota_history_line, entry.event, entry.versionName), 14f, primary = true, bold = true)
            )
            val detail = format.format(Date(entry.timeMs)) + if (entry.detail.isBlank()) "" else " · " + entry.detail
            row.addView(text(detail, 12f, primary = false).apply { setPadding(0, dp(2), 0, 0) })
            historyList.addView(row)
        }
    }

    // ---- Actions ------------------------------------------------------------------------------

    private fun setPrimary(labelRes: Int, enabled: Boolean) {
        primaryButton.text = getString(labelRes)
        primaryButton.isEnabled = enabled
        primaryButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun onPrimary() {
        val app = applicationContext
        when (val state = lastState) {
            is OtaState.Available -> lifecycleScope.launch { OtaManager.download(app) }
            is OtaState.Downloading -> lifecycleScope.launch { OtaManager.cancelDownload(app) }
            is OtaState.Ready -> lifecycleScope.launch { OtaManager.install(app) }
            is OtaState.NeedsInstallPermission -> {
                if (packageManager.canRequestPackageInstalls()) {
                    lifecycleScope.launch { OtaManager.install(app) }
                } else {
                    runCatching { startActivity(OtaManager.installPermissionIntent(app)) }
                }
            }
            is OtaState.Failed -> {
                if (state.canRetry && state.release != null) {
                    lifecycleScope.launch { OtaManager.download(app) }
                } else {
                    lifecycleScope.launch { OtaManager.check(app, manual = true) }
                }
            }
            else -> lifecycleScope.launch { OtaManager.check(app, manual = true) }
        }
    }

    private fun onSecondary() {
        lifecycleScope.launch { OtaManager.check(applicationContext, manual = true) }
    }

    // ---- View helpers (same look as the Clock pages) --------------------------------------------

    private fun OtaState.releaseOrNull(): OtaRelease? = when (this) {
        is OtaState.Available -> release
        is OtaState.Downloading -> release
        is OtaState.Verifying -> release
        is OtaState.Ready -> release
        is OtaState.NeedsInstallPermission -> release
        is OtaState.Installing -> release
        is OtaState.Failed -> release
        else -> null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun text(value: String, sizeSp: Float, primary: Boolean, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(themeColor(if (primary) R.attr.themeTextPrimary else R.attr.themeTextMuted))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(themeColor(R.attr.themeCardBg))
            setStroke(dp(1), themeColor(R.attr.themeCardBorder))
        }
        elevation = dp(3).toFloat()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(16) }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(themeColor(R.attr.themeCardBorder))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6) }
    }

    private fun sectionTitle(value: String): View = text(value, 18f, primary = true, bold = true).apply {
        setPadding(0, 0, 0, dp(8))
    }

    private fun button(emphasized: Boolean, onClick: () -> Unit) = TextView(this).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(themeColor(R.attr.themeTextPrimary))
        setPadding(dp(18), dp(10), dp(18), dp(10))
        minimumHeight = dp(44)
        minimumWidth = dp(44)
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(themeColor(R.attr.themeChipBg))
            if (emphasized) setStroke(dp(2), ThemeManager.getThemeAccentColor())
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(8) }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun switchRow(titleRes: Int, subtitleRes: Int, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, dp(10), 0, dp(10))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(text(getString(titleRes), 16f, primary = true, bold = true))
        info.addView(text(getString(subtitleRes), 13f, primary = false).apply { setPadding(0, dp(2), dp(8), 0) })
        row.addView(info)
        val toggle = SwitchCompat(this).apply {
            isChecked = checked
            contentDescription = getString(titleRes)
            minimumHeight = dp(44)
            minimumWidth = dp(44)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        ThemeManager.tintSwitch(toggle)
        row.addView(toggle)
        row.setOnClickListener { toggle.toggle() }
        return row
    }
}
