package com.jarvis.assistant.study

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Central state and command bus connecting Study Board, StudyVoice, and Jarvis voice services.
 *
 * Designed without Android framework dependencies so both JVM unit tests and background services
 * can observe narration state, suppress accidental wake-word triggering while Study Board is
 * speaking aloud, inspect current board state, and dispatch queued UI/playback commands safely
 * across activity cold-starts.
 */
object StudyBoardController {

    enum class PlaybackAction {
        PLAY,
        PAUSE,
        TOGGLE,
        NEXT,
        PREVIOUS,
        REPLAY_STEP,
        REPLAY_ALL,
        STOP,
        RETRY
    }

    enum class SettingToggleKey {
        VOICE,
        NOTES,
        PEN,
        STATUS,
        MEMORY,
        FULLSCREEN,
        FOLLOW_PEN,
        BOOKMARK_CURRENT
    }

    enum class SettingOptionKey {
        LANGUAGE,
        VOICE_LANGUAGE,
        LEVEL,
        DEPTH,
        PROVIDER,
        ASK_MODE
    }

    enum class BoardCanvasAction {
        ZOOM_IN,
        ZOOM_OUT,
        ZOOM_RESET,
        FIT_BOARD,
        SCROLL_TOP,
        SCROLL_BOTTOM,
        COPY_BOARD,
        COPY_STEP,
        UNDO,
        REDO,
        CLEAR_BOARD,
        CLEAR_MEMORY,
        SAVE_LESSON
    }

    enum class QuizAction {
        START,
        SUBMIT_ANSWER,
        NEXT,
        HINT,
        DISMISS
    }

    enum class UiTarget {
        HISTORY,
        CAMERA,
        IMAGE_PICKER,
        PDF_PICKER,
        TEACHING_SETTINGS,
        EXPORT_SHEET,
        EXPORT_PDF,
        EXPORT_PNG
    }

    sealed class StudyBoardCommand {
        data class OpenAndExplain(
            val question: String,
            val mode: String = "auto",
            val language: String? = null,
            val level: String? = null,
            val depth: String? = null,
            val stepNumber: Int? = null,
            val animateTyping: Boolean = true
        ) : StudyBoardCommand()

        data class Playback(val action: PlaybackAction) : StudyBoardCommand()

        data class SetSpeed(
            val speed: Float? = null,
            val delta: Float? = null
        ) : StudyBoardCommand()

        data class ToggleSetting(
            val key: SettingToggleKey,
            val value: Boolean? = null
        ) : StudyBoardCommand()

        data class SetOption(
            val key: SettingOptionKey,
            val value: String
        ) : StudyBoardCommand()

        data class CanvasAction(val action: BoardCanvasAction) : StudyBoardCommand()

        data class SelectStep(val stepNumber: Int) : StudyBoardCommand()

        data class BookmarkStep(val stepNumber: Int? = null) : StudyBoardCommand()

        data class QuickDoubt(val kind: String, val stepNumber: Int? = null) : StudyBoardCommand()

        data class QuizCommand(val action: QuizAction, val answer: String = "") : StudyBoardCommand()

        data object ResumeInterrupted : StudyBoardCommand()

        data class OpenUi(val target: UiTarget) : StudyBoardCommand()

        data object CloseBoard : StudyBoardCommand()
    }

    data class BoardState(
        val visible: Boolean = false,
        val phase: String = "empty",
        val question: String = "",
        val subject: String = "",
        val currentBlockIndex: Int = -1,
        val totalBlocks: Int = 0,
        val currentStepNumber: Int = 0,
        val selectedStepNumber: Int = 0,
        val totalSteps: Int = 0,
        val playbackState: String = "IDLE",
        val speed: Float = 1f,
        val voiceOn: Boolean = true,
        val notesOn: Boolean = true,
        val penOn: Boolean = true,
        val statusOn: Boolean = true,
        val memoryOn: Boolean = true,
        val askMode: String = "new",
        val language: String = "English",
        val voiceLanguage: String = "Auto",
        val level: String = "Auto",
        val depth: String = "Standard",
        val provider: String = "Gemini",
        val activeVoiceName: String = "",
        val isBusy: Boolean = false,
        val isNarrating: Boolean = false,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val hasContent: Boolean = false,
        val fullscreen: Boolean = false,
        val followPen: Boolean = true,
        val bookmarkedCount: Int = 0,
        val lastError: String = "",
        val boardDigest: String = "",
        val recentEvents: List<String> = emptyList(),
        val quizActive: Boolean = false,
        val quizQuestion: String = "",
        val quizOptions: List<String> = emptyList(),
        val quizProgress: String = "",
        val quizFeedback: String = ""
    )

    private val lock = Any()
    private val pendingCommands = ArrayDeque<StudyBoardCommand>()
    private val eventLog = ArrayDeque<String>()
    private var receiverAttached = false

    private val _isBoardVisible = MutableStateFlow(false)
    val isBoardVisible: StateFlow<Boolean> = _isBoardVisible.asStateFlow()

    private val _isNarrating = MutableStateFlow(false)
    val isNarrating: StateFlow<Boolean> = _isNarrating.asStateFlow()

    private val _isMicInputActive = MutableStateFlow(false)
    val isMicInputActive: StateFlow<Boolean> = _isMicInputActive.asStateFlow()

