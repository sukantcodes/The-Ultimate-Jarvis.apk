package com.jarvis.assistant.clock.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class ClockToolMapperTest {

    private fun set(args: Map<String, Any?>, said: String = ""): ClockIntent.SetAlarm =
        ClockToolMapper.map(ClockToolMapper.SET, args, said)!!.intent as ClockIntent.SetAlarm

    @Test fun amPmSaidByUserIsKept() {
        val a = set(mapOf("hour" to 6, "minute" to 0, "meridiem" to "am"), "set alarm for 6 am")
        assertEquals(6, a.time!!.hour24)
    }

    @Test fun amPmInventedByTheModelIsDroppedSoTheUserIsAsked() {
        val a = set(mapOf("hour" to 6, "minute" to 0, "meridiem" to "am"), "set an alarm for six")
        assertNull(a.time!!.hour24)
        assertTrue(a.time!!.needsMeridiem)
    }

    @Test fun partOfDayInAnyLanguageCountsAsSaid() {
        assertEquals(6, set(mapOf("hour" to 6, "meridiem" to "am"), "সকাল ৬টায় অ্যালার্ম দাও").time!!.hour24)
        assertEquals(6, set(mapOf("hour" to 6, "meridiem" to "am"), "ಬೆಳಿಗ್ಗೆ 6 ಗಂಟೆಗೆ ಅಲಾರಂ").time!!.hour24)
        assertEquals(18, set(mapOf("hour" to 6, "meridiem" to "pm"), "शाम 6 बजे का अलार्म लगाओ").time!!.hour24)
    }

    @Test fun twentyFourHourNeedsNoMeridiem() {
        assertEquals(18, set(mapOf("hour" to 18, "minute" to 30), "alarm at 18:30").time!!.hour24)
        assertEquals(0, set(mapOf("hour" to 0, "minute" to 5), "alarm at 00:05").time!!.hour24)
    }

    @Test fun noTranscriptMeansTheModelIsTrusted() {
        assertEquals(7, set(mapOf("hour" to 7, "meridiem" to "am")).time!!.hour24)
    }

    @Test fun repeatDaysAndNamedGroups() {
        val a = set(mapOf("hour" to 7, "meridiem" to "am", "repeat_days" to listOf("monday", "wednesday")), "every monday and wednesday 7 am")
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), a.repeatDays)
        assertEquals(5, set(mapOf("hour" to 7, "meridiem" to "am", "repeat_days" to listOf("weekdays")), "weekdays 7 am").repeatDays.size)
        assertEquals(7, set(mapOf("hour" to 7, "meridiem" to "am", "repeat_days" to listOf("daily")), "daily 7 am").repeatDays.size)
    }

    @Test fun dateWordAndLabelAndUnicode() {
        val a = set(mapOf("hour" to 5, "minute" to 30, "meridiem" to "am", "date" to "tomorrow", "label" to "কলেজ"), "kal subah 5:30 alarm college")
        assertEquals(DateWord.TOMORROW, a.dateWord)
        assertEquals("কলেজ", a.label)
    }

    @Test fun repeatingAlarmIgnoresDate() {
        val a = set(mapOf("hour" to 7, "meridiem" to "am", "repeat_days" to listOf("monday"), "date" to "tomorrow"), "every monday 7 am")
        assertNull(a.dateWord)
    }

    @Test fun enableAndDisable() {
        val off = ClockToolMapper.map(ClockToolMapper.ENABLE, mapOf("enabled" to false, "target_part_of_day" to "morning"), "disable morning alarm")!!
        assertTrue(off.intent is ClockIntent.Disable)
        val on = ClockToolMapper.map(ClockToolMapper.ENABLE, mapOf("enabled" to true, "target_hour" to 6, "target_minute" to 0, "target_meridiem" to "am"), "enable the 6 am alarm")!!
        assertEquals(6, (on.intent as ClockIntent.Enable).selector.time!!.hour24)
    }

    @Test fun alarmIdFromAnEarlierQuestionIsPassedThrough() {
        val d = ClockToolMapper.map(ClockToolMapper.DELETE, mapOf("alarm_id" to 7), "the second one")!!
        assertEquals(7L, d.targetId)
        assertTrue(d.intent is ClockIntent.Delete)
    }

    @Test fun changeCarriesNewTimeAndKeepsItOpenWithoutMeridiem() {
        val c = ClockToolMapper.map(ClockToolMapper.CHANGE, mapOf("new_hour" to 7, "new_minute" to 30), "change alarm to seven thirty")!!.intent as ClockIntent.Change
        assertTrue(c.newTime!!.needsMeridiem)
    }

    @Test fun ringControl() {
        assertEquals(ClockIntent.Snooze, ClockToolMapper.map(ClockToolMapper.RING, mapOf("action" to "snooze"), "snooze")!!.intent)
        assertEquals(ClockIntent.Stop, ClockToolMapper.map(ClockToolMapper.RING, mapOf("action" to "stop"), "stop")!!.intent)
        assertNull(ClockToolMapper.map(ClockToolMapper.RING, mapOf("action" to "dance"), "x"))
    }

    @Test fun unknownToolIsNotAClockTool() {
        assertNull(ClockToolMapper.map("open_app", emptyMap(), ""))
        assertNotNull(ClockToolMapper.map(ClockToolMapper.LIST, emptyMap(), ""))
    }
}
