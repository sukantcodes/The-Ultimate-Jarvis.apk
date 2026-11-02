package com.jarvis.assistant.clock

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Works out when an alarm should ring next. Pure date maths with no Android calls, so every rule can be unit tested.
 *
 * Alarms are wall-clock times: 06:30 means 06:30 in whatever zone the phone is in *now*. `ZonedDateTime.of(date, time, zone)`
 * handles daylight-saving changes (a time that does not exist is moved forward, a time that happens twice uses the first).
 */
object AlarmTimeCalculator {

    /** The next regular ring of [alarm] strictly after [now], or null if it is off or has nothing left to ring. */
    fun nextRegular(alarm: AlarmItem, now: ZonedDateTime): ZonedDateTime? {
        if (!alarm.enabled) return null
        val zone = now.zone
        val time = LocalTime.of(alarm.hour, alarm.minute)
        val today = now.toLocalDate()

        if (alarm.repeatDays.isEmpty()) {
            val fixed = alarm.oneTimeDate
            if (fixed != null) {
                val at = ZonedDateTime.of(fixed, time, zone)
                return if (at.isAfter(now)) at else null
            }
            val todayAt = ZonedDateTime.of(today, time, zone)
            return if (todayAt.isAfter(now)) todayAt else ZonedDateTime.of(today.plusDays(1), time, zone)
        }

        // Today is checked first (it may still be ahead), then up to a week out, so some day always matches.
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            if (date.dayOfWeek in alarm.repeatDays) {
                val at = ZonedDateTime.of(date, time, zone)
                if (at.isAfter(now)) return at
            }
        }
        return null
    }

    /** The pending snooze ring, if there is one still ahead of [nowMillis]. */
    fun pendingSnoozeMillis(alarm: AlarmItem, nowMillis: Long): Long? =
        alarm.snoozedUntilMillis.takeIf { it > nowMillis }

    /** The soonest of the regular ring and a pending snooze ring, as epoch millis. */
    fun nextRingMillis(alarm: AlarmItem, now: ZonedDateTime): Long? {
        val regular = nextRegular(alarm, now)?.toInstant()?.toEpochMilli()
        val snooze = pendingSnoozeMillis(alarm, now.toInstant().toEpochMilli())
        return listOfNotNull(regular, snooze).minOrNull()
    }

    /** The alarm that will ring first, with its time. Ties go to the lower id so the answer is stable. */
    fun nextOverall(alarms: List<AlarmItem>, now: ZonedDateTime): Pair<AlarmItem, Long>? =
        alarms.mapNotNull { a -> nextRingMillis(a, now)?.let { a to it } }
            .minWithOrNull(compareBy<Pair<AlarmItem, Long>> { it.second }.thenBy { it.first.id })

    /** The next date (today included) that falls on [day]. */
    fun nextDateOn(day: DayOfWeek, today: LocalDate): LocalDate {
        val ahead = (day.value - today.dayOfWeek.value + 7) % 7
        return today.plusDays(ahead.toLong())
    }

    /**
     * Turns a 12-hour clock reading plus am/pm into a 24-hour hour. 12 am is 0 and 12 pm is 12.
     * [hour12] must be 1..12.
     */
    fun to24Hour(hour12: Int, pm: Boolean): Int = (hour12 % 12) + if (pm) 12 else 0

    /** True when [hour] (as spoken) is a 12-hour reading that needs am/pm to be understood: 1 to 12. */
    fun needsAmPm(hour: Int): Boolean = hour in 1..12
}
