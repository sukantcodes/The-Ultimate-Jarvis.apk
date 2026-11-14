package com.jarvis.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatElapsedTest {
    @Test fun seconds_only_under_a_minute() {
        assertEquals("0s", formatElapsed(0))
        assertEquals("42s", formatElapsed(42_900))
    }

    @Test fun minutes_and_padded_seconds() {
        assertEquals("1m 00s", formatElapsed(60_000))
        assertEquals("5m 11s", formatElapsed(311_000))
    }

    @Test fun hours_and_padded_minutes() {
        assertEquals("1h 02m", formatElapsed(3_720_000))
    }

    @Test fun negative_time_is_shown_as_zero() {
        assertEquals("0s", formatElapsed(-5_000))
    }
}
