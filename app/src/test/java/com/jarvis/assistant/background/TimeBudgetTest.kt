package com.jarvis.assistant.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeBudgetTest {
    private fun p(s: String, requireUnit: Boolean = false) = TimeBudget.parse(s, requireUnit)

    @Test fun english_durations() {
        assertEquals(600, p("10 minutes"))
        assertEquals(600, p("ten minutes please"))
        assertEquals(3600, p("one hour"))
        assertEquals(3600, p("an hour"))
        assertEquals(1800, p("half an hour"))
        assertEquals(5400, p("1 hour 30 minutes"))
        assertEquals(5400, p("one and a half hours"))
        assertEquals(1500, p("twenty five minutes"))
        assertEquals(90, p("90 seconds"))
        assertEquals(5400, p("1.5 hours"))
    }

    @Test fun hindi_and_hinglish_durations() {
        assertEquals(600, p("das minute"))
        assertEquals(3600, p("ek ghanta"))
        assertEquals(1800, p("aadha ghanta"))
        assertEquals(5400, p("dedh ghante"))
        assertEquals(12600, p("sadhe teen ghante"))
        assertEquals(1800, p("30 मिनट"))
        assertEquals(1800, p("आधा घंटा"))
        assertEquals(7200, p("दो घंटे"))
        assertEquals(600, p("१० मिनट"))
    }

    @Test fun bengali_durations() {
        assertEquals(600, p("দশ মিনিট"))
        assertEquals(3600, p("এক ঘণ্টা"))
        assertEquals(1200, p("২০ মিনিট"))
        assertEquals(5400, p("দেড় ঘণ্টা"))
    }

    @Test fun a_bare_number_means_minutes_only_when_a_unit_is_not_required() {
        assertEquals(900, p("15"))
        assertNull(p("make a 3 level game", requireUnit = true))
        assertEquals(600, p("build a snake game in 10 minutes", requireUnit = true))
    }

    @Test fun no_limit_and_nonsense() {
        assertEquals(0, p("no limit"))
        assertEquals(0, p("jitna time lage"))
        assertEquals(0, p("कोई लिमिट नहीं"))
        assertNull(p("banana"))
    }

    @Test fun the_budget_is_kept_within_sane_bounds() {
        assertEquals(TimeBudget.MIN_SEC, p("5 seconds"))
        assertEquals(TimeBudget.MAX_SEC, p("40 hours"))
    }
}
