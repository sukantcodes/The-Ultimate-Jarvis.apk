package com.jarvis.assistant.util

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.abs

/**
 * Single owner of the window's refresh-rate request.
 *
 * Many OEM display policies (ColorOS included) honour `preferredDisplayModeId` far more reliably than the
 * legacy `preferredRefreshRate` float, so both are set, using a concrete mode of the active display:
 *  - a specific rate chosen in Settings -> that mode, otherwise
 *  - the highest refresh rate the panel offers *at its current resolution* (never a resolution switch).
 *
 * Android and the manufacturer keep final control (battery saver, thermal limits); this is a request.
 * Every caller (activity lifecycle, theme application, the Settings dialog) goes through [apply], so
 * nothing can silently clear the request again.
 */
object DisplayRefreshRateController {

    private const val TAG = "JarvisRefreshRate"

    /** Requests the highest supported rate (the app default). */
    fun applyMaximumRefreshRate(activity: Activity) = apply(activity, null)

    /** @param requestedHz a specific rate, or null for the highest available. */
    fun apply(activity: Activity, requestedHz: Int?) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val display = resolveDisplay(activity) ?: return
            val current = display.mode
            // Same physical resolution only: a refresh-rate request must never change the resolution.
            val candidates = display.supportedModes.filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight &&
                    it.refreshRate.isFinite() && it.refreshRate > 0f
            }
            if (candidates.isEmpty()) return

            val target = if (requestedHz != null) {
                candidates.minByOrNull { abs(it.refreshRate - requestedHz) }
                    ?.takeIf { abs(it.refreshRate - requestedHz) < 1.5f }
            } else null
            val mode = target ?: candidates.maxByOrNull { it.refreshRate } ?: return

            val attributes = activity.window.attributes
            if (attributes.preferredDisplayModeId != mode.modeId ||
                attributes.preferredRefreshRate != mode.refreshRate
            ) {
                attributes.preferredDisplayModeId = mode.modeId
                attributes.preferredRefreshRate = mode.refreshRate
                activity.window.attributes = attributes
            }
            Log.d(
                TAG,
                "Requested " + mode.refreshRate + "Hz (mode " + mode.modeId + ") for " +
                    activity.javaClass.simpleName + "; active=" + current.refreshRate + "Hz"
            )
        } catch (securityException: SecurityException) {
            Log.w(TAG, "Display refresh-rate request was rejected by the system", securityException)
        } catch (runtimeException: RuntimeException) {
            // A display can be reconfigured while an Activity is being resumed.
            // Never let an optional display hint prevent the screen from opening.
            Log.w(TAG, "Unable to apply the display refresh-rate preference", runtimeException)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveDisplay(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display ?: activity.windowManager.defaultDisplay
        } else {
            activity.windowManager.defaultDisplay
        }
}
