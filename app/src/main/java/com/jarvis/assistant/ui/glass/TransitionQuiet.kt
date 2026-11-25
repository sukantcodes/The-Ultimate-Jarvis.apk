package com.jarvis.assistant.ui.glass

import android.os.Handler
import android.os.Looper

/**
 * Screens and tabs slide for about half a second. While they do, the live glass would keep re-capturing the screen on the
 * CPU and steal frames from the slide, so for that moment captures and animated scenes hold still (the last picture stays
 * visible and moves with the screen) and resume the instant the motion ends.
 */
object TransitionQuiet {
    private val main = Handler(Looper.getMainLooper())
    private val release = Runnable { AuroraClock.quiet = false }

    fun begin(durationMs: Long = 560L) {
        AuroraClock.quiet = true
        main.removeCallbacks(release)
        main.postDelayed(release, durationMs)
    }
}
