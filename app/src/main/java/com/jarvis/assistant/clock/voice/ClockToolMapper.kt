package com.jarvis.assistant.clock.voice

import com.jarvis.assistant.clock.PartOfDay
import java.time.DayOfWeek
import java.util.Locale

/** What an AI tool call asks for: the command, and the exact alarm if the model named one by its id. */
data class MappedCall(val intent: ClockIntent, val targetId: Long?)

/**
 * Turns the arguments of an AI tool call into a [ClockIntent] for [ClockOps]. Pure Kotlin so it can be tested.
 *
 * The AI reads the person's words in any language and fills these fields, but it must not invent what the person did not
 * say: an am/pm is only accepted when the person's own words (the transcript) carry one. Without it the time stays open and
 * the assistant asks. The transcript check is skipped only when no transcript is available.
 */
object ClockToolMapper {
    const val SET = "clock_set_alarm"
    const val LIST = "clock_list_alarms"
    const val NEXT = "clock_next_alarm"
    const val CHANGE = "clock_change_alarm"
    const val ENABLE = "clock_set_alarm_enabled"
    const val DELETE = "clock_delete_alarm"
    const val RING = "clock_ring_control"
    const val OPEN = "clock_open_settings"

    val NAMES: Set<String> = setOf(SET, LIST, NEXT, CHANGE, ENABLE, DELETE, RING, OPEN)

    private val DAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY
    )
    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    private val WEEKENDS = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    private fun str(a: Map<String, Any?>, key: String): String? = (a[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    private fun int(a: Map<String, Any?>, key: String): Int? = when (val v = a[key]) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun meridiem(a: Map<String, Any?>, key: String, trusted: Boolean): Meridiem? {
        if (!trusted) return null
        return when (str(a, key)?.lowercase(Locale.ROOT)?.replace(".", "")) {
            "am", "a" -> Meridiem.AM
            "pm", "p" -> Meridiem.PM
            else -> null
        }
    }

    /** A time field set: 13..23 and 0 are unambiguous 24-hour readings; 1..12 need an am/pm said by the person. */
    private fun time(a: Map<String, Any?>, hourKey: String, minuteKey: String, meridiemKey: String, trusted: Boolean): ParsedTime? {
        val h = int(a, hourKey) ?: return null
        val m = int(a, minuteKey) ?: 0
        if (h !in 0..23 || m !in 0..59) return ParsedTime(h, m, null, true) // invalid: the engine reports it
        val explicit = h == 0 || h >= 13
        return ParsedTime(h, m, if (explicit) null else meridiem(a, meridiemKey, trusted), explicit)
    }

    private fun daysFrom(value: Any?): Set<DayOfWeek> {
        val items: List<String> = when (value) {
            is Collection<*> -> value.mapNotNull { it?.toString() }
            is String -> value.split(',', ' ', ';').filter { it.isNotBlank() }
            else -> emptyList()
        }
        val out = LinkedHashSet<DayOfWeek>()
        for (raw in items) {
            when (val k = raw.trim().lowercase(Locale.ROOT)) {
                "daily", "everyday", "every_day", "every day" -> out.addAll(DayOfWeek.values())
                "weekdays", "weekday" -> out.addAll(WEEKDAYS)
                "weekends", "weekend" -> out.addAll(WEEKENDS)
                else -> DAYS[k]?.let { out.add(it) }
            }
        }
        return out
    }

    private fun partOfDay(a: Map<String, Any?>): PartOfDay? = when (str(a, "target_part_of_day")?.lowercase(Locale.ROOT)) {
        "morning" -> PartOfDay.MORNING
        "afternoon" -> PartOfDay.AFTERNOON
        "evening" -> PartOfDay.EVENING
        "night" -> PartOfDay.NIGHT
        else -> null
    }

    private fun selector(a: Map<String, Any?>, trusted: Boolean): Selector = Selector(
        time = time(a, "target_hour", "target_minute", "target_meridiem", trusted)
            ?: int(a, "target_hour")?.let { ParsedTime(it, int(a, "target_minute") ?: 0, null, false) },
        partOfDay = partOfDay(a),
        weekday = str(a, "target_weekday")?.let { DAYS[it.lowercase(Locale.ROOT)] },
        all = (a["target_all"] as? Boolean) == true
    )

    /** Returns null for a tool name that is not a clock tool. [transcript] is what the person actually said this turn. */
    fun map(name: String, args: Map<String, Any?>, transcript: String): MappedCall? {
        val trusted = transcript.isBlank() || ClockCommandParser.hasMeridiemClue(transcript)
        val id = (args["alarm_id"] as? Number)?.toLong() ?: str(args, "alarm_id")?.toLongOrNull()
        return when (name) {
            SET -> {
                val days = daysFrom(args["repeat_days"])
                val dateWord = when (str(args, "date")?.lowercase(Locale.ROOT)) {
                    "today" -> DateWord.TODAY
                    "tomorrow" -> DateWord.TOMORROW
                    "day_after_tomorrow", "day_after" -> DateWord.DAY_AFTER
                    else -> null
                }
                val onWeekday = str(args, "weekday")?.let { DAYS[it.lowercase(Locale.ROOT)] }
                MappedCall(ClockIntent.SetAlarm(time(args, "hour", "minute", "meridiem", trusted), days, dateWord.takeIf { days.isEmpty() }, onWeekday.takeIf { days.isEmpty() && dateWord == null }, str(args, "label")), null)
            }
            LIST -> MappedCall(ClockIntent.ListAlarms, null)
            NEXT -> MappedCall(ClockIntent.NextAlarm, null)
            OPEN -> MappedCall(ClockIntent.OpenSettings, null)
            RING -> when (str(args, "action")?.lowercase(Locale.ROOT)) {
                "snooze" -> MappedCall(ClockIntent.Snooze, null)
                "stop", "dismiss" -> MappedCall(ClockIntent.Stop, null)
                else -> null
            }
            ENABLE -> {
                val on = (args["enabled"] as? Boolean) ?: (str(args, "enabled")?.lowercase(Locale.ROOT) == "true")
                val sel = selector(args, trusted)
                MappedCall(if (on) ClockIntent.Enable(sel) else ClockIntent.Disable(sel), id)
            }
            DELETE -> MappedCall(ClockIntent.Delete(selector(args, trusted)), id)
            CHANGE -> {
                val days = if (args.containsKey("new_repeat_days")) daysFrom(args["new_repeat_days"]) else null
                MappedCall(
                    ClockIntent.Change(selector(args, trusted), time(args, "new_hour", "new_minute", "new_meridiem", trusted), days, str(args, "new_label")),
                    id
                )
            }
            else -> null
        }
    }
}
