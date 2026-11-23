package com.jarvis.assistant.ui.home

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.R
import com.jarvis.assistant.ui.premium.PremiumDialog
import com.jarvis.assistant.ui.premium.dp
import com.jarvis.assistant.updates.OtaFormat
import com.jarvis.assistant.updates.OtaManager
import com.jarvis.assistant.updates.OtaPromptPolicy
import com.jarvis.assistant.updates.OtaRelease
import com.jarvis.assistant.updates.OtaSettings
import com.jarvis.assistant.updates.OtaState
import kotlinx.coroutines.launch

/**
 * The "Update Available" popup shown when JARVIS opens and a newer build exists.
 *
 * Nothing is downloaded until the person taps Update. Later remembers the postponed version, so the popup is not shown
 * again for it (a newer build is offered again). After Update the same popup shows the download with a percentage, then
 * the verification, and finally Android's own install confirmation takes over.
 */
internal class UpdatePromptController(private val activity: AppCompatActivity) {

    private enum class Phase { NONE, OFFER, PROGRESS, PERMISSION, FAILURE }

    private val settings = OtaSettings(activity)
    private var phase = Phase.NONE
    private var dialog: PremiumDialog? = null
    private var statusView: TextView? = null
    private var barView: ProgressBar? = null
    private var detailView: TextView? = null
    private var awaitingPermission = false

    fun attach() {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                OtaManager.setVisible(OtaManager.UPDATE_PROMPT, true)
            }

            override fun onResume(owner: LifecycleOwner) {
                continueAfterPermission()
            }

