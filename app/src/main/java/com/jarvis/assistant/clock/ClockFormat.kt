package com.jarvis.assistant.clock

import android.content.Context
import android.text.format.DateFormat
import com.jarvis.assistant.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Turns times, days and "how long until" into text that follows the Clock Display settings and the phone's language. */
object ClockFormat {

    fun is24Hour(context: Context, settings: ClockSettings): Boolean = when (settings.timeFormat) {
        TimeFormat.H24 -> true
        TimeFormat.H12 -> false
        TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
    }

    fun time(context: Context, hour: Int, minute: Int, settings: ClockSettings): String =
        time(context, hour, minute, settings, Locale.getDefault())

    fun time(context: Context, hour: Int, minute: Int, settings: ClockSettings, locale: Locale): String {
        val pattern = if (is24Hour(context, settings)) "HH:mm" else "h:mm a"
        return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    fun dayShort(day: DayOfWeek, locale: Locale = Locale.getDefault()): String = day.getDisplayName(TextStyle.SHORT, locale)

    /** The seven days in the order the Clock should show them (the chosen week start, else the phone's own). */
    fun orderedDays(settings: ClockSettings, locale: Locale = Locale.getDefault()): List<DayOfWeek> {
        val first = settings.weekStart ?: WeekFields.of(locale).firstDayOfWeek
        return (0..6).map { first.plus(it.toLong()) }
    }

    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKENDS = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    /** "Once", "Every day", "Weekdays", "Weekends", or the chosen days such as "Mon, Wed". */
    fun repeatSummary(context: Context, alarm: AlarmItem, settings: ClockSettings, locale: Locale = Locale.getDefault()): String {
        val days = alarm.repeatDays
        return when {
            days.isEmpty() -> alarm.oneTimeDate?.let { date ->
                val today = ZonedDateTime.now().toLocalDate()
                when (date) {
                    today -> context.getString(R.string.clock_today)
                    today.plusDays(1) -> context.getString(R.string.clock_tomorrow)
                    else -> date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
                }
            } ?: context.getString(R.string.clock_repeat_once)
            days.size == 7 -> context.getString(R.string.clock_repeat_daily)
            days == WEEKDAYS -> context.getString(R.string.clock_repeat_weekdays)
            days == WEEKENDS -> context.getString(R.string.clock_repeat_weekends)
            else -> orderedDays(settings, locale).filter { it in days }.joinToString(", ") { dayShort(it, locale) }
        }
    }

    /** "in 5 h 20 min", "in 40 min", "in under a minute". */
    fun timeUntil(context: Context, targetMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val minutes = ((targetMillis - nowMillis + 59_999L) / 60_000L).coerceAtLeast(0L)
        val days = minutes / (24 * 60)
        val hours = (minutes % (24 * 60)) / 60
        val mins = minutes % 60
        return when {
            minutes <= 0L -> context.getString(R.string.clock_in_less_than_minute)
            days > 0L -> context.getString(R.string.clock_in_dhm, days.toInt(), hours.toInt(), mins.toInt())
            hours > 0L && mins > 0L -> context.getString(R.string.clock_in_hm, hours.toInt(), mins.toInt())
            hours > 0L -> context.getString(R.string.clock_in_h, hours.toInt())
            else -> context.getString(R.string.clock_in_m, mins.toInt())
        }
    }

    /** "Today 06:00", "Tomorrow 06:00" or "Mon 06:00" for an exact ring time. */
    fun whenText(context: Context, millis: Long, settings: ClockSettings, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
        val today = ZonedDateTime.now(zone).toLocalDate()
        val day = when (at.toLocalDate()) {
            today -> context.getString(R.string.clock_today)
            today.plusDays(1) -> context.getString(R.string.clock_tomorrow)
            else -> dayShort(at.dayOfWeek, locale)
        }
        return day + " " + time(context, at.hour, at.minute, settings, locale)
    }

    /** The alarm's name, or a generic "Alarm" when it has none. */
    fun title(context: Context, alarm: AlarmItem): String =
        alarm.label.ifBlank { context.getString(R.string.clock_alarm_default_label) }
}
