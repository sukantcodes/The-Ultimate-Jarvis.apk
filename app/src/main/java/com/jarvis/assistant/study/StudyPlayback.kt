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

    val blockCount: Int get() = durations.size

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
        current = durations.size - 1; progress = 1f; writingDone = true; highWater = current; othersStayVisible = false; narrationBusy = false
        if (generationOpen) setState(State.WAITING_FOR_CONTENT) else finish()
    }

    fun durationOf(i: Int): Long = durations.getOrElse(i) { 0L }

    fun clear() {
        durations.clear()
        current = -1; progress = 0f; writingDone = false; stopAfterCurrent = false
        highWater = -1; othersStayVisible = false
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
        stopAfterCurrent = true
        startBlock(current.coerceAtLeast(0), replay = true, single = true)
    }

    fun replayAll() {
        if (durations.isEmpty()) return
        stopAfterCurrent = false
        startBlock(0, replay = true)
    }

    /** Jumps so that blocks before [index] count as written and [index] is the one being written. */
    fun seek(index: Int) {
        if (index !in durations.indices) return
        stopAfterCurrent = true
        startBlock(index, replay = true, single = true)
    }

    /** Advances the pen by [dtMs] of wall-clock time. Returns true when something changed that needs a redraw. */
    fun advance(dtMs: Long): Boolean {
        if (state != State.PLAYING || current !in durations.indices) return false
        if (!writingDone) {
            if (startGate) return false
            val total = durations[current].toFloat() / speed
            progress = (progress + dtMs / total).coerceAtMost(1f)
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
        const val MIN_BLOCK_MS = 450L
        const val MAX_BLOCK_MS = 9_000L

        /** How long a block takes to write at speed 1.0: pen travel plus a short thinking pause, within sane limits. */
        fun durationFor(block: BoardBlock): Long {
            var weight = 0f
            for (op in block.ops) weight += op.weight
            val ms = 350f + weight * 2.1f
            return ms.toLong().coerceIn(MIN_BLOCK_MS, MAX_BLOCK_MS)
        }
    }
}
