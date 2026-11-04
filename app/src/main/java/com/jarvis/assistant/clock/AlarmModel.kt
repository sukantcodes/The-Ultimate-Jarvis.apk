package com.jarvis.assistant.clock

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * How an alarm picks its sound. Only sounds that already exist on the phone are ever used:
 *  - [SYSTEM_DEFAULT]: whatever the phone's default alarm sound is at the moment the alarm rings.
 *  - [CUSTOM_URI]: one specific sound from the phone's own alarm list (RingtoneManager), kept as its URI.
 *  - [SILENT]: vibration only, no sound.
 */
enum class ToneMode { SYSTEM_DEFAULT, CUSTOM_URI, SILENT }

/** Which of the two Android registrations a trigger belongs to. A snoozed alarm keeps its normal schedule too. */
enum class TriggerKind { REGULAR, SNOOZE }

/**
 * One alarm. All times are wall-clock times in the phone's current time zone, so they follow the zone when it changes.
 *
 * @property repeatDays Days it repeats on. Empty means it rings once.
 * @property oneTimeDate For a once-only alarm: the exact date, or null for "the next time this clock time comes round".
 * @property toneTitle The sound's display name, kept only so the list can still show something if the sound is gone later.
 * @property snoozedUntilMillis Epoch millis of the pending snooze ring, or 0 when not snoozed.
 * @property snoozeCount How many times the current ring has been snoozed (reset when it is dismissed).
 * @property lastTriggerMillis The trigger time that last made this alarm ring, used to drop duplicate deliveries.
 * @property scheduledForMillis The ring time last handed to Android for the regular schedule (0 if none). After a restart
 *   this tells a missed alarm apart from one that is simply still ahead.
 */
data class AlarmItem(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = true,
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val oneTimeDate: LocalDate? = null,
    val toneMode: ToneMode = ToneMode.SYSTEM_DEFAULT,
    val toneUri: String? = null,
    val toneTitle: String? = null,
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = 10,
    val snoozedUntilMillis: Long = 0L,
    val snoozeCount: Int = 0,
    val lastTriggerMillis: Long = 0L,
    val scheduledForMillis: Long = 0L
) {
    val repeats: Boolean get() = repeatDays.isNotEmpty()
}

/** What the user (or the AI) asks for when creating an alarm. Anything left null takes the default from the settings. */
data class AlarmDraft(
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val oneTimeDate: LocalDate? = null,
    val toneMode: ToneMode? = null,
    val toneUri: String? = null,
    val toneTitle: String? = null,
    val vibrate: Boolean? = null,
    val snoozeMinutes: Int? = null,
    val enabled: Boolean = true
)

/**
 * A change to an existing alarm. A null field means "leave as it is". [clearOneTimeDate] drops a fixed date
 * (used when an alarm is made to repeat or is moved back to "next occurrence").
 */
data class AlarmPatch(
    val hour: Int? = null,
    val minute: Int? = null,
    val label: String? = null,
    val repeatDays: Set<DayOfWeek>? = null,
    val oneTimeDate: LocalDate? = null,
    val clearOneTimeDate: Boolean = false,
    val toneMode: ToneMode? = null,
    val toneUri: String? = null,
    val toneTitle: String? = null,
    val vibrate: Boolean? = null,
    val snoozeMinutes: Int? = null,
    val enabled: Boolean? = null
)

/** How a person points at an alarm in words. Every field that is set must match. */
data class AlarmQuery(
    val id: Long? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    /** True when the hour was said without am/pm, so 6 matches both 06:xx and 18:xx. */
    val hourIsAmbiguous: Boolean = false,
    val label: String? = null,
    val repeatsOn: DayOfWeek? = null,
    /** "my morning alarm": matches an alarm whose label has the word OR whose time falls in that part of the day. */
    val partOfDay: PartOfDay? = null
) {
    val isEmpty: Boolean
        get() = id == null && hour == null && minute == null && label.isNullOrBlank() && repeatsOn == null && partOfDay == null
}

enum class PartOfDay(val hours: IntRange) {
    MORNING(4..11), AFTERNOON(12..16), EVENING(16..20), NIGHT(20..23);

    fun contains(hour: Int): Boolean = hour in hours || (this == NIGHT && hour in 0..3)
}

enum class TimeFormat { SYSTEM, H12, H24 }

enum class VolumeKeyAction { SNOOZE, DISMISS, NOTHING }

/** Everything on the Clock Display and Alarm Behaviour pages, plus the default tone for new alarms. */
data class ClockSettings(
    val timeFormat: TimeFormat = TimeFormat.SYSTEM,
    /** Null means "follow the phone's language and region". */
    val weekStart: DayOfWeek? = null,
    val showNextAlarmBanner: Boolean = true,
    val showRingScreenDate: Boolean = true,
    val defaultToneMode: ToneMode = ToneMode.SYSTEM_DEFAULT,
    val defaultToneUri: String? = null,
    val defaultToneTitle: String? = null,
    val defaultVibrate: Boolean = true,
    val defaultSnoozeMinutes: Int = 10,
    /** Minutes an alarm may ring before it silences itself. 0 means it rings until stopped. */
    val silenceAfterMinutes: Int = 10,
    /** Most snoozes allowed for one ring. 0 means unlimited. */
    val maxSnoozes: Int = 0,
    val gradualVolume: Boolean = true,
    val volumeKeyAction: VolumeKeyAction = VolumeKeyAction.SNOOZE
) {
    companion object {
        const val MIN_SNOOZE = 1
        const val MAX_SNOOZE = 60
        const val MAX_SILENCE_AFTER = 60
        const val MAX_SNOOZE_LIMIT = 10
    }
}
