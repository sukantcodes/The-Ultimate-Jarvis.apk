package com.jarvis.assistant.ui.studyboard

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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
import com.jarvis.assistant.study.StudyPlayback
import com.jarvis.assistant.study.voice.StudyVoiceInput
import com.jarvis.assistant.ui.glass.GlassBackdropView
import com.jarvis.assistant.ui.premium.PremiumDialog
import com.jarvis.assistant.util.ImmersiveStatusBar
import com.jarvis.assistant.util.JarvisFonts
import com.jarvis.assistant.util.JarvisHandlesInsets
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
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
    private lateinit var verifyText: TextView
    private lateinit var playButton: ImageButton
    private lateinit var speedChip: TextView
    private lateinit var voiceButton: ImageButton
    private lateinit var notesButton: ImageButton
    private lateinit var bookmarkButton: ImageButton
    private lateinit var input: EditText
    private lateinit var inputBar: LinearLayout
    private lateinit var sendButton: ImageButton
    private lateinit var micButton: ImageButton
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

    // ----- result launchers -----
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else toast("Microphone permission is needed for voice questions.")
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

    /** "Explain this in Study Board" by voice: the question arrives in the intent and runs as soon as the board is open. */
    private fun runQuestionFrom(intent: Intent?) {
        val q = intent?.getStringExtra(EXTRA_QUESTION)?.trim().orEmpty()
        if (q.isEmpty()) return
        intent?.removeExtra(EXTRA_QUESTION) // so a rotation or a theme rebuild never asks it twice
        val start = { vm.newQuestionMode(); vm.startLesson(q, emptyList()) }
        if (vm.isBusy) {
            // A new spoken question replaces the one being written: stop it, then start the new one.
            vm.cancel()
            window.decorView.postDelayed({ if (!isFinishing) start() }, 500L)
        } else start()
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

        // ---- Header ----
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        header.addView(
            StudyTheme.iconButton(this, R.drawable.ic_back, "Back", pal, sizeDp = 46, padDp = 11) { finish() }.apply {
                StudyTheme.applyGlass(this, 16f, pal, soft = true)
            }
        )

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), 0, dp(8), 0)
        }
        val eyebrowRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(pal.accent) }
        }
        eyebrowRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) })
        eyebrowRow.addView(
            StudyTheme.label(this, "STUDY BOARD", 11.5f, pal.accent, hud = true).apply { letterSpacing = 0.14f }
        )
        statusPill = StudyTheme.label(this, "", 10.5f, pal.onAccent, bold = true).apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply { cornerRadius = dpf(10f); setColor(pal.accent) }
            visibility = View.GONE
            maxLines = 1
        }
        eyebrowRow.addView(statusPill, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
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
            vm.playback.togglePause()
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
            setPadding(dp(14), dp(11), dp(14), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                explainExpanded = !explainExpanded
                explainText.maxLines = if (explainExpanded) 12 else if (landscape) 6 else 4
            }
        }
        StudyTheme.applyGlass(explainCard, 20f, pal)

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
        explainMeta.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        verifyText = StudyTheme.label(this, "", 11f, pal.textSecondary, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        explainMeta.addView(verifyText)
        explainCard.addView(explainMeta)

        explainText = StudyTheme.label(this, "", 14f, pal.textPrimary).apply {
            maxLines = if (landscape) 6 else 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dpf(2.5f), 1.08f)
            setPadding(0, dp(6), 0, 0)
        }
        explainCard.addView(explainText)

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

        resumeButton = StudyTheme.label(this, "", 13f, StudyTheme.onColor(paperAccent, Color.WHITE, Color.BLACK), bold = true).apply {
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
        val starters = listOf(
            Triple("Human brain diagram", "Draw a detailed labelled diagram of the human brain", StudyPalette.color(Ink.VIOLET)),
            Triple("Human heart diagram", "Draw a detailed labelled diagram of the human heart", StudyPalette.color(Ink.WARN)),
            Triple("Human eye cross-section", "Draw a detailed labelled diagram of the human eye", StudyPalette.color(Ink.TEAL)),
            Triple("Convex lens rays", "Draw a labelled ray diagram of image formation by a convex lens", paperAccent),
            Triple("Plant cell structure", "Draw a neatly labelled diagram of a plant cell", StudyPalette.color(Ink.RESULT)),
            Triple("Plot y = x^2 - 4", "Plot y = x^2 - 4 and y = 2x and find their intersection", StudyPalette.color(Ink.ORANGE))
        )
        val demoScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val demoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for ((title, prompt, tone) in starters) {
            demoRow.addView(
                StudyTheme.label(this, title, 12f, tone, bold = true).apply {
                    minHeight = dp(44)
                    gravity = Gravity.CENTER
                    setPadding(dp(13), dp(8), dp(13), dp(8))
                    background = StudyTheme.pillDrawable(
                        this@StudyBoardActivity, 16f,
                        StudyTheme.alpha(tone, 0.10f),
                        StudyTheme.alpha(tone, 0.36f)
                    )
                    isClickable = true
                    isFocusable = true
                    StudyTheme.applyPressSpring(this, 0.95f)
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
        demoScroller.addView(demoRow)
        box.addView(demoScroller, LinearLayout.LayoutParams(-2, -2))

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

        row.addView(StudyTheme.iconButton(this, R.drawable.ic_study_prev, "Previous step", pal, 44, 10) { vm.previous(); board.followPen = true })
        playButton = StudyTheme.iconButton(this, R.drawable.ic_study_pause, "Pause or resume writing", pal, 48, 12, tint = pal.onAccent) {
            vm.togglePlay(); board.followPen = true
        }.apply {
            elevation = dpf(3f)
            background = StudyTheme.gradientPillDrawable(this@StudyBoardActivity, 24f, pal.accent, pal.secondary)
        }
        row.addView(playButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(2), 0, dp(2), 0) })
        row.addView(StudyTheme.iconButton(this, R.drawable.ic_study_next, "Next step", pal, 44, 10) { vm.next(); board.followPen = true })
        row.addView(StudyTheme.iconButton(this, R.drawable.ic_study_replay, "Replay this step", pal, 44, 10) { vm.replayStep(); board.followPen = true })
        row.addView(StudyTheme.iconButton(this, R.drawable.ic_study_replay_all, "Replay the whole solution", pal, 44, 10) { vm.replayAll(); board.followPen = true })
        speedChip = StudyTheme.chip(this, "1×", pal, minHeightDp = 44) { cycleSpeed() }.apply {
            minWidth = dp(48)
            typeface = JarvisFonts.hudBold
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        row.addView(speedChip, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(2) })
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
                launch { vm.events.collect { handle(it) } }
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
        explainCard.visibility = if (vm.prefs.notesOn && (ex.isNotEmpty() || ui.verification.isNotEmpty() || counter.isNotEmpty()) && vm.hasContent) View.VISIBLE else View.GONE
        explainText.text = ex
        explainText.visibility = if (ex.isEmpty()) View.GONE else View.VISIBLE
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
        val tag = when (vm.prefs.voiceLanguage.lowercase()) {
            "hindi" -> "hi-IN"
            "english" -> "en-IN"
            else -> if (vm.prefs.language.equals("Hindi", true)) "hi-IN" else "en-IN"
        }
        listening = true
        refresh()
        voice.start(tag, object : StudyVoiceInput.Listener {
            override fun onListening() {}
            override fun onPartial(text: String) { input.setText(text); input.setSelection(input.text.length) }
            override fun onFinal(text: String) {
                listening = false
                input.setText("")
                refresh()
                board.followPen = true
                vm.submit(text)
            }
            override fun onError(message: String) { listening = false; refresh(); toast(message) }
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

    // ----- lifecycle -----
    override fun onStart() {
        super.onStart()
        if (vm.resumeAfterBackground) { vm.resumeAfterBackground = false; vm.playback.resume() }
        board.kick()
    }

    override fun onStop() {
        if (listening) { voice.cancel(); listening = false }
        if (vm.playback.state == StudyPlayback.State.PLAYING) { vm.resumeAfterBackground = true; vm.playback.pause() }
        vm.stopSpeech()
        super.onStop()
    }

    override fun onDestroy() {
        statusDotPulse?.cancel()
        micPulse?.cancel()
        voice.cancel()
        super.onDestroy()
    }

    companion object {
        /** Intent extra: a question to solve as soon as the board opens (used by the voice assistant). */
        const val EXTRA_QUESTION = "study_question"
    }
}
