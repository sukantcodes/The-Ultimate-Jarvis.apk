package com.jarvis.assistant.util

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Applies a window-aware content width to every regular JARVIS Activity.
 *
 * Compact windows remain edge-to-edge. Medium and expanded windows use a centered
 * readable canvas instead of stretching phone-oriented controls across a tablet.
 * The calculation uses the current app window width, so split-screen and foldable
 * windows are handled using their actual available width.
 */
object ResponsiveLayoutController {

    private const val COMPACT_BREAKPOINT_DP = 600
    private const val MEDIUM_BREAKPOINT_DP = 840
    private const val EXPANDED_BREAKPOINT_DP = 1200

    /**
     * Returns the maximum content width for a window, or null when it should use
     * the full available width.
     */
    fun maxContentWidthDp(windowWidthDp: Int): Int? = when {
        windowWidthDp < COMPACT_BREAKPOINT_DP -> null
        windowWidthDp < MEDIUM_BREAKPOINT_DP -> 760
        windowWidthDp < EXPANDED_BREAKPOINT_DP -> 900
        else -> 1040
    }

    fun apply(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        // Camera previews, floating overlays and alarm surfaces are intentionally full-bleed.
        val name = activity.javaClass.simpleName
        if (name in FULL_BLEED_ACTIVITIES) return

        val widthDp = activity.resources.configuration.screenWidthDp
        val maxWidthDp = maxContentWidthDp(widthDp) ?: return
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        if (content.childCount == 0) return

        val child = content.getChildAt(0)
        val density = activity.resources.displayMetrics.density
        val availableWidthPx = activity.resources.displayMetrics.widthPixels
        val targetWidthPx = minOf(availableWidthPx, (maxWidthDp * density).toInt())
        if (targetWidthPx <= 0) return

        val params = (child.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        params.width = targetWidthPx
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        child.layoutParams = params
        child.requestLayout()
        content.requestLayout()
    }

    private val FULL_BLEED_ACTIVITIES = setOf(
        "CameraScanActivity",
        "OverlayAvatarActivity",
        "AlarmRingActivity"
    )
}