    private val _stateSnapshot = MutableStateFlow(BoardState())
    val stateSnapshot: StateFlow<BoardState> = _stateSnapshot.asStateFlow()

    private val _commands = MutableSharedFlow<StudyBoardCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<StudyBoardCommand> = _commands.asSharedFlow()

    fun isAudioSuppressionActive(): Boolean =
        _isNarrating.value || _isMicInputActive.value

    fun setBoardVisible(visible: Boolean) {
        _isBoardVisible.value = visible
        updateState { it.copy(visible = visible) }
    }

    fun setNarrating(active: Boolean) {
        _isNarrating.value = active
        updateState { it.copy(isNarrating = active) }
    }

    fun setMicInputActive(active: Boolean) {
        _isMicInputActive.value = active
    }

    fun updateState(transform: (BoardState) -> BoardState) {
        synchronized(lock) {
            _stateSnapshot.value = transform(_stateSnapshot.value)
        }
    }

    fun publishState(state: BoardState) {
        synchronized(lock) {
            _stateSnapshot.value = state
        }
    }

    /** Records an observable event on the Study Board so JARVIS always knows what happened recently. */
    fun recordEvent(summary: String) {
        val clean = summary.replace(Regex("\\s+"), " ").trim().take(160)
        if (clean.isEmpty()) return
        synchronized(lock) {
            if (eventLog.peekLast() == clean) return
            while (eventLog.size >= 12) eventLog.removeFirst()
            eventLog.addLast(clean)
            _stateSnapshot.value = _stateSnapshot.value.copy(recentEvents = eventLog.toList())
        }
    }

    /** Compact live Study Board awareness section injected into JARVIS voice & chat prompts. */
    fun promptContextSection(): String {
        val s = _stateSnapshot.value
        if (!s.visible && !s.hasContent && s.recentEvents.isEmpty()) return ""
        return buildString {
            appendLine("\n[LIVE STUDY BOARD STATE — YOU CAN INSPECT AND CONTROL EVERYTHING HERE]:")
            appendLine("Visible: ${s.visible} | Phase: ${s.phase} | Playback: ${s.playbackState} | AskMode: ${s.askMode}")
            if (s.question.isNotBlank()) appendLine("Active Question: ${s.question}")
            if (s.subject.isNotBlank()) appendLine("Subject/Level: ${s.subject}")
            if (s.totalSteps > 0) appendLine("Step: ${s.currentStepNumber}/${s.totalSteps} (Selected step: ${if (s.selectedStepNumber > 0) s.selectedStepNumber else "none"})")
            appendLine("Settings: lang=${s.language}, voiceLang=${s.voiceLanguage}, level=${s.level}, depth=${s.depth}, speed=${s.speed}x, voice=${s.voiceOn}, notes=${s.notesOn}, fullscreen=${s.fullscreen}")
            if (s.boardDigest.isNotBlank()) {
                appendLine("Written on Board Now:\n${s.boardDigest.take(2_400)}")
            }
            if (s.quizActive && s.quizQuestion.isNotBlank()) {
                appendLine("Active Checkup Quiz (${s.quizProgress}): ${s.quizQuestion}")
                if (s.quizOptions.isNotEmpty()) appendLine("Options: ${s.quizOptions.joinToString(" | ")}")
                if (s.quizFeedback.isNotBlank()) appendLine("Last Checkup Feedback: ${s.quizFeedback}")
            }
            if (s.recentEvents.isNotEmpty()) {
                appendLine("Recent Study Board Events: ${s.recentEvents.takeLast(6).joinToString(" -> ")}")
            }
        }.trimEnd()
    }

    /**
     * Marks whether [StudyBoardActivity] is actively collecting [commands].
     * Commands queued while detached are returned in FIFO order to be executed immediately.
     */
    fun attachReceiver(): List<StudyBoardCommand> {
        synchronized(lock) {
            receiverAttached = true
            if (pendingCommands.isEmpty()) return emptyList()
            val drained = ArrayList<StudyBoardCommand>(pendingCommands.size)
            while (pendingCommands.isNotEmpty()) {
                drained.add(pendingCommands.removeFirst())
            }
            return drained
        }
    }

    fun detachReceiver() {
        synchronized(lock) {
            receiverAttached = false
        }
    }

    /**
     * Dispatches [command] directly to the active Study Board UI, or buffers it until
     * [attachReceiver] is called after activity launch.
     */
    fun dispatchCommand(command: StudyBoardCommand) {
        val emitNow: Boolean
        synchronized(lock) {
            emitNow = receiverAttached
            if (!emitNow) {
                while (pendingCommands.size >= 24) {
                    pendingCommands.removeFirst()
                }
                pendingCommands.addLast(command)
            }
        }
        if (emitNow) {
            val delivered = _commands.tryEmit(command)
            if (!delivered) {
                synchronized(lock) {
                    pendingCommands.addLast(command)
                }
            }
        }
    }

    /**
     * Clears pending command queue and resets transient audio flags (for lifecycle cleanup or tests).
     */
    fun resetForTest() {
        synchronized(lock) {
            pendingCommands.clear()
            eventLog.clear()
            receiverAttached = false
            _stateSnapshot.value = BoardState()
        }
        _isBoardVisible.value = false
        _isNarrating.value = false
        _isMicInputActive.value = false
    }
}
