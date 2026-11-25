package com.jarvis.assistant.study.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityStudyBoardBinding
import com.jarvis.assistant.study.data.StudySessionEntity
import com.jarvis.assistant.study.export.StudyExportManager
import com.jarvis.assistant.study.model.BoardPlaybackStatus
import com.jarvis.assistant.study.model.EducationLevel
import com.jarvis.assistant.study.model.ExplanationDepth
import com.jarvis.assistant.study.model.StudyLanguage
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.render.StudyAnimationEngine
import com.jarvis.assistant.study.render.StudyWhiteboardView
import com.jarvis.assistant.ui.glass.LiquidGlass
import com.jarvis.assistant.util.JarvisFonts
import com.jarvis.assistant.util.JarvisHandlesInsets
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Dedicated full-screen Study Board classroom whiteboard screen.
 * Preserves Chatbox and all existing JARVIS navigation while delivering real-time
 * structured teaching across portrait, landscape, dark/light/custom themes & distraction-free mode.
 */
class StudyBoardActivity : AppCompatActivity(), JarvisHandlesInsets {

    private lateinit var binding: ActivityStudyBoardBinding
    private lateinit var viewModel: StudyBoardViewModel

    private var pendingCameraUri: Uri? = null
    private var ocrSheetDialog: BottomSheetDialog? = null
    private var doubtInputRef: EditText? = null
    private var pendingVoiceForDoubt: Boolean = false

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (pendingVoiceForDoubt) {
                    val targetEdit = doubtInputRef
                    viewModel.startVoiceQuestion { recognized ->
                        targetEdit?.setText(recognized)
                        targetEdit?.setSelection(recognized.length)
                    }
                } else {
                    viewModel.startVoiceQuestion()
                }
            } else {
                Toast.makeText(this, "Microphone permission is required for voice questions.", Toast.LENGTH_SHORT).show()
            }
            pendingVoiceForDoubt = false
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCameraCapture()
            else Toast.makeText(this, "Camera permission is needed to photograph questions.", Toast.LENGTH_SHORT).show()
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (saved && uri != null) {
                viewModel.handleImageInput(uri, fromCamera = true)
            }
        }

    private val pickGalleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                viewModel.handleImageInput(uri, fromCamera = false)
            }
        }

    private val pickPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                viewModel.handlePdfInput(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityStudyBoardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[StudyBoardViewModel::class.java]

        setupThemeAndGlass()
        setupInsets()
        setupTypography()
        setupWhiteboard()
        setupMathSymbolStrip()
        setupControls()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applyTheme(this)
        LiquidGlass.installBackdrop(binding.studyRoot, withBase = true)
        ThemeManager.applyDynamicBackground(binding.studyRoot)
        binding.studyWhiteboard.applyThemeRefresh()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopVoiceListening()
        viewModel.saveProgressOnBackground()
    }

    override fun onDestroy() {
        ocrSheetDialog?.dismiss()
        ocrSheetDialog = null
        doubtInputRef = null
        super.onDestroy()
    }

    private fun setupThemeAndGlass() {
        LiquidGlass.installBackdrop(binding.studyRoot, withBase = true)
        ThemeManager.applyDynamicBackground(binding.studyRoot)
        LiquidGlass.apply(binding.studyBoardContainer, radiusDp = 18f)
        LiquidGlass.applySoft(binding.studyBoardOverlayTools, radiusDp = 24f)
        LiquidGlass.applySoft(binding.studyFullscreenMiniBar, radiusDp = 24f)
        LiquidGlass.applySoft(binding.studyExplanationBar, radiusDp = 16f)
        LiquidGlass.apply(binding.studyInputDock, radiusDp = 20f)
    }

    private fun setupInsets() {
        val basePadL = binding.studyContentContainer.paddingLeft
        val basePadT = binding.studyContentContainer.paddingTop
        val basePadR = binding.studyContentContainer.paddingRight
        val basePadB = binding.studyContentContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.studyRoot) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(sysBars.bottom, ime.bottom) + basePadB
            binding.studyContentContainer.setPadding(
                sysBars.left + basePadL,
                sysBars.top + basePadT,
                sysBars.right + basePadR,
                bottom
            )
            insets
        }
    }

    private fun setupTypography() {
        binding.tvStudyTitle.typeface = JarvisFonts.displayBold
        binding.tvStudyMeta.typeface = JarvisFonts.medium
        binding.tvStudyLiveExplanation.typeface = JarvisFonts.medium
        binding.btnStudySpeed.typeface = JarvisFonts.monoMedium
        binding.tvStudyStepCounter.typeface = JarvisFonts.monoMedium
    }

    private fun setupWhiteboard() {
        binding.studyWhiteboard.attachEngine(viewModel.animationEngine, object : StudyWhiteboardView.Listener {
            override fun onFrameTick(deltaMs: Long): StudyAnimationEngine.FrameOutcome =
                viewModel.onFrameTick(deltaMs)

            override fun onStepTapped(stepIndex: Int) {
                viewModel.onStepTapped(stepIndex)
            }

            override fun onStepBookmarkTapped(stepIndex: Int) {
                viewModel.toggleStepBookmark(stepIndex)
            }

            override fun onUserScrolledManually(followingLive: Boolean) {
                viewModel.updateFollowingLive(followingLive)
            }
        })
    }

    private fun setupMathSymbolStrip() {
        val symbols = listOf(
            "x²" to "^2",
            "√" to "√()",
            "^" to "^",
            "/" to "/",
            "π" to "π",
            "θ" to "θ",
            "Δ" to "Δ",
            "→" to " → ",
            "⇌" to " ⇌ ",
            "°" to "°",
            "±" to "±",
            "≤" to "≤",
            "≥" to "≥",
            "H₂O" to "H2O"
        )
        val container = binding.studySymbolContainer
        container.removeAllViews()
        val accent = ThemeManager.getThemeAccentColor()
        val cardBg = resolveAttrColor(R.attr.themeCardBg)
        val border = resolveAttrColor(R.attr.themeCardBorder)
        val textCol = resolveAttrColor(R.attr.themeTextPrimary)
        val d = resources.displayMetrics.density

        symbols.forEach { (label, insertText) ->
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                setTextColor(textCol)
                textSize = 13.5f
                minWidth = (44 * d).toInt()
                minimumWidth = (44 * d).toInt()
                minHeight = (44 * d).toInt()
                minimumHeight = (44 * d).toInt()
                setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
                background = ThemeManager.createDynamicCardDrawable(
                    strokeColor = border,
                    fillColor = cardBg,
                    cornerRadiusDp = 12f,
                    strokeWidthDp = 1f,
                    context = this@StudyBoardActivity
                )
                setOnClickListener { insertSymbolIntoInput(insertText) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (40 * d).toInt()
            ).apply {
                marginEnd = (6 * d).toInt()
            }
            btn.setTextColor(if (label == "→" || label == "⇌") accent else textCol)
            container.addView(btn, lp)
        }
    }

    private fun insertSymbolIntoInput(token: String) {
        val et = binding.etStudyQuestion
        val editable = et.text ?: return
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(start)
        editable.replace(start, end, token)
        val cursorOffset = if (token == "√()") start + 2 else start + token.length
        et.setSelection(cursorOffset.coerceAtMost(editable.length))
        et.requestFocus()
    }

    private fun setupControls() {
        binding.btnStudyBack.setOnClickListener { finish() }
        binding.btnStudyHistory.setOnClickListener { showHistorySheet() }
        binding.btnStudyPersonalize.setOnClickListener { showPersonalizeSheet() }
        binding.btnStudyVoiceToggle.setOnClickListener { viewModel.toggleVoiceEnabled() }
        binding.btnStudyExport.setOnClickListener { showExportSheet() }
        binding.btnStudyFullscreen.setOnClickListener { viewModel.toggleFullscreen() }
        binding.btnFsExit.setOnClickListener { viewModel.toggleFullscreen() }

        binding.btnStudyUndo.setOnClickListener { viewModel.undoBoard() }
        binding.btnStudyRedo.setOnClickListener { viewModel.redoBoard() }
        binding.btnStudyZoomIn.setOnClickListener { binding.studyWhiteboard.zoomIn() }
        binding.btnStudyZoomOut.setOnClickListener { binding.studyWhiteboard.zoomOut() }
        binding.btnStudyFitLive.setOnClickListener { binding.studyWhiteboard.followLiveWriting() }
        binding.btnStudyClear.setOnClickListener { viewModel.clearBoard() }

        binding.btnStudyPrev.setOnClickListener { viewModel.previousStep() }
        binding.btnFsPrev.setOnClickListener { viewModel.previousStep() }
        binding.btnStudyPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        binding.btnFsPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        binding.btnStudyNext.setOnClickListener { viewModel.nextStep() }
        binding.btnFsNext.setOnClickListener { viewModel.nextStep() }
        binding.btnStudyReplayStep.setOnClickListener { viewModel.replayCurrentStep() }
        binding.btnStudyReplayAll.setOnClickListener { viewModel.replayAll() }
        binding.btnStudySpeed.setOnClickListener { viewModel.cycleSpeed() }
        binding.btnStudyBookmark.setOnClickListener { viewModel.toggleStepBookmark() }
        binding.btnStudyToggleExplain.setOnClickListener { viewModel.toggleExplanationsVisibility() }
        binding.btnStudySave.setOnClickListener { viewModel.saveLessonExplicit() }
        binding.btnStudyAskDoubt.setOnClickListener { showDoubtSheet() }
        binding.btnStudyRetry.setOnClickListener { viewModel.retryLastQuestion() }

        binding.btnStudyCamera.setOnClickListener { onCameraClicked() }
        binding.btnStudyGallery.setOnClickListener { pickGalleryLauncher.launch("image/*") }
        binding.btnStudyPdf.setOnClickListener { pickPdfLauncher.launch(arrayOf("application/pdf")) }
        binding.btnStudySymbols.setOnClickListener {
            binding.studySymbolStrip.visibility =
                if (binding.studySymbolStrip.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnStudyMic.setOnClickListener {
            if (viewModel.uiState.value.isVoiceListening) {
                viewModel.stopVoiceListening()
            } else {
                startVoiceWithPermission(forDoubt = false)
            }
        }

        binding.btnStudySend.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isGenerating) {
                viewModel.cancelGeneration()
            } else {
                submitTypedQuestion()
            }
        }

        binding.etStudyQuestion.setOnEditorActionListener { _, actionId, event ->
            val isEnterSend = actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN && !event.isShiftPressed)
            if (isEnterSend) {
                submitTypedQuestion()
                true
            } else {
                false
            }
        }
    }

    private fun submitTypedQuestion() {
        val text = binding.etStudyQuestion.text?.toString().orEmpty().trim()
        if (text.isBlank()) {
            Toast.makeText(this, "Enter an educational question first.", Toast.LENGTH_SHORT).show()
            return
        }
        binding.etStudyQuestion.setText("")
        viewModel.submitQuestion(text)
    }

    private fun startVoiceWithPermission(forDoubt: Boolean) {
        pendingVoiceForDoubt = forDoubt
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (forDoubt) {
                val targetEdit = doubtInputRef
                viewModel.startVoiceQuestion { recognized ->
                    targetEdit?.setText(recognized)
                    targetEdit?.setSelection(recognized.length)
                }
            } else {
                viewModel.startVoiceQuestion()
            }
            pendingVoiceForDoubt = false
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onCameraClicked() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            launchCameraCapture()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraCapture() {
        runCatching {
            val dir = File(cacheDir, "study_camera").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000L }?.forEach { it.delete() }
            val photoFile = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        }.onFailure { err ->
            Toast.makeText(this, "Unable to launch camera: ${err.message ?: "error"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state -> renderState(state) }
                }
                launch {
                    viewModel.events.collect { ev -> handleEvent(ev) }
                }
            }
        }
    }

    private fun handleEvent(ev: StudyBoardViewModel.UiEvent) {
        when (ev) {
            is StudyBoardViewModel.UiEvent.ToastMessage -> {
                Toast.makeText(this, ev.message, Toast.LENGTH_SHORT).show()
            }
            is StudyBoardViewModel.UiEvent.ExportReady -> {
                showExportCompletedDialog(ev.artifact)
            }
            is StudyBoardViewModel.UiEvent.RequestDoubtTextFromVoice -> {
                doubtInputRef?.setText(ev.recognizedText)
                doubtInputRef?.setSelection(ev.recognizedText.length)
            }
            is StudyBoardViewModel.UiEvent.SyncBoardView -> {
                binding.studyWhiteboard.syncWithEngine(forceLayout = false)
            }
            is StudyBoardViewModel.UiEvent.ScrollToStep -> {
                binding.studyWhiteboard.scrollToStep(ev.stepIndex)
            }
        }
    }

    private fun renderState(state: StudyBoardViewModel.StudyUiState) {
        val subjLabel = state.subject?.displayName ?: getString(R.string.study_board_title)
        binding.tvStudyTitle.text = if (state.topic.isNotBlank()) "$subjLabel · ${state.topic}" else subjLabel
        binding.tvStudyMeta.text = buildString {
            append(state.profile.educationLevel.label)
            append(" · ")
            append(state.profile.preferredLanguage.label)
            append(" · ")
            append(state.profile.preferredDepth.label)
        }

        // Display preferences & whiteboard grid/notes
        binding.studyWhiteboard.setDisplayOptions(state.profile.showGrid, state.profile.showExplanations)

        // Banner state (Loading / Error / Partial Voice)
        val bannerMsg = when {
            state.isVoiceListening && state.voicePartialText.isNotBlank() -> "\"${state.voicePartialText}\""
            else -> state.statusBannerText
        }
        if (bannerMsg.isNullOrBlank() && !state.isAnalyzingMedia && !state.isGenerating) {
            binding.studyStatusBanner.visibility = View.GONE
        } else {
            binding.studyStatusBanner.visibility = View.VISIBLE
            binding.tvStudyBannerMessage.text = bannerMsg ?: "Processing…"
            binding.studyLoadingSpinner.visibility =
                if ((state.isAnalyzingMedia || state.isGenerating || state.isVoiceListening) && !state.isErrorBanner) View.VISIBLE else View.GONE
            binding.btnStudyRetry.visibility = if (state.canRetry && state.isErrorBanner) View.VISIBLE else View.GONE
            val warnColor = ThemeManager.getThemeWarningColor()
            val primaryText = resolveAttrColor(R.attr.themeTextPrimary)
            binding.tvStudyBannerMessage.setTextColor(if (state.isErrorBanner) warnColor else primaryText)
        }

        // Live Explanation Bar
        val hasExplain = state.profile.showExplanations && state.totalSteps > 0
        binding.studyExplanationBar.visibility = if (hasExplain && !state.isFullscreen) View.VISIBLE else View.GONE
        if (hasExplain) {
            binding.tvStudyStepCounter.text = "Step ${state.focusedStepIndex + 1}/${state.totalSteps}"
            binding.tvStudyLiveExplanation.text = state.liveExplanationText.ifBlank { "Following step on board…" }
        }

        // Play/Pause Icon & Controls
        val isPlaying = state.playbackStatus == BoardPlaybackStatus.PLAYING ||
            state.playbackStatus == BoardPlaybackStatus.STREAMING
        val playPauseRes = if (isPlaying) R.drawable.ic_study_pause else R.drawable.ic_study_play
        binding.btnStudyPlayPause.setImageResource(playPauseRes)
        binding.btnFsPlayPause.setImageResource(playPauseRes)

        val speedFormatted = if (state.profile.writingSpeed % 1f == 0f) {
            "${state.profile.writingSpeed.toInt()}x"
        } else {
            "${state.profile.writingSpeed}x"
        }
        binding.btnStudySpeed.text = speedFormatted

        val accent = ThemeManager.getThemeAccentColor()
        val secondaryText = resolveAttrColor(R.attr.themeTextSecondary)
        val warnColor = ThemeManager.getThemeWarningColor()

        binding.btnStudyVoiceToggle.setColorFilter(if (state.profile.voiceEnabled) accent else secondaryText)
        binding.btnStudyBookmark.setColorFilter(if (state.isFocusedStepBookmarked) warnColor else secondaryText)
        binding.btnStudyToggleExplain.setTextColor(if (state.profile.showExplanations) accent else secondaryText)
        binding.btnStudyUndo.isEnabled = state.canUndo
        binding.btnStudyUndo.alpha = if (state.canUndo) 1f else 0.35f
        binding.btnStudyRedo.isEnabled = state.canRedo
        binding.btnStudyRedo.alpha = if (state.canRedo) 1f else 0.35f
        binding.btnStudyFitLive.setColorFilter(if (state.isFollowingLive) accent else secondaryText)

        // Mic pulse feedback & Send/Stop toggle
        binding.btnStudyMic.setColorFilter(if (state.isVoiceListening && !state.voiceTargetDoubtMode) warnColor else accent)
        if (state.isVoiceListening) {
            val scale = 1f + state.voiceRmsLevel * 0.24f
            binding.btnStudyMic.scaleX = scale
            binding.btnStudyMic.scaleY = scale
        } else {
            binding.btnStudyMic.scaleX = 1f
            binding.btnStudyMic.scaleY = 1f
        }
        binding.btnStudySend.setImageResource(if (state.isGenerating) R.drawable.ic_study_stop else R.drawable.ic_study_send)

        // Fullscreen distraction-free mode
        applyFullscreenUi(state.isFullscreen)

        // Verification Gate Sheet for Camera / Gallery / PDF extraction
        val pendingReview = state.pendingOcrReview
        if (pendingReview != null) {
            showOrUpdateOcrReviewSheet(pendingReview)
        } else {
            ocrSheetDialog?.takeIf { it.isShowing }?.dismiss()
            ocrSheetDialog = null
        }

        binding.studyWhiteboard.syncWithEngine(forceLayout = false)
    }

    private fun applyFullscreenUi(fullscreen: Boolean) {
        val visibleIfNotFs = if (fullscreen) View.GONE else View.VISIBLE
        binding.studyHeaderBar.visibility = visibleIfNotFs
        binding.studyBottomDock.visibility = visibleIfNotFs
        binding.studyFullscreenMiniBar.visibility = if (fullscreen) View.VISIBLE else View.GONE
        binding.studyBoardOverlayTools.visibility = if (fullscreen) View.GONE else View.VISIBLE

        val controller = WindowInsetsControllerCompat(window, binding.studyRoot)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showOrUpdateOcrReviewSheet(review: StudyBoardViewModel.PendingOcrReview) {
        val existing = ocrSheetDialog
        val dialog = if (existing != null && existing.isShowing) {
            existing
        } else {
            BottomSheetDialog(this).also { d ->
                d.setContentView(R.layout.dialog_study_ocr_review)
                LiquidGlass.styleDialog(d, radiusDp = 24f)
                d.setOnCancelListener { viewModel.dismissOcrReview() }
                ocrSheetDialog = d
                d.show()
            }
        }

        val tvTitle = dialog.findViewById<TextView>(R.id.tvOcrTitle) ?: return
        val tvSub = dialog.findViewById<TextView>(R.id.tvOcrSubtitle) ?: return
        val ivPreview = dialog.findViewById<ImageView>(R.id.ivOcrPreview) ?: return
        val layoutPdf = dialog.findViewById<LinearLayout>(R.id.layoutPdfPageBar) ?: return
        val tvPdfPage = dialog.findViewById<TextView>(R.id.tvPdfPageInfo) ?: return
        val btnPdfPrev = dialog.findViewById<Button>(R.id.btnPdfPrevPage) ?: return
        val btnPdfNext = dialog.findViewById<Button>(R.id.btnPdfNextPage) ?: return
        val btnPdfScan = dialog.findViewById<Button>(R.id.btnPdfVisionScan) ?: return
        val tvHeader = dialog.findViewById<TextView>(R.id.tvCandidatesHeader) ?: return
        val container = dialog.findViewById<LinearLayout>(R.id.containerOcrCandidates) ?: return
        val etConfirmed = dialog.findViewById<EditText>(R.id.etConfirmedQuestion) ?: return
        val btnCancel = dialog.findViewById<Button>(R.id.btnOcrCancel) ?: return
        val btnSolve = dialog.findViewById<Button>(R.id.btnOcrSolve) ?: return

        tvTitle.text = "Verify ${review.sourceLabel}"
        tvSub.text = review.errorNote.ifBlank {
            "Confirm or edit the extracted question before JARVIS writes the solution."
        }

        if (review.previewBitmap != null && !review.previewBitmap.isRecycled) {
            ivPreview.visibility = View.VISIBLE
            ivPreview.setImageBitmap(review.previewBitmap)
        } else {
            ivPreview.visibility = View.GONE
        }

        val pdf = review.pdfInspection
        if (pdf != null) {
            layoutPdf.visibility = View.VISIBLE
            tvPdfPage.text = "Page ${review.activePdfPage} / ${pdf.pageCount}"
            btnPdfPrev.isEnabled = review.activePdfPage > 1
            btnPdfNext.isEnabled = review.activePdfPage < pdf.pageCount
            btnPdfPrev.setOnClickListener { viewModel.navigatePdfPage(review.activePdfPage - 1) }
            btnPdfNext.setOnClickListener { viewModel.navigatePdfPage(review.activePdfPage + 1) }
            btnPdfScan.setOnClickListener { viewModel.navigatePdfPage(review.activePdfPage, forceVisionScan = true) }
        } else {
            layoutPdf.visibility = View.GONE
        }

        container.removeAllViews()
        val accent = ThemeManager.getThemeAccentColor()
        val cardBg = resolveAttrColor(R.attr.themeCardBg)
        val borderCol = resolveAttrColor(R.attr.themeCardBorder)

        if (review.candidates.isNotEmpty()) {
            tvHeader.visibility = View.VISIBLE
            tvHeader.text = if (review.candidates.size > 1) {
                "${review.candidates.size} questions detected — tap one to select:"
            } else {
                "Recognized question (tap or edit below):"
            }
            review.candidates.forEachIndexed { idx, candidate ->
                val row = LayoutInflater.from(this).inflate(R.layout.item_study_ocr_candidate, container, false)
                val tvMeta = row.findViewById<TextView>(R.id.tvCandidateMeta)
                val tvQ = row.findViewById<TextView>(R.id.tvCandidateQuestion)
                val isSelected = idx == review.selectedCandidateIndex
                tvMeta.text = buildString {
                    append(candidate.subjectHint.displayName)
                    candidate.pageNumber?.let { append(" · Page $it") }
                    if (candidate.diagramSummary.isNotBlank()) append(" · Includes diagram")
                }
                tvQ.text = candidate.questionText
                row.background = ThemeManager.createDynamicCardDrawable(
                    strokeColor = if (isSelected) accent else borderCol,
                    fillColor = if (isSelected) ColorUtils.setAlphaComponent(accent, 34) else cardBg,
                    cornerRadiusDp = 14f,
                    strokeWidthDp = if (isSelected) 1.6f else 1.0f,
                    context = this
                )
                row.setOnClickListener {
                    viewModel.selectOcrCandidate(idx)
                    val full = buildString {
                        append(candidate.questionText)
                        if (candidate.diagramSummary.isNotBlank()) {
                            append(" [Diagram context: ").append(candidate.diagramSummary).append("]")
                        }
                    }
                    etConfirmed.setText(full)
                    etConfirmed.setSelection(full.length)
                }
                container.addView(row)
            }
            val selected = review.candidates.getOrNull(review.selectedCandidateIndex) ?: review.candidates.first()
            val formatted = buildString {
                append(selected.questionText)
                if (selected.diagramSummary.isNotBlank()) {
                    append(" [Diagram context: ").append(selected.diagramSummary).append("]")
                }
            }
            if (etConfirmed.text.isNullOrBlank()) {
                etConfirmed.setText(formatted)
                etConfirmed.setSelection(formatted.length)
            }
        } else {
            tvHeader.visibility = View.GONE
        }

        btnCancel.setOnClickListener { viewModel.dismissOcrReview() }
        btnSolve.setOnClickListener {
            viewModel.confirmOcrReviewAndSolve(etConfirmed.text?.toString().orEmpty())
        }
    }

    private fun showPersonalizeSheet() {
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(R.layout.dialog_study_personalize)
        LiquidGlass.styleDialog(dialog, radiusDp = 24f)

        var working = viewModel.uiState.value.profile
        val tvStats = dialog.findViewById<TextView>(R.id.tvPersonalizeStats)
        val levelBox = dialog.findViewById<LinearLayout>(R.id.containerLevelChips)
        val langBox = dialog.findViewById<LinearLayout>(R.id.containerLanguageChips)
        val depthBox = dialog.findViewById<LinearLayout>(R.id.containerDepthChips)
        val swAuto = dialog.findViewById<SwitchCompat>(R.id.switchAutoAdaptDepth)
        val swVoice = dialog.findViewById<SwitchCompat>(R.id.switchVoiceNarration)
        val swExplain = dialog.findViewById<SwitchCompat>(R.id.switchShowExplanations)
        val swGrid = dialog.findViewById<SwitchCompat>(R.id.switchShowGrid)
        val btnDone = dialog.findViewById<Button>(R.id.btnPersonalizeDone)

        val accent = ThemeManager.getThemeAccentColor()
        tvStats?.text = "${working.completedSessionsCount} lessons completed · Adapts automatically"

        fun renderChips() {
            levelBox?.let { box ->
                populateChipRow(
                    box = box,
                    items = EducationLevel.entries,
                    selected = working.educationLevel,
                    labelOf = { it.label },
                    accent = accent
                ) { chosen ->
                    working = working.copy(educationLevel = chosen)
                    renderChips()
                }
            }
            langBox?.let { box ->
                populateChipRow(
                    box = box,
                    items = StudyLanguage.entries,
                    selected = working.preferredLanguage,
                    labelOf = { it.label },
                    accent = accent
                ) { chosen ->
                    working = working.copy(preferredLanguage = chosen, voiceLanguage = chosen)
                    renderChips()
                }
            }
            depthBox?.let { box ->
                populateChipRow(
                    box = box,
                    items = ExplanationDepth.entries,
                    selected = working.preferredDepth,
                    labelOf = { it.label },
                    accent = accent
                ) { chosen ->
                    working = working.copy(preferredDepth = chosen)
                    renderChips()
                }
            }
        }
        renderChips()

        swAuto?.isChecked = working.autoAdaptDepth
        swVoice?.isChecked = working.voiceEnabled
        swExplain?.isChecked = working.showExplanations
        swGrid?.isChecked = working.showGrid

        listOfNotNull(swAuto, swVoice, swExplain, swGrid).forEach { sw ->
            ThemeManager.tintSwitch(sw, accent)
        }

        btnDone?.setOnClickListener {
            val updated = working.copy(
                autoAdaptDepth = swAuto?.isChecked ?: working.autoAdaptDepth,
                voiceEnabled = swVoice?.isChecked ?: working.voiceEnabled,
                showExplanations = swExplain?.isChecked ?: working.showExplanations,
                showGrid = swGrid?.isChecked ?: working.showGrid
            )
            viewModel.updateProfile(updated)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showHistorySheet() {
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(R.layout.dialog_study_history)
        LiquidGlass.styleDialog(dialog, radiusDp = 24f)

        val etSearch = dialog.findViewById<EditText>(R.id.etHistorySearch)
        val filterBox = dialog.findViewById<LinearLayout>(R.id.containerSubjectFilterChips)
        val tvEmpty = dialog.findViewById<TextView>(R.id.tvHistoryEmpty)
        val rv = dialog.findViewById<RecyclerView>(R.id.rvStudyHistory) ?: return

        var selectedFilterSubject: StudySubject? = null
        var bookmarkedOnly = false
        var searchJob: Job? = null

        val adapter = HistoryAdapter(
            onOpen = { entity ->
                dialog.dismiss()
                viewModel.openSavedSession(entity.id)
            },
            onDelete = { entity ->
                lifecycleScope.launch {
                    viewModel.deleteHistorySession(entity.id)
                    val q = etSearch?.text?.toString().orEmpty()
                    val list = viewModel.searchHistory(q, selectedFilterSubject, bookmarkedOnly)
                    (rv.adapter as? HistoryAdapter)?.submit(list)
                    tvEmpty?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        fun refreshList() {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                val query = etSearch?.text?.toString().orEmpty()
                val items = viewModel.searchHistory(query, selectedFilterSubject, bookmarkedOnly)
                adapter.submit(items)
                tvEmpty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        val accent = ThemeManager.getThemeAccentColor()
        val filterKeys = buildList<Pair<String, () -> Unit>> {
            add("All" to {
                selectedFilterSubject = null
                bookmarkedOnly = false
            })
            add("Bookmarked" to {
                selectedFilterSubject = null
                bookmarkedOnly = true
            })
            StudySubject.entries.forEach { subj ->
                add(subj.displayName to {
                    selectedFilterSubject = subj
                    bookmarkedOnly = false
                })
            }
        }
        var activeFilterLabel = "All"

        fun renderFilters() {
            val box = filterBox ?: return
            box.removeAllViews()
            val d = resources.displayMetrics.density
            val border = resolveAttrColor(R.attr.themeCardBorder)
            val cardBg = resolveAttrColor(R.attr.themeCardBg)
            val textCol = resolveAttrColor(R.attr.themeTextPrimary)
            filterKeys.forEach { (label, action) ->
                val selected = label == activeFilterLabel
                val chip = Button(this).apply {
                    text = label
                    isAllCaps = false
                    textSize = 12.5f
                    setTextColor(if (selected) accent else textCol)
                    minHeight = (44 * d).toInt()
                    setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                    background = ThemeManager.createDynamicCardDrawable(
                        strokeColor = if (selected) accent else border,
                        fillColor = if (selected) ColorUtils.setAlphaComponent(accent, 34) else cardBg,
                        cornerRadiusDp = 16f,
                        strokeWidthDp = if (selected) 1.5f else 1f,
                        context = this@StudyBoardActivity
                    )
                    setOnClickListener {
                        activeFilterLabel = label
                        action()
                        renderFilters()
                        refreshList()
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (40 * d).toInt()
                ).apply { marginEnd = (6 * d).toInt() }
                box.addView(chip, lp)
            }
        }
        renderFilters()
        refreshList()

        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(180L)
                    val items = viewModel.searchHistory(s?.toString().orEmpty(), selectedFilterSubject, bookmarkedOnly)
                    adapter.submit(items)
                    tvEmpty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        })

        dialog.show()
    }

    private fun showDoubtSheet() {
        val state = viewModel.uiState.value
        if (state.totalSteps == 0 && state.question.isBlank()) {
            Toast.makeText(this, "Ask or open a lesson first, then tap Doubt on any step.", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(R.layout.dialog_study_doubt)
        LiquidGlass.styleDialog(dialog, radiusDp = 24f)

        val tvTarget = dialog.findViewById<TextView>(R.id.tvDoubtTarget)
        val quickBox = dialog.findViewById<LinearLayout>(R.id.containerDoubtQuickPrompts)
        val etDoubt = dialog.findViewById<EditText>(R.id.etDoubtQuestion)
        val btnVoice = dialog.findViewById<ImageButton>(R.id.btnDoubtVoice)
        val btnSubmit = dialog.findViewById<Button>(R.id.btnDoubtSubmit)

        doubtInputRef = etDoubt
        val targetStepIdx = if (state.totalSteps > 0) state.focusedStepIndex else null
        tvTarget?.text = if (targetStepIdx != null) {
            "Clarifying Step ${targetStepIdx + 1} (keeps your existing solution intact)"
        } else {
            "Clarifying overall lesson"
        }

        val quickSuggestions = listOf(
            "Why this formula?",
            "Explain in simpler words",
            "Why did the sign change?",
            "Check units & dimensions"
        )
        quickBox?.let { box ->
            box.removeAllViews()
            val d = resources.displayMetrics.density
            val accent = ThemeManager.getThemeAccentColor()
            val cardBg = resolveAttrColor(R.attr.themeCardBg)
            val border = resolveAttrColor(R.attr.themeCardBorder)
            quickSuggestions.forEach { prompt ->
                val chip = Button(this).apply {
                    text = prompt
                    isAllCaps = false
                    textSize = 12f
                    setTextColor(accent)
                    minHeight = (44 * d).toInt()
                    setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
                    background = ThemeManager.createDynamicCardDrawable(border, cardBg, 14f, 1f, this@StudyBoardActivity)
                    setOnClickListener {
                        etDoubt?.setText(prompt)
                        etDoubt?.setSelection(prompt.length)
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (40 * d).toInt()
                ).apply { marginEnd = (6 * d).toInt() }
                box.addView(chip, lp)
            }
        }

        btnVoice?.setOnClickListener { startVoiceWithPermission(forDoubt = true) }
        btnSubmit?.setOnClickListener {
            val txt = etDoubt?.text?.toString().orEmpty().trim()
            if (txt.isBlank()) {
                Toast.makeText(this, "Enter or speak your doubt first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            viewModel.askDoubt(txt, targetStepIdx)
        }
        dialog.setOnDismissListener {
            doubtInputRef = null
            if (viewModel.uiState.value.voiceTargetDoubtMode) {
                viewModel.stopVoiceListening()
            }
        }
        dialog.show()
    }

    private fun showExportSheet() {
        val options = arrayOf(
            "Export Clean Study Notes (PDF)",
            "Export Whiteboard Snapshot (PNG)",
            "Save Lesson to Subject History"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save & Export Lesson")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.exportPdf(this)
                    1 -> viewModel.exportImage(this, binding.studyWhiteboard)
                    2 -> viewModel.saveLessonExplicit()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExportCompletedDialog(artifact: StudyExportManager.ExportedArtifact) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Exported Successfully")
            .setMessage("Saved to ${artifact.savedDisplayPath}")
            .setPositiveButton("Share / Open") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = artifact.mimeType
                    putExtra(Intent.EXTRA_STREAM, artifact.shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, artifact.fileName))
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun <T> populateChipRow(
        box: LinearLayout,
        items: List<T>,
        selected: T,
        labelOf: (T) -> String,
        accent: Int,
        onSelect: (T) -> Unit
    ) {
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val border = resolveAttrColor(R.attr.themeCardBorder)
        val cardBg = resolveAttrColor(R.attr.themeCardBg)
        val textCol = resolveAttrColor(R.attr.themeTextPrimary)
        items.forEach { item ->
            val isSel = item == selected
            val chip = Button(this).apply {
                text = labelOf(item)
                isAllCaps = false
                textSize = 12.5f
                setTextColor(if (isSel) accent else textCol)
                minHeight = (44 * d).toInt()
                setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                background = ThemeManager.createDynamicCardDrawable(
                    strokeColor = if (isSel) accent else border,
                    fillColor = if (isSel) ColorUtils.setAlphaComponent(accent, 34) else cardBg,
                    cornerRadiusDp = 16f,
                    strokeWidthDp = if (isSel) 1.5f else 1f,
                    context = this@StudyBoardActivity
                )
                setOnClickListener { onSelect(item) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (40 * d).toInt()
            ).apply { marginEnd = (6 * d).toInt() }
            box.addView(chip, lp)
        }
    }

    private fun resolveAttrColor(attrRes: Int): Int {
        val tv = TypedValue()
        val ok = theme.resolveAttribute(attrRes, tv, true)
        if (!ok) return Color.WHITE
        return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
    }

    private inner class HistoryAdapter(
        private val onOpen: (StudySessionEntity) -> Unit,
        private val onDelete: (StudySessionEntity) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private val items = ArrayList<StudySessionEntity>()
        private val dateFmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newItems: List<StudySessionEntity>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_study_history_row, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val subj = runCatching { StudySubject.valueOf(item.subject).displayName }.getOrDefault(item.subject)
            holder.tvMeta.text = buildString {
                append(subj)
                if (item.topic.isNotBlank()) append(" · ").append(item.topic)
                if (item.bookmarkedStepCount > 0) append(" · ★ ${item.bookmarkedStepCount}")
            }
            holder.tvQuestion.text = item.question
            val stateLabel = if (item.isCompleted) "Completed" else "Interrupted (Step ${item.lastStepIndex + 1}/${item.stepCount.coerceAtLeast(1)})"
            holder.tvStatus.text = "$stateLabel · ${dateFmt.format(Date(item.updatedAt))}"
            holder.itemView.setOnClickListener { onOpen(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvMeta: TextView = view.findViewById(R.id.tvRowSubjectMeta)
            val tvQuestion: TextView = view.findViewById(R.id.tvRowQuestion)
            val tvStatus: TextView = view.findViewById(R.id.tvRowStatus)
            val btnDelete: ImageButton = view.findViewById(R.id.btnRowDelete)
        }
    }
}
