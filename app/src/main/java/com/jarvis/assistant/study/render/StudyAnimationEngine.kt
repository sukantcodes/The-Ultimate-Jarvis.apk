package com.jarvis.assistant.study.render

import com.jarvis.assistant.study.model.BoardPlaybackStatus
import com.jarvis.assistant.study.model.StudyDoubtEntry
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.study.model.StudyStreamEvent
import com.jarvis.assistant.study.model.TeacherProfile
import com.jarvis.assistant.study.model.VerificationStatus
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic 60 FPS playback & streaming animation state machine for [StudyWhiteboardView].
 *
 * Keeps incremental AI steps strictly ordered, animates Step N while Step N+1 streams in,
 * supports pause/resume/prev/next/replay-step/replay-all/speed/erase/undo/redo, and
 * coordinates optional voice narration synchronization without ever stalling UI threads.
 */
class StudyAnimationEngine {

    data class Snapshot(
        val solution: StudySolution?,
        val stepProgress: List<Float>,
        val finalAnswerProgress: Float,
        val activeStepIndex: Int,
        val focusedStepIndex: Int,
        val status: BoardPlaybackStatus,
        val isStreamOpen: Boolean
    )

    data class FrameOutcome(
        val changed: Boolean = false,
        val stepEntered: StudyStep? = null,
        val stepVisualCompleted: StudyStep? = null,
        val completedSolution: Boolean = false,
        val clearedAfterErase: Boolean = false
    )

    private var solution: StudySolution? = null
    private val stepProgress = ArrayList<Float>()
    private var finalAnswerProgress: Float = 0f
    private var activeStepIndex: Int = 0
    private var focusedStepIndex: Int = 0
    private var status: BoardPlaybackStatus = BoardPlaybackStatus.IDLE
    private var isStreamOpen: Boolean = false
    private var replaySingleStepOnly: Boolean = false
    private var speedMultiplier: Float = 1.0f

    private var voiceEnabled: Boolean = false
    private var awaitingVoiceForStep: Int? = null
    private var voiceWaitElapsedMs: Long = 0L
    private var stepDwellRemainingMs: Long = 0L

    private var eraseProgress: Float = 0f
    private var pendingAfterEraseAction: (() -> Unit)? = null

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    fun getSolution(): StudySolution? = solution
    fun getStatus(): BoardPlaybackStatus = status
    fun getActiveStepIndex(): Int = activeStepIndex
    fun getFocusedStepIndex(): Int = focusedStepIndex
    fun getFinalAnswerProgress(): Float = finalAnswerProgress
    fun getEraseProgress(): Float = eraseProgress
    fun getSpeed(): Float = speedMultiplier
    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    fun isStreamOpen(): Boolean = isStreamOpen

    fun getStepProgress(index: Int): Float = stepProgress.getOrElse(index) { 0f }

    fun setVoiceEnabled(enabled: Boolean) {
        voiceEnabled = enabled
        if (!enabled) {
            awaitingVoiceForStep = null
            voiceWaitElapsedMs = 0L
        }
    }

    fun setSpeed(speed: Float) {
        speedMultiplier = speed.coerceIn(0.5f, 3.0f)
    }

    fun focusStep(index: Int) {
        val count = solution?.steps?.size ?: 0
        if (count == 0) return
        focusedStepIndex = index.coerceIn(0, count - 1)
    }

    fun beginStreaming(profile: TeacherProfile, initialQuestion: String) {
        pushUndoIfMeaningful()
        redoStack.clear()
        val draft = StudySolution(
            educationLevel = profile.educationLevel,
            language = profile.preferredLanguage,
            depth = profile.preferredDepth,
            question = initialQuestion,
            verificationStatus = VerificationStatus.PARTIAL
        )
        solution = draft
        stepProgress.clear()
        finalAnswerProgress = 0f
        activeStepIndex = 0
        focusedStepIndex = 0
        isStreamOpen = true
        replaySingleStepOnly = false
        awaitingVoiceForStep = null
        stepDwellRemainingMs = 0L
        eraseProgress = 0f
        pendingAfterEraseAction = null
        status = BoardPlaybackStatus.STREAMING
    }

