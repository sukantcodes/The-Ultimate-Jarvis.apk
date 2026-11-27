package com.jarvis.assistant.ui.studyboard

import android.Manifest
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.study.Ink
import com.jarvis.assistant.study.StepKind
import com.jarvis.assistant.study.StudyBoardController
import com.jarvis.assistant.study.StudyLevels
import com.jarvis.assistant.study.StudyPlayback
import com.jarvis.assistant.study.StudyProfile
import com.jarvis.assistant.study.voice.StudyVoiceCodec
import com.jarvis.assistant.study.voice.StudyVoiceInput
import com.jarvis.assistant.ui.glass.GlassBackdropView
import com.jarvis.assistant.ui.premium.PremiumDialog
import com.jarvis.assistant.util.ImmersiveStatusBar
import com.jarvis.assistant.util.JarvisFonts
import com.jarvis.assistant.util.JarvisHandlesInsets
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Study Board: a full-screen AI whiteboard teacher. Not part of ChatBox. Everything that matters lives in
 * [StudyViewModel] (it survives rotation); this class only builds the screen and forwards what the student does.
 */
class StudyBoardActivity : AppCompatActivity(), StudyBoardView.Host, JarvisHandlesInsets {

    private val vm: StudyViewModel by viewModels()
    private lateinit var board: StudyBoardView
    private lateinit var voice: StudyVoiceInput
    private lateinit var pal: StudyTheme.Palette

    private lateinit var boardFrame: FrameLayout
    private lateinit var subjectLine: TextView
    private lateinit var statusDot: View
    private lateinit var statusPill: TextView
    private lateinit var emptyHint: ViewGroup
    private lateinit var resumeButton: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerIcon: ImageView
    private lateinit var bannerText: TextView
    private lateinit var bannerRetry: TextView
    private lateinit var explainCard: LinearLayout
    private lateinit var explainText: TextView
    private lateinit var explainToggle: TextView
    private lateinit var verifyText: TextView
    private lateinit var draftingPill: LinearLayout
    private lateinit var draftingText: TextView
    private lateinit var playButton: ImageButton
    private lateinit var speedChip: TextView
    private lateinit var langChip: TextView
    private lateinit var voiceButton: ImageButton
    private lateinit var notesButton: ImageButton
    private lateinit var bookmarkButton: ImageButton
    private lateinit var voiceLiveBadge: LinearLayout
    private lateinit var voiceWaveLabel: TextView
    private val voiceWaveBars = ArrayList<View>(4)
    private lateinit var explainActions: LinearLayout
    private lateinit var explainReplayChip: TextView
    private lateinit var explainDoubtChip: TextView
    private lateinit var input: EditText
    private lateinit var inputBar: LinearLayout
    private lateinit var sendButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var voiceBar: LinearLayout
    private lateinit var voiceStatusText: TextView
    private lateinit var voiceRmsDot: View
    private lateinit var chipNew: TextView
    private lateinit var chipDoubt: TextView
    private lateinit var header: LinearLayout
    private lateinit var side: LinearLayout
    private lateinit var stepText: TextView
    private lateinit var symbols: HorizontalScrollView
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton
    private lateinit var fullscreenButton: ImageButton
    private var fullscreen = false
    private lateinit var fsOverlay: LinearLayout
    private lateinit var fsPlay: ImageButton
    private lateinit var fitAllPill: TextView
    private lateinit var zoomResetPill: TextView
    private lateinit var followPenPill: TextView
    private lateinit var progressTrack: FrameLayout
    private lateinit var progressFill: View

    private var explainExpanded = false
    private var currentZoom = 1f
    private var hasBoardOverflow = false
    private var lastProgressFrac = -1f
    private var activeBoardHighlight: Boolean? = null
    private var listening = false
    private var cameraFile: File? = null
    private var appliedThemeKey = ""
    private var appliedCustomHex = ""
    private var statusDotPulse: ObjectAnimator? = null
    private var micPulse: ObjectAnimator? = null
    private var typingJob: Job? = null

