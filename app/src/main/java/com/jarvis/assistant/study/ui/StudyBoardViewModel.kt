package com.jarvis.assistant.study.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.study.ai.StudyAIEngine
import com.jarvis.assistant.study.data.StudyHistoryManager
import com.jarvis.assistant.study.data.StudySessionEntity
import com.jarvis.assistant.study.export.StudyExportManager
import com.jarvis.assistant.study.input.StudyInputManager
import com.jarvis.assistant.study.model.BoardPlaybackStatus
import com.jarvis.assistant.study.model.EducationLevel
import com.jarvis.assistant.study.model.ExplanationDepth
import com.jarvis.assistant.study.model.ExtractedQuestionCandidate
import com.jarvis.assistant.study.model.StudyInputKind
import com.jarvis.assistant.study.model.StudyLanguage
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudyStreamEvent
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.model.TeacherProfile
import com.jarvis.assistant.study.render.StudyAnimationEngine
import com.jarvis.assistant.study.render.StudyWhiteboardView
import com.jarvis.assistant.study.voice.StudySpeechSynchronizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Central state coordinator for JARVIS Live Study Board.
 * Survives configuration changes cleanly, prevents duplicate AI requests, saves & restores
 * interrupted lessons via Room, and orchestrates streaming, animation, voice sync & export.
 */
