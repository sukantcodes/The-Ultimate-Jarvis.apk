package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsiveMotionMetricsTest {

    @Test
    fun smallPhoneUsesReducedMotionDistance() {
        val scale = ResponsiveMotionMetrics.scaleFactor(320, 568)
        assertEquals(true, scale < 1f)
        assertEquals(0.78f, scale, 0.001f)
    }

    @Test
    fun referencePhoneUsesBaselineMotion() {
        assertEquals(1f, ResponsiveMotionMetrics.scaleFactor(393, 852), 0.001f)
    }

    @Test
    fun largeTabletUsesExpandedMotion() {
        assertEquals(1.50f, ResponsiveMotionMetrics.scaleFactor(1200, 1600), 0.001f)
    }

    @Test
    fun landscapeWindowBalancesBothDimensions() {
        val scale = ResponsiveMotionMetrics.scaleFactor(900, 500)
        assertEquals(true, scale in 0.9f..1.3f)
    }
}
