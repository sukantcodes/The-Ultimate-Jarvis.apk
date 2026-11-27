package com.jarvis.assistant.study

/**
 * The clock behind the board. It decides which block is being written and how far the pen has got, independent of any
 * screen, so pause, resume, previous, next and replay behave identically with and without voice and can be tested on a JVM.
 *
 * A block is finished only when both its writing and its narration are finished (when narration is on), which keeps the
 * spoken explanation and the writing of the matching step together.
 */
class StudyPlayback {

    enum class State { IDLE, PLAYING, PAUSED, WAITING_FOR_CONTENT, FINISHED }

    interface Listener {
        fun onBlockStarted(index: Int, replay: Boolean) {}
        fun onBlockFinished(index: Int) {}
        fun onAllFinished() {}
        fun onStateChanged(state: State) {}
    }

    /** Writing time for each block in ms at speed 1.0, set by the owner as blocks arrive. */
    private val durations = ArrayList<Long>()

    var listener: Listener? = null
    var state: State = State.IDLE
        private set
    var current: Int = -1
        private set
    /** 0..1 progress of the pen through the current block. */
    var progress: Float = 0f
        private set
    var speed: Float = 1f
        set(v) { field = v.coerceIn(0.4f, 3f) }

    /** True while narration for the current block is still being spoken; the next block waits for it. */
    var narrationBusy: Boolean = false

    /**
     * True while the voice for the block that just started is still being prepared. The pen waits (the clock does not run), so
     * the first word and the first stroke begin together instead of the voice arriving late.
     */
    var startGate: Boolean = false

    /** Furthest block that has been written so far. Blocks up to here count as already on the board. */
    var highWater: Int = -1
        private set

    /** True while a single step is being replayed: every other written block stays visible instead of being hidden. */
    var othersStayVisible: Boolean = false
        private set

    /** When true, playback stops after the current block instead of continuing (used by "replay this step"). */
    private var stopAfterCurrent = false
    private var writingDone = false
    private var generationOpen = true
    private var syncedMaxProgress: Float? = null
    private var syncedTargetProgress: Float? = null

    val blockCount: Int get() = durations.size

    fun setBlockDuration(index: Int, durationMs: Long) {
        if (index in durations.indices) {
            durations[index] = durationMs.coerceIn(MIN_BLOCK_MS, 24_000L)
        }
    }

    /**
     * Synchronizes block writing progress [0f..1f] smoothly with live spoken audio for [blockIndex],
     * matching the exact speaking speed without racing ahead or lagging behind.
     */
    fun syncContinuousProgress(blockIndex: Int, spokenFraction: Float) {
        if (state != State.PLAYING || current != blockIndex || blockIndex !in durations.indices) return
        val clamped = spokenFraction.coerceIn(0f, 1f)
        syncedTargetProgress = clamped
        syncedMaxProgress = if (clamped >= 0.98f) 1f else clamped.coerceAtMost(0.992f)
        if (!writingDone && clamped > progress + 0.10f) {
            progress = (clamped - 0.04f).coerceIn(0f, 1f)
            if (progress >= 1f) {
                writingDone = true
                listener?.onBlockFinished(current)
            }
        }
    }

    /** Clears voice lockstep cap cleanly when speech ends so any trailing strokes complete smoothly. */
    fun releaseNarrationSync() {
        syncedTargetProgress = null
        syncedMaxProgress = null
    }

    fun setGenerationOpen(open: Boolean) {
        generationOpen = open
        if (!open && state == State.WAITING_FOR_CONTENT && current + 1 >= durations.size) finish()
    }

    /** Adds a block that has just been laid out. If the pen was waiting for it, writing starts immediately. */
    fun addBlock(durationMs: Long) {
        durations.add(durationMs.coerceIn(MIN_BLOCK_MS, MAX_BLOCK_MS))
        if (state == State.WAITING_FOR_CONTENT) startBlock(current + 1, replay = false)
        else if (state == State.IDLE) { startBlock(0, replay = false) }
    }