    fun onStreamMetadata(meta: StudyStreamEvent.Metadata) {
        val cur = solution ?: StudySolution()
        solution = cur.copy(
            subject = meta.subject,
            topic = meta.topic,
            chapter = meta.chapter,
            difficulty = meta.difficulty,
            educationLevel = meta.inferredLevel,
            question = meta.question.ifBlank { cur.question },
            givenValues = meta.givenValues,
            requiredQuantity = meta.requiredQuantity,
            formulas = meta.formulas,
            definitions = meta.definitions
        )
    }

    fun appendValidatedStep(step: StudyStep) {
        val cur = solution ?: return
        val mutableSteps = cur.steps.toMutableList()
        if (step.stepIndex < mutableSteps.size) {
            mutableSteps[step.stepIndex] = step
            while (stepProgress.size < mutableSteps.size) stepProgress += 0f
        } else {
            val normalized = step.copy(stepIndex = mutableSteps.size)
            mutableSteps += normalized
            stepProgress += 0f
        }
        solution = cur.copy(steps = mutableSteps.sortedBy { it.stepIndex })

        if (status == BoardPlaybackStatus.STREAMING || status == BoardPlaybackStatus.PLAYING) {
            // If prior steps already finished animating, immediately bring the new step onto stage!
            val prevIdx = mutableSteps.lastIndex - 1
            if (prevIdx < 0 || stepProgress.getOrElse(prevIdx) { 0f } >= 1f) {
                activeStepIndex = min(activeStepIndex, mutableSteps.lastIndex)
            }
        }
    }

    fun onStreamCompleted(completed: StudySolution) {
        val curSteps = solution?.steps.orEmpty()
        val mergedSteps = if (completed.steps.size >= curSteps.size) {
            completed.steps.mapIndexed { idx, s ->
                val wasBookmarked = curSteps.getOrNull(idx)?.isBookmarked == true
                if (wasBookmarked) s.copy(isBookmarked = true) else s
            }
        } else curSteps

        solution = completed.copy(steps = mergedSteps)
        while (stepProgress.size < mergedSteps.size) stepProgress += 0f
        isStreamOpen = false
        if (mergedSteps.isEmpty() && completed.finalAnswer.isBlank()) {
            status = BoardPlaybackStatus.ERROR
        } else if (status == BoardPlaybackStatus.STREAMING) {
            status = BoardPlaybackStatus.PLAYING
        }
    }

    fun onStreamError() {
        isStreamOpen = false
        val hasContent = !solution?.steps.isNullOrEmpty()
        status = if (hasContent) BoardPlaybackStatus.PAUSED else BoardPlaybackStatus.ERROR
    }

    fun loadSavedSolution(
        saved: StudySolution,
        resumeStepIndex: Int,
        completedAlready: Boolean
    ) {
        pushUndoIfMeaningful()
        redoStack.clear()
        solution = saved
        isStreamOpen = false
        replaySingleStepOnly = false
        awaitingVoiceForStep = null
        stepDwellRemainingMs = 0L
        eraseProgress = 0f
        pendingAfterEraseAction = null

        stepProgress.clear()
        val total = saved.steps.size
        if (total == 0) {
            finalAnswerProgress = if (saved.finalAnswer.isNotBlank()) 1f else 0f
            activeStepIndex = 0
            focusedStepIndex = 0
            status = BoardPlaybackStatus.COMPLETED
            return
        }

        if (completedAlready) {
            repeat(total) { stepProgress += 1f }
            finalAnswerProgress = if (saved.finalAnswer.isNotBlank()) 1f else 0f
            activeStepIndex = total - 1
            focusedStepIndex = resumeStepIndex.coerceIn(0, total - 1)
            status = BoardPlaybackStatus.COMPLETED
        } else {
            val startIdx = resumeStepIndex.coerceIn(0, total - 1)
            for (i in 0 until total) {
                stepProgress += if (i < startIdx) 1f else 0f
            }
            finalAnswerProgress = 0f
            activeStepIndex = startIdx
            focusedStepIndex = startIdx
            status = BoardPlaybackStatus.PLAYING
        }
    }

