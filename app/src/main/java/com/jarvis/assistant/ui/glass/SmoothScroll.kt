package com.jarvis.assistant.ui.glass

import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

/**
 * Buttery scrolling on glass screens. While a list or scroll view is moving, the shared glass heartbeat (aurora drift, sheens)
 * is paused so every frame goes to scrolling; it resumes a moment after the motion stops.
 */
internal object SmoothScroll {

    private const val TAG = 0x4A535343 // "JSSC"
    private const val RESUME_MS = 160L

    private val resume = Runnable { AuroraClock.paused = false }

    fun install(view: View) {
        if (view.getTag(TAG) != null) return
        view.setTag(TAG, true)
        when (view) {
            is NestedScrollView -> view.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ -> bump(view) })
            is android.widget.ScrollView -> view.viewTreeObserver.addOnScrollChangedListener { bump(view) }
            is RecyclerView -> {
                view.setHasFixedSize(view.hasFixedSize())
                view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { if (dx != 0 || dy != 0) bump(rv) }
                })
            }
        }
        view.overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun bump(v: View) {
        AuroraClock.paused = true
        v.removeCallbacks(resume)
        v.postDelayed(resume, RESUME_MS)
    }
}
