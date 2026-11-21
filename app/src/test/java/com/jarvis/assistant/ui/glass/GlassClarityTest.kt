package com.jarvis.assistant.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassClarityTest {

    @Test fun the_default_setting_leaves_the_glass_exactly_as_tuned() {
        val p = GlassClarity.forLevel(GlassClarity.DEFAULT_CLARITY)
        assertEquals(1f, p.veilScale, 1e-6f)
        assertEquals(1f, p.blurScale, 1e-6f)
        assertEquals(1f, p.refractScale, 1e-6f)
        assertEquals(1f, p.rimScale, 1e-6f)
        assertEquals(0f, p.opacity, 1e-6f)
    }

    @Test fun the_opaque_end_is_solid_and_diffuse() {
        val p = GlassClarity.forLevel(0f)
        assertTrue("solid surface", p.opacity > 0.85f)
        assertTrue("heavily frosted", p.veilScale > 2f)
        assertTrue("strong diffusion", p.blurScale >= 2f)
        assertTrue("a thick slab bends little", p.refractScale < 0.5f)
    }

    @Test fun the_clear_end_is_transparent_with_crisp_refraction() {
        val p = GlassClarity.forLevel(1f)
        assertEquals(0f, p.opacity, 1e-6f)
        assertTrue(p.veilScale < 0.5f)
        assertTrue(p.blurScale < 1f)
        assertTrue(p.refractScale > 1f)
    }

    @Test fun moving_toward_clear_never_makes_the_glass_more_frosted() {
        var prev = GlassClarity.forLevel(0f)
        var level = 0.05f
        while (level <= 1.0001f) {
            val p = GlassClarity.forLevel(level)
            assertTrue("veil at $level", p.veilScale <= prev.veilScale + 1e-6f)
            assertTrue("blur at $level", p.blurScale <= prev.blurScale + 1e-6f)
            assertTrue("opacity at $level", p.opacity <= prev.opacity + 1e-6f)
            assertTrue("refraction at $level", p.refractScale >= prev.refractScale - 1e-6f)
            prev = p
            level += 0.05f
        }
    }

    @Test fun out_of_range_levels_are_clamped() {
        assertEquals(GlassClarity.forLevel(0f).opacity, GlassClarity.forLevel(-3f).opacity, 1e-6f)
        assertEquals(GlassClarity.forLevel(1f).veilScale, GlassClarity.forLevel(9f).veilScale, 1e-6f)
    }
}
