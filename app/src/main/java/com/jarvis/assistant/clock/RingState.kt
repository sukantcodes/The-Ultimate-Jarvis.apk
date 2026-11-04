package com.jarvis.assistant.clock

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which alarm is ringing right now, shared between the ringing service (which owns the sound) and the ring screen and
 * the AI (which only read it). The service is the single owner, so no Activity has to stay open for an alarm to ring.
 */
object RingState {
    @Volatile var ringingId: Long = -1L
        private set

    /** A short note for the ring screen (for example "snooze limit reached"). Cleared when ringing ends. */
    @Volatile var notice: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    val isRinging: Boolean get() = ringingId >= 0L

    fun set(id: Long, notice: String? = null) {
        ringingId = id
        this.notice = notice
        fire()
    }

    fun clear() {
        ringingId = -1L
        notice = null
        fire()
    }

    fun addListener(listener: () -> Unit) { listeners.addIfAbsent(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    private fun fire() {
        main.post { listeners.forEach { runCatching { it() } } }
    }
}
