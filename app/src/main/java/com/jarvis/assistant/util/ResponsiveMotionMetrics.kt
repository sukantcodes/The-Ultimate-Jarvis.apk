package com.jarvis.assistant.util

import android.content.Context
import kotlin.math.sqrt

/**
 * Window-aware motion sizing shared by app-opening and home-entry animations.
 *
 * It scales the physical travel distance and starting transform with the available
 * app window, rather than using a fixed phone-sized animation on every device.
 * The geometric mean balances width and height, so landscape phones do not get
 * incorrectly treated as tiny merely because their height is short.
 */
object ResponsiveMotionMetrics {

    private const val REFERENCE_WIDTH_DP = 393f
    private const val REFERENCE_HEIGHT_DP = 852f
    private const val MIN_SCALE = 0.78f
    private const val MAX_SCALE = 1.50f

    fun scaleFactor(context: Context): Float {
        val configuration = context.resources.configuration
        return scaleFactor(
            configuration.screenWidthDp.coerceAtLeast(1),
            configuration.screenHeightDp.coerceAtLeast(1)
        )
    }

    internal fun scaleFactor(windowWidthDp: Int, windowHeightDp: Int): Float {
        val widthRatio = windowWidthDp / REFERENCE_WIDTH_DP
        val heightRatio = windowHeightDp / REFERENCE_HEIGHT_DP
        val balancedScale = sqrt(widthRatio * heightRatio)
        return balancedScale.coerceIn(MIN_SCALE, MAX_SCALE)
    }
}
