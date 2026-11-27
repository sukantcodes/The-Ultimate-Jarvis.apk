package com.jarvis.assistant.ui.studyboard

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.provider.ChatAttachment
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.Thinking
import com.jarvis.assistant.study.BoardBlock
import com.jarvis.assistant.study.BoardStyle
import com.jarvis.assistant.study.DiagramSpec
import com.jarvis.assistant.study.ExtractedQuestion
import com.jarvis.assistant.study.StepKind
import com.jarvis.assistant.study.StudyAssembler
import com.jarvis.assistant.study.StudyBoardController
import com.jarvis.assistant.study.StudyBoardLayout
import com.jarvis.assistant.study.StudyDiagramAtlas
import com.jarvis.assistant.study.StudyDoubt
import com.jarvis.assistant.study.StudyDoubts
import com.jarvis.assistant.study.StudyLesson
import com.jarvis.assistant.study.StudyPlayback
import com.jarvis.assistant.study.StudyProfile
import com.jarvis.assistant.study.StudyPrompt
import com.jarvis.assistant.study.StudySpeechText
import com.jarvis.assistant.study.StudyStep
import com.jarvis.assistant.study.StudyStreamParser
import com.jarvis.assistant.study.Verification
import com.jarvis.assistant.study.ai.GeminiModels
import com.jarvis.assistant.study.ai.StudyAiEngine
import com.jarvis.assistant.study.ai.StudyAiException
import com.jarvis.assistant.study.data.StudyHistoryManager
import com.jarvis.assistant.study.voice.StudyVoice
import com.jarvis.assistant.study.voice.StudyVoiceCodec
import com.jarvis.assistant.study.StudyMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Owns one Study Board session. It lives through screen rotation and activity recreation, so a solution that is being
 * written is never lost. Pipeline: AI text stream -> stream parser -> validating assembler -> board layout -> playback clock
 * (which the board view and the narration follow). Every stage runs off the main thread; only finished blocks are posted to it.
 */
class StudyViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase { IDLE, EXTRACTING, GENERATING, READY, ERROR }
    enum class AskMode { NEW, DOUBT }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val retryable: Boolean = false,
        val subjectLine: String = "",
        val verification: String = "",
        val warnings: Int = 0,
        val loadingDetail: String = ""
    )

    sealed class Event {
        data class Toast(val text: String) : Event()
        data class ConfirmQuestions(val questions: List<ExtractedQuestion>, val source: String, val previewPath: String? = null) : Event()
        data class PdfReady(val path: String, val pages: Int) : Event()
        data class Reveal(val block: Int) : Event()
    }

    data class Spotlight(
        val active: Boolean = false,
        val blockIndex: Int = -1,
        val blockProgress: Float = 0f,
        val transcript: String = ""
    )

    val ui = MutableStateFlow(Ui())
    val blocks = MutableStateFlow<List<BoardBlock>>(emptyList())
    /** Bumped whenever playback or the session changes, so the screen refreshes its controls. */
    val version = MutableStateFlow(0)
    val eraseLimit = MutableStateFlow(Int.MAX_VALUE)
    val askMode = MutableStateFlow(AskMode.NEW)
    val selectedBlock = MutableStateFlow(-1)
    val bookmarks = MutableStateFlow<Set<Int>>(emptySet())
    val narrationAmplitude = MutableStateFlow(0f)
    val isNarrating = MutableStateFlow(false)
    val activeVoiceName = MutableStateFlow("")
    val spotlight = MutableStateFlow(Spotlight())
    val events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val interrupted = MutableStateFlow<Pair<String, String>?>(null)

    val playback = StudyPlayback()
    /** Set when the screen went to the background (or rotated) while writing, so writing resumes when it returns. */
    var resumeAfterBackground = false
    val prefs = StudyPrefs(app)
    private val history = StudyHistoryManager(app)
    private val main = Handler(Looper.getMainLooper())
    private val layoutExec = Executors.newSingleThreadExecutor { r -> Thread(r, "study-layout") }
    private val layoutDispatcher = layoutExec.asCoroutineDispatcher()
    private var metrics: AndroidMetrics? = null
    private val style = BoardStyle(width = StudyBoardView.BOARD_WIDTH, baseSize = 34f, margin = StudyBoardView.MARGIN)

    private var voice: StudyVoice? = null
    val memory = StudyMemory()
    private var narrationToken = 0L
    private var lastState = StudyPlayback.State.IDLE
    private var voiceFailureShown = false
    private var continuousActive = false
    private var continuousPlan: StudyVoiceCodec.ContinuousPlan? = null
    private var continuousEndBlock = -1
    private var singleNarratingBlock = -1
    private var fromSavedSession = false

    private class StagedBlock(val block: BoardBlock, val speech: String, val explanation: String)

    // ----- session state (read and written on the main thread unless noted) -----
    private var sessionId = UUID.randomUUID().toString()
    private var createdAt = System.currentTimeMillis()
    private var assembler: StudyAssembler? = null
    private val doubts = ArrayList<StudyDoubt>()
    private val speeches = ArrayList<String>()
    private val explanations = ArrayList<String>()
    private var job: Job? = null
    private var lastSave = 0L
    private var pendingImages: List<ChatAttachment.Image> = emptyList()
    private var lastAttachments: List<ChatAttachment> = emptyList()
    private var lastQuestion = ""
    private var lastWasDoubt = false
    private var lastDoubtText = ""
    private var lastDoubtRef: Int? = null
    private var doubtBlockStart = -1
    private var savedFlag = false

    val voiceOn: Boolean get() = prefs.voiceOn
    val lesson: StudyLesson? get() = assembler?.lesson
    val doubtList: List<StudyDoubt> get() = doubts.toList()
    val isBusy: Boolean get() = job?.isActive == true
    val currentExplanation: String get() = explanations.getOrNull(playback.current).orEmpty()
    val hasContent: Boolean get() = blocks.value.isNotEmpty()

    init {
        playback.speed = prefs.speed
        memory.load(prefs.memoryJson)
        playback.listener = object : StudyPlayback.Listener {
            override fun onBlockStarted(index: Int, replay: Boolean) {
                if (!continuousActive || replay) {
                    continuousActive = false
                    continuousPlan = null
                    narrate(index)
                }
                bump()
            }
            override fun onBlockFinished(index: Int) { bump() }
            override fun onAllFinished() { bump() }
            override fun onStateChanged(state: StudyPlayback.State) {
                val prev = lastState
                lastState = state
                if (state == StudyPlayback.State.PAUSED) {
                    if (continuousActive) {
                        voice?.pause()
                        isNarrating.value = false
                        narrationAmplitude.value = 0f
                        spotlight.value = Spotlight()
                    } else {
                        stopSpeech()
                    }
                } else if (state == StudyPlayback.State.PLAYING && prev == StudyPlayback.State.PAUSED) {
                    if (continuousActive) {
                        val resumed = prefs.voiceOn && voice?.resume() == true
                        if (resumed) {
                            isNarrating.value = true
                            playback.narrationBusy = true
                        } else {
                            continuousActive = false
                            continuousPlan = null
                            val endIdx = continuousEndBlock.takeIf { it in playback.current until blocks.value.size }
                                ?: blocks.value.lastIndex
                            startContinuousRange(playback.current.coerceAtLeast(0), endIdx, keepPrinted = playback.continuousKeepPrinted)
                        }
                    } else {
                        narrate(playback.current)
                    }
                }
                bump()
            }
        }
        viewModelScope.launch { interrupted.value = runCatching { history.latestInterrupted() }.getOrNull() }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val key = JarvisApp.instance.preferences.apiKey.trim()
                if (key.isNotEmpty()) GeminiModels.warmUp(ProviderRuntime.transport(), key)
            }
        }
    }

    // ----- the teacher's memory of this study session -----

    private fun memoryBlock(exceptQuestion: String? = null): String = if (prefs.memoryOn) memory.block(exceptQuestion) else ""

    private fun remember(turn: StudyMemory.Turn) {
        if (!prefs.memoryOn) return
        memory.add(turn)
        prefs.memoryJson = memory.toJson()
    }

    /** Forgets everything the teacher remembers about earlier questions. The saved lessons in history are not touched. */
    fun clearMemory() {
        memory.clear()
        prefs.memoryJson = ""
        toast("The teacher's memory of earlier questions was cleared.")
        bump()
    }

    fun setMemory(on: Boolean) { prefs.memoryOn = on; bump() }

    private fun bump() {
        version.value = version.value + 1
        publishControllerSnapshot()
    }

    fun publishControllerSnapshot() {
        val currentUi = ui.value
        val totalSteps = lesson?.steps?.size ?: 0
        val curStep = blocks.value.getOrNull(playback.current)?.stepIndex?.takeIf { it in 1..totalSteps } ?: 0
        val phaseStr = when {
            isNarrating.value -> "narrating"
            currentUi.phase == Phase.EXTRACTING -> "thinking"
            currentUi.phase == Phase.GENERATING -> "drawing"
            playback.state == StudyPlayback.State.PAUSED -> "paused"
            currentUi.phase == Phase.ERROR -> "error"
            hasContent -> "ready"
            else -> "empty"
        }
        StudyBoardController.updateState { prev ->
            prev.copy(
                phase = phaseStr,
                question = lesson?.question ?: lastQuestion,
                subject = currentUi.subjectLine,
                currentBlockIndex = playback.current,
                totalBlocks = blocks.value.size,
                currentStepNumber = curStep,
                totalSteps = totalSteps,
                playbackState = playback.state.name,
                speed = playback.speed,
                voiceOn = prefs.voiceOn,
                notesOn = prefs.notesOn,
                penOn = prefs.penOn,
                statusOn = prefs.statusOn,
                memoryOn = prefs.memoryOn,
                askMode = if (askMode.value == AskMode.DOUBT) "doubt" else "new",
                language = prefs.language,
                voiceLanguage = prefs.voiceLanguage,
                level = prefs.level,
                depth = prefs.depth.label,
                provider = prefs.provider,
                activeVoiceName = activeVoiceName.value,
                isBusy = isBusy,
                isNarrating = isNarrating.value,
                canUndo = canUndo,
                canRedo = canRedo,
                hasContent = hasContent,
                bookmarkedCount = bookmarks.value.size,
                lastError = currentUi.message
            )
        }
    }

    /** Pre-warms the JARVIS streaming voice socket while Study Board is open so first speech starts instantaneously. */
    fun warmUpVoice() {
        if (!prefs.voiceOn) return
        val engine = voiceEngine()
        activeVoiceName.value = engine.activeVoiceName()
        engine.warmUp(effectiveVoiceLanguage())
    }
    private fun toast(t: String) { events.tryEmit(Event.Toast(t)) }

    // ======================================================================================================
    // Asking
    // ======================================================================================================

    /** Entry point for typed and spoken input. A doubt is asked about the lesson already on the board. */
    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (isBusy) { toast("Still working on the current question. Stop it first to ask another."); return }
        if (askMode.value == AskMode.DOUBT && (lesson?.steps?.isNotEmpty() == true)) askDoubt(t) else startLesson(t, emptyList())
    }

    fun newQuestionMode() { askMode.value = AskMode.NEW; selectedBlock.value = -1 }

    /** Starts a fresh lesson for [question]. Anything already on the board is replaced (it stays in history). */
    fun startLesson(question: String, attachments: List<ChatAttachment>) {
        if (isBusy) return
        stopSpeech()
        playback.clear()
        blocks.value = emptyList(); speeches.clear(); explanations.clear(); doubts.clear()
        bookmarks.value = emptySet(); selectedBlock.value = -1; eraseLimit.value = Int.MAX_VALUE
        sessionId = UUID.randomUUID().toString(); createdAt = System.currentTimeMillis(); savedFlag = false
        lastQuestion = question; lastWasDoubt = false; lastAttachments = attachments; fromSavedSession = false
        val profile = prefs.profile()
        val asm = StudyAssembler(question, if (profile.level.equals("auto", true)) "" else profile.level)
        assembler = asm
        playback.setGenerationOpen(true)
        askMode.value = AskMode.NEW
        ui.value = Ui(Phase.GENERATING, subjectLine = "", loadingDetail = "Generating complete solution…")
        warmUpVoice()
        job = viewModelScope.launch(Dispatchers.Default) {
            val qBlock = layoutOn { st, m -> StudyBoardLayout.layoutQuestion(question, 0f, st, m) }
            addBlock(qBlock, "", "")
            withContext(Dispatchers.Main) { playback.jumpToEnd() }
            persist(StudyHistoryManager.Status.INTERRUPTED, force = true)
            val fastThinking = if (profile.depth == StudyProfile.Depth.ADVANCED) Thinking.LOW else Thinking.MINIMAL
            val wantsDiagram = Regex("\\b(draw|diagram|sketch|label|labelled|labeled|structure|figure|illustrate|parts of)\\b", RegexOption.IGNORE_CASE).containsMatchIn(question)
            val hasCanonicalDiagram = wantsDiagram && StudyDiagramAtlas.match(question) != null
            val staged = ArrayList<StagedBlock>()
            val ok = consume(
                StudyAiEngine.Request(
                    StudyPrompt.lessonSystem(profile),
                    StudyPrompt.lessonUser(question, memoryBlock(), hasCanonicalDiagram = hasCanonicalDiagram),
                    attachments,
                    thinking = fastThinking
                ),
                asm, doubt = false, baseBottom = qBlock.bottom, staged = staged
            )
            val startBlockIdx = blocks.value.size
            if (staged.isNotEmpty()) commitStagedBlocks(staged)
            finishLesson(asm, ok, startPlaybackFrom = if (staged.isNotEmpty()) startBlockIdx else -1)
        }
    }

    private fun askDoubt(text: String) {
        val l = lesson ?: return
        stopSpeech()
        val profile = prefs.profile()
        val ref = StudyDoubts.resolveStep(text, selectedBlock.value.takeIf { it >= 0 }?.let { blocks.value.getOrNull(it)?.stepIndex }, l.steps.size)
        lastWasDoubt = true; lastDoubtText = text; lastDoubtRef = ref
        val asm = StudyAssembler(text, l.level)
        ui.value = ui.value.copy(phase = Phase.GENERATING, message = "", retryable = false, loadingDetail = "Preparing doubt explanation…")
        playback.setGenerationOpen(true)
        doubtBlockStart = blocks.value.size
        warmUpVoice()
        job = viewModelScope.launch(Dispatchers.Default) {
            val dBlock = layoutOn { st, m -> StudyBoardLayout.layoutQuestion(text, blocks.value.lastOrNull()?.bottom ?: 0f, st, m, prefix = "?") }
            addBlock(dBlock, "", "")
            withContext(Dispatchers.Main) {
                playback.jumpToEnd()
                if (ref != null) blockOfStep(ref)?.let { events.tryEmit(Event.Reveal(it)) }
            }
            val fastThinking = if (profile.depth == StudyProfile.Depth.ADVANCED) Thinking.LOW else Thinking.MINIMAL
            val staged = ArrayList<StagedBlock>()
            val ok = consume(
                StudyAiEngine.Request(StudyPrompt.doubtSystem(profile), StudyPrompt.doubtUser(l, text, ref, memoryBlock(l.question)), thinking = fastThinking),
                asm, doubt = true, baseBottom = dBlock.bottom, staged = staged
            )
            val startIdx = blocks.value.size
            if (staged.isNotEmpty()) commitStagedBlocks(staged)
            val result = asm.lesson
            withContext(Dispatchers.Main) {
                playback.setGenerationOpen(false)
                if (ok && result.steps.isNotEmpty() || ok && result.finalAnswer.isNotEmpty()) {
                    val steps = result.steps.toMutableList()
                    if (result.finalAnswer.isNotEmpty() && steps.lastOrNull()?.isFinalHighlight != true) {
                        steps.add(StudyStep(Int.MAX_VALUE, StepKind.RESULT, result.finalAnswer, result.finalExplanation, spoken = result.finalSpoken))
                    }
                    val doubt = StudyDoubt(text, ref, steps, System.currentTimeMillis())
                    doubts.add(doubt)
                    remember(StudyMemory.turnFromDoubt(lesson, doubt, doubt.createdAt))
                    ui.value = ui.value.copy(phase = Phase.READY, message = "", retryable = false, loadingDetail = "")
                } else {
                    ui.value = ui.value.copy(loadingDetail = "")
                }
                if (staged.isNotEmpty()) startContinuousRange(startIdx, blocks.value.lastIndex)
            }
            persist(StudyHistoryManager.Status.COMPLETE, force = true)
        }
    }

    private fun blockOfStep(step: Int): Int? = blocks.value.indexOfFirst { it.stepIndex == step && !it.isQuestion }.takeIf { it >= 0 }

    /**
     * Streams [request] through the parser and assembler, writing each validated step to the board as soon as it passes.
     * Returns true when the stream ended normally.
     */
    private suspend fun consume(
        request: StudyAiEngine.Request,
        asm: StudyAssembler,
        doubt: Boolean,
        baseBottom: Float,
        staged: MutableList<StagedBlock>
    ): Boolean {
        val parser = StudyStreamParser()
        var restyled = false
        var negative = 0
        suspend fun handle(u: StudyAssembler.Update) {
            when (u) {
                is StudyAssembler.Update.LessonChanged -> if (!doubt) withContext(Dispatchers.Main) {
                    val l = u.lesson
                    ui.value = ui.value.copy(subjectLine = listOf(l.subject.label, l.topic, l.level).filter { it.isNotBlank() }.joinToString(" · "))
                }
                is StudyAssembler.Update.StepAdded -> {
                    restyled = false
                    val step = if (doubt) u.step.copy(index = --negative) else u.step
                    val prevBottom = staged.lastOrNull()?.block?.bottom ?: baseBottom
                    val block = layoutOn { st, m -> StudyBoardLayout.layoutStep(step, prevBottom, st, m) }
                    val say = speechFor(step, asm)
                    staged.add(StagedBlock(block, say, step.explanation))
                    withContext(Dispatchers.Main) {
                        val tag = if (step.kind == StepKind.DIAGRAM) "diagram" else "step ${staged.size}"
                        ui.value = ui.value.copy(loadingDetail = "Preparing $tag…")
                    }
                }
                is StudyAssembler.Update.StepRestyled -> {
                    restyled = true
                    if (staged.isNotEmpty()) {
                        val i = staged.lastIndex
                        val prevBottom = if (i > 0) staged[i - 1].block.bottom else baseBottom
                        val block = layoutOn { st, m -> StudyBoardLayout.layoutStep(u.step, prevBottom, st, m, isFinalBlock = true) }
                        staged[i] = StagedBlock(block, staged[i].speech, staged[i].explanation)
                    }
                }
                is StudyAssembler.Update.FinalAnswer -> {
                    if (!restyled) {
                        val prevBottom = staged.lastOrNull()?.block?.bottom ?: baseBottom
                        val block = layoutOn { st, m -> StudyBoardLayout.layoutFinal(u.answer, u.explanation, prevBottom, st, m) }
                        val say = finalSpeech(u.answer, u.explanation, asm, u.spoken)
                        staged.add(StagedBlock(block, say, u.explanation))
                        withContext(Dispatchers.Main) {
                            ui.value = ui.value.copy(loadingDetail = "Finalizing board & JARVIS voice…")
                        }
                    }
                }
                is StudyAssembler.Update.StepRejected -> withContext(Dispatchers.Main) {
                    ui.value = ui.value.copy(warnings = ui.value.warnings + 1, message = u.reason)
                }
                is StudyAssembler.Update.Declined -> withContext(Dispatchers.Main) {
                    ui.value = ui.value.copy(phase = Phase.ERROR, message = "This could not be solved reliably: ${u.reason}", retryable = true, loadingDetail = "")
                }
                is StudyAssembler.Update.DoubtTarget -> withContext(Dispatchers.Main) { blockOfStep(u.step)?.let { selectedBlock.value = it; events.tryEmit(Event.Reveal(it)) } }
            }
        }
        try {
            StudyAiEngine.stream(getApplication(), request).collect { chunk ->
                for (ev in parser.feed(chunk)) for (u in asm.accept(ev)) handle(u)
            }
            for (ev in parser.finish()) for (u in asm.accept(ev)) handle(u)
            for (u in asm.finish(true)) handle(u)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: StudyAiException) {
            for (ev in parser.finish()) asm.accept(ev)
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(phase = Phase.ERROR, message = e.message ?: "The AI request failed.", retryable = e.retryable, loadingDetail = "") }
            return false
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) { ui.value = ui.value.copy(phase = Phase.ERROR, message = "Something went wrong: ${e.message ?: e.javaClass.simpleName}", retryable = true, loadingDetail = "") }
            return false
        }
    }

    private suspend fun commitStagedBlocks(staged: List<StagedBlock>) = withContext(Dispatchers.Main) {
        for (item in staged) {
            speeches.add(item.speech)
            explanations.add(item.explanation)
        }
        blocks.value = blocks.value + staged.map { it.block }
        eraseLimit.value = Int.MAX_VALUE
        for (item in staged) {
            playback.addBlock(StudyPlayback.durationFor(item.block))
        }
        bump()
    }

    private suspend fun finishLesson(asm: StudyAssembler, streamOk: Boolean, startPlaybackFrom: Int) {
        withContext(Dispatchers.Main) {
            playback.setGenerationOpen(false)
            val l = asm.lesson
            val current = ui.value
            if (streamOk && l.complete && l.declinedReason.isEmpty()) {
                ui.value = current.copy(phase = Phase.READY, verification = l.verification.label,
                    message = if (l.verification == Verification.FAILED) current.message else "", loadingDetail = "")
                if (askMode.value == AskMode.NEW) askMode.value = AskMode.DOUBT
            } else if (current.phase != Phase.ERROR) {
                ui.value = current.copy(phase = Phase.ERROR, retryable = true,
                    message = current.message.ifEmpty { "The solution did not finish." }, verification = l.verification.label, loadingDetail = "")
            } else ui.value = current.copy(verification = l.verification.label, loadingDetail = "")
            if (startPlaybackFrom in blocks.value.indices) {
                startContinuousRange(startPlaybackFrom, blocks.value.lastIndex)
            }
        }
        val l = asm.lesson
        if (streamOk && l.complete && l.declinedReason.isEmpty() && l.verification != Verification.FAILED) {
            withContext(Dispatchers.Main) { remember(StudyMemory.turnFrom(l, System.currentTimeMillis())) }
        }
        persist(if (streamOk && l.complete && l.declinedReason.isEmpty()) StudyHistoryManager.Status.COMPLETE else StudyHistoryManager.Status.INTERRUPTED, force = true)
    }

    // ----- layout and block bookkeeping -----

    private suspend fun layoutOn(block: (BoardStyle, AndroidMetrics) -> BoardBlock): BoardBlock = withContext(layoutDispatcher) {
        val m = metrics ?: AndroidMetrics(StudyFonts(getApplication())).also { metrics = it }
        block(style, m)
    }

    private suspend fun addBlock(block: BoardBlock, speech: String, explanation: String) = withContext(Dispatchers.Main) {
        speeches.add(speech); explanations.add(explanation)
        blocks.value = blocks.value + block
        eraseLimit.value = Int.MAX_VALUE
        playback.addBlock(StudyPlayback.durationFor(block))
        bump()
    }

    // ======================================================================================================
    // Cancel / retry
    // ======================================================================================================

    fun cancel() {
        val j = job ?: return
        if (!j.isActive) return
        j.cancel()
        stopSpeech()
        playback.setGenerationOpen(false)
        ui.value = ui.value.copy(phase = if (hasContent) Phase.READY else Phase.IDLE, message = "Stopped.", retryable = hasContent && !lastWasDoubt, loadingDetail = "")
        viewModelScope.launch { persist(StudyHistoryManager.Status.INTERRUPTED, force = true) }
    }

    fun retry() {
        if (isBusy) return
        if (lastWasDoubt && lesson != null) {
            // drop the unfinished doubt and ask it again
            if (doubtBlockStart in 0 until blocks.value.size) {
                blocks.value = blocks.value.subList(0, doubtBlockStart).toList()
                while (speeches.size > doubtBlockStart) { speeches.removeAt(speeches.size - 1); explanations.removeAt(explanations.size - 1) }
                playback.truncateTo(doubtBlockStart)
            }
            askMode.value = AskMode.DOUBT
            askDoubt(lastDoubtText)
        } else if (lastQuestion.isNotEmpty()) startLesson(lastQuestion, lastAttachments)
    }

    // ======================================================================================================
    // Photos and PDFs
    // ======================================================================================================

    /** Reads a photo/picked image, saves a safe local preview, extracts its questions, and always lets the student check or ask about the image. */
    fun onImage(uri: Uri, source: String) {
        if (isBusy) { toast("Still working. Stop the current question first."); return }
        ui.value = Ui(Phase.EXTRACTING, message = "Loading and reading the selected image…")
        job = viewModelScope.launch {
            try {
                val loaded = StudyMedia.loadImage(getApplication(), uri)
                pendingImages = listOf(loaded.attachment)
                val preview = loaded.previewFile.absolutePath
                val qs = try {
                    StudyAiEngine.extractQuestions(getApplication(), pendingImages)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StudyAiException) {
                    // Image decoded fine: let user preview the picture and edit/confirm their question even if OCR found none
                    listOf(ExtractedQuestion("Solve or explain what is shown in this image", "low", hasDiagram = true, note = e.message.orEmpty()))
                }
                ui.value = ui.value.copy(phase = if (hasContent) Phase.READY else Phase.IDLE, message = "")
                events.tryEmit(Event.ConfirmQuestions(qs, source, preview))
            } catch (e: CancellationException) { throw e }
            catch (e: StudyMedia.MediaException) { fail(e.message ?: "Could not read the image.") }
            catch (e: Throwable) { fail("Could not read the image: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    fun onPdf(uri: Uri) {
        if (isBusy) { toast("Still working. Stop the current question first."); return }
        ui.value = Ui(Phase.EXTRACTING, message = "Opening the PDF…")
        job = viewModelScope.launch {
            try {
                val file = StudyMedia.importPdf(getApplication(), uri)
                val pages = StudyMedia.pageCount(file)
                ui.value = ui.value.copy(phase = if (hasContent) Phase.READY else Phase.IDLE, message = "")
                events.tryEmit(Event.PdfReady(file.absolutePath, pages))
            } catch (e: CancellationException) { throw e }
            catch (e: StudyMedia.MediaException) { fail(e.message ?: "Could not open the PDF.") }
            catch (e: Throwable) { fail("Could not open the PDF: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    /** Extracts the questions on one PDF page (0-based) for confirmation. */
    fun extractFromPdfPage(path: String, page: Int) {
        if (isBusy) return
        ui.value = Ui(Phase.EXTRACTING, message = "Reading page ${page + 1}…")
        job = viewModelScope.launch {
            try {
                val loaded = StudyMedia.pageWithPreview(getApplication(), File(path), page)
                pendingImages = listOf(loaded.attachment)
                val preview = loaded.previewFile.absolutePath
                val qs = try {
                    StudyAiEngine.extractQuestions(getApplication(), pendingImages)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: StudyAiException) {
                    listOf(ExtractedQuestion("Solve or explain page ${page + 1}", "low", hasDiagram = true, note = e.message.orEmpty()))
                }
                ui.value = ui.value.copy(phase = if (hasContent) Phase.READY else Phase.IDLE, message = "")
                events.tryEmit(Event.ConfirmQuestions(qs, "page ${page + 1}", preview))
            } catch (e: CancellationException) { throw e }
            catch (e: StudyMedia.MediaException) { fail(e.message ?: "Could not read that page.") }
            catch (e: Throwable) { fail("Could not read that page: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    /** A question about the whole document (not one extracted question). */
    fun askAboutPdf(path: String, question: String) {
        if (isBusy) return
        ui.value = Ui(Phase.EXTRACTING, message = "Preparing the document…")
        job = viewModelScope.launch {
            try {
                val pdf = StudyMedia.wholePdfAttachment(File(path))
                ui.value = Ui(Phase.IDLE)
                job = null
                startLesson("About the attached PDF: ${question.trim()}", listOf(pdf))
            } catch (e: CancellationException) { throw e }
            catch (e: StudyMedia.MediaException) { fail(e.message ?: "Could not use the PDF.") }
            catch (e: Throwable) { fail("Could not use the PDF: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    /** The student checked (and maybe corrected) a question read from an image. Only now is it solved. */
    fun solveConfirmed(text: String, keepImage: Boolean) {
        val images = if (keepImage) pendingImages else emptyList()
        pendingImages = emptyList()
        if (job?.isActive == true) return
        startLesson(text.trim(), images)
    }

    private fun fail(message: String) {
        ui.value = ui.value.copy(phase = if (hasContent) Phase.READY else Phase.ERROR, message = message, retryable = false)
    }

    fun dismissMessage() { ui.value = ui.value.copy(message = "", phase = if (ui.value.phase == Phase.ERROR && !hasContent) Phase.IDLE else if (ui.value.phase == Phase.ERROR) Phase.READY else ui.value.phase) }

    // ======================================================================================================
    // Playback, board and selection controls
    // ======================================================================================================

    fun togglePlay() {
        if (playback.state == StudyPlayback.State.FINISHED) {
            if (fromSavedSession) explainSavedAll() else replayAll()
        } else {
            playback.togglePause()
        }
    }
    fun next() = playback.next()
    fun previous() = playback.previous()
    fun replayStep() = playback.replayCurrent()
    fun explainSavedAll() {
        eraseLimit.value = Int.MAX_VALUE
        val list = blocks.value
        if (list.isEmpty()) return
        val startIdx = if (list.size > 1 && list[0].isQuestion) 1 else 0
        startContinuousRange(startIdx, list.lastIndex, keepPrinted = true)
    }
    fun replayAll() {
        fromSavedSession = false
        eraseLimit.value = Int.MAX_VALUE
        val list = blocks.value
        if (list.isEmpty()) return
        val startIdx = if (list.size > 1 && list[0].isQuestion) 1 else 0
        startContinuousRange(startIdx, list.lastIndex, keepPrinted = false)
    }

    fun setSpeed(s: Float) { playback.speed = s; prefs.speed = playback.speed; voice?.setRate(playback.speed); bump() }

    val canUndo: Boolean get() = minOf(eraseLimit.value, blocks.value.size) > 1
    val canRedo: Boolean get() = minOf(eraseLimit.value, blocks.value.size) < blocks.value.size

    /** "Step 2 of 5" for the block being written, counting only real steps (not the question or doubts). Empty when not applicable. */
    fun stepCounter(): String {
        val list = blocks.value
        val total = list.count { !it.isQuestion && it.stepIndex > 0 || it.stepIndex == Int.MAX_VALUE && !it.isQuestion }
        val i = playback.current
        if (total == 0 || i !in list.indices || list[i].isQuestion) return ""
        val n = list.subList(0, i + 1).count { !it.isQuestion && (it.stepIndex > 0 || it.stepIndex == Int.MAX_VALUE) }
        return if (n <= 0) "" else "Step $n of $total" + if (isBusy) "+" else ""
    }

    fun undo() {
        if (playback.state == StudyPlayback.State.PLAYING) playback.pause()
        val shown = minOf(eraseLimit.value, blocks.value.size)
        if (shown > 1) {
            eraseLimit.value = shown - 1
            bump()
        }
    }

    fun redo() {
        val shown = minOf(eraseLimit.value, blocks.value.size)
        if (shown < blocks.value.size) {
            eraseLimit.value = shown + 1
            bump()
        }
    }

    fun clearBoard() {
        if (playback.state == StudyPlayback.State.PLAYING) playback.pause()
        stopSpeech()
        eraseLimit.value = 0
        bump()
    }

    fun selectBlock(index: Int) {
        val b = blocks.value.getOrNull(index)
        if (b == null || b.isQuestion) {
            selectedBlock.value = -1
            bump()
            return
        }
        selectedBlock.value = if (selectedBlock.value == index) -1 else index
        if (selectedBlock.value >= 0 && lesson?.steps?.isNotEmpty() == true) askMode.value = AskMode.DOUBT
        bump()
    }

    /** The lesson step number of the selected block, or currently active step if none is tapped manually. */
    fun selectedStepNumber(): Int? = blocks.value.getOrNull(selectedBlock.value)?.stepIndex?.takeIf { it in 1..(lesson?.steps?.size ?: 0) }

    fun toggleBookmark() {
        val step = selectedStepNumber()
            ?: blocks.value.getOrNull(playback.current)?.stepIndex?.takeIf { it in 1..(lesson?.steps?.size ?: 0) }
            ?: run { toast("Tap a step first, then bookmark it."); return }
        bookmarks.value = if (step in bookmarks.value) bookmarks.value - step else bookmarks.value + step
        toast(if (step in bookmarks.value) "Bookmarked step $step." else "Removed bookmark on step $step.")
        bump()
        viewModelScope.launch { persist(StudyHistoryManager.Status.COMPLETE, force = true) }
    }

    fun setNotes(on: Boolean) { prefs.notesOn = on; bump() }

    fun setVoice(on: Boolean) {
        prefs.voiceOn = on
        if (!on) {
            stopSpeech()
            playback.narrationBusy = false
            toast("Voice narration muted.")
        } else {
            voiceFailureShown = false
            toast("Voice narration on.")
            val curIdx = playback.current.coerceAtLeast(0)
            if (playback.state == StudyPlayback.State.PLAYING && hasContent) {
                startContinuousRange(curIdx, blocks.value.lastIndex, keepPrinted = playback.continuousKeepPrinted)
            } else if (playback.state == StudyPlayback.State.FINISHED && hasContent) {
                val startIdx = if (blocks.value.size > 1 && blocks.value[0].isQuestion) 1 else 0
                startContinuousRange(startIdx, blocks.value.lastIndex, keepPrinted = true)
            } else if (hasContent && curIdx in speeches.indices && speeches[curIdx].isNotBlank()) {
                narrate(curIdx)
            }
        }
        bump()
    }

    // ======================================================================================================
    // Narration
    // ======================================================================================================

    private fun effectiveVoiceLanguage(): String =
        prefs.voiceLanguage.takeUnless { it.equals("auto", true) }
            ?.takeIf { it.isNotBlank() }
            ?: prefs.language.takeIf { it.isNotBlank() }
            ?: "Auto"

    private fun languageKey(): String =
        effectiveVoiceLanguage().trim().lowercase().ifEmpty { "english" }

    private fun speechFor(step: StudyStep, @Suppress("UNUSED_PARAMETER") asm: StudyAssembler): String {
        val cleanedSpoken = step.spoken.trim()
        val expl = cleanForSpeech(step.explanation)
        if (cleanedSpoken.isNotBlank()) {
            // If a diagram has labels and the spoken line was very brief, enrich with labels so nothing on board goes unexplained
            if (step.kind == StepKind.DIAGRAM && step.diagram != null && cleanedSpoken.length < 40) {
                val labels = step.diagram.items.filterIsInstance<DiagramSpec.Item.Label>()
                    .map { it.text.trim() }.filter { it.isNotEmpty() }.distinct()
                if (labels.isNotEmpty()) {
                    val tail = labels.take(8).joinToString(", ")
                    return "$cleanedSpoken Key labelled parts shown here are $tail."
                }
            }
            return cleanedSpoken
        }
        if (step.kind == StepKind.DIAGRAM) {
            val diag = step.diagram
            val title = diag?.title?.trim().orEmpty()
            val labels = diag?.items?.filterIsInstance<DiagramSpec.Item.Label>()
                ?.map { it.text.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()
            val parts = ArrayList<String>()
            if (title.isNotEmpty()) parts.add("Here is the diagram of $title.")
            if (expl.isNotEmpty()) parts.add(expl)
            if (labels.isNotEmpty()) parts.add("Key parts shown on the board are ${labels.take(10).joinToString(", ")}.")
            return parts.joinToString(" ").trim()
        }
        if (step.kind == StepKind.CODE) {
            if (expl.isNotEmpty()) return expl
            val lang = step.language.trim().ifEmpty { "code" }
            return "Look at the $lang snippet written on the board."
        }
        if (step.content.isBlank()) return expl
        val contentSpoken = if (step.proseMode) cleanForSpeech(step.content) else StudySpeechText.verbalize(step.content, languageKey())
        return if (expl.isNotEmpty() && !contentSpoken.contains(expl, ignoreCase = true) && !expl.contains(contentSpoken, ignoreCase = true)) {
            "$contentSpoken. $expl"
        } else {
            contentSpoken.ifEmpty { expl }
        }
    }

    private fun finalSpeech(answer: String, explanation: String, @Suppress("UNUSED_PARAMETER") asm: StudyAssembler, spoken: String = ""): String {
        val expl = cleanForSpeech(explanation)
        if (spoken.isNotBlank()) {
            return if (expl.isNotEmpty() && !spoken.contains(expl, true) && spoken.length < 180) "$spoken $expl" else spoken
        }
        val lang = languageKey()
        val verbal = if (StudyMathLike.isMath(answer)) StudySpeechText.verbalize(answer, lang) else cleanForSpeech(answer)
        val base = StudySpeechText.answerPrefix(lang) + ": " + verbal
        return if (expl.isNotEmpty()) "$base. $expl" else base
    }

    private fun cleanForSpeech(s: String) = s.replace(Regex("[*_`$\\\\{}^]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun localeFor(text: String): Locale {
        val named = effectiveVoiceLanguage()
        return Locale.forLanguageTag(StudyVoiceCodec.languageTag(named, text))
    }

    private fun voiceEngine(): StudyVoice = voice ?: StudyVoice(getApplication()).also { v ->
        v.setRate(playback.speed)
        v.setCallback(object : StudyVoice.Callback {
            override fun onStarted(token: Long, cloud: Boolean) {
                if (token != narrationToken) return
                // the first word is being spoken: the pen starts writing smoothly now
                isNarrating.value = true
                activeVoiceName.value = v.activeVoiceName()
                playback.startGate = false
                bump()
            }
            override fun onAmplitude(amplitude: Float) {
                narrationAmplitude.value = if (isNarrating.value) amplitude else 0f
            }
            override fun onProgress(token: Long, progressFrac: Float, spokenTranscript: String) {
                if (token != narrationToken) return
                if (!continuousActive) {
                    val idx = singleNarratingBlock
                    if (idx in blocks.value.indices) {
                        playback.syncContinuousProgress(idx, progressFrac)
                        spotlight.value = Spotlight(
                            active = true,
                            blockIndex = idx,
                            blockProgress = progressFrac.coerceIn(0f, 1f),
                            transcript = spokenTranscript
                        )
                    }
                    return
                }
                val plan = continuousPlan ?: return
                val segState = StudyVoiceCodec.segmentStateForProgress(plan.segments, progressFrac) ?: return
                val targetBlock = segState.first
                if (targetBlock > playback.current && targetBlock <= continuousEndBlock) {
                    playback.advanceContinuous(targetBlock)
                    bump()
                }
                val curBlock = playback.current
                val curSeg = plan.segments.firstOrNull { it.blockIndex == curBlock }
                    ?: plan.segments.lastOrNull { it.blockIndex <= curBlock }
                val localFrac = if (curSeg != null) {
                    val span = (curSeg.endFrac - curSeg.startFrac).coerceAtLeast(0.005f)
                    ((progressFrac - curSeg.startFrac) / span).coerceIn(0f, 1f)
                } else {
                    segState.second
                }
                playback.syncContinuousProgress(curBlock, localFrac)
                spotlight.value = Spotlight(
                    active = true,
                    blockIndex = curBlock,
                    blockProgress = localFrac,
                    transcript = spokenTranscript
                )
            }
            override fun onFinished(token: Long, ok: Boolean, reason: String) {
                if (token != narrationToken) return
                val wasContinuous = continuousActive
                val endIdx = continuousEndBlock
                continuousActive = false
                continuousPlan = null
                singleNarratingBlock = -1
                if (wasContinuous && endIdx in (playback.current + 1) until blocks.value.size) {
                    playback.advanceContinuous(endIdx)
                }
                playback.releaseNarrationSync()
                playback.startGate = false
                playback.narrationBusy = false
                isNarrating.value = false
                narrationAmplitude.value = 0f
                spotlight.value = Spotlight()
                if (!ok && !voiceFailureShown) {
                    voiceFailureShown = true
                    toast(reason.ifEmpty { "Voice narration could not play on this step." })
                }
                bump()
            }
            override fun onNote(text: String) {
                if (!voiceFailureShown) {
                    voiceFailureShown = true
                    toast(text)
                }
            }
        })
        activeVoiceName.value = v.activeVoiceName()
        voice = v
    }

    private fun startContinuousRange(startBlockIdx: Int, endBlockIdx: Int, keepPrinted: Boolean = false) {
        val list = blocks.value
        if (startBlockIdx !in list.indices || endBlockIdx < startBlockIdx) return
        val speakPairs = (startBlockIdx..endBlockIdx).mapNotNull { idx ->
            val s = speeches.getOrNull(idx).orEmpty().trim()
            if (s.isEmpty()) null else idx to s
        }
        val plan = StudyVoiceCodec.buildContinuousPlan(speakPairs)
        if (!prefs.voiceOn || plan.script.isBlank()) {
            continuousActive = false
            continuousPlan = null
            continuousEndBlock = endBlockIdx
            playback.startContinuousFrom(startBlockIdx, keepPrinted = keepPrinted)
            playback.startGate = false
            playback.narrationBusy = false
            bump()
            return
        }

        val engine = voiceEngine()
        activeVoiceName.value = engine.activeVoiceName()
        continuousActive = true
        continuousPlan = plan
        continuousEndBlock = endBlockIdx
        singleNarratingBlock = -1

        // Align block write-on durations dynamically to each step's share of spoken explanation
        for (seg in plan.segments) {
            val segMs = (plan.estimatedDurationMs * (seg.endFrac - seg.startFrac)).toLong().coerceAtLeast(1_100L)
            playback.setBlockDuration(seg.blockIndex, segMs)
        }

        playback.startContinuousFrom(startBlockIdx, keepPrinted = keepPrinted)
        val cloudOk = engine.cloudAvailable()
        playback.narrationBusy = true
        playback.startGate = cloudOk
        if (cloudOk) {
            isNarrating.value = true
            spotlight.value = Spotlight(active = true, blockIndex = startBlockIdx, blockProgress = 0.02f, transcript = "")
        }

        val myToken = engine.speakContinuous(plan, effectiveVoiceLanguage())
        narrationToken = myToken
        main.postDelayed({ if (narrationToken == myToken) playback.startGate = false }, 9_000L)
        val maxGuardMs = (plan.estimatedDurationMs + 45_000L).coerceIn(55_000L, 260_000L)
        main.postDelayed({
            if (narrationToken == myToken && playback.narrationBusy) {
                continuousActive = false
                continuousPlan = null
                playback.releaseNarrationSync()
                playback.startGate = false
                playback.narrationBusy = false
                isNarrating.value = false
                narrationAmplitude.value = 0f
                spotlight.value = Spotlight()
                bump()
            }
        }, maxGuardMs)
        bump()
    }

    /** Prepares the audio for a step that has just arrived, so it is ready before the pen gets there. */
    private fun warmVoice(text: String) {
        if (prefs.voiceOn && text.isNotBlank()) voiceEngine().prefetch(text, effectiveVoiceLanguage())
    }

    private fun narrate(index: Int) {
        if (!prefs.voiceOn || playback.state == StudyPlayback.State.PAUSED) return
        val text = speeches.getOrNull(index).orEmpty()
        if (text.isBlank()) {
            playback.narrationBusy = false
            playback.startGate = false
            isNarrating.value = false
            spotlight.value = Spotlight()
            return
        }
        val engine = voiceEngine()
        activeVoiceName.value = engine.activeVoiceName()
        singleNarratingBlock = index
        playback.narrationBusy = true
        val available = engine.cloudAvailable()
        if (available) {
            isNarrating.value = true
            spotlight.value = Spotlight(active = true, blockIndex = index, blockProgress = 0.02f, transcript = "")
        }
        playback.startGate = available
        val token = engine.speak(text, localeFor(text), effectiveVoiceLanguage())
        narrationToken = token
        main.postDelayed({ if (narrationToken == token) { playback.startGate = false } }, 7_500L)
        main.postDelayed({
            if (narrationToken == token && playback.narrationBusy) {
                playback.releaseNarrationSync()
                playback.narrationBusy = false
                isNarrating.value = false
                narrationAmplitude.value = 0f
                spotlight.value = Spotlight()
                bump()
            }
        }, 45_000L)
        bump()
    }

    /** Stops narration without touching the lesson or the board. Safe to call at any time. */
    fun stopSpeech() {
        narrationToken++
        continuousActive = false
        continuousPlan = null
        singleNarratingBlock = -1
        voice?.stop()
        playback.releaseNarrationSync()
        playback.narrationBusy = false
        playback.startGate = false
        isNarrating.value = false
        narrationAmplitude.value = 0f
        spotlight.value = Spotlight()
        bump()
    }

    /** Selects a 1-based step number on the board (for voice control / UI step selection). */
    fun selectStepNumber(stepNumber: Int): Boolean {
        val blockIdx = blockOfStep(stepNumber) ?: return false
        selectedBlock.value = blockIdx
        askMode.value = if (lesson?.steps?.isNotEmpty() == true) AskMode.DOUBT else AskMode.NEW
        events.tryEmit(Event.Reveal(blockIdx))
        bump()
        return true
    }

    /** Jumps playback to a specific step number (1-based) and replays its narration. */
    fun seekToStepNumber(stepNumber: Int): Boolean {
        val blockIdx = blockOfStep(stepNumber) ?: return false
        eraseLimit.value = Int.MAX_VALUE
        playback.seek(blockIdx)
        selectedBlock.value = blockIdx
        events.tryEmit(Event.Reveal(blockIdx))
        bump()
        return true
    }

    /** Called when the student starts talking: the narration yields to the microphone and the board waits. */
    fun interruptForMic() {
        stopSpeech()
        if (playback.state == StudyPlayback.State.PLAYING) playback.pause()
    }

    // ======================================================================================================
    // History
    // ======================================================================================================

    private fun persistThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastSave < 1500) return
        viewModelScope.launch { persist(StudyHistoryManager.Status.INTERRUPTED, force = false) }
    }

    private suspend fun persist(status: StudyHistoryManager.Status, force: Boolean) {
        val l = assembler?.lesson ?: return
        if (!force && System.currentTimeMillis() - lastSave < 1500) return
        lastSave = System.currentTimeMillis()
        val d = withContext(Dispatchers.Main) { doubts.toList() to bookmarks.value }
        runCatching { history.save(sessionId, l, d.first, d.second, status, createdAt, null) }
    }

    fun saveSession() {
        viewModelScope.launch {
            val l = assembler?.lesson
            if (l == null || l.steps.isEmpty()) { toast("Nothing to save yet."); return@launch }
            persist(if (l.complete) StudyHistoryManager.Status.COMPLETE else StudyHistoryManager.Status.INTERRUPTED, force = true)
            savedFlag = !savedFlag
            history.setSaved(sessionId, savedFlag)
            toast(if (savedFlag) "Saved to your Study history." else "Removed from saved lessons.")
        }
    }

    /** Opens a lesson from history and shows it fully written, without animating or calling the AI. */
    fun openSession(id: String) {
        if (isBusy) cancel()
        viewModelScope.launch {
            val loaded = history.load(id)
            if (loaded == null) { toast("That lesson could not be opened. It may be from a newer version."); return@launch }
            stopSpeech()
            playback.clear()
            speeches.clear(); explanations.clear(); doubts.clear()
            eraseLimit.value = Int.MAX_VALUE; selectedBlock.value = -1
            val l = loaded.restored.lesson
            sessionId = loaded.id; savedFlag = loaded.saved; createdAt = System.currentTimeMillis()
            lastQuestion = l.question; lastWasDoubt = false; lastAttachments = emptyList(); fromSavedSession = true
            val asm = StudyAssembler(l.question, l.level)
            val built = withContext(layoutDispatcher) {
                val m = metrics ?: AndroidMetrics(StudyFonts(getApplication())).also { metrics = it }
                val list = ArrayList<BoardBlock>(); val sp = ArrayList<String>(); val ex = ArrayList<String>()
                fun push(b: BoardBlock, s: String, e: String) { list.add(b); sp.add(s); ex.add(e) }
                push(StudyBoardLayout.layoutQuestion(l.question, 0f, style, m), "", "")
                for (s in l.steps) push(StudyBoardLayout.layoutStep(s, list.last().bottom, style, m), speechFor(s, asm), s.explanation)
                if (l.finalAnswer.isNotEmpty() && l.steps.lastOrNull()?.isFinalHighlight != true) push(StudyBoardLayout.layoutFinal(l.finalAnswer, l.finalExplanation, list.last().bottom, style, m), finalSpeech(l.finalAnswer, l.finalExplanation, asm, l.finalSpoken), l.finalExplanation)
                for (d in loaded.restored.doubts) {
                    push(StudyBoardLayout.layoutQuestion(d.question, list.last().bottom, style, m, prefix = "?"), "", "")
                    var k = 0
                    for (s in d.steps) {
                        val step = s.copy(index = if (s.index == Int.MAX_VALUE) Int.MAX_VALUE else --k)
                        val spText = if (s.index == Int.MAX_VALUE) finalSpeech(s.content, s.explanation, asm, s.spoken) else speechFor(step, asm)
                        push(if (s.index == Int.MAX_VALUE) StudyBoardLayout.layoutFinal(s.content, s.explanation, list.last().bottom, style, m) else StudyBoardLayout.layoutStep(step, list.last().bottom, style, m), spText, s.explanation)
                    }
                }
                Triple(list, sp, ex)
            }
            speeches.addAll(built.second); explanations.addAll(built.third)
            doubts.addAll(loaded.restored.doubts)
            bookmarks.value = loaded.restored.bookmarks
            blocks.value = built.first
            playback.setGenerationOpen(false)
            for (b in built.first) playback.addBlock(StudyPlayback.durationFor(b))
            playback.jumpToEnd()
            // keep the restored lesson in the assembler so doubts and export work on it
            restoreAssembler(asm, l)
            val complete = l.complete && l.declinedReason.isEmpty()
            ui.value = Ui(
                if (complete) Phase.READY else Phase.ERROR,
                message = if (complete) "" else "This solution was interrupted before it finished. Retry to solve it again.",
                retryable = !complete,
                subjectLine = listOf(l.subject.label, l.topic, l.level).filter { it.isNotBlank() }.joinToString(" · "),
                verification = l.verification.label
            )
            askMode.value = if (complete) AskMode.DOUBT else AskMode.NEW
            bump()
        }
    }

    private fun restoreAssembler(asm: StudyAssembler, l: StudyLesson) {
        // Re-feed the saved, already validated steps so the assembler holds the same lesson. Verification is kept as saved.
        asm.accept(com.jarvis.assistant.study.StudyEvent.Meta(l.subject.label, l.topic, l.level))
        for (s in l.steps) asm.accept(com.jarvis.assistant.study.StudyEvent.Step(s.kind, s.content, s.explanation, s.diagramJson.takeIf { it.isNotEmpty() }?.let { runCatching { com.google.gson.JsonParser.parseString(it).asJsonObject }.getOrNull() }, s.language, s.proseMode))
        if (l.finalAnswer.isNotEmpty()) asm.accept(com.jarvis.assistant.study.StudyEvent.Final(l.finalAnswer, l.finalExplanation, if (l.verification == Verification.MODEL_UNCERTAIN) "low" else ""))
        assembler = asm
    }

    fun dismissInterrupted() { interrupted.value = null }

    override fun onCleared() {
        job?.cancel()
        voice?.shutdown()
        layoutExec.shutdown()
        playback.listener = null
        super.onCleared()
    }
}

/** Tiny helper so the view model does not depend on the layout engine's internals. */
private object StudyMathLike {
    fun isMath(s: String) = com.jarvis.assistant.study.StudyMath.looksLikeMath(s)
}
