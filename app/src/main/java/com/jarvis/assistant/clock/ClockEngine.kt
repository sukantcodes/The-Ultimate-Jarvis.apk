package com.jarvis.assistant.clock

import java.text.Normalizer
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Why an operation did not happen. The UI shows a translated message for each; the AI is given the English text. */
enum class ClockError {
    INVALID_TIME,
    TIME_IN_PAST,
    NOT_FOUND,
    AMBIGUOUS,
    NEEDS_SELECTOR,
    NEEDS_AM_PM,
    EXACT_ALARM_DENIED,
    SCHEDULE_FAILED,
    VERIFY_FAILED,
    NOTHING_RINGING,
    SNOOZE_LIMIT
}

sealed class ClockResult<out T> {
    /** [nextRingMillis] is the time Android was actually given, so a spoken or shown time is never a guess. */
    data class Ok<T>(val value: T, val nextRingMillis: Long? = null) : ClockResult<T>()

    /** Nothing was changed. [candidates] lists the alarms to choose from when the error is AMBIGUOUS or NEEDS_SELECTOR. */
    data class Failed(
        val error: ClockError,
        val message: String,
        val candidates: List<AlarmItem> = emptyList()
    ) : ClockResult<Nothing>()
}

sealed class ScheduleOutcome {
    object Scheduled : ScheduleOutcome()
    data class Rejected(val error: ClockError, val detail: String) : ScheduleOutcome()
}

/** What the engine needs from Android's AlarmManager. The real one lives in [AlarmScheduler]; tests use a fake. */
interface AlarmSchedulerPort {
    fun schedule(alarm: AlarmItem, triggerAtMillis: Long, kind: TriggerKind): ScheduleOutcome
    fun cancel(alarmId: Long, kind: TriggerKind)

    /** True only if Android really holds a registration for this alarm right now. */
    fun isScheduled(alarmId: Long, kind: TriggerKind): Boolean
}

/** The answer to "should this ring?" when Android reports that a scheduled time has arrived. */
data class TriggerOutcome(val ring: Boolean, val alarm: AlarmItem?, val reason: String)

data class MissedAlarm(val alarm: AlarmItem, val wasDueAtMillis: Long)

data class RestoreReport(
    val rescheduled: Int,
    val missed: List<MissedAlarm>,
    val failed: List<Pair<AlarmItem, ClockError>>
)

/**
 * The one place alarms are created, changed, found and rung. The Clock screens, the AI tools and the voice paths all
 * call this same class, so they always agree. It has no Android code in it: storage and scheduling come in through
 * [KeyValueStore] and [AlarmSchedulerPort].
 *
 * Rules it keeps:
 *  - An alarm is saved only after Android has accepted the schedule and the registration has been checked.
 *  - If Android refuses, nothing is changed and the failure says why. It never reports success it did not get.
 *  - Targets for change or delete are never guessed: several matches (or none) come back as a question.
 */