    fun attachDoubt(doubt: StudyDoubtEntry) {
        val cur = solution ?: return
        solution = cur.copy(
            doubts = cur.doubts + doubt,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun toggleBookmark(stepIndex: Int): StudySolution? {
        val cur = solution ?: return null
        if (stepIndex !in cur.steps.indices) return cur
        val updated = cur.steps.mapIndexed { idx, step ->
            if (idx == stepIndex) step.copy(isBookmarked = !step.isBookmarked) else step
        }
        val nextSol = cur.copy(steps = updated, updatedAt = System.currentTimeMillis())
        solution = nextSol
        return nextSol
    }

    fun pause() {
        awaitingVoiceForStep = null
        stepDwellRemainingMs = 0L
        if (status == BoardPlaybackStatus.PLAYING || status == BoardPlaybackStatus.STREAMING) {
            status = BoardPlaybackStatus.PAUSED
        }
    }

    fun resume() {
        val sol = solution ?: return
        when (status) {
            BoardPlaybackStatus.PAUSED -> {
                replaySingleStepOnly = false
                status = if (isStreamOpen) BoardPlaybackStatus.STREAMING else BoardPlaybackStatus.PLAYING
            }
            BoardPlaybackStatus.COMPLETED -> {
                if (sol.steps.isNotEmpty()) replayAll()
            }
            BoardPlaybackStatus.IDLE, BoardPlaybackStatus.ERROR -> {
                if (sol.steps.isNotEmpty()) replayAll()
            }
            else -> Unit
        }
    }

    fun previousStep(): Int {
        val sol = solution ?: return 0
        if (sol.steps.isEmpty()) return 0
        pushUndoSnapshot()
        awaitingVoiceForStep = null
        stepDwellRemainingMs = 0L
        replaySingleStepOnly = false

        val target = (activeStepIndex - 1).coerceAtLeast(0)
        for (i in sol.steps.indices) {
            stepProgress[i] = if (i <= target) 1f else 0f
        }
        finalAnswerProgress = 0f
        activeStepIndex = target
        focusedStepIndex = target
        status = BoardPlaybackStatus.PAUSED
        return target
    }

    fun nextStep(): Int {
        val sol = solution ?: return 0
        if (sol.steps.isEmpty()) return 0
        pushUndoSnapshot()
        awaitingVoiceForStep = null
        stepDwellRemainingMs = 0L
        replaySingleStepOnly = false

        val curProg = stepProgress.getOrElse(activeStepIndex) { 0f }
        if (curProg < 0.99f) {
            stepProgress[activeStepIndex] = 1f
        } else if (activeStepIndex + 1 < sol.steps.size) {
            activeStepIndex++
            stepProgress[activeStepIndex] = 1f
        } else if (sol.finalAnswer.isNotBlank()) {
            finalAnswerProgress = 1f
            status = if (isStreamOpen) BoardPlaybackStatus.STREAMING else BoardPlaybackStatus.COMPLETED
        }
        focusedStepIndex = activeStepIndex
        if (activeStepIndex == sol.steps.lastIndex &&
            stepProgress[activeStepIndex] >= 1f &&
            !isStreamOpen &&
            (sol.finalAnswer.isBlank() || finalAnswerProgress >= 1f)
        ) {
            status = BoardPlaybackStatus.COMPLETED
        }
        return activeStepIndex
    }

    fun replayCurrentStep() {
        val sol = solution ?: return
        if (sol.steps.isEmpty()) return
        pushUndoSnapshot()
        awaitingVoiceForStep = null
        stepDwellRemainingMs = 0L

        val target = focusedStepIndex.coerceIn(0, sol.steps.lastIndex)
        activeStepIndex = target
        for (i in sol.steps.indices) {
            stepProgress[i] = if (i < target) 1f else 0f
        }
        finalAnswerProgress = 0f
        replaySingleStepOnly = true
        status = if (isStreamOpen) BoardPlaybackStatus.STREAMING else BoardPlaybackStatus.PLAYING
    }

    fun replayAll() {
        val sol = solution ?: return
        if (sol.steps.isEmpty()) return
        pushUndoSnapshot()
        awaitingVoiceForStep = null
        stepDwellRemainingMs = 0L
        for (i in sol.steps.indices) stepProgress[i] = 0f
        finalAnswerProgress = 0f
        activeStepIndex = 0
        focusedStepIndex = 0
        replaySingleStepOnly = false
        status = if (isStreamOpen) BoardPlaybackStatus.STREAMING else BoardPlaybackStatus.PLAYING
    }

    fun clearBoardAnimated(afterErase: (() -> Unit)? = null) {
        if (solution == null && status == BoardPlaybackStatus.IDLE) {
            afterErase?.invoke()
            return
        }
        pushUndoSnapshot()
        awaitingVoiceForStep = null
        stepDwellRemainingMs = 0L
        eraseProgress = 0f
        pendingAfterEraseAction = afterErase
        status = BoardPlaybackStatus.ERASING
    }

    fun undo(): Boolean {
        val snap = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(captureSnapshot())
        restoreSnapshot(snap)
        return true
    }

    fun redo(): Boolean {
        val snap = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(captureSnapshot())
        restoreSnapshot(snap)
        return true
    }

    fun notifyVoiceFinishedForStep(stepIndex: Int) {
        if (awaitingVoiceForStep == stepIndex) {
            awaitingVoiceForStep = null
            voiceWaitElapsedMs = 0L
        }
    }

    /**
     * Advances animation by [deltaMs] (typically ~16ms at 60 FPS).
     */
    fun tick(deltaMs: Long): FrameOutcome {
        val dt = deltaMs.coerceIn(1L, 120L)

        if (status == BoardPlaybackStatus.ERASING) {
            eraseProgress = (eraseProgress + dt / 360f).coerceAtMost(1f)
            if (eraseProgress >= 1f) {
                solution = null
                stepProgress.clear()
                finalAnswerProgress = 0f
                activeStepIndex = 0
                focusedStepIndex = 0
                isStreamOpen = false
                eraseProgress = 0f
                status = BoardPlaybackStatus.IDLE
                val cb = pendingAfterEraseAction
                pendingAfterEraseAction = null
                cb?.invoke()
                return FrameOutcome(changed = true, clearedAfterErase = true)
            }
            return FrameOutcome(changed = true)
        }

        if (status != BoardPlaybackStatus.PLAYING && status != BoardPlaybackStatus.STREAMING) {
            return FrameOutcome(changed = false)
        }

        val sol = solution ?: return FrameOutcome(changed = false)
        val steps = sol.steps
        if (steps.isEmpty()) return FrameOutcome(changed = false)

        if (activeStepIndex !in steps.indices) {
            activeStepIndex = activeStepIndex.coerceIn(0, steps.lastIndex)
        }

        val waitStep = awaitingVoiceForStep
        if (waitStep != null) {
            voiceWaitElapsedMs += dt
            // Safety fallback: never wedge forever if external TTS completion drops.
            if (voiceWaitElapsedMs < 14_000L) {
                return FrameOutcome(changed = false)
            }
            awaitingVoiceForStep = null
            voiceWaitElapsedMs = 0L
        }

        if (stepDwellRemainingMs > 0L) {
            stepDwellRemainingMs = (stepDwellRemainingMs - (dt * speedMultiplier).toLong()).coerceAtLeast(0L)
            if (stepDwellRemainingMs > 0L) return FrameOutcome(changed = false)
            return advanceToNextStepOrFinish(sol)
        }

        val curIdx = activeStepIndex
        val step = steps[curIdx]
        val prevProg = stepProgress.getOrElse(curIdx) { 0f }
        var entered: StudyStep? = null
        if (prevProg == 0f) {
            entered = step
            focusedStepIndex = curIdx
        }

        if (prevProg < 1f) {
            val durationMs = computeStepDurationMs(step)
            val deltaProg = (dt.toFloat() * speedMultiplier) / durationMs.toFloat()
            val nextProg = (prevProg + deltaProg).coerceAtMost(1f)
            stepProgress[curIdx] = nextProg
            if (nextProg >= 1f) {
                if (voiceEnabled && step.narrationText().isNotBlank()) {
                    awaitingVoiceForStep = curIdx
                    voiceWaitElapsedMs = 0L
                } else {
                    stepDwellRemainingMs = 260L
                }
                return FrameOutcome(
                    changed = true,
                    stepEntered = entered,
                    stepVisualCompleted = step
                )
            }
            return FrameOutcome(changed = true, stepEntered = entered)
        }

        // Current step already at 1f and neither waiting voice nor dwelling: advance!
        return advanceToNextStepOrFinish(sol)
    }

    private fun advanceToNextStepOrFinish(sol: StudySolution): FrameOutcome {
        if (replaySingleStepOnly) {
            replaySingleStepOnly = false
            // If all steps are already revealed after single-step replay, mark completed cleanly.
            val allDone = sol.steps.indices.all { stepProgress.getOrElse(it) { 0f } >= 1f }
            if (allDone && !isStreamOpen) {
                finalAnswerProgress = if (sol.finalAnswer.isNotBlank()) 1f else 0f
                status = BoardPlaybackStatus.COMPLETED
                return FrameOutcome(changed = true, completedSolution = true)
            }
            status = BoardPlaybackStatus.PAUSED
            return FrameOutcome(changed = true)
        }

        if (activeStepIndex + 1 < sol.steps.size) {
            activeStepIndex++
            focusedStepIndex = activeStepIndex
            return FrameOutcome(changed = true)
        }

        if (!isStreamOpen) {
            if (sol.finalAnswer.isNotBlank() && finalAnswerProgress < 1f) {
                finalAnswerProgress = (finalAnswerProgress + 0.08f * speedMultiplier).coerceAtMost(1f)
                if (finalAnswerProgress < 1f) {
                    return FrameOutcome(changed = true)
                }
            }
            status = BoardPlaybackStatus.COMPLETED
            return FrameOutcome(changed = true, completedSolution = true)
        }

        // Still waiting for Step N+1 from active stream! Stay cleanly in STREAMING.
        return FrameOutcome(changed = false)
    }

    private fun computeStepDurationMs(step: StudyStep): Long {
        val textWeight = step.boardText.length +
            step.mathExpressions.sumOf { it.length } +
            step.codeBlock.length / 2
        val diagramWeight = if (step.diagram != null) 750L else 0L
        return min(3800L, max(780L, 580L + textWeight * 16L + diagramWeight))
    }

    private fun pushUndoIfMeaningful() {
        if (solution != null) pushUndoSnapshot()
    }

    private fun pushUndoSnapshot() {
        if (undoStack.size >= 20) undoStack.removeFirst()
        undoStack.addLast(captureSnapshot())
    }

    private fun captureSnapshot(): Snapshot = Snapshot(
        solution = solution,
        stepProgress = stepProgress.toList(),
        finalAnswerProgress = finalAnswerProgress,
        activeStepIndex = activeStepIndex,
        focusedStepIndex = focusedStepIndex,
        status = status,
        isStreamOpen = isStreamOpen
    )

    private fun restoreSnapshot(snap: Snapshot) {
        solution = snap.solution
        stepProgress.clear()
        stepProgress.addAll(snap.stepProgress)
        finalAnswerProgress = snap.finalAnswerProgress
        activeStepIndex = snap.activeStepIndex
        focusedStepIndex = snap.focusedStepIndex
        status = snap.status
        isStreamOpen = snap.isStreamOpen
        awaitingVoiceForStep = null
        stepDwellRemainingMs = 0L
        eraseProgress = 0f
        pendingAfterEraseAction = null
    }
}