    /** Drops every block from [count] on (used when a failed doubt is retried). Writing state is kept consistent. */
    fun truncateTo(count: Int) {
        if (count >= durations.size) return
        while (durations.size > count.coerceAtLeast(0)) durations.removeAt(durations.size - 1)
        if (durations.isEmpty()) { clear(); return }
        if (highWater >= durations.size) highWater = durations.size - 1
        if (current >= durations.size) {
            current = durations.size - 1; progress = 1f; writingDone = true; othersStayVisible = false
            if (state != State.PAUSED) setState(State.PAUSED)
        }
    }

    /** Shows everything as already written, without animating (opening a saved lesson). */
    fun jumpToEnd() {
        if (durations.isEmpty()) return
        stopAfterCurrent = false
        current = durations.size - 1; progress = 1f; writingDone = true; highWater = current; othersStayVisible = false; continuousKeepPrinted = false; narrationBusy = false; syncedMaxProgress = null; syncedTargetProgress = null
        if (generationOpen) setState(State.WAITING_FOR_CONTENT) else finish()
    }

    fun durationOf(i: Int): Long = durations.getOrElse(i) { 0L }

    /** True when re-explaining an already-printed lesson (keep existing strokes visible while highlighting live). */
    var continuousKeepPrinted = false
        private set

    fun clear() {
        durations.clear()
        current = -1; progress = 0f; writingDone = false; stopAfterCurrent = false
        highWater = -1; othersStayVisible = false; continuousKeepPrinted = false; syncedMaxProgress = null; syncedTargetProgress = null
        setState(State.IDLE)
    }

    fun pause() { if (state == State.PLAYING) setState(State.PAUSED) }

    fun resume() {
        when (state) {
            State.PAUSED -> setState(State.PLAYING)
            State.FINISHED -> if (durations.isNotEmpty()) replayAll()
            else -> Unit
        }
    }

    fun togglePause() { if (state == State.PLAYING) pause() else resume() }

    fun next() {
        if (durations.isEmpty()) return
        stopAfterCurrent = false
        if (current >= 0 && (state == State.PLAYING || state == State.PAUSED) && !writingDone) completeCurrentBlock(finishListener = true)
        val target = current + 1
        if (target < durations.size) startBlock(target, replay = true, paused = state == State.PAUSED)
        else if (generationOpen) setState(State.WAITING_FOR_CONTENT) else finish()
    }

    fun previous() {
        if (durations.isEmpty()) return
        stopAfterCurrent = false
        val target = if (progress > 0.15f || current <= 0) current.coerceAtLeast(0) else current - 1
        startBlock(target, replay = true, paused = state == State.PAUSED)
    }

    /** Writes the current block again from the start, then stops. */
    fun replayCurrent() {
        if (durations.isEmpty()) return
        continuousKeepPrinted = false
        stopAfterCurrent = true
        startBlock(current.coerceAtLeast(0), replay = true, single = true)
    }

    fun replayAll() {
        if (durations.isEmpty()) return
        continuousKeepPrinted = false
        stopAfterCurrent = false
        startBlock(0, replay = true)
    }

    /** Begins continuous full-lesson playback at [index], optionally keeping all prior printed strokes visible. */
    fun startContinuousFrom(index: Int, keepPrinted: Boolean = false) {
        if (index !in durations.indices) return
        continuousKeepPrinted = keepPrinted
        stopAfterCurrent = false
        startBlock(index, replay = false, single = keepPrinted)
        if (keepPrinted) {
            highWater = durations.lastIndex
        }
    }

    /** Jumps so that blocks before [index] count as written and [index] is the one being written. */
    fun seek(index: Int) {
        if (index !in durations.indices) return
        continuousKeepPrinted = false
        stopAfterCurrent = true
        startBlock(index, replay = true, single = true)
    }

