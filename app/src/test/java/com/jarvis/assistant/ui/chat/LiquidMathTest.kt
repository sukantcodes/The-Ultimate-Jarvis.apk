package com.jarvis.assistant.ui.chat

import com.jarvis.assistant.ui.chat.liquid.LiquidMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidMathTest {

    @Test
    fun spring_starts_at_zero_and_settles_exactly_on_one() {
        assertEquals(0f, LiquidMath.spring(0f), 0.0001f)
        assertEquals(1f, LiquidMath.spring(1f), 0f)
        assertEquals(1f, LiquidMath.spring(3f), 0f)
        assertEquals(0f, LiquidMath.spring(-2f), 0.0001f)
    }

    @Test
    fun spring_overshoots_slightly_but_never_runs_away() {
        var peak = 0f
        var t = 0f
        while (t <= 1f) {
            val v = LiquidMath.spring(t)
            if (v > peak) peak = v
            assertTrue("spring($t) = $v is out of range", v > -0.2f && v < 1.3f)
            t += 0.01f
        }
        assertTrue("a liquid settle should overshoot a little", peak > 1f)
    }

    @Test
    fun ease_out_cubic_is_monotonic_and_bounded() {
        var last = -1f
        var t = 0f
        while (t <= 1f) {
            val v = LiquidMath.easeOutCubic(t)
            assertTrue(v >= last)
            assertTrue(v in 0f..1f)
            last = v
            t += 0.05f
        }
        assertEquals(1f, LiquidMath.easeOutCubic(1f), 0.0001f)
    }

    @Test
    fun bead_wave_stays_in_range_and_each_bead_trails_the_previous() {
        for (ms in 0..3000 step 37) {
            for (i in 0..2) assertTrue(LiquidMath.beadWave(ms.toFloat(), i) in -1f..1f)
        }
        // A fixed instant: the beads are at different heights, so the row ripples instead of moving as one block.
        val a = LiquidMath.beadWave(300f, 0)
        val b = LiquidMath.beadWave(300f, 1)
        val c = LiquidMath.beadWave(300f, 2)
        assertTrue(a != b && b != c)
    }

    @Test
    fun bead_scale_is_fullest_at_the_top_of_the_wave() {
        assertTrue(LiquidMath.beadScale(1f) > LiquidMath.beadScale(0f))
        assertTrue(LiquidMath.beadScale(0f) > LiquidMath.beadScale(-1f))
        assertTrue(LiquidMath.beadScale(-1f) > 0.7f)
    }

    @Test
    fun glint_is_invisible_at_both_ends_and_strongest_in_the_middle() {
        assertEquals(0f, LiquidMath.glintAlpha(0f), 0.0001f)
        assertEquals(0f, LiquidMath.glintAlpha(1f), 0.0001f)
        assertEquals(1f, LiquidMath.glintAlpha(0.5f), 0.0001f)
        assertEquals(0f, LiquidMath.glintPosition(0f), 0f)
        assertEquals(1f, LiquidMath.glintPosition(1f), 0f)
        assertEquals(0.5f, LiquidMath.glintPosition(0.5f), 0.0001f)
    }

    @Test
    fun focus_blur_sharpens_from_max_to_zero() {
        assertEquals(10f, LiquidMath.focusBlur(0f, 10f), 0.0001f)
        assertEquals(0f, LiquidMath.focusBlur(1f, 10f), 0.0001f)
        assertTrue(LiquidMath.focusBlur(0.3f, 10f) > LiquidMath.focusBlur(0.7f, 10f))
    }
}