    // ----- result launchers -----
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else toast("Microphone permission is needed for voice questions.")
    }
    private val systemVoicePrompt = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        listening = false
        val spoken = if (res.resultCode == RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        } else {
            ""
        }
        if (spoken.isNotEmpty()) {
            input.setText("")
            board.followPen = true
            refresh()
            vm.submit(spoken)
        } else {
            refresh()
            if (res.resultCode == RESULT_OK) toast("Could not hear a question — please try again.")
        }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) launchCamera() else toast("Camera permission is needed to photograph a question.")
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val f = cameraFile
        if (saved && f != null && f.exists()) vm.onImage(Uri.fromFile(f), "photo") else if (!saved) toast("No photo was taken.")
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.onImage(uri, "image") }
    private fun launchImagePicker() = pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.onPdf(uri) }
    private val historyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(StudyHistoryActivity.EXTRA_ID)?.let { vm.openSession(it) }
    }
    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> if (uri != null) export(uri, true) }
    private val createPng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> if (uri != null) export(uri, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val prefs = runCatching { JarvisApp.instance.preferences }.getOrNull()
        appliedThemeKey = prefs?.appTheme.orEmpty()
        appliedCustomHex = prefs?.customThemeSignature.orEmpty()
        pal = StudyTheme.palette(this)
        voice = StudyVoiceInput(this)
        setContentView(buildUi())
        board.bind(vm.playback, vm.blocks.value, vm.eraseLimit.value)
        observe()
        refresh()
        if (savedInstanceState == null) runQuestionFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        GlassBackdropView.refreshAll()
        val prefs = runCatching { JarvisApp.instance.preferences }.getOrNull()
        if (prefs != null && (appliedThemeKey != prefs.appTheme || appliedCustomHex != prefs.customThemeSignature)) {
            appliedThemeKey = prefs.appTheme
            appliedCustomHex = prefs.customThemeSignature
            recreate()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runQuestionFrom(intent)
    }

    /** "Explain this in Study Board" by voice: the question arrives in the intent, types into the prompt bar, and runs. */
    private fun runQuestionFrom(intent: Intent?) {
        val q = intent?.getStringExtra(EXTRA_QUESTION)?.trim().orEmpty()
        if (q.isEmpty()) return
        val mode = intent?.getStringExtra(EXTRA_MODE)?.trim().orEmpty().ifEmpty { "new" }
        val language = intent?.getStringExtra(EXTRA_LANGUAGE)?.trim()?.takeIf { it.isNotEmpty() }
        val level = intent?.getStringExtra(EXTRA_LEVEL)?.trim()?.takeIf { it.isNotEmpty() }
        val depth = intent?.getStringExtra(EXTRA_DEPTH)?.trim()?.takeIf { it.isNotEmpty() }
        val step = intent?.getIntExtra(EXTRA_STEP, -1)?.takeIf { it > 0 }
        intent?.removeExtra(EXTRA_QUESTION) // so a rotation or a theme rebuild never asks it twice
        intent?.removeExtra(EXTRA_MODE)
        intent?.removeExtra(EXTRA_LANGUAGE)
        intent?.removeExtra(EXTRA_LEVEL)
        intent?.removeExtra(EXTRA_DEPTH)
        intent?.removeExtra(EXTRA_STEP)
        typeAndSubmitQuestion(q, mode = mode, language = language, level = level, depth = depth, stepNumber = step, animateTyping = true)
    }

    /** Visibly types [question] into the composer field (with readback verification) and submits it to the board. */
    private fun typeAndSubmitQuestion(
        question: String,
        mode: String = "new",
        language: String? = null,
        level: String? = null,
        depth: String? = null,
        stepNumber: Int? = null,
        animateTyping: Boolean = true
    ) {
        val clean = question.trim()
        if (clean.isEmpty()) return
        typingJob?.cancel()
        if (listening) {
            voice.cancel()
            listening = false
        }
        language?.let { applyLanguageChoice(it, silent = true) }
        level?.let { applyLevelChoice(it) }
        depth?.let { applyDepthChoice(it) }

        val targetDoubt = mode.equals("doubt", true) && (vm.lesson?.steps?.isNotEmpty() == true)
        if (targetDoubt) {
            vm.askMode.value = StudyViewModel.AskMode.DOUBT
            if (stepNumber != null) vm.selectStepNumber(stepNumber)
        } else {
            vm.newQuestionMode()
        }

        typingJob = lifecycleScope.launch {
            if (vm.isBusy) {
                vm.cancel()
                delay(320L)
            }
            if (!isActive || isFinishing) return@launch
            hideKeyboard()
            input.requestFocus()
            refresh()

            if (animateTyping) {
                val totalChars = clean.length
                val stepSize = when {
                    totalChars <= 24 -> 1
                    totalChars <= 70 -> 2
                    totalChars <= 160 -> 4
                    else -> 8
                }
                val tickDelayMs = (420L / maxOf(1, (totalChars + stepSize - 1) / stepSize)).coerceIn(8L, 26L)
                var pos = 0
                while (pos < totalChars && isActive) {
                    pos = (pos + stepSize).coerceAtMost(totalChars)
                    input.setText(clean.substring(0, pos))
                    input.setSelection(input.text?.length ?: 0)
                    delay(tickDelayMs)
                }
            }
            if (input.text.toString().trim() != clean) {
                input.setText(clean)
                input.setSelection(input.text?.length ?: 0)
            }
            if (animateTyping) delay(120L)
            if (!isActive || isFinishing) return@launch
            input.setText("")
            board.followPen = true
            refresh()
            vm.submit(clean)
        }
    }

    // ======================================================================================================
    // UI construction
    // ======================================================================================================

    private fun dp(v: Int): Int = StudyTheme.dp(this, v)
    private fun dpf(v: Float): Float = StudyTheme.dpf(this, v)

    private fun glassIconButton(res: Int, desc: String, sizeDp: Int = 44, onClick: () -> Unit): ImageButton =
        StudyTheme.iconButton(this, res, desc, pal, sizeDp = sizeDp, padDp = 10, tint = pal.textPrimary, onClick = onClick).apply {
            background = StudyTheme.pillDrawable(this@StudyBoardActivity, 14f, StudyTheme.alpha(pal.cardBg, 0.55f), pal.cardBorder)
        }

    private fun buildUi(): View {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val contentColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val compactHeader = resources.configuration.screenWidthDp in 1..360
        // ---- Header ----
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (compactHeader) 6 else 10), dp(6), dp(if (compactHeader) 6 else 10), dp(6))
        }
        header.addView(
            StudyTheme.iconButton(this, R.drawable.ic_back, "Back", pal, sizeDp = 44, padDp = 10) { finish() }.apply {
                StudyTheme.applyGlass(this, 16f, pal, soft = true)
            }
        )

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(if (compactHeader) 6 else 10), 0, dp(4), 0)
        }
        val eyebrowRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.accent) }
        }
        eyebrowRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(5) })
        eyebrowRow.addView(
            StudyTheme.label(this, "STUDY BOARD", if (compactHeader) 10.5f else 11.5f, pal.accent, hud = true).apply {
                letterSpacing = if (compactHeader) 0.06f else 0.11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        statusPill = StudyTheme.label(this, "", 10.5f, pal.onAccent, bold = true).apply {
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply { cornerRadius = dpf(10f); setColor(pal.accent) }
            visibility = View.GONE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        eyebrowRow.addView(statusPill, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        titles.addView(eyebrowRow)

        subjectLine = StudyTheme.label(this, "AI Whiteboard Teacher", 13.5f, pal.textPrimary, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        titles.addView(subjectLine)
        header.addView(titles)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fullscreenButton = glassIconButton(R.drawable.ic_study_fullscreen, "Full screen", 44) { setFullscreen(!fullscreen) }
        actions.addView(fullscreenButton)
        actions.addView(
            glassIconButton(R.drawable.ic_study_history, "Saved lessons", 44) {
                historyLauncher.launch(Intent(this, StudyHistoryActivity::class.java))
            },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(5) }
        )
        actions.addView(
            glassIconButton(R.drawable.ic_study_tune, "Teaching settings", 44) {
                StudyDialogs.settings(this, vm.prefs, { vm.clearMemory() }) { vm.setVoice(vm.prefs.voiceOn) }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(5) }
        )
        header.addView(actions)
        contentColumn.addView(header)

        // ---- Board Area & Overlays ----
        boardFrame = FrameLayout(this).apply {
            elevation = dpf(4f)
            foreground = GradientDrawable().apply {
                cornerRadius = dpf(22f)
                setColor(Color.TRANSPARENT)
                setStroke(dp(1).coerceAtLeast((dpf(1.2f) + 0.5f).toInt()), pal.cardBorder)
            }
        }
        board = StudyBoardView(this).apply {
            host = this@StudyBoardActivity
            onViewportChanged = { z, _, overflow ->
                currentZoom = z
                hasBoardOverflow = overflow
                updateViewportPills()
            }
        }
        boardFrame.addView(board, FrameLayout.LayoutParams(-1, -1))

        // Top hairline gradient playback/drawing progress indicator inside the whiteboard frame
        progressFill = View(this).apply {
            background = StudyTheme.gradientPillDrawable(this@StudyBoardActivity, 2f, pal.accent, pal.secondary)
        }
        progressTrack = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(StudyTheme.alpha(pal.cardBorder, 0.25f)) }
            visibility = View.GONE
            addView(progressFill, FrameLayout.LayoutParams(0, dp(3)))
        }
        boardFrame.addView(progressTrack, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))

        val zoom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpf(4f)
            background = StudyTheme.pillDrawable(this@StudyBoardActivity, 22f, pal.floatPanelBg, pal.floatPanelBorder)
        }
        zoom.addView(
            StudyTheme.iconButton(this, R.drawable.ic_study_zoom_in, "Zoom in", pal, sizeDp = 44, padDp = 11, tint = pal.floatPanelInk) {
                board.zoomBy(1.4f)
            }
        )
        zoom.addView(
            View(this).apply { setBackgroundColor(pal.floatPanelBorder) },
            LinearLayout.LayoutParams(dp(26), dp(1)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        zoom.addView(
            StudyTheme.iconButton(this, R.drawable.ic_study_zoom_out, "Zoom out", pal, sizeDp = 44, padDp = 11, tint = pal.floatPanelInk) {
                board.zoomBy(1f / 1.4f)
            }
        )
        zoom.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.height > 0) board.safeBottomPx = v.height + dpf(24f)
        }
        boardFrame.addView(zoom, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(12), dp(12))
        })

        // Floating viewport pills (Fit board / 1.0x Reset / Follow Pen)
        val viewportPills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fitAllPill = StudyTheme.label(this, "Fit board", 12f, pal.floatPanelInk, bold = true).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            elevation = dpf(4f)
            background = StudyTheme.pillDrawable(this@StudyBoardActivity, 22f, pal.floatPanelBg, pal.floatPanelBorder)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            StudyTheme.applyPressSpring(this, 0.94f)
            setOnClickListener {
                board.followPen = false
                board.fitToBoard()
            }
        }
        zoomResetPill = StudyTheme.label(this, "", 12f, pal.floatPanelInk, bold = true).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            elevation = dpf(4f)
            background = StudyTheme.pillDrawable(this@StudyBoardActivity, 22f, pal.floatPanelBg, pal.floatPanelBorder)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            StudyTheme.applyPressSpring(this, 0.94f)
            setOnClickListener { board.resetZoom() }
        }
        followPenPill = StudyTheme.label(this, "Follow pen", 12f, pal.onAccent, bold = true).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            elevation = dpf(4f)
            background = StudyTheme.gradientPillDrawable(this@StudyBoardActivity, 22f, pal.accent, pal.secondary)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            StudyTheme.applyPressSpring(this, 0.94f)
            setOnClickListener {
                board.followPen = true
                board.scrollToEnd()
                updateViewportPills()
            }
        }
        viewportPills.addView(fitAllPill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        viewportPills.addView(zoomResetPill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        viewportPills.addView(followPenPill)
        boardFrame.addView(viewportPills, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(12), 0, dp(72), dp(12))
        })

        // Fullscreen Floating Controls
        fsOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dpf(4f)
            background = StudyTheme.pillDrawable(this@StudyBoardActivity, 22f, pal.floatPanelBg, pal.floatPanelBorder)
            visibility = View.GONE
        }
        fsPlay = StudyTheme.iconButton(this, R.drawable.ic_study_pause, "Play or pause", pal, sizeDp = 44, padDp = 11, tint = pal.floatPanelInk) {
            vm.togglePlay(); board.followPen = true
        }
        fsOverlay.addView(fsPlay)
        fsOverlay.addView(
            View(this).apply { setBackgroundColor(pal.floatPanelBorder) },
            LinearLayout.LayoutParams(dp(1), dp(22))
        )
        fsOverlay.addView(
            StudyTheme.iconButton(this, R.drawable.ic_study_fullscreen_exit, "Leave full screen", pal, sizeDp = 44, padDp = 11, tint = pal.floatPanelInk) {
                setFullscreen(false)
            }
        )
        boardFrame.addView(fsOverlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(10), dp(12), 0)
        })

        // Empty State Card on White Paper
        emptyHint = buildEmptyHint()
        boardFrame.addView(emptyHint, FrameLayout.LayoutParams(-1, -1))

        // Live Studio Drafting Indicator (shown cleanly between submitting a question and first step arrival)
        draftingPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(18), dp(10))
            elevation = dpf(5f)
            background = StudyTheme.pillDrawable(
                this@StudyBoardActivity,
                24f,
                pal.floatPanelBg,
                StudyTheme.alpha(pal.accent, 0.50f)
            )
            visibility = View.GONE
        }
        draftingPill.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(pal.accent)
            },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
        )
        draftingText = StudyTheme.label(this, "Drafting solution & visuals…", 13f, pal.floatPanelInk, bold = true)
        draftingPill.addView(draftingText)
        boardFrame.addView(
            draftingPill,
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                setMargins(dp(16), dp(12), dp(16), 0)
            }
        )

        val boardHolder = FrameLayout(this).apply {
            setPadding(dp(10), dp(4), if (landscape) dp(4) else dp(10), dp(6))
            addView(boardFrame, FrameLayout.LayoutParams(-1, -1))
        }

        // ---- Banner, Explanation Card, Controls, Input ----
        banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = StudyTheme.pillDrawable(this@StudyBoardActivity, 18f, pal.errorContainer, StudyTheme.alpha(pal.error, 0.55f))
            setPadding(dp(12), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }
        bannerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_warning)
            setColorFilter(pal.error)
            contentDescription = null
        }
        banner.addView(
            bannerIcon,
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
        )
        bannerText = StudyTheme.label(this, "", 12.5f, pal.textPrimary).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setLineSpacing(dpf(2f), 1f)
        }
        bannerRetry = StudyTheme.chip(this, "Retry", pal, minHeightDp = 44) { vm.retry() }.apply {
            background = StudyTheme.pillDrawable(this@StudyBoardActivity, 14f, pal.error, pal.error)
            setTextColor(StudyTheme.onColor(pal.error, pal.textPrimary, pal.baseBg))
        }
        banner.addView(bannerText)
        banner.addView(bannerRetry, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        banner.addView(StudyTheme.iconButton(this, R.drawable.ic_close, "Dismiss", pal, sizeDp = 44, padDp = 12, tint = pal.textPrimary) {
            if (vm.ui.value.phase == StudyViewModel.Phase.EXTRACTING) vm.cancel() else vm.dismissMessage()
        })

        explainCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutTransition = LayoutTransition()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                explainExpanded = !explainExpanded
                explainText.maxLines = if (explainExpanded) 10 else if (landscape) 5 else 3
                explainToggle.text = if (explainExpanded) "Less ▴" else "More ▾"
            }
        }
        StudyTheme.applyGlass(explainCard, 20f, pal)

        val explainMetaScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val explainMeta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stepText = StudyTheme.label(this, "", 11f, pal.accent, hud = true).apply {
            letterSpacing = 0.06f
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = StudyTheme.pillDrawable(this@StudyBoardActivity, 10f, StudyTheme.alpha(pal.accent, 0.16f), StudyTheme.alpha(pal.accent, 0.35f))
        }
        explainMeta.addView(stepText)

        voiceLiveBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(24)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = StudyTheme.pillDrawable(
                this@StudyBoardActivity,
                10f,
                StudyTheme.alpha(pal.secondary, 0.14f),
                StudyTheme.alpha(pal.secondary, 0.38f)
            )
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { vm.setVoice(!vm.prefs.voiceOn) }
        }
        voiceWaveBars.clear()
        val barHeightsDp = intArrayOf(6, 11, 8, 12)
        for (i in 0 until 4) {
            val bar = View(this).apply {
                background = StudyTheme.pillDrawable(this@StudyBoardActivity, 2f, pal.secondary, pal.secondary)
            }
            voiceWaveBars.add(bar)
            voiceLiveBadge.addView(
                bar,
                LinearLayout.LayoutParams(dp(3), dp(barHeightsDp[i])).apply {
                    marginEnd = dp(if (i == 3) 5 else 2)
                }
            )
        }
        voiceWaveLabel = StudyTheme.label(this, "JARVIS", 10.5f, pal.secondary, hud = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        voiceLiveBadge.addView(voiceWaveLabel)
        explainMeta.addView(voiceLiveBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })

        explainMeta.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        verifyText = StudyTheme.label(this, "", 11f, pal.textSecondary, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        explainMeta.addView(verifyText)
        explainToggle = StudyTheme.label(this, "More ▾", 11f, pal.accent, bold = true).apply {
            visibility = View.GONE
            setPadding(dp(8), dp(2), dp(2), dp(2))
        }
        explainMeta.addView(explainToggle)
        explainMetaScroll.addView(explainMeta)
        explainCard.addView(explainMetaScroll)

        explainText = StudyTheme.label(this, "", 13.5f, pal.textPrimary).apply {
            maxLines = if (landscape) 5 else 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dpf(2f), 1.06f)
            setPadding(0, dp(5), 0, 0)
        }
        explainCard.addView(explainText)

        explainActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
            visibility = View.GONE
        }
        explainReplayChip = StudyTheme.chip(this, "Replay step", pal, minHeightDp = 44) {
            vm.replayStep()
            board.followPen = true
        }.apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        explainDoubtChip = StudyTheme.chip(this, "Ask doubt", pal, minHeightDp = 44) {
            val activeStep = vm.blocks.value.getOrNull(vm.playback.current)?.stepIndex
            if (activeStep != null && activeStep in 1..(vm.lesson?.steps?.size ?: 0)) {
                vm.selectStepNumber(activeStep)
            } else if (vm.lesson?.steps?.isNotEmpty() == true) {
                vm.askMode.value = StudyViewModel.AskMode.DOUBT
            }
            input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }.apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        explainActions.addView(explainReplayChip, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) })
        explainActions.addView(explainDoubtChip, LinearLayout.LayoutParams(-2, dp(44)))
        explainCard.addView(explainActions)

        val controls = buildControls()
        val inputArea = buildInput()

        side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), dp(2)) })
        side.addView(explainCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) })
        side.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(2), dp(10), dp(4)) })
        side.addView(inputArea, LinearLayout.LayoutParams(-1, -2))

        if (landscape) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(boardHolder, LinearLayout.LayoutParams(0, -1, 1.65f))
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                addView(side)
            }
            row.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
            contentColumn.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            contentColumn.addView(boardHolder, LinearLayout.LayoutParams(-1, 0, 1f))
            contentColumn.addView(side, LinearLayout.LayoutParams(-1, -2))
        }

        return StudyTheme.wrapWithBackdrop(this, contentColumn)
    }

    private fun buildEmptyHint(): ViewGroup {
        val paperAccent = paperAccentColor()
        val paperInk = StudyPalette.color(Ink.INK)
        val paperMuted = StudyPalette.color(Ink.NOTE)
        val paperStroke = StudyPalette.color(Ink.FAINT)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }

        val iconBadge = FrameLayout(this).apply {
            background = StudyTheme.pillDrawable(
                this@StudyBoardActivity, 18f,
                StudyTheme.alpha(paperAccent, 0.12f),
                StudyTheme.alpha(paperAccent, 0.30f)
            )
        }
        iconBadge.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_study_board)
                setColorFilter(paperAccent)
                contentDescription = null
            },
            FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        )
        box.addView(iconBadge, LinearLayout.LayoutParams(dp(58), dp(58)).apply { bottomMargin = dp(14) })

        box.addView(
            StudyTheme.label(this, "AI Whiteboard Teacher", 18f, paperInk, bold = true).apply {
                typeface = JarvisFonts.displayBold
                gravity = Gravity.CENTER
            }
        )
        box.addView(
            StudyTheme.label(this, "Ask a question by voice, text, photo, or PDF.", 13.5f, paperMuted).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dpf(2f), 1f)
                setPadding(0, dp(6), 0, dp(16))
            }
        )

        fun paperActionChip(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            background = StudyTheme.pillDrawable(this@StudyBoardActivity, 16f, StudyTheme.alpha(paperAccent, 0.08f), StudyTheme.alpha(paperStroke, 0.7f))
            isClickable = true
            isFocusable = true
            StudyTheme.applyPressSpring(this, 0.94f)
            addView(
                ImageView(this@StudyBoardActivity).apply {
                    setImageResource(iconRes)
                    setColorFilter(paperAccent)
                    contentDescription = null
                },
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) }
            )
            addView(StudyTheme.label(this@StudyBoardActivity, label, 12.5f, paperInk, bold = true))
            setOnClickListener { onClick() }
        }

        val quickScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        quickRow.addView(paperActionChip(R.drawable.ic_study_camera, "Photo") { requestCamera() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        quickRow.addView(paperActionChip(R.drawable.ic_study_image, "Image") { launchImagePicker() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        quickRow.addView(paperActionChip(R.drawable.ic_study_pdf, "PDF") { pickPdf.launch(arrayOf("application/pdf")) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        quickRow.addView(paperActionChip(R.drawable.ic_mic_jarvis, "Voice") { toggleMic() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        quickRow.addView(paperActionChip(R.drawable.ic_study_history, "Saved") { historyLauncher.launch(Intent(this, StudyHistoryActivity::class.java)) })
        quickScroller.addView(quickRow)
        box.addView(quickScroller, LinearLayout.LayoutParams(-2, -2))

        resumeButton = StudyTheme.label(this, "", 13f, StudyTheme.onColor(paperAccent, StudyPalette.PAPER, paperInk), bold = true).apply {
            minHeight = dp(46)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = StudyTheme.pillDrawable(this@StudyBoardActivity, 20f, paperAccent, paperAccent)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            StudyTheme.applyPressSpring(this, 0.95f)
            setOnClickListener { vm.interrupted.value?.let { vm.openSession(it.first); vm.dismissInterrupted() } }
        }
        box.addView(resumeButton, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })

        // Interactive live starter showcase chips ("Try live on the board")
        box.addView(
            StudyTheme.label(this, "TRY LIVE ON THE BOARD", 10.5f, paperMuted, hud = true).apply {
                letterSpacing = 0.12f
                setPadding(0, dp(18), 0, dp(8))
            }
        )
        val bioChemStarters = listOf(
            Triple("Human Brain", "Draw a detailed labelled diagram of the human brain", StudyPalette.color(Ink.VIOLET)),
            Triple("Human Heart", "Draw a detailed labelled diagram of the human heart", StudyPalette.color(Ink.WARN)),
            Triple("Human Eye", "Draw a detailed labelled diagram of the human eye", StudyPalette.color(Ink.TEAL)),
            Triple("Mitochondria", "Draw a neat labelled diagram of mitochondria", StudyPalette.color(Ink.ORANGE)),
            Triple("Human Ear", "Draw a labelled anatomy diagram of the human ear", StudyPalette.color(Ink.ROSE)),
            Triple("Reflex Arc", "Draw a labelled reflex arc diagram showing pathway", StudyPalette.color(Ink.ACCENT)),
            Triple("Plant Cell", "Draw a neatly labelled diagram of a plant cell", StudyPalette.color(Ink.RESULT)),
            Triple("Benzene C6H6", "Draw the ring structure of Benzene C6H6 with pi cloud", StudyPalette.color(Ink.VIOLET))
        )
        val physMathCsStarters = listOf(
            Triple("Convex Lens", "Draw a labelled ray diagram of image formation by a convex lens", paperAccent),
            Triple("Glass Slab", "Draw a labelled diagram of refraction through a rectangular glass slab", StudyPalette.color(Ink.TEAL)),
            Triple("Electric Circuit", "Draw a labelled circuit diagram with battery, ammeter, resistor and switch", StudyPalette.color(Ink.ORANGE)),
            Triple("AC Generator", "Draw a neat labelled diagram of an AC generator", StudyPalette.color(Ink.WARN)),
            Triple("Solar System", "Draw a neat labelled diagram of the solar system orbits", StudyPalette.color(Ink.GOLD)),
            Triple("Binary Tree", "Draw a labelled diagram of a Binary Search Tree data structure", StudyPalette.color(Ink.TEAL)),
            Triple("Plot y = x² - 4", "Plot y = x^2 - 4 and y = 2x and find their intersection", StudyPalette.color(Ink.ORANGE))
        )

        fun buildStarterScroller(list: List<Triple<String, String, Int>>, topGapDp: Int): HorizontalScrollView {
            val scroller = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            for ((title, prompt, tone) in list) {
                row.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(44)
                        setPadding(dp(12), dp(8), dp(14), dp(8))
                        background = StudyTheme.pillDrawable(
                            this@StudyBoardActivity, 16f,
                            StudyTheme.alpha(tone, 0.09f),
                            StudyTheme.alpha(tone, 0.34f)
                        )
                        isClickable = true
                        isFocusable = true
                        StudyTheme.applyPressSpring(this, 0.95f)
                        addView(
                            View(this@StudyBoardActivity).apply {
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(tone)
                                }
                            },
                            LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) }
                        )
                        addView(StudyTheme.label(this@StudyBoardActivity, title, 12f, tone, bold = true))
                        setOnClickListener {
                            hideKeyboard()
                            board.followPen = true
                            vm.newQuestionMode()
                            vm.submit(prompt)
                        }
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                )
            }
            scroller.addView(row)
            return scroller.apply {
                if (topGapDp > 0) setPadding(0, dp(topGapDp), 0, 0)
            }
        }
        box.addView(buildStarterScroller(bioChemStarters, 0), LinearLayout.LayoutParams(-2, -2))
        box.addView(buildStarterScroller(physMathCsStarters, 8), LinearLayout.LayoutParams(-2, -2))

        scroll.addView(box, FrameLayout.LayoutParams(-1, -1))
        return scroll
    }

    private fun paperAccentColor(): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(pal.accent, hsv)
        if (hsv[1] > 0.12f) hsv[1] = hsv[1].coerceAtLeast(0.68f)
        if (hsv[2] > 0.60f) hsv[2] = 0.60f
        return Color.HSVToColor(hsv)
    }

    private fun buildControls(): View {
        val dock = FrameLayout(this).apply {
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        StudyTheme.applyGlass(dock, 24f, pal, soft = true)

        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun sep() = View(this).apply {
            setBackgroundColor(pal.cardBorder)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(22)).apply { setMargins(dp(5), 0, dp(5), 0) }
        }

        val transportGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(2), dp(3), dp(2))
            background = StudyTheme.pillDrawable(
                this@StudyBoardActivity,
                24f,
                StudyTheme.alpha(pal.chipBg, 0.72f),
                StudyTheme.alpha(pal.cardBorder, 0.65f)
            )
        }
        transportGroup.addView(StudyTheme.iconButton(this, R.drawable.ic_study_prev, "Previous step", pal, 44, 10) { vm.previous(); board.followPen = true })
        playButton = StudyTheme.iconButton(this, R.drawable.ic_study_pause, "Pause or resume writing", pal, 46, 11, tint = pal.onAccent) {
            vm.togglePlay(); board.followPen = true
        }.apply {
            elevation = dpf(3f)
            background = StudyTheme.gradientPillDrawable(this@StudyBoardActivity, 23f, pal.accent, pal.secondary)
        }
        transportGroup.addView(playButton, LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(2), 0, dp(2), 0) })
        transportGroup.addView(StudyTheme.iconButton(this, R.drawable.ic_study_next, "Next step", pal, 44, 10) { vm.next(); board.followPen = true })
        row.addView(transportGroup)
        row.addView(StudyTheme.iconButton(this, R.drawable.ic_study_replay, "Replay this step", pal, 44, 10) { vm.replayStep(); board.followPen = true })
        row.addView(StudyTheme.iconButton(this, R.drawable.ic_study_replay_all, "Replay the whole solution", pal, 44, 10) { vm.replayAll(); board.followPen = true })
        speedChip = StudyTheme.chip(this, "1×", pal, minHeightDp = 44) { cycleSpeed() }.apply {
            minWidth = dp(48)
            typeface = JarvisFonts.hudBold
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        row.addView(speedChip, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(2) })
        langChip = StudyTheme.chip(this, languageShortLabel(vm.prefs.language), pal, minHeightDp = 44) {
            showQuickLanguageDialog()
        }.apply {
            minWidth = dp(48)
            typeface = JarvisFonts.hudBold
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        row.addView(langChip, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(4) })
        row.addView(sep())

        voiceButton = StudyTheme.iconButton(this, R.drawable.ic_study_volume_off, "Narration on or off", pal, 44, 10) { vm.setVoice(!vm.prefs.voiceOn) }
        row.addView(voiceButton)
        notesButton = StudyTheme.iconButton(this, R.drawable.ic_study_notes, "Show or hide explanations", pal, 44, 10) { vm.setNotes(!vm.prefs.notesOn) }
        row.addView(notesButton)
        undoButton = StudyTheme.iconButton(this, R.drawable.ic_study_undo, "Undo", pal, 44, 10) { vm.undo() }
        redoButton = StudyTheme.iconButton(this, R.drawable.ic_study_redo, "Redo", pal, 44, 10) { vm.redo() }
        row.addView(undoButton)
        row.addView(redoButton)
        row.addView(StudyTheme.iconButton(this, R.drawable.ic_study_clear, "Clear board", pal, 44, 10) { confirmClear() })
        row.addView(sep())

        bookmarkButton = StudyTheme.iconButton(this, R.drawable.ic_study_bookmark, "Bookmark the selected step", pal, 44, 10) { vm.toggleBookmark() }
        row.addView(bookmarkButton)
        row.addView(StudyTheme.iconButton(this, R.drawable.ic_study_star, "Save this lesson", pal, 44, 10) { vm.saveSession() })
        lateinit var exportButton: ImageButton
        exportButton = StudyTheme.iconButton(this, R.drawable.ic_study_export, "Export", pal, 44, 10) { showExportMenu(exportButton) }
        row.addView(exportButton)

        scroller.addView(row)
        dock.addView(scroller, FrameLayout.LayoutParams(-1, -2))
        return dock
    }

    private fun buildInput(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(8))
        }

        // Row 1: Mode switcher (left) + Attachment buttons (right) in a horizontally adaptive row for 320dp..600dp+
        val topStrip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val stripRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val segmented = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = StudyTheme.pillDrawable(this@StudyBoardActivity, 20f, StudyTheme.alpha(pal.cardBg, 0.6f), pal.cardBorder)
        }
        chipNew = StudyTheme.chip(this, "New question", pal, minHeightDp = 44) { vm.newQuestionMode() }.apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        chipDoubt = StudyTheme.chip(this, "Doubt", pal, minHeightDp = 44) {
            if (vm.lesson?.steps?.isNotEmpty() == true) vm.askMode.value = StudyViewModel.AskMode.DOUBT
            else toast("Solve a question first, then ask doubts about it.")
        }.apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        segmented.addView(chipNew)
        segmented.addView(chipDoubt, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        stripRow.addView(segmented)
        stripRow.addView(Space(this).apply { minimumWidth = dp(8) }, LinearLayout.LayoutParams(0, 1, 1f))

        val attachRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        attachRow.addView(glassIconButton(R.drawable.ic_study_camera, "Photograph a question", 44) { requestCamera() })
        attachRow.addView(
            glassIconButton(R.drawable.ic_study_image, "Choose an image", 44) { launchImagePicker() },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) }
        )
        attachRow.addView(
            glassIconButton(R.drawable.ic_study_pdf, "Import a PDF", 44) { pickPdf.launch(arrayOf("application/pdf")) },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) }
        )
        stripRow.addView(attachRow)
        topStrip.addView(stripRow)
        col.addView(topStrip, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        // Row 2: Math Symbol Strip (appears smoothly when keyboard input gains focus)
        symbols = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
        }
        val symRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (sym in listOf("x²", "√", "π", "θ", "Δ", "→", "⇌", "≤", "≥", "≠", "±", "×", "÷", "°", "∫", "∑", "½", "(", ")", "=")) {
            symRow.addView(
                StudyTheme.chip(this, sym, pal, minHeightDp = 44) { insertSymbol(sym) }.apply {
                    minWidth = dp(44)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    textSize = 14f
                },
                LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) }
            )
        }
        symbols.addView(symRow)
        col.addView(symbols, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        // Live Voice Capture Bar (visible while listening; satisfies >=44dp touch targets)
        voiceBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(14), dp(4), dp(6), dp(4))
            background = StudyTheme.pillDrawable(
                this@StudyBoardActivity,
                22f,
                StudyTheme.alpha(pal.cardBg, 0.92f),
                StudyTheme.alpha(pal.accent, 0.55f)
            )
            elevation = dpf(4f)
            visibility = View.GONE
        }
        voiceRmsDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pal.accent)
            }
        }
        voiceBar.addView(voiceRmsDot, LinearLayout.LayoutParams(dp(12), dp(12)))

        voiceStatusText = StudyTheme.label(this, "Preparing mic…", 13f, pal.textPrimary, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(8), 0)
        }
        voiceBar.addView(voiceStatusText, LinearLayout.LayoutParams(0, -2, 1f))

        voiceBar.addView(
            StudyTheme.chip(this, "Done", pal, minHeightDp = 44) { voice.stopAndUse() }.apply {
                minimumWidth = dp(64)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setTextColor(pal.onAccent)
                background = StudyTheme.pillDrawable(this@StudyBoardActivity, 18f, pal.accent, pal.accent)
            },
            LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(4) }
        )
        voiceBar.addView(
            StudyTheme.iconButton(this, R.drawable.ic_close, "Cancel voice input", pal, 44, 10) {
                voice.cancel()
                listening = false
                refresh()
            },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )
        col.addView(voiceBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        // Row 3: Floating Composer Glass Bar
        inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(14), dp(4), dp(6), dp(4))
        }
        StudyTheme.applyGlass(inputBar, 27f, pal, soft = true)

        input = EditText(this).apply {
            hint = "Type a question or equation"
            setHintTextColor(pal.textMuted)
            setTextColor(pal.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = JarvisFonts.regular
            background = null
            maxLines = 4
            minHeight = dp(44)
            setPadding(dp(2), dp(8), dp(6), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        input.setOnFocusChangeListener { _, focused ->
            updateSymbolStrip(focused)
            updateInputFocusRing()
        }
        inputBar.addView(input)

        micButton = StudyTheme.iconButton(this, R.drawable.ic_mic_jarvis, "Ask by voice", pal, 44, 10) { toggleMic() }
        inputBar.addView(micButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(4) })

        sendButton = StudyTheme.iconButton(this, R.drawable.ic_send_up, "Send", pal, 44, 10, tint = pal.onAccent) { sendOrStop() }.apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.accent) }
        }
        inputBar.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)))

        col.addView(inputBar, LinearLayout.LayoutParams(-1, -2))
        return col
    }

    private fun updateSymbolStrip(show: Boolean) {
        if (show) {
            if (symbols.visibility != View.VISIBLE) {
                symbols.alpha = 0f
                symbols.visibility = View.VISIBLE
                symbols.animate().alpha(1f).setDuration(180L).start()
            }
        } else {
            symbols.visibility = View.GONE
        }
    }

    private fun updateInputFocusRing() {
        val active = input.hasFocus() || listening
        inputBar.foreground = if (active) {
            GradientDrawable().apply {
                cornerRadius = dpf(27f)
                setColor(Color.TRANSPARENT)
                setStroke(dp(1).coerceAtLeast((dpf(1.5f) + 0.5f).toInt()), StudyTheme.alpha(pal.accent, 0.8f))
            }
        } else {
            null
        }
    }

    private fun updateViewportPills() {
        if (!::board.isInitialized) return
        val pb = vm.playback
        val hasContent = vm.hasContent
        val showFit = hasContent && hasBoardOverflow && currentZoom > board.fitAllZoom() + 0.04f
        fitAllPill.visibility = if (showFit) View.VISIBLE else View.GONE

        val showZoom = hasContent && kotlin.math.abs(currentZoom - 1f) > 0.05f
        zoomResetPill.visibility = if (showZoom) View.VISIBLE else View.GONE
        if (showZoom) {
            val pct = (currentZoom * 100f + 0.5f).toInt()
            zoomResetPill.text = "$pct% · 100%"
        }
        val showFollow = pb.state == StudyPlayback.State.PLAYING && !board.followPen
        followPenPill.visibility = if (showFollow) View.VISIBLE else View.GONE
    }

    private fun updateProgressBar() {
        if (!::progressTrack.isInitialized) return
        val total = vm.blocks.value.size
        if (total <= 0) {
            progressTrack.visibility = View.GONE
            lastProgressFrac = -1f
            return
        }
        progressTrack.visibility = View.VISIBLE
        val pb = vm.playback
        val frac = when {
            pb.state == StudyPlayback.State.FINISHED -> 1f
            pb.current < 0 -> 0f
            else -> ((pb.current + pb.progress) / total.toFloat()).coerceIn(0f, 1f)
        }
        if (kotlin.math.abs(frac - lastProgressFrac) >= 0.01f || frac == 1f) {
            lastProgressFrac = frac
            val trackW = progressTrack.width
            if (trackW > 0) {
                val targetW = (trackW * frac).toInt().coerceIn(0, trackW)
                if (progressFill.layoutParams.width != targetW) {
                    progressFill.layoutParams = (progressFill.layoutParams as FrameLayout.LayoutParams).apply { width = targetW }
                    progressFill.requestLayout()
                }
            } else {
                progressTrack.post { updateProgressBar() }
            }
        }
    }

    private fun updateStatusAnimations(active: Boolean) {
        if (active) {
            if (statusDotPulse == null) {
                statusDotPulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.30f).apply {
                    duration = 850L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }
        } else {
            statusDotPulse?.cancel()
            statusDotPulse = null
            statusDot.alpha = 1f
        }

        if (listening) {
            if (micPulse == null) {
                micPulse = ObjectAnimator.ofFloat(micButton, View.ALPHA, 1f, 0.45f).apply {
                    duration = 650L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
            }
        } else {
            micPulse?.cancel()
            micPulse = null
            micButton.alpha = 1f
        }
    }

    private fun styleToolToggle(btn: ImageButton, active: Boolean, activeTint: Int = pal.accent) {
        if (active) {
            btn.background = StudyTheme.pillDrawable(
                this, 14f, StudyTheme.alpha(activeTint, 0.18f), StudyTheme.alpha(activeTint, 0.45f)
            )
            btn.setColorFilter(activeTint)
            btn.alpha = 1f
        } else {
            btn.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            btn.setColorFilter(pal.textMuted)
            btn.alpha = 0.85f
        }
    }

    // ======================================================================================================
    // State -> screen
    // ======================================================================================================

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.blocks.collect { board.setBlocks(it); refresh() } }
                launch { vm.version.collect { board.kick(); refresh() } }
                launch { vm.ui.collect { refresh() } }
                launch { vm.eraseLimit.collect { board.setEraseLimit(it) } }
                launch { vm.selectedBlock.collect { board.selectedBlock = it; refresh() } }
                launch { vm.bookmarks.collect { board.bookmarkedBlocks = it; refresh() } }
                launch { vm.askMode.collect { refresh() } }
                launch { vm.interrupted.collect { refresh() } }
                launch { vm.isNarrating.collect { refresh() } }
                launch { vm.activeVoiceName.collect { refresh() } }
                launch { vm.narrationAmplitude.collect { updateVoiceWave(it) } }
                launch { vm.spotlight.collect { s -> board.setNarrationSpotlight(s.active, s.blockIndex, s.blockProgress, s.transcript) } }
                launch { vm.events.collect { handle(it) } }
                launch { StudyBoardController.commands.collect { executeBoardCommand(it) } }
            }
        }
    }

    private fun refresh() {
        if (!::board.isInitialized) return
        val ui = vm.ui.value
        val pb = vm.playback
        board.showNotes = vm.prefs.notesOn
        subjectLine.text = ui.subjectLine.ifEmpty { vm.lesson?.question?.take(60) ?: "AI Whiteboard Teacher" }
        emptyHint.visibility = if (!vm.hasContent && ui.phase != StudyViewModel.Phase.EXTRACTING) View.VISIBLE else View.GONE
        val pending = vm.interrupted.value
        resumeButton.visibility = if (pending != null && !vm.hasContent) View.VISIBLE else View.GONE
        if (pending != null) resumeButton.text = "Resume: " + pending.second.take(36)

        val status = when {
            ui.phase == StudyViewModel.Phase.EXTRACTING -> "Reading…"
            pb.state == StudyPlayback.State.PLAYING -> "Writing"
            pb.state == StudyPlayback.State.PAUSED -> "Paused"
            pb.state == StudyPlayback.State.WAITING_FOR_CONTENT && ui.phase == StudyViewModel.Phase.GENERATING -> "Thinking…"
            else -> ""
        }
        board.showPen = vm.prefs.penOn
        statusPill.visibility = if (status.isEmpty() || !vm.prefs.statusOn) View.GONE else View.VISIBLE
        statusPill.text = status

        val showDrafting = ui.phase == StudyViewModel.Phase.EXTRACTING ||
            ui.phase == StudyViewModel.Phase.GENERATING ||
            (vm.isBusy && ui.loadingDetail.isNotEmpty())
        draftingPill.visibility = if (showDrafting) View.VISIBLE else View.GONE
        if (showDrafting) {
            draftingText.text = when {
                ui.loadingDetail.isNotEmpty() -> ui.loadingDetail
                ui.phase == StudyViewModel.Phase.EXTRACTING -> "Reading image & extracting question…"
                else -> "Preparing complete lesson & voice…"
            }
        }

        val dotColor = when {
            ui.phase == StudyViewModel.Phase.ERROR -> pal.error
            pb.state == StudyPlayback.State.PLAYING || vm.isBusy -> pal.accent
            pb.state == StudyPlayback.State.PAUSED -> pal.warning
            vm.hasContent -> pal.success
            else -> pal.textMuted
        }
        (statusDot.background as? GradientDrawable)?.setColor(dotColor)
        updateStatusAnimations(pb.state == StudyPlayback.State.PLAYING || vm.isBusy)
        updateViewportPills()
        updateProgressBar()

        val boardHighlight = pb.state == StudyPlayback.State.PLAYING || vm.isBusy
        if (activeBoardHighlight != boardHighlight) {
            activeBoardHighlight = boardHighlight
            boardFrame.foreground = GradientDrawable().apply {
                cornerRadius = dpf(22f)
                setColor(Color.TRANSPARENT)
                setStroke(
                    dp(1).coerceAtLeast((dpf(1.2f) + 0.5f).toInt()),
                    if (boardHighlight) StudyTheme.alpha(pal.accent, 0.55f) else pal.cardBorder
                )
            }
        }

        playButton.setImageResource(if (pb.state == StudyPlayback.State.PLAYING) R.drawable.ic_study_pause else R.drawable.ic_study_play)
        speedChip.text = speedLabel(pb.speed)
        langChip.text = languageShortLabel(
            vm.prefs.voiceLanguage.takeUnless { it.equals("auto", true) } ?: vm.prefs.language
        )
        fsPlay.setImageResource(if (pb.state == StudyPlayback.State.PLAYING) R.drawable.ic_study_pause else R.drawable.ic_study_play)
        voiceButton.setImageResource(if (vm.prefs.voiceOn) R.drawable.ic_study_volume_on else R.drawable.ic_study_volume_off)

        styleToolToggle(voiceButton, vm.prefs.voiceOn)
        styleToolToggle(notesButton, vm.prefs.notesOn)
        val step = vm.selectedStepNumber()
        val isBookmarked = step != null && vm.bookmarks.value.contains(step)
        styleToolToggle(bookmarkButton, step != null, if (isBookmarked) pal.warning else pal.accent)

        undoButton.isEnabled = vm.canUndo
        undoButton.alpha = if (vm.canUndo) 1f else 0.35f
        redoButton.isEnabled = vm.canRedo
        redoButton.alpha = if (vm.canRedo) 1f else 0.35f

        val counter = vm.stepCounter()
        val activeBlock = vm.blocks.value.getOrNull(pb.current)
        val kindTag = when {
            activeBlock == null || activeBlock.isQuestion -> ""
            activeBlock.isFinal -> " · Answer"
            activeBlock.kind == StepKind.DIAGRAM -> " · Diagram"
            activeBlock.kind == StepKind.FORMULA -> " · Formula"
            activeBlock.kind == StepKind.DEFINITION -> " · Concept"
            activeBlock.kind == StepKind.GIVEN -> " · Given"
            activeBlock.kind == StepKind.REQUIRED -> " · Find"
            else -> ""
        }
        val stepTint = when {
            activeBlock?.isFinal == true -> pal.success
            activeBlock?.kind == StepKind.DEFINITION || activeBlock?.kind == StepKind.REQUIRED -> pal.secondary
            else -> pal.accent
        }
        stepText.text = if (counter.isNotEmpty()) counter + kindTag else ""
        stepText.setTextColor(stepTint)
        stepText.background = StudyTheme.pillDrawable(this, 10f, StudyTheme.alpha(stepTint, 0.16f), StudyTheme.alpha(stepTint, 0.35f))
        stepText.visibility = if (counter.isEmpty()) View.GONE else View.VISIBLE

        val showBanner = ui.message.isNotEmpty()
        val isExtracting = ui.phase == StudyViewModel.Phase.EXTRACTING
        banner.visibility = if (showBanner) View.VISIBLE else View.GONE
        bannerText.text = ui.message
        bannerIcon.setImageResource(if (isExtracting) R.drawable.ic_study_image else R.drawable.ic_warning)
        bannerIcon.setColorFilter(if (isExtracting) pal.accent else pal.error)
        banner.background = if (isExtracting) {
            StudyTheme.pillDrawable(this, 18f, StudyTheme.alpha(pal.accent, 0.16f), StudyTheme.alpha(pal.accent, 0.45f))
        } else {
            StudyTheme.pillDrawable(this, 18f, pal.errorContainer, StudyTheme.alpha(pal.error, 0.55f))
        }
        bannerRetry.visibility = if (ui.phase == StudyViewModel.Phase.ERROR && ui.retryable) View.VISIBLE else View.GONE

        val ex = vm.currentExplanation
        val speakingNow = vm.isNarrating.value && vm.prefs.voiceOn
        val hasStepDetails = ex.isNotEmpty() || ui.verification.isNotEmpty() || counter.isNotEmpty() || speakingNow
        explainCard.visibility = if (vm.prefs.notesOn && hasStepDetails && vm.hasContent) View.VISIBLE else View.GONE
        explainText.text = ex
        explainText.visibility = if (ex.isEmpty()) View.GONE else View.VISIBLE
        explainToggle.visibility = if (ex.length > 90) View.VISIBLE else View.GONE
        explainToggle.text = if (explainExpanded) "Less ▴" else "More ▾"

        voiceLiveBadge.visibility = if (speakingNow) View.VISIBLE else View.GONE
        if (speakingNow) {
            val vName = vm.activeVoiceName.value.ifBlank { "JARVIS" }
            val vLang = vm.prefs.voiceLanguage.takeUnless { it.equals("auto", true) } ?: vm.prefs.language
            voiceWaveLabel.text = if (vLang.equals("auto", true)) vName else "$vName · ${languageShortLabel(vLang)}"
        }
        val activeStepNum = activeBlock?.stepIndex?.takeIf { it in 1..(vm.lesson?.steps?.size ?: 0) }
        explainActions.visibility = if (vm.hasContent && activeBlock != null && !activeBlock.isQuestion) View.VISIBLE else View.GONE
        explainReplayChip.text = if (activeStepNum != null) "Replay step $activeStepNum" else "Replay step"
        explainDoubtChip.visibility = if (vm.lesson?.steps?.isNotEmpty() == true && !vm.isBusy) View.VISIBLE else View.GONE
        explainDoubtChip.text = if (activeStepNum != null) "Ask about step $activeStepNum" else "Ask doubt"
        val verText = if (pb.state == StudyPlayback.State.FINISHED || ui.phase == StudyViewModel.Phase.READY) ui.verification else ""
        verifyText.text = verText
        verifyText.setTextColor(
            when {
                verText.contains("re-checked", true) -> pal.success
                verText.contains("could not", true) -> pal.error
                verText.contains("not fully sure", true) -> pal.warning
                else -> pal.textMuted
            }
        )

        val doubtMode = vm.askMode.value == StudyViewModel.AskMode.DOUBT
        chipNew.background = if (!doubtMode) {
            StudyTheme.pillDrawable(this, 17f, pal.accent, pal.accent)
        } else {
            GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dpf(17f) }
        }
        chipNew.setTextColor(if (!doubtMode) pal.onAccent else pal.textPrimary)

        chipDoubt.text = if (doubtMode && step != null) "Doubt · step $step" else "Doubt"
        chipDoubt.background = if (doubtMode) {
            StudyTheme.pillDrawable(this, 17f, pal.accent, pal.accent)
        } else {
            GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dpf(17f) }
        }
        chipDoubt.setTextColor(if (doubtMode) pal.onAccent else pal.textPrimary)
        chipDoubt.alpha = if (vm.lesson?.steps?.isNotEmpty() == true) 1f else 0.5f

        input.hint = if (listening) "Listening…" else if (doubtMode) (if (step != null) "Ask about step $step" else "Ask about the solution") else "Type a question or equation"

        val busy = vm.isBusy
        sendButton.setImageResource(if (busy) R.drawable.ic_study_stop else R.drawable.ic_send_up)
        sendButton.contentDescription = if (busy) "Stop" else "Send"
        val sendBg = if (busy) pal.error else pal.accent
        sendButton.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(sendBg) }
        sendButton.setColorFilter(StudyTheme.onColor(sendBg, pal.textPrimary, pal.baseBg))

        micButton.setColorFilter(if (listening) pal.accent else pal.textPrimary)
        micButton.background = if (listening) {
            StudyTheme.pillDrawable(this, 22f, StudyTheme.alpha(pal.accent, 0.20f), pal.accent)
        } else {
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
        }
        voiceBar.visibility = if (listening) View.VISIBLE else View.GONE
        if (!listening) {
            voiceRmsDot.scaleX = 1f
            voiceRmsDot.scaleY = 1f
        }
        updateInputFocusRing()
    }

    private fun insertSymbol(sym: String) {
        val text = if (sym == "x²") "²" else sym
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        input.text.replace(minOf(start, end), maxOf(start, end), text)
    }

    /** Hides the header and the controls so the board fills the screen, and hides the system bars. */
    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        val landscapeSide = side
        header.visibility = if (on) View.GONE else View.VISIBLE
        landscapeSide.visibility = if (on) View.GONE else View.VISIBLE
        val c = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            ImmersiveStatusBar.apply(window)
        }
        fullscreenButton.setImageResource(if (on) R.drawable.ic_study_fullscreen_exit else R.drawable.ic_study_fullscreen)
        fsOverlay.visibility = if (on) View.VISIBLE else View.GONE
        refresh()
    }

    private fun speedLabel(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "×"

    private fun cycleSpeed() {
        val steps = floatArrayOf(0.5f, 0.75f, 1f, 1.5f, 2f)
        val cur = vm.playback.speed
        val next = steps.firstOrNull { it > cur + 0.01f } ?: steps.first()
        vm.setSpeed(next)
    }

    private fun handle(e: StudyViewModel.Event) {
        when (e) {
            is StudyViewModel.Event.Toast -> toast(e.text)
            is StudyViewModel.Event.ConfirmQuestions -> StudyDialogs.confirmQuestions(this, e.questions, e.source, e.previewPath) { text, keep -> vm.solveConfirmed(text, keep) }
            is StudyViewModel.Event.PdfReady -> StudyDialogs.pdfPicker(this, lifecycleScope, e.path, e.pages,
                onPage = { page -> vm.extractFromPdfPage(e.path, page) }, onWhole = { q -> vm.askAboutPdf(e.path, q) })
            is StudyViewModel.Event.Reveal -> board.reveal(e.block)
        }
    }

    // ======================================================================================================
    // Actions
    // ======================================================================================================

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun sendOrStop() {
        if (vm.isBusy) { vm.cancel(); return }
        val t = input.text.toString().trim()
        if (t.isEmpty()) { toast("Type or say a question first."); return }
        hideKeyboard()
        input.setText("")
        board.followPen = true
        vm.submit(t)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun toggleMic() {
        if (listening) { voice.stopAndUse(); return }
        if (vm.isBusy) { toast("Stop the current answer before asking another question."); return }
        if (!PermissionHelper.hasRecordAudioPermission(this)) micPermission.launch(Manifest.permission.RECORD_AUDIO) else startListening()
    }

    private fun startListening() {
        vm.interruptForMic() // narration stops so the microphone does not hear it
        val namedLang = vm.prefs.voiceLanguage.takeUnless { it.equals("auto", true) }
            ?.takeIf { it.isNotBlank() }
            ?: vm.prefs.language.takeIf { it.isNotBlank() }
            ?: "English"
        val tag = StudyVoiceCodec.languageTag(namedLang, input.text?.toString().orEmpty())
        listening = true
        voiceStatusText.text = "Preparing mic…"
        voiceRmsDot.scaleX = 1f
        voiceRmsDot.scaleY = 1f
        refresh()
        voice.start(tag, object : StudyVoiceInput.Listener {
            override fun onListening() {
                voiceStatusText.text = "Listening… speak now"
            }
            override fun onRms(rmsdB: Float) {
                val scale = (1f + ((rmsdB + 2f).coerceIn(0f, 12f) / 16f)).coerceIn(0.95f, 1.75f)
                voiceRmsDot.scaleX = scale
                voiceRmsDot.scaleY = scale
            }
            override fun onPartial(text: String) {
                if (text.isNotBlank()) {
                    voiceStatusText.text = text
                    input.setText(text)
                    input.setSelection(input.text.length)
                }
            }
            override fun onFinal(text: String) {
                listening = false
                input.setText("")
                refresh()
                board.followPen = true
                vm.submit(text)
            }
            override fun onError(message: String) {
                listening = false
                refresh()
                toast(message)
            }
            override fun onFallbackPrompt(intent: Intent) {
                listening = false
                refresh()
                try {
                    systemVoicePrompt.launch(intent)
                } catch (_: Exception) {
                    toast("Speech recognition is not available on this device.")
                }
            }
        })
    }

    private fun requestCamera() {
        if (PermissionHelper.hasCameraPermission(this)) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        try {
            val f = StudyMedia.newCaptureFile(this)
            cameraFile = f
            takePicture.launch(FileProvider.getUriForFile(this, "$packageName.studyfiles", f))
        } catch (e: Exception) {
            toast("The camera could not be opened: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun confirmClear() {
        if (!vm.hasContent) return
        PremiumDialog.confirm(
            this,
            eyebrow = "BOARD",
            title = "Clear the board?",
            message = "The board is wiped. The lesson stays saved in your history, and Redo brings it back.",
            confirmLabel = "Clear",
            destructive = true
        ) { vm.clearBoard() }
    }

    private fun showExportMenu(anchor: View) {
        if (!vm.hasContent) { toast("Nothing to export yet."); return }
        val base = (vm.lesson?.topic?.takeIf { it.isNotBlank() } ?: "study-notes")
            .replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifEmpty { "study-notes" }.take(40)
        StudyDialogs.exportSheet(
            this,
            onPdf = { createPdf.launch("$base.pdf") },
            onImage = { createPng.launch("$base.png") }
        )
    }

    private fun export(uri: Uri, pdf: Boolean) {
        val blocks = vm.blocks.value
        val lesson = vm.lesson
        val notes = vm.prefs.notesOn
        val marks = vm.bookmarks.value
        toast("Exporting…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        if (pdf) StudyExport.writePdf(this@StudyBoardActivity, lesson?.topic?.ifBlank { null } ?: "Study notes",
                            listOfNotNull(lesson?.subject?.label, lesson?.level?.ifBlank { null }).joinToString(" · "), blocks, true, marks, out)
                        else StudyExport.writeImage(this@StudyBoardActivity, blocks, notes, marks, out)
                    } ?: throw StudyExport.ExportException("Could not open the destination file.")
                }
                toast(if (pdf) "PDF saved." else "Image saved.")
            } catch (e: StudyExport.ExportException) { toast(e.message ?: "Export failed.") }
            catch (e: Exception) { toast("Export failed: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    // ----- board host -----
    override fun onBlockTapped(index: Int) { vm.selectBlock(index) }
    override fun onUserPanned() { updateViewportPills() }

    // ----- voice wave & quick language & command control -----

    private fun updateVoiceWave(amplitude: Float) {
        if (voiceWaveBars.size < 4 || voiceLiveBadge.visibility != View.VISIBLE) return
        val level = amplitude.coerceIn(0.08f, 1f)
        val ratios = floatArrayOf(0.55f, 1.0f, 0.72f, 0.90f)
        val minH = dp(4)
        val maxExtra = dp(10)
        for (i in 0 until 4) {
            val bar = voiceWaveBars[i]
            val targetH = minH + (maxExtra * level * ratios[i]).toInt()
            if (bar.layoutParams.height != targetH) {
                bar.layoutParams = (bar.layoutParams as LinearLayout.LayoutParams).apply { height = targetH }
                bar.requestLayout()
            }
        }
    }

    private fun languageShortLabel(language: String): String = when (language.trim().lowercase()) {
        "", "auto" -> "AUTO"
        "english" -> "EN"
        "hindi" -> "HI"
        "hinglish" -> "HING"
        "bengali", "bangla" -> "BN"
        "marathi" -> "MR"
        "gujarati" -> "GU"
        "tamil" -> "TA"
        "telugu" -> "TE"
        "kannada" -> "KN"
        "malayalam" -> "ML"
        "punjabi" -> "PA"
        "urdu" -> "UR"
        "odia" -> "OR"
        "assamese" -> "AS"
        "nepali" -> "NE"
        "spanish" -> "ES"
        "french" -> "FR"
        "german" -> "DE"
        "portuguese" -> "PT"
        "arabic" -> "AR"
        "indonesian" -> "ID"
        "japanese" -> "JA"
        "korean" -> "KO"
        "chinese" -> "ZH"
        else -> language.trim().take(4).uppercase()
    }

    private fun showQuickLanguageDialog() {
        val current = vm.prefs.language
        val idx = StudyLevels.LANGUAGES.indexOfFirst { it.equals(current, true) }.coerceAtLeast(0)
        PremiumDialog.choice(
            this,
            "STUDY LANGUAGE",
            "Board & Voice Language",
            "Sets both board explanations and live JARVIS voice language.",
            StudyLevels.LANGUAGES,
            idx,
            instant = true
        ) { pick ->
            applyLanguageChoice(StudyLevels.LANGUAGES[pick], silent = false)
        }
    }

    private fun applyLanguageChoice(language: String, silent: Boolean) {
        val matched = StudyLevels.LANGUAGES.firstOrNull { it.equals(language.trim(), true) } ?: language.trim()
        if (matched.isEmpty()) return
        vm.prefs.language = matched
        vm.prefs.voiceLanguage = matched
        vm.warmUpVoice()
        vm.publishControllerSnapshot()
        refresh()
        if (!silent) toast("Study Board language: $matched")
    }

    private fun applyLevelChoice(level: String) {
        val matched = StudyLevels.CHOICES.firstOrNull { it.equals(level.trim(), true) } ?: level.trim()
        if (matched.isNotEmpty()) {
            vm.prefs.level = matched
            vm.publishControllerSnapshot()
        }
    }

    private fun applyDepthChoice(depth: String) {
        val matched = StudyProfile.Depth.values().firstOrNull { it.label.equals(depth.trim(), true) || it.name.equals(depth.trim(), true) } ?: return
        vm.prefs.depth = matched
        vm.publishControllerSnapshot()
    }

    private fun executeBoardCommand(cmd: StudyBoardController.StudyBoardCommand) {
        when (cmd) {
            is StudyBoardController.StudyBoardCommand.OpenAndExplain -> {
                typeAndSubmitQuestion(
                    question = cmd.question,
                    mode = cmd.mode,
                    language = cmd.language,
                    level = cmd.level,
                    depth = cmd.depth,
                    stepNumber = cmd.stepNumber,
                    animateTyping = cmd.animateTyping
                )
            }
            is StudyBoardController.StudyBoardCommand.Playback -> when (cmd.action) {
                StudyBoardController.PlaybackAction.PLAY -> {
                    when (vm.playback.state) {
                        StudyPlayback.State.PAUSED -> { vm.playback.resume(); board.followPen = true }
                        StudyPlayback.State.FINISHED -> { vm.replayAll(); board.followPen = true }
                        StudyPlayback.State.IDLE -> if (vm.hasContent) { vm.replayAll(); board.followPen = true }
                        else -> board.followPen = true
                    }
                }
                StudyBoardController.PlaybackAction.PAUSE -> vm.playback.pause()
                StudyBoardController.PlaybackAction.TOGGLE -> { vm.togglePlay(); board.followPen = true }
                StudyBoardController.PlaybackAction.NEXT -> { vm.next(); board.followPen = true }
                StudyBoardController.PlaybackAction.PREVIOUS -> { vm.previous(); board.followPen = true }
                StudyBoardController.PlaybackAction.REPLAY_STEP -> { vm.replayStep(); board.followPen = true }
                StudyBoardController.PlaybackAction.REPLAY_ALL -> { vm.replayAll(); board.followPen = true }
                StudyBoardController.PlaybackAction.STOP -> { typingJob?.cancel(); vm.cancel(); vm.stopSpeech() }
                StudyBoardController.PlaybackAction.RETRY -> vm.retry()
            }
            is StudyBoardController.StudyBoardCommand.SetSpeed -> {
                val target = cmd.speed ?: cmd.delta?.let { vm.playback.speed + it } ?: 1f
                vm.setSpeed(target.coerceIn(0.5f, 2.0f))
            }
            is StudyBoardController.StudyBoardCommand.ToggleSetting -> when (cmd.key) {
                StudyBoardController.SettingToggleKey.VOICE -> vm.setVoice(cmd.value ?: !vm.prefs.voiceOn)
                StudyBoardController.SettingToggleKey.NOTES -> vm.setNotes(cmd.value ?: !vm.prefs.notesOn)
                StudyBoardController.SettingToggleKey.PEN -> {
                    vm.prefs.penOn = cmd.value ?: !vm.prefs.penOn
                    board.showPen = vm.prefs.penOn
                    vm.publishControllerSnapshot()
                    refresh()
                }
                StudyBoardController.SettingToggleKey.STATUS -> {
                    vm.prefs.statusOn = cmd.value ?: !vm.prefs.statusOn
                    vm.publishControllerSnapshot()
                    refresh()
                }
                StudyBoardController.SettingToggleKey.MEMORY -> vm.setMemory(cmd.value ?: !vm.prefs.memoryOn)
                StudyBoardController.SettingToggleKey.FULLSCREEN -> setFullscreen(cmd.value ?: !fullscreen)
                StudyBoardController.SettingToggleKey.FOLLOW_PEN -> {
                    val on = cmd.value ?: !board.followPen
                    board.followPen = on
                    if (on) board.scrollToEnd()
                    updateViewportPills()
                    StudyBoardController.updateState { it.copy(followPen = on) }
                }
                StudyBoardController.SettingToggleKey.BOOKMARK_CURRENT -> vm.toggleBookmark()
            }
            is StudyBoardController.StudyBoardCommand.SetOption -> when (cmd.key) {
                StudyBoardController.SettingOptionKey.LANGUAGE -> applyLanguageChoice(cmd.value, silent = false)
                StudyBoardController.SettingOptionKey.VOICE_LANGUAGE -> {
                    val matched = StudyLevels.LANGUAGES.firstOrNull { it.equals(cmd.value.trim(), true) } ?: cmd.value.trim()
                    if (matched.isNotEmpty()) {
                        vm.prefs.voiceLanguage = matched
                        vm.warmUpVoice()
                        vm.publishControllerSnapshot()
                        refresh()
                    }
                }
                StudyBoardController.SettingOptionKey.LEVEL -> applyLevelChoice(cmd.value)
                StudyBoardController.SettingOptionKey.DEPTH -> applyDepthChoice(cmd.value)
                StudyBoardController.SettingOptionKey.PROVIDER -> {
                    vm.prefs.provider = if (cmd.value.equals("custom", true)) StudyPrefs.PROVIDER_CUSTOM else StudyPrefs.PROVIDER_GEMINI
                    vm.publishControllerSnapshot()
                }
                StudyBoardController.SettingOptionKey.ASK_MODE -> {
                    if (cmd.value.equals("doubt", true) && vm.lesson?.steps?.isNotEmpty() == true) {
                        vm.askMode.value = StudyViewModel.AskMode.DOUBT
                    } else {
                        vm.newQuestionMode()
                    }
                    refresh()
                }
            }
            is StudyBoardController.StudyBoardCommand.CanvasAction -> when (cmd.action) {
                StudyBoardController.BoardCanvasAction.ZOOM_IN -> board.zoomBy(1.4f)
                StudyBoardController.BoardCanvasAction.ZOOM_OUT -> board.zoomBy(1f / 1.4f)
                StudyBoardController.BoardCanvasAction.ZOOM_RESET -> board.resetZoom()
                StudyBoardController.BoardCanvasAction.FIT_BOARD -> { board.followPen = false; board.fitToBoard() }
                StudyBoardController.BoardCanvasAction.UNDO -> vm.undo()
                StudyBoardController.BoardCanvasAction.REDO -> vm.redo()
                StudyBoardController.BoardCanvasAction.CLEAR_BOARD -> vm.clearBoard()
                StudyBoardController.BoardCanvasAction.CLEAR_MEMORY -> vm.clearMemory()
                StudyBoardController.BoardCanvasAction.SAVE_LESSON -> vm.saveSession()
            }
            is StudyBoardController.StudyBoardCommand.SelectStep -> {
                if (vm.seekToStepNumber(cmd.stepNumber)) board.followPen = true
                else toast("Step ${cmd.stepNumber} is not on the board yet.")
            }
            is StudyBoardController.StudyBoardCommand.OpenUi -> when (cmd.target) {
                StudyBoardController.UiTarget.HISTORY -> historyLauncher.launch(Intent(this, StudyHistoryActivity::class.java))
                StudyBoardController.UiTarget.CAMERA -> requestCamera()
                StudyBoardController.UiTarget.IMAGE_PICKER -> launchImagePicker()
                StudyBoardController.UiTarget.PDF_PICKER -> pickPdf.launch(arrayOf("application/pdf"))
                StudyBoardController.UiTarget.TEACHING_SETTINGS -> StudyDialogs.settings(this, vm.prefs, { vm.clearMemory() }) { vm.setVoice(vm.prefs.voiceOn) }
                StudyBoardController.UiTarget.EXPORT_SHEET -> showExportMenu(header)
                StudyBoardController.UiTarget.EXPORT_PDF -> if (vm.hasContent) {
                    val base = (vm.lesson?.topic?.takeIf { it.isNotBlank() } ?: "study-notes")
                        .replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifEmpty { "study-notes" }.take(40)
                    createPdf.launch("$base.pdf")
                } else toast("Nothing to export yet.")
                StudyBoardController.UiTarget.EXPORT_PNG -> if (vm.hasContent) {
                    val base = (vm.lesson?.topic?.takeIf { it.isNotBlank() } ?: "study-notes")
                        .replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifEmpty { "study-notes" }.take(40)
                    createPng.launch("$base.png")
                } else toast("Nothing to export yet.")
            }
            StudyBoardController.StudyBoardCommand.CloseBoard -> finish()
        }
        StudyBoardController.updateState { it.copy(fullscreen = fullscreen, followPen = board.followPen) }
    }

    // ----- lifecycle -----
    override fun onStart() {
        super.onStart()
        StudyBoardController.setBoardVisible(true)
        vm.warmUpVoice()
        val queued = StudyBoardController.attachReceiver()
        for (cmd in queued) executeBoardCommand(cmd)
        if (vm.resumeAfterBackground) { vm.resumeAfterBackground = false; vm.playback.resume() }
        vm.publishControllerSnapshot()
        board.kick()
    }

    override fun onStop() {
        StudyBoardController.detachReceiver()
        StudyBoardController.setBoardVisible(false)
        if (listening) { voice.cancel(); listening = false }
        if (vm.playback.state == StudyPlayback.State.PLAYING) { vm.resumeAfterBackground = true; vm.playback.pause() }
        vm.stopSpeech()
        super.onStop()
    }

    override fun onDestroy() {
        typingJob?.cancel()
        statusDotPulse?.cancel()
        micPulse?.cancel()
        voice.cancel()
        super.onDestroy()
    }

    companion object {
        /** Intent extra: a question to solve as soon as the board opens (used by the voice assistant). */
        const val EXTRA_QUESTION = "study_question"
        const val EXTRA_MODE = "study_mode"
        const val EXTRA_LANGUAGE = "study_language"
        const val EXTRA_LEVEL = "study_level"
        const val EXTRA_DEPTH = "study_depth"
        const val EXTRA_STEP = "study_step"
    }
}