class ClockEngine(
    private val alarmStore: AlarmStore,
    private val settingsStore: ClockSettingsStore,
    private val scheduler: AlarmSchedulerPort,
    private val clock: () -> ZonedDateTime
) {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    // ---- Observing ------------------------------------------------------------------------------

    /** Called after any change, from whichever thread made it. The Clock screens use this to refresh at once. */
    fun addChangeListener(listener: () -> Unit) { listeners.addIfAbsent(listener) }
    fun removeChangeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyChanged() { listeners.forEach { runCatching { it() } } }

    /**
     * Runs one change under the lock. If anything throws (for example the phone refuses to write storage), the change is
     * reported as failed instead of crashing the caller, so the UI and the AI never announce a save that did not happen.
     */
    private inline fun <T> mutate(block: () -> ClockResult<T>): ClockResult<T> {
        val result = try {
            synchronized(lock) { block() }
        } catch (e: Exception) {
            ClockResult.Failed(ClockError.VERIFY_FAILED, "The change could not be saved: ${e.message ?: "storage error"}")
        }
        if (result is ClockResult.Ok) notifyChanged()
        return result
    }

    private fun nowMillis(): Long = clock().toInstant().toEpochMilli()

    // ---- Reading --------------------------------------------------------------------------------

    fun settings(): ClockSettings = settingsStore.get()

    fun updateSettings(change: (ClockSettings) -> ClockSettings): ClockSettings {
        val updated = synchronized(lock) { settingsStore.update(change) }
        notifyChanged()
        return updated
    }

    fun alarms(): List<AlarmItem> = alarmStore.all()

    fun alarm(id: Long): AlarmItem? = alarmStore.get(id)

    /** The alarm that will ring first (counting a pending snooze) and the exact time. Null if nothing is set to ring. */
    fun nextAlarm(): Pair<AlarmItem, Long>? = AlarmTimeCalculator.nextOverall(alarmStore.all(), clock())

    /** Finds the alarm a person means. Several matches or none is an answer to ask about, never a guess. */
    fun resolve(query: AlarmQuery, requireSelector: Boolean = false): ClockResult<AlarmItem> {
        val all = alarmStore.all()
        if (all.isEmpty()) return ClockResult.Failed(ClockError.NOT_FOUND, "There are no alarms.")
        if (query.isEmpty) {
            if (requireSelector) {
                return ClockResult.Failed(ClockError.NEEDS_SELECTOR, "Say which alarm, by its name or time.", all)
            }
            return if (all.size == 1) ClockResult.Ok(all[0])
            else ClockResult.Failed(ClockError.AMBIGUOUS, "There are ${all.size} alarms; say which one.", all)
        }
        query.id?.let { id ->
            val found = alarmStore.get(id)
            return if (found != null) ClockResult.Ok(found)
            else ClockResult.Failed(ClockError.NOT_FOUND, "There is no alarm with that id.")
        }
        val matches = all.filter { matches(it, query) }
        return when (matches.size) {
            0 -> ClockResult.Failed(ClockError.NOT_FOUND, "No alarm matches that.")
            1 -> ClockResult.Ok(matches[0])
            else -> ClockResult.Failed(ClockError.AMBIGUOUS, "${matches.size} alarms match; say which one.", matches)
        }
    }

    private fun matches(a: AlarmItem, q: AlarmQuery): Boolean {
        if (q.hour != null) {
            val hourMatches = if (q.hourIsAmbiguous) a.hour % 12 == q.hour % 12 else a.hour == q.hour
            if (!hourMatches) return false
        }
        if (q.minute != null && a.minute != q.minute) return false
        if (!q.label.isNullOrBlank() && !foldForMatch(a.label).contains(foldForMatch(q.label))) return false
        if (q.repeatsOn != null && q.repeatsOn !in a.repeatDays) return false
        if (q.partOfDay != null) {
            val byTime = q.partOfDay.contains(a.hour)
            val byLabel = a.label.isNotBlank() && foldForMatch(a.label).contains(q.partOfDay.name.lowercase(Locale.ROOT))
            if (!byTime && !byLabel) return false
        }
        return true
    }

    /** Case, accents and width differences must not stop "college" finding "College" or a Bengali label finding itself. */
    private fun foldForMatch(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()

    // ---- Creating and changing ------------------------------------------------------------------

    fun create(draft: AlarmDraft): ClockResult<AlarmItem> = mutate {
        if (draft.hour !in 0..23 || draft.minute !in 0..59) {
            return@mutate ClockResult.Failed(ClockError.INVALID_TIME, "That is not a valid time.")
        }
        val s = settingsStore.get()
        val toneMode = draft.toneMode ?: s.defaultToneMode
        val usesDefaultTone = draft.toneMode == null
        val toneUri = if (usesDefaultTone) s.defaultToneUri else draft.toneUri
        val toneTitle = if (usesDefaultTone) s.defaultToneTitle else draft.toneTitle
        val safeMode = if (toneMode == ToneMode.CUSTOM_URI && toneUri.isNullOrBlank()) ToneMode.SYSTEM_DEFAULT else toneMode

        val item = AlarmItem(
            id = alarmStore.nextId(),
            hour = draft.hour,
            minute = draft.minute,
            label = draft.label.trim().take(MAX_LABEL),
            enabled = draft.enabled,
            repeatDays = draft.repeatDays,
            oneTimeDate = if (draft.repeatDays.isEmpty()) draft.oneTimeDate else null,
            toneMode = safeMode,
            toneUri = if (safeMode == ToneMode.CUSTOM_URI) toneUri else null,
            toneTitle = if (safeMode == ToneMode.CUSTOM_URI) toneTitle else null,
            vibrate = draft.vibrate ?: s.defaultVibrate,
            snoozeMinutes = (draft.snoozeMinutes ?: s.defaultSnoozeMinutes).coerceIn(ClockSettings.MIN_SNOOZE, ClockSettings.MAX_SNOOZE)
        )

        val finalAlarm: AlarmItem = if (item.enabled) {
            when (val r = scheduleRegular(item)) {
                is Scheduled -> r.alarm
                is NotScheduled -> return@mutate r.failure
            }
        } else item

        alarmStore.put(finalAlarm)
        if (alarmStore.get(finalAlarm.id) != finalAlarm) {
            scheduler.cancel(finalAlarm.id, TriggerKind.REGULAR)
            return@mutate ClockResult.Failed(ClockError.VERIFY_FAILED, "The alarm could not be saved on this phone.")
        }
        ClockResult.Ok(finalAlarm, if (finalAlarm.enabled) finalAlarm.scheduledForMillis else null)
    }

    fun update(id: Long, patch: AlarmPatch): ClockResult<AlarmItem> = mutate {
        val old = alarmStore.get(id) ?: return@mutate ClockResult.Failed(ClockError.NOT_FOUND, "That alarm no longer exists.")
        val hour = patch.hour ?: old.hour
        val minute = patch.minute ?: old.minute
        if (hour !in 0..23 || minute !in 0..59) {
            return@mutate ClockResult.Failed(ClockError.INVALID_TIME, "That is not a valid time.")
        }
        val days = patch.repeatDays ?: old.repeatDays
        val date = when {
            days.isNotEmpty() -> null
            patch.clearOneTimeDate -> null
            patch.oneTimeDate != null -> patch.oneTimeDate
            else -> old.oneTimeDate
        }
        val timingChanged = hour != old.hour || minute != old.minute || days != old.repeatDays || date != old.oneTimeDate

        val mode = patch.toneMode ?: old.toneMode
        val uri = if (patch.toneMode != null) patch.toneUri else (patch.toneUri ?: old.toneUri)
        val safeMode = if (mode == ToneMode.CUSTOM_URI && uri.isNullOrBlank()) ToneMode.SYSTEM_DEFAULT else mode

        var next = old.copy(
            hour = hour,
            minute = minute,
            label = patch.label?.trim()?.take(MAX_LABEL) ?: old.label,
            repeatDays = days,
            oneTimeDate = date,
            toneMode = safeMode,
            toneUri = if (safeMode == ToneMode.CUSTOM_URI) uri else null,
            toneTitle = if (safeMode == ToneMode.CUSTOM_URI) (patch.toneTitle ?: if (patch.toneMode != null || patch.toneUri != null) null else old.toneTitle) else null,
            vibrate = patch.vibrate ?: old.vibrate,
            snoozeMinutes = (patch.snoozeMinutes ?: old.snoozeMinutes).coerceIn(ClockSettings.MIN_SNOOZE, ClockSettings.MAX_SNOOZE),
            enabled = patch.enabled ?: old.enabled
        )
        // Moving an alarm to another time makes any pending snooze meaningless.
        if (timingChanged && old.snoozedUntilMillis != 0L) {
            next = next.copy(snoozedUntilMillis = 0L, snoozeCount = 0)
            scheduler.cancel(id, TriggerKind.SNOOZE)
        }
        if (next.enabled) {
            next = when (val r = scheduleRegular(next)) {
                is Scheduled -> r.alarm
                is NotScheduled -> return@mutate r.failure
            }
        } else {
            scheduler.cancel(id, TriggerKind.REGULAR)
            scheduler.cancel(id, TriggerKind.SNOOZE)
            next = next.copy(snoozedUntilMillis = 0L, snoozeCount = 0, scheduledForMillis = 0L)
        }
        alarmStore.put(next)
        if (alarmStore.get(id) != next) {
            return@mutate ClockResult.Failed(ClockError.VERIFY_FAILED, "The change could not be saved on this phone.")
        }
        ClockResult.Ok(next, if (next.enabled) next.scheduledForMillis else null)
    }

    fun setEnabled(id: Long, enabled: Boolean): ClockResult<AlarmItem> = update(id, AlarmPatch(enabled = enabled))

    fun delete(id: Long): ClockResult<AlarmItem> = mutate {
        val old = alarmStore.get(id) ?: return@mutate ClockResult.Failed(ClockError.NOT_FOUND, "That alarm no longer exists.")
        scheduler.cancel(id, TriggerKind.REGULAR)
        scheduler.cancel(id, TriggerKind.SNOOZE)
        alarmStore.remove(id)
        if (alarmStore.get(id) != null || scheduler.isScheduled(id, TriggerKind.REGULAR) || scheduler.isScheduled(id, TriggerKind.SNOOZE)) {
            return@mutate ClockResult.Failed(ClockError.VERIFY_FAILED, "The alarm could not be fully removed.")
        }
        ClockResult.Ok(old)
    }

    // ---- When Android says a time has come ------------------------------------------------------

    /**
     * Decides whether an alarm should ring, and does the bookkeeping that follows: a repeating alarm is scheduled for
     * its next day, a one-time alarm switches itself off. Duplicate deliveries and alarms that were deleted or switched
     * off in the meantime do not ring.
     */
    fun onTriggered(id: Long, triggerMillis: Long, kind: TriggerKind): TriggerOutcome {
        val outcome = synchronized(lock) {
            val alarm = alarmStore.get(id) ?: return@synchronized TriggerOutcome(false, null, "alarm no longer exists")
            when (kind) {
                TriggerKind.SNOOZE -> {
                    if (alarm.snoozedUntilMillis == 0L || alarm.snoozedUntilMillis != triggerMillis) {
                        return@synchronized TriggerOutcome(false, alarm, "stale snooze")
                    }
                    val cleared = alarm.copy(snoozedUntilMillis = 0L, lastTriggerMillis = triggerMillis)
                    alarmStore.put(cleared)
                    TriggerOutcome(true, cleared, "snooze ended")
                }
                TriggerKind.REGULAR -> {
                    if (!alarm.enabled) return@synchronized TriggerOutcome(false, alarm, "alarm is off")
                    if (triggerMillis == alarm.lastTriggerMillis) return@synchronized TriggerOutcome(false, alarm, "duplicate delivery")
                    var after = alarm.copy(lastTriggerMillis = triggerMillis, snoozeCount = 0, snoozedUntilMillis = if (alarm.snoozedUntilMillis > triggerMillis) alarm.snoozedUntilMillis else 0L)
                    if (alarm.repeats) {
                        // Schedule the next day first, so the alarm keeps working even if ringing fails.
                        val from = clock().toInstant().toEpochMilli().coerceAtLeast(triggerMillis + 1000)
                        val zoned = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(from), clock().zone)
                        val next = AlarmTimeCalculator.nextRegular(after, zoned)
                        after = if (next != null) {
                            val at = next.toInstant().toEpochMilli()
                            when (scheduler.schedule(after, at, TriggerKind.REGULAR)) {
                                is ScheduleOutcome.Scheduled -> after.copy(scheduledForMillis = at)
                                is ScheduleOutcome.Rejected -> after.copy(scheduledForMillis = 0L)
                            }
                        } else after.copy(scheduledForMillis = 0L)
                    } else {
                        after = after.copy(enabled = false, scheduledForMillis = 0L)
                    }
                    alarmStore.put(after)
                    TriggerOutcome(true, after, "ring")
                }
            }
        }
        if (outcome.ring) notifyChanged()
        return outcome
    }

    /**
     * Snooze a ringing alarm. [ignoreLimit] is for an alarm that was pushed aside by another one: it must come back
     * whatever the snooze limit says, or it would be lost.
     */
    fun snooze(id: Long, ignoreLimit: Boolean = false): ClockResult<AlarmItem> = mutate {
        val alarm = alarmStore.get(id) ?: return@mutate ClockResult.Failed(ClockError.NOT_FOUND, "That alarm no longer exists.")
        val limit = settingsStore.get().maxSnoozes
        if (!ignoreLimit && limit > 0 && alarm.snoozeCount >= limit) {
            return@mutate ClockResult.Failed(ClockError.SNOOZE_LIMIT, "This alarm has already been snoozed $limit times.")
        }
        val until = nowMillis() + alarm.snoozeMinutes * 60_000L
        val snoozed = alarm.copy(snoozedUntilMillis = until, snoozeCount = alarm.snoozeCount + 1)
        when (val outcome = scheduler.schedule(snoozed, until, TriggerKind.SNOOZE)) {
            is ScheduleOutcome.Rejected -> return@mutate ClockResult.Failed(outcome.error, outcome.detail)
            is ScheduleOutcome.Scheduled -> Unit
        }
        if (!scheduler.isScheduled(id, TriggerKind.SNOOZE)) {
            return@mutate ClockResult.Failed(ClockError.VERIFY_FAILED, "Android did not keep the snooze.")
        }
        alarmStore.put(snoozed)
        ClockResult.Ok(snoozed, until)
    }

    /** Ends a ring: no snooze pending, snooze count back to zero. A repeating alarm keeps its schedule. */
    fun dismiss(id: Long): ClockResult<AlarmItem> = mutate {
        val alarm = alarmStore.get(id) ?: return@mutate ClockResult.Failed(ClockError.NOT_FOUND, "That alarm no longer exists.")
        scheduler.cancel(id, TriggerKind.SNOOZE)
        val cleared = alarm.copy(snoozedUntilMillis = 0L, snoozeCount = 0)
        alarmStore.put(cleared)
        ClockResult.Ok(cleared)
    }

    // ---- Restoring after a restart or a clock change --------------------------------------------

    /**
     * Registers every alarm with Android again. Used after the phone restarts, the app is updated, or the time, date or
     * time zone changes (all of which can drop or invalidate registrations).
     *
     * With [detectMissed] (restart, update), an alarm whose time passed while nothing was running is reported as missed:
     * a one-time alarm is switched off, a repeating one moves on to its next day. Without it (the time or zone changed),
     * alarms are simply recalculated for the new wall-clock time.
     */
    fun restoreAll(detectMissed: Boolean): RestoreReport = synchronized(lock) {
        val now = nowMillis()
        var rescheduled = 0
        val missed = ArrayList<MissedAlarm>()
        val failed = ArrayList<Pair<AlarmItem, ClockError>>()

        for (original in alarmStore.all()) {
            var alarm = original

            if (detectMissed) {
                if (alarm.snoozedUntilMillis in 1 until now) {
                    missed += MissedAlarm(alarm, alarm.snoozedUntilMillis)
                    alarm = alarm.copy(snoozedUntilMillis = 0L, snoozeCount = 0)
                }
                if (alarm.enabled && alarm.scheduledForMillis in 1 until now) {
                    missed += MissedAlarm(alarm, alarm.scheduledForMillis)
                    if (!alarm.repeats) alarm = alarm.copy(enabled = false, scheduledForMillis = 0L)
                }
            }

            if (alarm.snoozedUntilMillis > now) {
                when (val o = scheduler.schedule(alarm, alarm.snoozedUntilMillis, TriggerKind.SNOOZE)) {
                    is ScheduleOutcome.Rejected -> failed += alarm to o.error
                    is ScheduleOutcome.Scheduled -> Unit
                }
            }

            if (alarm.enabled) {
                when (val r = scheduleRegular(alarm)) {
                    is Scheduled -> { alarm = r.alarm; rescheduled++ }
                    is NotScheduled -> {
                        // A dated alarm that is now in the past is finished, not broken.
                        if (r.failure.error == ClockError.TIME_IN_PAST) {
                            alarm = alarm.copy(enabled = false, scheduledForMillis = 0L)
                            scheduler.cancel(alarm.id, TriggerKind.REGULAR)
                        } else {
                            failed += alarm to r.failure.error
                        }
                    }
                }
            } else {
                scheduler.cancel(alarm.id, TriggerKind.REGULAR)
            }
            if (alarm != original) alarmStore.put(alarm)
        }
        RestoreReport(rescheduled, missed, failed)
    }.also { notifyChanged() }

    // ---- Scheduling helper ----------------------------------------------------------------------

    private sealed class ScheduleStep
    private class Scheduled(val alarm: AlarmItem) : ScheduleStep()
    private class NotScheduled(val failure: ClockResult.Failed) : ScheduleStep()

    /** Gives the alarm's next ring to Android and checks the registration exists. Saves nothing itself. */
    private fun scheduleRegular(alarm: AlarmItem): ScheduleStep {
        val trigger = AlarmTimeCalculator.nextRegular(alarm, clock())
            ?: return NotScheduled(ClockResult.Failed(ClockError.TIME_IN_PAST, "That time has already passed."))
        val at = trigger.toInstant().toEpochMilli()
        when (val outcome = scheduler.schedule(alarm, at, TriggerKind.REGULAR)) {
            is ScheduleOutcome.Rejected -> return NotScheduled(ClockResult.Failed(outcome.error, outcome.detail))
            is ScheduleOutcome.Scheduled -> Unit
        }
        if (!scheduler.isScheduled(alarm.id, TriggerKind.REGULAR)) {
            return NotScheduled(ClockResult.Failed(ClockError.VERIFY_FAILED, "Android did not keep the alarm."))
        }
        return Scheduled(alarm.copy(scheduledForMillis = at))
    }

    companion object {
        const val MAX_LABEL = 80
    }
}