            override fun onStop(owner: LifecycleOwner) {
                OtaManager.setVisible(OtaManager.UPDATE_PROMPT, false)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                val open = dialog
                dialog = null
                phase = Phase.NONE
                open?.dismiss()
            }
        })
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                OtaManager.state.collect { render(it) }
            }
        }
    }

    // ---- State handling ---------------------------------------------------------------------------------------

    private fun render(state: OtaState) {
        if (activity.isFinishing || activity.isDestroyed) return
        when (state) {
            is OtaState.Available -> offer(state.release, ready = false)
            is OtaState.Ready -> if (phase == Phase.NONE) offer(state.release, ready = true)
            is OtaState.Downloading -> if (phase == Phase.PROGRESS) showDownloading(state)
            is OtaState.Verifying -> if (phase == Phase.PROGRESS) showStatus(activity.getString(R.string.ota_popup_verifying))
            is OtaState.Installing -> handOverToAndroid()
            is OtaState.NeedsInstallPermission -> if (phase == Phase.PROGRESS) showPermission()
            is OtaState.Failed -> if (phase == Phase.PROGRESS || phase == Phase.PERMISSION) showFailure(state)
            else -> Unit
        }
    }

    private fun offer(release: OtaRelease, ready: Boolean) {
        if (phase != Phase.NONE) return
        val allowed = OtaPromptPolicy.shouldPrompt(
            release, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT, settings.postponedCode, promptedThisRun
        )
        if (!allowed) return
        promptedThisRun.add(release.versionCode)
        phase = Phase.OFFER

        val d = PremiumDialog.create(activity)
            .eyebrow(activity.getString(R.string.ota_popup_eyebrow))
            .title(activity.getString(R.string.ota_popup_title))
        d.add(offerBody(release, ready, d.palette.accent1))
        d.negative(activity.getString(R.string.ota_popup_later)) { postpone(release) }
        d.positive(activity.getString(R.string.ota_popup_update)) {
            phase = Phase.PROGRESS
            showProgressDialog()
            beginUpdate()
            true
        }
        d.onDismissed {
            if (dialog === d) {
                dialog = null
                // Tapping outside or pressing back counts as Later.
                if (phase == Phase.OFFER) postpone(release)
            }
        }
        dialog = d
        d.show()
    }

    private fun postpone(release: OtaRelease) {
        settings.postponedCode = OtaPromptPolicy.postponedAfterLater(settings.postponedCode, release)
        phase = Phase.NONE
    }

    private fun beginUpdate() {
        activity.lifecycleScope.launch { OtaManager.startUpdate(activity.applicationContext) }
    }

    private fun cancelUpdate() {
        phase = Phase.NONE
        val app = activity.applicationContext
        activity.lifecycleScope.launch { OtaManager.cancelDownload(app) }
    }

    private fun handOverToAndroid() {
        if (phase != Phase.PROGRESS && phase != Phase.PERMISSION && phase != Phase.FAILURE) return
        phase = Phase.NONE
        val open = dialog
        dialog = null
        open?.dismiss()
    }

    private fun continueAfterPermission() {
        if (!awaitingPermission || !activity.packageManager.canRequestPackageInstalls()) return
        if (OtaManager.state.value !is OtaState.NeedsInstallPermission) return
        awaitingPermission = false
        phase = Phase.PROGRESS
        showProgressDialog()
        showStatus(activity.getString(R.string.ota_popup_verifying))
        val app = activity.applicationContext
        activity.lifecycleScope.launch { OtaManager.install(app) }
    }

    // ---- Popups -----------------------------------------------------------------------------------------------

    private fun showProgressDialog() {
        val d = PremiumDialog.create(activity)
            .eyebrow(activity.getString(R.string.ota_popup_eyebrow))
            .title(activity.getString(R.string.ota_popup_progress_title))
        val accent = d.palette.accent1
        val status = label(activity.getString(R.string.ota_popup_starting), 15f, 1f, bold = true)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(accent)
            indeterminateTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(Color.WHITE, 60))
        }
        val detail = label("", 12f, 0.7f)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(8f).toInt()).apply {
                topMargin = activity.dp(12f).toInt()
            })
            addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = activity.dp(8f).toInt()
            })
        }
        d.add(body)
        d.negative(activity.getString(R.string.ota_popup_cancel)) { cancelUpdate() }
        d.positive(activity.getString(R.string.ota_popup_hide)) { true }
        d.onDismissed {
            if (dialog === d) {
                dialog = null
                statusView = null
                barView = null
                detailView = null
            }
        }
        statusView = status
        barView = bar
        detailView = detail
        dialog = d
        d.show()
    }

    private fun showDownloading(state: OtaState.Downloading) {
        val status = statusView ?: return
        val bar = barView ?: return
        val percent = OtaFormat.percent(state.bytes, state.total)
        status.text = activity.getString(R.string.ota_popup_downloading, percent)
        bar.isIndeterminate = false
        bar.progress = percent
        val remaining = (state.total - state.bytes).coerceAtLeast(0L)
        val eta = if (state.bytesPerSecond >= 1.0) OtaFormat.eta((remaining / state.bytesPerSecond).toLong()) else ""
        val line = OtaFormat.bytes(state.bytes) + " / " + OtaFormat.bytes(state.total) +
            (if (state.bytesPerSecond >= 1.0) " · " + OtaFormat.speed(state.bytesPerSecond) else "") +
            (if (eta.isNotEmpty()) " · $eta" else "")
        detailView?.text = if (state.note.isNullOrBlank()) line else state.note + "\n" + line
    }

    private fun showStatus(text: String) {
        statusView?.text = text
        barView?.isIndeterminate = true
        detailView?.text = ""
    }

    private fun showPermission() {
        phase = Phase.PERMISSION
        val d = PremiumDialog.create(activity)
            .eyebrow(activity.getString(R.string.ota_popup_eyebrow))
            .title(activity.getString(R.string.ota_popup_permission_title))
            .message(activity.getString(R.string.ota_popup_permission_message))
        d.negative(activity.getString(R.string.ota_popup_not_now)) { phase = Phase.NONE }
        d.positive(activity.getString(R.string.ota_popup_permission_open)) {
            awaitingPermission = true
            phase = Phase.NONE
            runCatching { activity.startActivity(OtaManager.installPermissionIntent(activity)) }
            true
        }
        swapTo(d)
    }

    private fun showFailure(state: OtaState.Failed) {
        phase = Phase.FAILURE
        val d = PremiumDialog.create(activity)
            .eyebrow(activity.getString(R.string.ota_popup_eyebrow))
            .title(activity.getString(R.string.ota_popup_failed_title))
            .message(state.message)
            .destructive()
        d.negative(activity.getString(R.string.ota_popup_close)) { phase = Phase.NONE }
        if (state.canRetry) {
            d.positive(activity.getString(R.string.ota_popup_retry)) {
                phase = Phase.PROGRESS
                showProgressDialog()
                beginUpdate()
                true
            }
        } else {
            d.positive(activity.getString(R.string.ota_popup_close)) { phase = Phase.NONE; true }
        }
        swapTo(d)
    }

    /** Replaces whatever popup is open with [next]; the old one's dismiss callback no longer matches and is ignored. */
    private fun swapTo(next: PremiumDialog) {
        val old = dialog
        statusView = null
        barView = null
        detailView = null
        dialog = next
        next.onDismissed {
            if (dialog === next) {
                dialog = null
                if (phase == Phase.PERMISSION || phase == Phase.FAILURE) phase = Phase.NONE
            }
        }
        next.show()
        old?.dismiss()
    }

    private fun offerBody(release: OtaRelease, ready: Boolean, accent: Int): LinearLayout {
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(label(activity.getString(R.string.ota_popup_current, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), 13f, 0.7f))
        body.addView(
            label(activity.getString(R.string.ota_popup_new, release.versionName, release.versionCode), 16f, 1f, bold = true)
                .apply { setTextColor(accent) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = activity.dp(4f).toInt()
            }
        )
        val notes = OtaPromptPolicy.conciseNotes(release.releaseNotes)
        if (notes.isNotEmpty()) {
            body.addView(
                label(notes.joinToString("\n") { "•  $it" }, 13f, 0.9f).apply { setLineSpacing(activity.dp(3f), 1f) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = activity.dp(12f).toInt()
                }
            )
        }
        val note = if (ready) activity.getString(R.string.ota_popup_ready_note)
        else activity.getString(R.string.ota_popup_size, OtaFormat.bytes(release.sizeBytes))
        body.addView(
            label(note, 12f, 0.6f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = activity.dp(12f).toInt()
            }
        )
        return body
    }

    private fun label(text: String, sizeSp: Float, alpha: Float, bold: Boolean = false) = TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(ColorUtils.setAlphaComponent(Color.WHITE, (alpha * 255f).toInt().coerceIn(0, 255)))
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private companion object {
        /** Versions already offered during this run of the app, so one build is offered once however often the screen reopens. */
        val promptedThisRun: MutableSet<Long> = java.util.Collections.synchronizedSet(HashSet())
    }
}
