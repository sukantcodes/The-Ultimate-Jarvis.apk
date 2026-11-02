package com.jarvis.assistant.clock

import com.jarvis.assistant.clock.Kit.at
import com.jarvis.assistant.clock.Kit.millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

class AlarmTimeCalculatorTest {

    private fun alarm(h: Int, m: Int, days: Set<DayOfWeek> = emptySet(), date: LocalDate? = null, enabled: Boolean = true, id: Long = 1) =
        AlarmItem(id = id, hour = h, minute = m, repeatDays = days, oneTimeDate = date, enabled = enabled)

    // 2026-11-02 is a Monday.

    @Test fun oneTimeLaterToday() {
        val next = AlarmTimeCalculator.nextRegular(alarm(8, 0), at(2026, 11, 2, 7, 0))
        assertEquals(at(2026, 11, 2, 8, 0), next)
    }

    @Test fun oneTimeAlreadyPassedGoesToTomorrow() {
        val next = AlarmTimeCalculator.nextRegular(alarm(6, 0), at(2026, 11, 2, 7, 0))
        assertEquals(at(2026, 11, 3, 6, 0), next)
    }

    @Test fun oneTimeExactlyNowGoesToTomorrow() {
        val next = AlarmTimeCalculator.nextRegular(alarm(7, 0), at(2026, 11, 2, 7, 0))
        assertEquals(at(2026, 11, 3, 7, 0), next)
    }

    @Test fun oneTimeJustAfterNowStaysToday() {
        val now = at(2026, 11, 2, 6, 59)
        assertEquals(at(2026, 11, 2, 7, 0), AlarmTimeCalculator.nextRegular(alarm(7, 0), now))
    }

    @Test fun midnightAndLateNight() {
        assertEquals(at(2026, 11, 3, 0, 0), AlarmTimeCalculator.nextRegular(alarm(0, 0), at(2026, 11, 2, 23, 59)))
        assertEquals(at(2026, 11, 2, 23, 59), AlarmTimeCalculator.nextRegular(alarm(23, 59), at(2026, 11, 2, 0, 0)))
    }

    @Test fun datedAlarmInFutureAndPast() {
        val future = alarm(9, 30, date = LocalDate.of(2026, 11, 10))
        assertEquals(at(2026, 11, 10, 9, 30), AlarmTimeCalculator.nextRegular(future, at(2026, 11, 2, 7, 0)))
        val past = alarm(9, 30, date = LocalDate.of(2026, 11, 1))
        assertNull(AlarmTimeCalculator.nextRegular(past, at(2026, 11, 2, 7, 0)))
    }

    @Test fun disabledNeverRings() {
        assertNull(AlarmTimeCalculator.nextRegular(alarm(8, 0, enabled = false), at(2026, 11, 2, 7, 0)))
    }

    @Test fun repeatingTodayStillAhead() {
        val a = alarm(8, 0, setOf(MONDAY, WEDNESDAY))
        assertEquals(at(2026, 11, 2, 8, 0), AlarmTimeCalculator.nextRegular(a, at(2026, 11, 2, 7, 0)))
    }

    @Test fun repeatingTodayAlreadyPassedMovesToNextDay() {
        val a = alarm(6, 0, setOf(MONDAY, WEDNESDAY))
        assertEquals(at(2026, 11, 4, 6, 0), AlarmTimeCalculator.nextRegular(a, at(2026, 11, 2, 7, 0)))
    }

    @Test fun repeatingSingleDayTodayPassedWaitsAWeek() {
        val a = alarm(6, 0, setOf(MONDAY))
        assertEquals(at(2026, 11, 9, 6, 0), AlarmTimeCalculator.nextRegular(a, at(2026, 11, 2, 7, 0)))
    }

    @Test fun repeatingWeekendOnlyFromMidweek() {
        val a = alarm(9, 0, setOf(SATURDAY, SUNDAY))
        assertEquals(at(2026, 11, 7, 9, 0), AlarmTimeCalculator.nextRegular(a, at(2026, 11, 4, 12, 0)))
    }

    @Test fun repeatingEveryDayRollsOverMonthAndYear() {
        val a = alarm(5, 30, DayOfWeek.values().toSet())
        assertEquals(at(2027, 1, 1, 5, 30), AlarmTimeCalculator.nextRegular(a, at(2026, 12, 31, 6, 0)))
    }

    @Test fun leapDayDatedAlarm() {
        val a = alarm(6, 0, date = LocalDate.of(2028, 2, 29))
        assertEquals(at(2028, 2, 29, 6, 0), AlarmTimeCalculator.nextRegular(a, at(2028, 2, 28, 6, 1)))
    }

