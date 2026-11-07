package com.jarvis.assistant.util

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display

/**
 * Requests the highest refresh rate exposed by the display currently hosting
 * an Activity. This is a window preference, not a display-mode or resolution
 * switch: Android and the device manufacturer retain final control.
 *
 * Applying this at the Application activity lifecycle keeps the policy
 * consistent across every JARVIS screen without requiring each Activity to
 * duplicate display-specific code.
 */
object DisplayRefreshRateController {

    private const val TAG = "JarvisRefreshRate"

    fun applyMaximumRefreshRate(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        try {
            val display = resolveDisplay(activity) ?: return
            val maximumSupportedRate = display.supportedModes
                .asSequence()
                .map { it.refreshRate }
                .filter { it.isFinite() && it > 0f }
                .maxOrNull()
                ?: return

            val attributes = activity.window.attributes
            if (attributes.preferredRefreshRate != maximumSupportedRate) {
                attributes.preferredRefreshRate = maximumSupportedRate
                activity.window.attributes = attributes
            }

            Log.d(
                TAG,
                "Requested max refresh rate " + maximumSupportedRate + "Hz for " +
                    activity.javaClass.simpleName + "; display=" + display.displayId
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
