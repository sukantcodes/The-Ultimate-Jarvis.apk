package com.jarvis.assistant.ui.glass

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy

/**
 * A small picture of the screen that was showing just before a Settings screen opened. In "See-through" mode the Settings
 * backdrop draws it, and every glass pane zooms and refracts it with the lens shader. The chain follows the navigation:
 * the hub sees the Home screen, and each category screen sees the hub (which in turn shows Home through its own glass).
 *
 * Taken with PixelCopy (it sees WebGL and glass exactly as they are on screen) at half size, so it costs about 2.5 MB and
 * a few milliseconds.
 */
object ScreenSnapshot {

    @Volatile var bitmap: Bitmap? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Runnable>()

    fun addListener(l: Runnable) { listeners.add(l) }
    fun removeListener(l: Runnable) { listeners.remove(l) }

    /** True when the chosen scene needs the picture, so other scenes never pay for a capture. */
    fun wanted(): Boolean = SettingsScene.effective() == SettingsScene.SEE_THROUGH && !GlassMode.plain()

    /**
     * Captures the activity's window, leaving out the bottom [cropBottomPx] (the dock). [done] always runs on the main
     * thread, whether or not the capture worked, so callers can just continue with their navigation.
     */
    fun capture(activity: Activity, cropBottomPx: Int = 0, done: (() -> Unit)? = null) {
        if (!wanted()) { done?.invoke(); return }
        val window = activity.window
        val width = window.decorView.width
        val height = window.decorView.height - cropBottomPx
        if (width <= 0 || height <= 0) { done?.invoke(); return }
        // Navigation never waits more than a moment for the picture: whichever comes first runs [done], once.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) done?.invoke() }
        main.postDelayed({ finish() }, 140L)
        try {
            val bmp = Bitmap.createBitmap((width / 2).coerceAtLeast(1), (height / 2).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, Rect(0, 0, width, height), bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    bitmap = bmp
                    listeners.toList().forEach { it.run() }
                }
                finish()
            }, main)
        } catch (_: Throwable) {
            finish()
        }
    }
}
