package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponsiveLayoutControllerTest {

    @Test
    fun compactWindowsKeepFullWidth() {
        assertNull(ResponsiveLayoutController.maxContentWidthDp(320))
        assertNull(ResponsiveLayoutController.maxContentWidthDp(599))
    }

    @Test
    fun mediumWindowsUseReadableCanvas() {
        assertEquals(760, ResponsiveLayoutController.maxContentWidthDp(600))
        assertEquals(760, ResponsiveLayoutController.maxContentWidthDp(839))
    }

    @Test
    fun expandedWindowsUseLargerCanvas() {
        assertEquals(900, ResponsiveLayoutController.maxContentWidthDp(840))
        assertEquals(900, ResponsiveLayoutController.maxContentWidthDp(1199))
        assertEquals(1040, ResponsiveLayoutController.maxContentWidthDp(1200))
    }
}