    /**
     * Advances cleanly to [targetIndex] during continuous lesson playback without resetting the active narration session.
     */
    fun advanceContinuous(targetIndex: Int) {
        if (targetIndex !in durations.indices || targetIndex <= current) return
        stopAfterCurrent = false
        if (current >= 0 && !writingDone) completeCurrentBlock(finishListener = true)
        current = targetIndex
        othersStayVisible = continuousKeepPrinted
        syncedMaxProgress = null
        syncedTargetProgress = null
        highWater = if (continuousKeepPrinted) durations.lastIndex else maxOf(highWater, targetIndex)
        progress = 0f
        writingDone = false
        startGate = false
        listener?.onBlockStarted(targetIndex, false)
        if (state != State.PAUSED) setState(State.PLAYING)
    }

    /** Advances the pen by [dtMs] of wall-clock time. Returns true when something changed that needs a redraw. */
    fun advance(dtMs: Long): Boolean {
        if (state != State.PLAYING || current !in durations.indices) return false
        if (!writingDone) {
            if (startGate) return false
            val tgt = syncedTargetProgress
            if (tgt != null) {
                if (continuousKeepPrinted) {
                    if (progress == tgt && progress < 1f) return false
                    progress = tgt
                    if (progress >= 0.998f) {
                        progress = 1f
                        writingDone = true
                        listener?.onBlockFinished(current)
                    }
                    return true
                }
                val cap = syncedMaxProgress ?: tgt
                val nextProg = if (tgt >= 0.98f) {
                    (progress + dtMs / 110f).coerceAtMost(1f)
                } else {
                    val diff = tgt - progress
                    if (diff <= 0.0005f) {
                        progress
                    } else {
                        val gain = (dtMs / 85f).coerceIn(0.18f, 0.72f)
                        val step = (diff * gain + dtMs * 0.0002f).coerceAtMost(diff)
                        (progress + step).coerceAtMost(cap)
                    }
                }
                if (nextProg == progress && nextProg < 1f) return false
                progress = nextProg
                if (progress >= 1f) { writingDone = true; listener?.onBlockFinished(current) }
                return true
            }
            val total = durations[current].toFloat() / speed
            val nextProg = (progress + dtMs / total).coerceAtMost(1f)
            if (nextProg == progress && nextProg < 1f) return false
            progress = nextProg
            if (progress >= 1f) { writingDone = true; listener?.onBlockFinished(current) }
            return true
        }
        if (narrationBusy) return false
        completeBlockAndContinue()
        return true
    }

    private fun completeCurrentBlock(finishListener: Boolean) {
        progress = 1f
        if (!writingDone) { writingDone = true; if (finishListener) listener?.onBlockFinished(current) }
    }

    private fun completeBlockAndContinue() {
        if (stopAfterCurrent) { stopAfterCurrent = false; setState(State.PAUSED); return }
        val nextIndex = current + 1
        when {
            nextIndex < durations.size -> startBlock(nextIndex, replay = false)
            generationOpen -> setState(State.WAITING_FOR_CONTENT)
            else -> finish()
        }
    }

    private fun startBlock(index: Int, replay: Boolean, paused: Boolean = false, single: Boolean = false) {
        current = index
        othersStayVisible = single
        syncedMaxProgress = null
        syncedTargetProgress = null
        if (index > highWater) highWater = index
        progress = 0f
        writingDone = false
        narrationBusy = false
        startGate = false
        listener?.onBlockStarted(index, replay)
        setState(if (paused) State.PAUSED else State.PLAYING)
    }

    private fun finish() {
        setState(State.FINISHED)
        listener?.onAllFinished()
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        listener?.onStateChanged(s)
    }

    companion object {
        const val MIN_BLOCK_MS = 320L
        const val MAX_BLOCK_MS = 4_200L

        /** How long a block takes to write at speed 1.0: smooth calligraphic pen travel without dragging out lessons. */
        fun durationFor(block: BoardBlock): Long {
            var weight = 0f
            for (op in block.ops) weight += op.weight
            val ms = 240f + weight * 1.15f
            return ms.toLong().coerceIn(MIN_BLOCK_MS, MAX_BLOCK_MS)
        }
    }
}