    @Test fun followsTheZoneOfNow() {
        // 06:00 means 06:00 wherever the phone is, so the instant changes with the zone.
        val a = alarm(6, 0)
        val inIst = AlarmTimeCalculator.nextRegular(a, at(2026, 11, 2, 7, 0, Kit.IST))!!
        val inNy = AlarmTimeCalculator.nextRegular(a, at(2026, 11, 2, 7, 0, Kit.NY))!!
        assertEquals("Asia/Kolkata", inIst.zone.id)
        assertEquals("America/New_York", inNy.zone.id)
        assertEquals(6, inNy.hour)
        assertTrue(inIst.toInstant() != inNy.toInstant())
    }

    @Test fun daylightSavingGapMovesForward() {
        // 2026-03-08 02:30 does not exist in New York (clocks jump 02:00 -> 03:00).
        val a = alarm(2, 30, date = LocalDate.of(2026, 3, 8))
        val next = AlarmTimeCalculator.nextRegular(a, at(2026, 3, 8, 0, 0, Kit.NY))
        assertNotNull(next)
        assertEquals(3, next!!.hour)
    }

    @Test fun snoozePendingOnlyWhenAhead() {
        val a = alarm(6, 0).copy(snoozedUntilMillis = 5_000)
        assertEquals(5_000L, AlarmTimeCalculator.pendingSnoozeMillis(a, 1_000))
        assertNull(AlarmTimeCalculator.pendingSnoozeMillis(a, 5_000))
        assertNull(AlarmTimeCalculator.pendingSnoozeMillis(a, 9_000))
    }

    @Test fun nextRingPicksSoonerOfSnoozeAndRegular() {
        val now = at(2026, 11, 2, 7, 0)
        val snoozeAt = millis(at(2026, 11, 2, 7, 10))
        val a = alarm(6, 0).copy(snoozedUntilMillis = snoozeAt)
        // Regular ring is tomorrow 06:00, snooze is in ten minutes.
        assertEquals(snoozeAt, AlarmTimeCalculator.nextRingMillis(a, now))
    }

    @Test fun snoozeStillCountsWhileAlarmIsOffDueToOneTimeFinishing() {
        val now = at(2026, 11, 2, 7, 0)
        val snoozeAt = millis(at(2026, 11, 2, 7, 10))
        val a = alarm(6, 0, enabled = false).copy(snoozedUntilMillis = snoozeAt)
        assertEquals(snoozeAt, AlarmTimeCalculator.nextRingMillis(a, now))
    }

    @Test fun nextOverallChoosesEarliestAndBreaksTiesByLowestId() {
        val now = at(2026, 11, 2, 7, 0)
        val list = listOf(
            alarm(9, 0, id = 3),
            alarm(8, 0, id = 2),
            alarm(8, 0, id = 1),
            alarm(6, 0, id = 4, enabled = false)
        )
        val (a, t) = AlarmTimeCalculator.nextOverall(list, now)!!
        assertEquals(1L, a.id)
        assertEquals(millis(at(2026, 11, 2, 8, 0)), t)
    }

    @Test fun nextOverallNullWhenNothingEnabled() {
        assertNull(AlarmTimeCalculator.nextOverall(listOf(alarm(8, 0, enabled = false)), at(2026, 11, 2, 7, 0)))
        assertNull(AlarmTimeCalculator.nextOverall(emptyList(), at(2026, 11, 2, 7, 0)))
    }

    @Test fun nextDateOn() {
        val monday = LocalDate.of(2026, 11, 2)
        assertEquals(monday, AlarmTimeCalculator.nextDateOn(MONDAY, monday))
        assertEquals(LocalDate.of(2026, 11, 6), AlarmTimeCalculator.nextDateOn(FRIDAY, monday))
        assertEquals(LocalDate.of(2026, 11, 8), AlarmTimeCalculator.nextDateOn(SUNDAY, monday))
    }

    @Test fun twelveHourConversion() {
        assertEquals(0, AlarmTimeCalculator.to24Hour(12, false))
        assertEquals(12, AlarmTimeCalculator.to24Hour(12, true))
        assertEquals(6, AlarmTimeCalculator.to24Hour(6, false))
        assertEquals(18, AlarmTimeCalculator.to24Hour(6, true))
        assertEquals(23, AlarmTimeCalculator.to24Hour(11, true))
        assertEquals(1, AlarmTimeCalculator.to24Hour(1, false))
    }

    @Test fun needsAmPmOnlyForTwelveHourReadings() {
        assertTrue(AlarmTimeCalculator.needsAmPm(6))
        assertTrue(AlarmTimeCalculator.needsAmPm(12))
        assertTrue(!AlarmTimeCalculator.needsAmPm(0))
        assertTrue(!AlarmTimeCalculator.needsAmPm(13))
    }
}