class StudyBoardViewModel private constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val aiEngine: StudyAIEngine,
    private val historyManager: StudyHistoryManager,
    val inputManager: StudyInputManager,
    private val speechSync: StudySpeechSynchronizer
) : AndroidViewModel(application) {

    constructor(application: Application, savedStateHandle: SavedStateHandle) : this(
        application = application,
        savedStateHandle = savedStateHandle,
        aiEngine = StudyAIEngine.create(application),
        historyManager = StudyHistoryManager.create(application),
        inputManager = StudyInputManager(application, StudyAIEngine.create(application)),
        speechSync = StudySpeechSynchronizer(application)
    )

    data class PendingQuestionRequest(
        val question: String,
        val inputKind: StudyInputKind,
        val documentContext: String = "",
        val attachmentRef: String? = null
    )

    data class PendingOcrReview(
        val sourceLabel: String,
        val inputKind: StudyInputKind,
        val candidates: List<ExtractedQuestionCandidate>,
        val selectedCandidateIndex: Int,
        val previewBitmap: Bitmap? = null,
        val attachmentRef: String? = null,
        val pdfInspection: StudyInputManager.PdfInspection? = null,
        val activePdfPage: Int = 1,
        val errorNote: String = ""
    )

    data class StudyUiState(
        val subject: StudySubject? = null,
        val topic: String = "",
        val chapter: String = "",
        val question: String = "",
        val playbackStatus: BoardPlaybackStatus = BoardPlaybackStatus.IDLE,
        val activeStepIndex: Int = 0,
        val totalSteps: Int = 0,
        val focusedStepIndex: Int = 0,
        val isFocusedStepBookmarked: Boolean = false,
        val liveExplanationText: String = "",
        val profile: TeacherProfile = TeacherProfile(),
        val isGenerating: Boolean = false,
        val isAnalyzingMedia: Boolean = false,
        val statusBannerText: String? = null,
        val isErrorBanner: Boolean = false,
        val isOfflineLimitation: Boolean = false,
        val canRetry: Boolean = false,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val isFullscreen: Boolean = false,
        val isFollowingLive: Boolean = true,
        val isVoiceListening: Boolean = false,
        val voiceTargetDoubtMode: Boolean = false,
        val voicePartialText: String = "",
        val voiceRmsLevel: Float = 0f,
        val isSpeaking: Boolean = false,
        val pendingOcrReview: PendingOcrReview? = null
    )

    sealed class UiEvent {
        data class ToastMessage(val message: String) : UiEvent()
        data class ExportReady(val artifact: StudyExportManager.ExportedArtifact) : UiEvent()
        data class RequestDoubtTextFromVoice(val recognizedText: String) : UiEvent()
        data object SyncBoardView : UiEvent()
        data class ScrollToStep(val stepIndex: Int) : UiEvent()
    }

    val animationEngine = StudyAnimationEngine()

    private val _uiState = MutableStateFlow(StudyUiState())
    val uiState: StateFlow<StudyUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var generationJob: Job? = null
    private var mediaJob: Job? = null
    private var doubtJob: Job? = null
    private var activeNormalizedQuestion: String? = null
    private var lastSubmittedRequest: PendingQuestionRequest? = null
    private var voiceDoubtCallback: ((String) -> Unit)? = null

    init {
        val baseProfile = historyManager.loadTeacherProfile()
        applyProfileToEngines(baseProfile)
        _uiState.update { it.copy(profile = baseProfile) }

        viewModelScope.launch {
            speechSync.isSpeaking.collect { speaking ->
                _uiState.update { it.copy(isSpeaking = speaking) }
            }
        }

        viewModelScope.launch {
            val enriched = historyManager.loadEnrichedProfile()
            applyProfileToEngines(enriched)
            _uiState.update { it.copy(profile = enriched) }

            val stateSessionId = savedStateHandle.get<String>(KEY_SAVED_SESSION_ID)
            val restored = if (!stateSessionId.isNullOrBlank()) {
                historyManager.loadSession(stateSessionId)
            } else {
                historyManager.loadLastRecoverableSession()
            }
            if (restored != null && animationEngine.getSolution() == null) {
                val (sol, entity) = restored
                animationEngine.loadSavedSolution(
                    saved = sol,
                    resumeStepIndex = entity.lastStepIndex,
                    completedAlready = entity.isCompleted
                )
                refreshUiFromEngine()
                _events.tryEmit(UiEvent.SyncBoardView)
            }
        }
    }

    fun submitQuestion(
        rawQuestion: String,
        inputKind: StudyInputKind = StudyInputKind.TEXT,
        documentContext: String = "",
        attachmentRef: String? = null,
        forceOffline: Boolean = false
    ) {
        val clean = rawQuestion.trim()
        if (clean.isBlank()) {
            _events.tryEmit(UiEvent.ToastMessage("Please enter or speak a question first."))
            return
        }
        val normalized = clean.lowercase()
        if (generationJob?.isActive == true && activeNormalizedQuestion == normalized) {
            // Strict duplicate-request guard.
            return
        }

        generationJob?.cancel()
        speechSync.stop()
        if (inputKind != StudyInputKind.VOICE) {
            stopVoiceListening()
        }

        activeNormalizedQuestion = normalized
        lastSubmittedRequest = PendingQuestionRequest(clean, inputKind, documentContext, attachmentRef)

        val profile = _uiState.value.profile
        animationEngine.beginStreaming(profile, clean)
        savedStateHandle[KEY_SAVED_QUESTION] = clean

        _uiState.update {
            it.copy(
                question = clean,
                isGenerating = true,
                statusBannerText = "Preparing structured lesson…",
                isErrorBanner = false,
                isOfflineLimitation = false,
                canRetry = false,
                subject = null,
                topic = "",
                chapter = ""
            )
        }
        _events.tryEmit(UiEvent.SyncBoardView)

        generationJob = viewModelScope.launch {
            val enrichedProfile = historyManager.loadEnrichedProfile()
            _uiState.update { it.copy(profile = enrichedProfile) }
            try {
                aiEngine.streamSolution(
                    question = clean,
                    profile = enrichedProfile,
                    inputKind = inputKind,
                    documentContext = documentContext,
                    attachmentRef = attachmentRef,
                    forceOffline = forceOffline
                ).collect { ev ->
                    when (ev) {
                        is StudyStreamEvent.Metadata -> {
                            animationEngine.onStreamMetadata(ev)
                            refreshUiFromEngine(banner = "Writing ${ev.subject.displayName} lesson live…")
                            _events.tryEmit(UiEvent.SyncBoardView)
                        }
                        is StudyStreamEvent.StepValidated -> {
                            animationEngine.appendValidatedStep(ev.step)
                            refreshUiFromEngine(banner = "Writing step ${ev.step.stepIndex + 1}…")
                            _events.tryEmit(UiEvent.SyncBoardView)
                            // Persist interrupted-recovery checkpoint as soon as first validated step arrives.
                            animationEngine.getSolution()?.let { partial ->
                                val saved = historyManager.saveSession(
                                    solution = partial,
                                    lastStepIndex = animationEngine.getActiveStepIndex(),
                                    isCompleted = false,
                                    markSaved = partial.isSaved
                                )
                                savedStateHandle[KEY_SAVED_SESSION_ID] = saved.id
                            }
                        }
                        is StudyStreamEvent.Completed -> {
                            animationEngine.onStreamCompleted(ev.solution)
                            val saved = historyManager.saveSession(
                                solution = ev.solution,
                                lastStepIndex = animationEngine.getActiveStepIndex(),
                                isCompleted = animationEngine.getStatus() == BoardPlaybackStatus.COMPLETED,
                                markSaved = true
                            )
                            savedStateHandle[KEY_SAVED_SESSION_ID] = saved.id
                            val refreshedProfile = historyManager.loadEnrichedProfile()
                            _uiState.update {
                                it.copy(
                                    profile = refreshedProfile,
                                    isGenerating = false,
                                    statusBannerText = null,
                                    isErrorBanner = false,
                                    canRetry = false
                                )
                            }
                            refreshUiFromEngine()
                            _events.tryEmit(UiEvent.SyncBoardView)
                        }
                        is StudyStreamEvent.Error -> {
                            animationEngine.onStreamError()
                            _uiState.update {
                                it.copy(
                                    isGenerating = false,
                                    statusBannerText = ev.message,
                                    isErrorBanner = true,
                                    isOfflineLimitation = ev.isOfflineLimitation,
                                    canRetry = true
                                )
                            }
                            refreshUiFromEngine()
                            _events.tryEmit(UiEvent.SyncBoardView)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Cancelled cleanly by new request or user action.
            } catch (e: Exception) {
                animationEngine.onStreamError()
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        statusBannerText = e.message ?: "Failed to generate lesson.",
                        isErrorBanner = true,
                        canRetry = true
                    )
                }
            } finally {
                if (generationJob?.isCancelled == false) {
                    activeNormalizedQuestion = null
                }
            }
        }
    }

    fun retryLastQuestion() {
        val req = lastSubmittedRequest ?: return
        activeNormalizedQuestion = null
        submitQuestion(
            rawQuestion = req.question,
            inputKind = req.inputKind,
            documentContext = req.documentContext,
            attachmentRef = req.attachmentRef
        )
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        activeNormalizedQuestion = null
        speechSync.stop()
        animationEngine.onStreamError()
        _uiState.update {
            it.copy(
                isGenerating = false,
                statusBannerText = null,
                isErrorBanner = false
            )
        }
        refreshUiFromEngine()
        _events.tryEmit(UiEvent.SyncBoardView)
    }

    fun handleImageInput(uri: Uri, fromCamera: Boolean) {
        mediaJob?.cancel()
        mediaJob = viewModelScope.launch {
            val sourceLabel = if (fromCamera) "Camera Photo" else "Gallery Image"
            _uiState.update {
                it.copy(
                    isAnalyzingMedia = true,
                    statusBannerText = "Reading text & equations from $sourceLabel…",
                    isErrorBanner = false,
                    canRetry = false
                )
            }
            val prepared = inputManager.loadAndPrepareImage(uri).getOrElse { err ->
                _uiState.update {
                    it.copy(
                        isAnalyzingMedia = false,
                        statusBannerText = err.message ?: "Could not open image.",
                        isErrorBanner = true
                    )
                }
                return@launch
            }

            when (val res = inputManager.extractQuestionsFromImage(prepared, sourceLabel)) {
                is StudyAIEngine.OcrResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isAnalyzingMedia = false,
                            statusBannerText = null,
                            pendingOcrReview = PendingOcrReview(
                                sourceLabel = sourceLabel,
                                inputKind = if (fromCamera) StudyInputKind.CAMERA else StudyInputKind.GALLERY,
                                candidates = res.candidates,
                                selectedCandidateIndex = 0,
                                previewBitmap = prepared.previewBitmap,
                                attachmentRef = prepared.sourceRef
                            )
                        )
                    }
                }
                is StudyAIEngine.OcrResult.Failed -> {
                    // Open editable review sheet anyway with image preview so user can type or retry cleanly!
                    _uiState.update {
                        it.copy(
                            isAnalyzingMedia = false,
                            statusBannerText = null,
                            pendingOcrReview = PendingOcrReview(
                                sourceLabel = sourceLabel,
                                inputKind = if (fromCamera) StudyInputKind.CAMERA else StudyInputKind.GALLERY,
                                candidates = emptyList(),
                                selectedCandidateIndex = 0,
                                previewBitmap = prepared.previewBitmap,
                                attachmentRef = prepared.sourceRef,
                                errorNote = res.reason
                            )
                        )
                    }
                }
            }
        }
    }

    fun handlePdfInput(uri: Uri) {
        mediaJob?.cancel()
        mediaJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAnalyzingMedia = true,
                    statusBannerText = "Inspecting PDF pages safely…",
                    isErrorBanner = false,
                    canRetry = false
                )
            }
            val inspection = inputManager.inspectPdf(uri).getOrElse { err ->
                _uiState.update {
                    it.copy(
                        isAnalyzingMedia = false,
                        statusBannerText = err.message ?: "Could not read PDF document.",
                        isErrorBanner = true
                    )
                }
                return@launch
            }

            val page = 1
            val candidates = when (val res = inputManager.extractQuestionsFromPdfPage(inspection, page)) {
                is StudyAIEngine.OcrResult.Success -> res.candidates
                is StudyAIEngine.OcrResult.Failed -> emptyList()
            }
            val preview = inputManager.renderPdfPageForOcr(inspection.cacheFilePath, page).getOrNull()?.previewBitmap
            _uiState.update {
                it.copy(
                    isAnalyzingMedia = false,
                    statusBannerText = null,
                    pendingOcrReview = PendingOcrReview(
                        sourceLabel = inspection.fileName,
                        inputKind = StudyInputKind.PDF,
                        candidates = candidates,
                        selectedCandidateIndex = 0,
                        previewBitmap = preview,
                        attachmentRef = "${inspection.fileName}#page=$page",
                        pdfInspection = inspection,
                        activePdfPage = page,
                        errorNote = if (candidates.isEmpty()) "Select text, tap Scan Page, or type a question about this PDF." else ""
                    )
                )
            }
        }
    }

    fun navigatePdfPage(targetPage: Int, forceVisionScan: Boolean = false) {
        val review = _uiState.value.pendingOcrReview ?: return
        val pdf = review.pdfInspection ?: return
        val clamped = targetPage.coerceIn(1, pdf.pageCount.coerceAtLeast(1))
        mediaJob?.cancel()
        mediaJob = viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzingMedia = true) }
            val rendered = inputManager.renderPdfPageForOcr(pdf.cacheFilePath, clamped).getOrNull()
            val ocrResult = if (forceVisionScan && rendered != null) {
                aiEngine.extractQuestionsFromImage(rendered.jpegBytes, rendered.mimeType, "${pdf.fileName} Page $clamped")
            } else {
                inputManager.extractQuestionsFromPdfPage(pdf, clamped)
            }
            val candidates = when (ocrResult) {
                is StudyAIEngine.OcrResult.Success -> ocrResult.candidates
                is StudyAIEngine.OcrResult.Failed -> emptyList()
            }
            recycleReviewBitmap(review)
            _uiState.update {
                it.copy(
                    isAnalyzingMedia = false,
                    pendingOcrReview = review.copy(
                        candidates = candidates,
                        selectedCandidateIndex = 0,
                        previewBitmap = rendered?.previewBitmap,
                        attachmentRef = "${pdf.fileName}#page=$clamped",
                        activePdfPage = clamped,
                        errorNote = if (candidates.isEmpty() && ocrResult is StudyAIEngine.OcrResult.Failed) ocrResult.reason else ""
                    )
                )
            }
        }
    }

    fun selectOcrCandidate(index: Int) {
        val review = _uiState.value.pendingOcrReview ?: return
        if (index in review.candidates.indices) {
            _uiState.update { it.copy(pendingOcrReview = review.copy(selectedCandidateIndex = index)) }
        }
    }

    fun dismissOcrReview() {
        val review = _uiState.value.pendingOcrReview ?: return
        recycleReviewBitmap(review)
        _uiState.update { it.copy(pendingOcrReview = null) }
    }

    fun confirmOcrReviewAndSolve(editedQuestion: String) {
        val review = _uiState.value.pendingOcrReview ?: return
        val clean = editedQuestion.trim()
        if (clean.isBlank()) {
            _events.tryEmit(UiEvent.ToastMessage("Please enter or select a question before solving."))
            return
        }
        val docCtx = review.pdfInspection?.let { pdf ->
            inputManager.searchPdfContextForQuestion(pdf, clean)
        }.orEmpty()
        val kind = review.inputKind
        val ref = review.attachmentRef
        recycleReviewBitmap(review)
        _uiState.update { it.copy(pendingOcrReview = null) }
        submitQuestion(
            rawQuestion = clean,
            inputKind = kind,
            documentContext = docCtx,
            attachmentRef = ref
        )
    }

    fun startVoiceQuestion(doubtCallback: ((String) -> Unit)? = null) {
        speechSync.interrupt()
        if (doubtCallback == null) {
            animationEngine.pause()
        }
        voiceDoubtCallback = doubtCallback
        val lang = _uiState.value.profile.voiceLanguage
        _uiState.update {
            it.copy(
                isVoiceListening = true,
                voiceTargetDoubtMode = doubtCallback != null,
                voicePartialText = "",
                voiceRmsLevel = 0f
            )
        }
        inputManager.startVoiceInput(lang, object : StudyInputManager.VoiceListener {
            override fun onReady() {
                _uiState.update { it.copy(statusBannerText = if (doubtCallback != null) "Listening for your doubt…" else "Listening for your question…", isErrorBanner = false) }
            }

            override fun onPartialText(text: String) {
                _uiState.update { it.copy(voicePartialText = text) }
            }

            override fun onRmsChanged(level: Float) {
                _uiState.update { it.copy(voiceRmsLevel = level) }
            }

            override fun onFinalResult(text: String) {
                val cb = voiceDoubtCallback
                voiceDoubtCallback = null
                _uiState.update {
                    it.copy(
                        isVoiceListening = false,
                        voiceTargetDoubtMode = false,
                        voicePartialText = "",
                        voiceRmsLevel = 0f,
                        statusBannerText = null
                    )
                }
                if (cb != null) {
                    cb(text)
                    _events.tryEmit(UiEvent.RequestDoubtTextFromVoice(text))
                } else {
                    submitQuestion(text, inputKind = StudyInputKind.VOICE)
                }
            }

            override fun onError(message: String) {
                voiceDoubtCallback = null
                _uiState.update {
                    it.copy(
                        isVoiceListening = false,
                        voiceTargetDoubtMode = false,
                        voicePartialText = "",
                        voiceRmsLevel = 0f,
                        statusBannerText = null
                    )
                }
                _events.tryEmit(UiEvent.ToastMessage(message))
            }
        })
    }

    fun stopVoiceListening() {
        voiceDoubtCallback = null
        inputManager.stopVoiceInput()
        if (_uiState.value.isVoiceListening) {
            _uiState.update {
                it.copy(
                    isVoiceListening = false,
                    voiceTargetDoubtMode = false,
                    voicePartialText = "",
                    voiceRmsLevel = 0f,
                    statusBannerText = null
                )
            }
        }
    }

    fun askDoubt(doubtQuestion: String, targetStepIndex: Int?) {
        val sol = animationEngine.getSolution() ?: run {
            _events.tryEmit(UiEvent.ToastMessage("Solve a question on the board first to ask a follow-up doubt."))
            return
        }
        val clean = doubtQuestion.trim()
        if (clean.isBlank()) {
            _events.tryEmit(UiEvent.ToastMessage("Please enter your doubt first."))
            return
        }
        speechSync.interrupt()
        animationEngine.pause()
        val targetStep = targetStepIndex?.let { idx -> sol.steps.firstOrNull { it.stepIndex == idx } }

        doubtJob?.cancel()
        doubtJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAnalyzingMedia = true,
                    statusBannerText = "Clarifying ${targetStep?.let { s -> "Step ${s.stepIndex + 1}" } ?: "solution"}…",
                    isErrorBanner = false
                )
            }
            when (val res = aiEngine.askDoubt(sol, targetStep, clean, _uiState.value.profile)) {
                is StudyAIEngine.DoubtResult.Answered -> {
                    animationEngine.attachDoubt(res.entry)
                    animationEngine.getSolution()?.let { updated ->
                        historyManager.saveSession(
                            solution = updated,
                            lastStepIndex = animationEngine.getActiveStepIndex(),
                            isCompleted = animationEngine.getStatus() == BoardPlaybackStatus.COMPLETED,
                            markSaved = true
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isAnalyzingMedia = false,
                            statusBannerText = null,
                            liveExplanationText = "${res.entry.boardSummary} — ${res.entry.explanation}"
                        )
                    }
                    refreshUiFromEngine()
                    _events.tryEmit(UiEvent.SyncBoardView)
                    if (targetStep != null) _events.tryEmit(UiEvent.ScrollToStep(targetStep.stepIndex))
                    speechSync.speakText("${res.entry.boardSummary}. ${res.entry.explanation}")
                }
                is StudyAIEngine.DoubtResult.Failed -> {
                    _uiState.update {
                        it.copy(
                            isAnalyzingMedia = false,
                            statusBannerText = null
                        )
                    }
                    _events.tryEmit(UiEvent.ToastMessage(res.reason))
                }
            }
        }
    }

    fun onFrameTick(deltaMs: Long): StudyAnimationEngine.FrameOutcome {
        val outcome = animationEngine.tick(deltaMs)
        outcome.stepEntered?.let { step ->
            if (_uiState.value.profile.voiceEnabled) {
                speechSync.speakStep(step) {
                    animationEngine.notifyVoiceFinishedForStep(step.stepIndex)
                }
            }
            refreshUiFromEngine()
        }
        if (outcome.completedSolution) {
            refreshUiFromEngine()
            viewModelScope.launch {
                animationEngine.getSolution()?.let { sol ->
                    historyManager.updatePlaybackProgress(sol.id, animationEngine.getActiveStepIndex(), true)
                }
            }
        } else if (outcome.clearedAfterErase) {
            historyManager.setActiveSessionId(null)
            savedStateHandle[KEY_SAVED_SESSION_ID] = null
            refreshUiFromEngine()
        }
        return outcome
    }

    fun togglePlayPause() {
        when (animationEngine.getStatus()) {
            BoardPlaybackStatus.PLAYING, BoardPlaybackStatus.STREAMING -> {
                animationEngine.pause()
                speechSync.pause()
            }
            BoardPlaybackStatus.PAUSED, BoardPlaybackStatus.COMPLETED -> {
                speechSync.resume()
                animationEngine.resume()
            }
            else -> Unit
        }
        refreshUiFromEngine()
        _events.tryEmit(UiEvent.SyncBoardView)
    }

    fun previousStep() {
        speechSync.interrupt()
        val idx = animationEngine.previousStep()
        refreshUiFromEngine()
        _events.tryEmit(UiEvent.SyncBoardView)
        _events.tryEmit(UiEvent.ScrollToStep(idx))
    }

    fun nextStep() {
        speechSync.interrupt()
        val idx = animationEngine.nextStep()
        refreshUiFromEngine()
        _events.tryEmit(UiEvent.SyncBoardView)
        _events.tryEmit(UiEvent.ScrollToStep(idx))
    }

    fun replayCurrentStep() {
        speechSync.interrupt()
        speechSync.resume()
        animationEngine.replayCurrentStep()
        refreshUiFromEngine()
        _events.tryEmit(UiEvent.SyncBoardView)
    }

    fun replayAll() {
        speechSync.interrupt()
        speechSync.resume()
        animationEngine.replayAll()
        refreshUiFromEngine()
        _events.tryEmit(UiEvent.SyncBoardView)
    }

    fun cycleSpeed() {
        val steps = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val cur = _uiState.value.profile.writingSpeed
        val next = steps.firstOrNull { it > cur + 0.05f } ?: steps.first()
        updateProfile(_uiState.value.profile.copy(writingSpeed = next))
    }

    fun undoBoard() {
        speechSync.interrupt()
        if (animationEngine.undo()) {
            refreshUiFromEngine()
            _events.tryEmit(UiEvent.SyncBoardView)
        }
    }

    fun redoBoard() {
        speechSync.interrupt()
        if (animationEngine.redo()) {
            refreshUiFromEngine()
            _events.tryEmit(UiEvent.SyncBoardView)
        }
    }

    fun clearBoard() {
        generationJob?.cancel()
        activeNormalizedQuestion = null
        speechSync.stop()
        animationEngine.clearBoardAnimated {
            refreshUiFromEngine()
        }
        _uiState.update { it.copy(isGenerating = false, statusBannerText = null, isErrorBanner = false) }
        _events.tryEmit(UiEvent.SyncBoardView)
    }

    fun onStepTapped(stepIndex: Int) {
        animationEngine.focusStep(stepIndex)
        refreshUiFromEngine()
        _events.tryEmit(UiEvent.SyncBoardView)
    }

    fun toggleStepBookmark(stepIndex: Int = animationEngine.getFocusedStepIndex()) {
        val updated = animationEngine.toggleBookmark(stepIndex) ?: return
        refreshUiFromEngine()
        _events.tryEmit(UiEvent.SyncBoardView)
        val nowBookmarked = updated.steps.getOrNull(stepIndex)?.isBookmarked == true
        _events.tryEmit(UiEvent.ToastMessage(if (nowBookmarked) "Step ${stepIndex + 1} bookmarked" else "Bookmark removed"))
        viewModelScope.launch {
            historyManager.saveSession(
                solution = updated,
                lastStepIndex = animationEngine.getActiveStepIndex(),
                isCompleted = animationEngine.getStatus() == BoardPlaybackStatus.COMPLETED,
                markSaved = true
            )
        }
    }

    fun saveLessonExplicit() {
        val sol = animationEngine.getSolution() ?: run {
            _events.tryEmit(UiEvent.ToastMessage("No lesson on the board yet to save."))
            return
        }
        viewModelScope.launch {
            val saved = historyManager.saveSession(
                solution = sol,
                lastStepIndex = animationEngine.getActiveStepIndex(),
                isCompleted = animationEngine.getStatus() == BoardPlaybackStatus.COMPLETED,
                markSaved = true
            )
            savedStateHandle[KEY_SAVED_SESSION_ID] = saved.id
            _events.tryEmit(UiEvent.ToastMessage("Saved to ${saved.subject.displayName} lessons"))
        }
    }

    fun openSavedSession(sessionId: String) {
        generationJob?.cancel()
        speechSync.stop()
        viewModelScope.launch {
            val loaded = historyManager.loadSession(sessionId) ?: run {
                _events.tryEmit(UiEvent.ToastMessage("Could not load saved lesson."))
                return@launch
            }
            val (sol, entity) = loaded
            animationEngine.loadSavedSolution(
                saved = sol,
                resumeStepIndex = entity.lastStepIndex,
                completedAlready = entity.isCompleted
            )
            savedStateHandle[KEY_SAVED_SESSION_ID] = entity.id
            historyManager.setActiveSessionId(entity.id)
            refreshUiFromEngine()
            _events.tryEmit(UiEvent.SyncBoardView)
        }
    }

    suspend fun searchHistory(
        query: String,
        subject: StudySubject?,
        bookmarkedOnly: Boolean
    ): List<StudySessionEntity> = historyManager.searchSessions(query, subject, bookmarkedOnly)

    suspend fun deleteHistorySession(sessionId: String) {
        historyManager.deleteSession(sessionId)
        if (animationEngine.getSolution()?.id == sessionId) {
            savedStateHandle[KEY_SAVED_SESSION_ID] = null
        }
    }

    fun exportPdf(context: Context) {
        val sol = animationEngine.getSolution() ?: run {
            _events.tryEmit(UiEvent.ToastMessage("Solve or open a lesson first before exporting PDF."))
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzingMedia = true, statusBannerText = "Exporting clean A4 PDF notes…") }
            val res = StudyExportManager.exportSolutionPdf(context, sol)
            _uiState.update { it.copy(isAnalyzingMedia = false, statusBannerText = null) }
            res.onSuccess { artifact ->
                _events.tryEmit(UiEvent.ExportReady(artifact))
            }.onFailure { err ->
                _events.tryEmit(UiEvent.ToastMessage("PDF export failed: ${err.message ?: "error"}"))
            }
        }
    }

    fun exportImage(context: Context, whiteboardView: StudyWhiteboardView) {
        val sol = animationEngine.getSolution() ?: run {
            _events.tryEmit(UiEvent.ToastMessage("Nothing on the whiteboard yet to save."))
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzingMedia = true, statusBannerText = "Saving board snapshot image…") }
            val res = StudyExportManager.exportBoardSnapshot(context, whiteboardView, sol)
            _uiState.update { it.copy(isAnalyzingMedia = false, statusBannerText = null) }
            res.onSuccess { artifact ->
                _events.tryEmit(UiEvent.ExportReady(artifact))
            }.onFailure { err ->
                _events.tryEmit(UiEvent.ToastMessage("Image export failed: ${err.message ?: "error"}"))
            }
        }
    }

    fun toggleVoiceEnabled() {
        val next = !_uiState.value.profile.voiceEnabled
        updateProfile(_uiState.value.profile.copy(voiceEnabled = next))
        _events.tryEmit(UiEvent.ToastMessage(if (next) "Synchronized voice explanation ON" else "Text-only board mode"))
    }

    fun toggleExplanationsVisibility() {
        val next = !_uiState.value.profile.showExplanations
        updateProfile(_uiState.value.profile.copy(showExplanations = next))
    }

    fun toggleFullscreen() {
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    fun updateFollowingLive(following: Boolean) {
        _uiState.update { it.copy(isFollowingLive = following) }
    }

    fun updateProfile(profile: TeacherProfile) {
        applyProfileToEngines(profile)
        historyManager.saveTeacherProfile(profile)
        _uiState.update { it.copy(profile = profile) }
    }

    fun saveProgressOnBackground() {
        val sol = animationEngine.getSolution() ?: return
        viewModelScope.launch {
            historyManager.saveSession(
                solution = sol,
                lastStepIndex = animationEngine.getActiveStepIndex(),
                isCompleted = animationEngine.getStatus() == BoardPlaybackStatus.COMPLETED,
                markSaved = sol.isSaved
            )
        }
    }

    private fun applyProfileToEngines(profile: TeacherProfile) {
        animationEngine.setSpeed(profile.writingSpeed)
        animationEngine.setVoiceEnabled(profile.voiceEnabled)
        speechSync.configure(profile.voiceEnabled, profile.voiceLanguage, profile.writingSpeed)
    }

    private fun refreshUiFromEngine(banner: String? = _uiState.value.statusBannerText) {
        val sol = animationEngine.getSolution()
        val focusedIdx = animationEngine.getFocusedStepIndex()
        val activeIdx = animationEngine.getActiveStepIndex()
        val focusedStep = sol?.steps?.getOrNull(focusedIdx) ?: sol?.steps?.getOrNull(activeIdx)
        val explanation = focusedStep?.explanation?.takeIf { it.isNotBlank() }
            ?: focusedStep?.narrationText()?.takeIf { it.isNotBlank() }
            ?: ""

        _uiState.update { cur ->
            cur.copy(
                subject = sol?.subject,
                topic = sol?.topic.orEmpty(),
                chapter = sol?.chapter.orEmpty(),
                question = sol?.question.orEmpty(),
                playbackStatus = animationEngine.getStatus(),
                activeStepIndex = activeIdx,
                totalSteps = sol?.steps?.size ?: 0,
                focusedStepIndex = focusedIdx,
                isFocusedStepBookmarked = focusedStep?.isBookmarked == true,
                liveExplanationText = explanation,
                canUndo = animationEngine.canUndo(),
                canRedo = animationEngine.canRedo(),
                statusBannerText = banner
            )
        }
    }

    private fun recycleReviewBitmap(review: PendingOcrReview) {
        val bmp = review.previewBitmap
        if (bmp != null && !bmp.isRecycled) bmp.recycle()
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        mediaJob?.cancel()
        doubtJob?.cancel()
        _uiState.value.pendingOcrReview?.let { recycleReviewBitmap(it) }
        speechSync.release()
        inputManager.release()
    }

    companion object {
        private const val KEY_SAVED_SESSION_ID = "study_saved_session_id"
        private const val KEY_SAVED_QUESTION = "study_saved_question"

        internal fun createForTest(
            application: Application,
            savedStateHandle: SavedStateHandle,
            aiEngine: StudyAIEngine,
            historyManager: StudyHistoryManager,
            inputManager: StudyInputManager,
            speechSync: StudySpeechSynchronizer
        ): StudyBoardViewModel = StudyBoardViewModel(
            application = application,
            savedStateHandle = savedStateHandle,
            aiEngine = aiEngine,
            historyManager = historyManager,
            inputManager = inputManager,
            speechSync = speechSync
        )
    }
}
