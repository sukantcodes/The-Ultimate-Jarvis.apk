package com.jarvis.assistant.clock.voice

import com.jarvis.assistant.clock.AlarmDraft
import com.jarvis.assistant.clock.AlarmItem
import com.jarvis.assistant.clock.AlarmPatch
import com.jarvis.assistant.clock.AlarmQuery
import com.jarvis.assistant.clock.AlarmTimeCalculator
import com.jarvis.assistant.clock.ClockEngine
import com.jarvis.assistant.clock.ClockError
import com.jarvis.assistant.clock.ClockResult
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale

/** What a clock command did. Every success here has been read back from the engine, never assumed. */
enum class Done { CREATED, UPDATED, ENABLED, DISABLED, DELETED, LISTED, NEXT, SNOOZED, STOPPED, OPEN_SETTINGS, CANCELLED }

enum class Question { TIME, AM_PM, WHICH_ALARM }

sealed class ClockOutcome {
    /**
     * @property atMillis the time Android was given (created / changed / enabled / snoozed), or the next ring for NEXT.
     * @property unchanged true when the alarm was already in the state asked for (already on, already off).
     */
    data class Success(
        val kind: Done,
        val alarm: AlarmItem? = null,
        val alarms: List<AlarmItem> = emptyList(),
        val atMillis: Long? = null,
        val unchanged: Boolean = false
    ) : ClockOutcome()

    /** The command is not complete or not certain: the person must be asked. Nothing was changed. */
    data class Ask(val question: Question, val hour: Int? = null, val minute: Int? = null, val candidates: List<AlarmItem> = emptyList()) : ClockOutcome()

    /** Nothing was changed, and this is why. */
    data class Failure(val error: ClockError, val message: String) : ClockOutcome()
}

/** How the ringing alarm is reached. The service owns the sound, so Stop and Snooze are requests to it. */
interface RingPort {
    fun ringingId(): Long
    fun requestSnooze(id: Long)
    fun requestStop(id: Long)
}

/**
 * Runs a [ClockIntent] against the shared [ClockEngine]. The Clock screens call the engine directly; the AI tools, the voice
 * commands and the offline parser all come through here, so they use exactly the same alarm functions.
 *
 * Rules: an am/pm, a date or the alarm to change is never guessed (an [ClockOutcome.Ask] is returned instead), and a
 * success is only reported after reading the alarm back from storage.
 */
class ClockOps(
    private val engine: ClockEngine,
    private val ring: RingPort,
    private val clock: () -> ZonedDateTime,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val waitMillis: Long = 2_500L
) {

    fun execute(intent: ClockIntent, targetId: Long? = null, spoken: String? = null): ClockOutcome = when (intent) {
        is ClockIntent.SetAlarm -> setAlarm(intent)
        ClockIntent.ListAlarms -> ClockOutcome.Success(Done.LISTED, alarms = sorted(engine.alarms()))
        ClockIntent.NextAlarm -> engine.nextAlarm()?.let { (a, at) -> ClockOutcome.Success(Done.NEXT, a, atMillis = at) }
            ?: ClockOutcome.Success(Done.NEXT)
        ClockIntent.Snooze -> snooze()
        ClockIntent.Stop -> stop()
        ClockIntent.OpenSettings -> ClockOutcome.Success(Done.OPEN_SETTINGS)
        is ClockIntent.Enable -> toggle(intent.selector, true, targetId, spoken)
        is ClockIntent.Disable -> toggle(intent.selector, false, targetId, spoken)
        is ClockIntent.Delete -> delete(intent.selector, targetId, spoken)
        is ClockIntent.Change -> change(intent, targetId, spoken)
    }

    private fun sorted(list: List<AlarmItem>) = list.sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))

    // ---- Create ---------------------------------------------------------------------------------

    private fun setAlarm(intent: ClockIntent.SetAlarm): ClockOutcome {
        val time = intent.time ?: return ClockOutcome.Ask(Question.TIME)
        val hour = time.hour24 ?: return ClockOutcome.Ask(Question.AM_PM, time.hour, time.minute)
        if (time.minute !in 0..59 || hour !in 0..23) return fail(ClockError.INVALID_TIME, "That is not a valid time.")

        val now = clock()
        val today = now.toLocalDate()
        val date: LocalDate? = when {
            intent.repeatDays.isNotEmpty() -> null
            intent.dateWord == DateWord.TODAY -> today
            intent.dateWord == DateWord.TOMORROW -> today.plusDays(1)
            intent.dateWord == DateWord.DAY_AFTER -> today.plusDays(2)
            intent.onWeekday != null -> {
                val d = AlarmTimeCalculator.nextDateOn(intent.onWeekday, today)
                // Said on that very weekday after the time has gone: it means next week, not the past.
                if (d == today && !LocalTime.of(hour, time.minute).isAfter(now.toLocalTime().withSecond(0).withNano(0))) d.plusDays(7) else d
            }
            else -> null
        }
        val draft = AlarmDraft(hour = hour, minute = time.minute, label = intent.label.orEmpty(), repeatDays = intent.repeatDays, oneTimeDate = date)
        return when (val r = engine.create(draft)) {
            is ClockResult.Failed -> failure(r)
            is ClockResult.Ok -> {
                val saved = engine.alarm(r.value.id)
                if (saved == null || !saved.enabled || saved.scheduledForMillis == 0L || saved.scheduledForMillis != r.nextRingMillis) {
                    fail(ClockError.VERIFY_FAILED, "The alarm could not be confirmed after saving.")
                } else ClockOutcome.Success(Done.CREATED, saved, atMillis = saved.scheduledForMillis)
            }
        }
    }

    // ---- Finding the alarm a person means ------------------------------------------------------

    private sealed class Target {
        class Found(val alarm: AlarmItem) : Target()
        class Stop(val outcome: ClockOutcome) : Target()
    }

    private fun queryOf(sel: Selector): AlarmQuery {
        val t = sel.time
        return AlarmQuery(
            hour = t?.let { it.hour24 ?: it.hour },
            minute = t?.minute,
            hourIsAmbiguous = t != null && t.hour24 == null,
            partOfDay = sel.partOfDay,
            repeatsOn = sel.weekday
        )
    }

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()

    /** Alarms whose name is said in the sentence ("delete college alarm" finds the alarm called College). */
    private fun labelHits(spoken: String?, alarms: List<AlarmItem>): List<AlarmItem> {
        if (spoken.isNullOrBlank()) return emptyList()
        val said = ClockCommandParser.normalize(spoken)
        return alarms.filter { a ->
            val label = ClockCommandParser.normalize(a.label)
            label.length >= 2 && label !in GENERIC_LABELS && said.contains(label)
        }
    }

    private fun locate(selector: Selector, targetId: Long?, spoken: String?): Pair<AlarmItem?, ClockOutcome?> {
        if (targetId != null) {
            val a = engine.alarm(targetId) ?: return null to fail(ClockError.NOT_FOUND, "That alarm no longer exists.")
            return a to null
        }
        val all = engine.alarms()
        if (all.isEmpty()) return null to fail(ClockError.NOT_FOUND, "There are no alarms.")
        if (selector.all) return null to ClockOutcome.Ask(Question.WHICH_ALARM, candidates = sorted(all))

        val query = queryOf(selector)
        val byQuery = if (query.isEmpty) emptyList() else all.filter { matchesQuery(it, query) }
        val hits = labelHits(spoken, all)
        if (hits.isNotEmpty() && selector.time == null) {
            val narrowed = if (query.isEmpty) hits else hits.filter { it in byQuery }.ifEmpty { hits }
            return if (narrowed.size == 1) narrowed[0] to null
            else null to ClockOutcome.Ask(Question.WHICH_ALARM, candidates = sorted(narrowed))
        }
        return when (val r = engine.resolve(query)) {
            is ClockResult.Ok -> r.value to null
            is ClockResult.Failed -> when (r.error) {
                ClockError.AMBIGUOUS, ClockError.NEEDS_SELECTOR -> null to ClockOutcome.Ask(Question.WHICH_ALARM, candidates = sorted(r.candidates.ifEmpty { all }))
                else -> null to failure(r)
            }
        }
    }

    /** Same matching the engine uses for a spoken pointer, applied here to pick label hits that also fit the words. */
    private fun matchesQuery(a: AlarmItem, q: AlarmQuery): Boolean {
        if (q.hour != null) {
            val ok = if (q.hourIsAmbiguous) a.hour % 12 == q.hour % 12 else a.hour == q.hour
            if (!ok) return false
        }
        if (q.minute != null && a.minute != q.minute) return false
        if (q.repeatsOn != null && q.repeatsOn !in a.repeatDays) return false
        if (q.partOfDay != null && !q.partOfDay.contains(a.hour) && !fold(a.label).contains(q.partOfDay.name.lowercase(Locale.ROOT))) return false
        return true
    }

    private fun resolveTarget(selector: Selector, targetId: Long?, spoken: String?): Target {
        val (alarm, outcome) = locate(selector, targetId, spoken)
        return if (alarm != null) Target.Found(alarm) else Target.Stop(outcome!!)
    }

    // ---- Switch on / off, delete, change -------------------------------------------------------

    private fun toggle(selector: Selector, on: Boolean, targetId: Long?, spoken: String?): ClockOutcome {
        // "Turn it off" while an alarm is ringing, with no alarm named, means stop the ringing one.
        if (!on && targetId == null && isEmpty(selector) && ring.ringingId() >= 0L && labelHits(spoken, engine.alarms()).isEmpty()) return stop()

        val alarm = when (val t = resolveTarget(selector, targetId, spoken)) { is Target.Found -> t.alarm; is Target.Stop -> return t.outcome }
        if (alarm.enabled == on && !(on && alarm.scheduledForMillis == 0L)) {
            return ClockOutcome.Success(if (on) Done.ENABLED else Done.DISABLED, alarm, atMillis = alarm.scheduledForMillis.takeIf { on && it > 0 }, unchanged = true)
        }
        return when (val r = engine.setEnabled(alarm.id, on)) {
            is ClockResult.Failed -> failure(r)
            is ClockResult.Ok -> {
                val saved = engine.alarm(alarm.id)
                val good = saved != null && saved.enabled == on && (!on || saved.scheduledForMillis != 0L)
                if (!good) fail(ClockError.VERIFY_FAILED, "The alarm could not be confirmed after the change.")
                else ClockOutcome.Success(if (on) Done.ENABLED else Done.DISABLED, saved, atMillis = if (on) saved!!.scheduledForMillis else null)
            }
        }
    }

    private fun isEmpty(s: Selector) = s.time == null && s.partOfDay == null && s.weekday == null && !s.all

    private fun delete(selector: Selector, targetId: Long?, spoken: String?): ClockOutcome {
        val alarm = when (val t = resolveTarget(selector, targetId, spoken)) { is Target.Found -> t.alarm; is Target.Stop -> return t.outcome }
        // Deleting is never done on a guess: with no pointer at all (and more than one alarm) the person is asked.
        if (targetId == null && isEmpty(selector) && labelHits(spoken, engine.alarms()).isEmpty() && engine.alarms().size > 1) {
            return ClockOutcome.Ask(Question.WHICH_ALARM, candidates = sorted(engine.alarms()))
        }
        return when (val r = engine.delete(alarm.id)) {
            is ClockResult.Failed -> failure(r)
            is ClockResult.Ok -> if (engine.alarm(alarm.id) != null) fail(ClockError.VERIFY_FAILED, "The alarm is still there.")
            else ClockOutcome.Success(Done.DELETED, alarm)
        }
    }

    private fun change(intent: ClockIntent.Change, targetId: Long?, spoken: String?): ClockOutcome {
        val alarm = when (val t = resolveTarget(intent.selector, targetId, spoken)) { is Target.Found -> t.alarm; is Target.Stop -> return t.outcome }
        val newTime = intent.newTime
        if (newTime == null && intent.newRepeatDays == null && intent.newLabel == null) return ClockOutcome.Ask(Question.TIME, candidates = listOf(alarm))
        var hour: Int? = null
        var minute: Int? = null
        if (newTime != null) {
            hour = newTime.hour24 ?: return ClockOutcome.Ask(Question.AM_PM, newTime.hour, newTime.minute, listOf(alarm))
            minute = newTime.minute
        }
        val patch = AlarmPatch(hour = hour, minute = minute, repeatDays = intent.newRepeatDays, label = intent.newLabel, enabled = true.takeIf { !alarm.enabled })
        return when (val r = engine.update(alarm.id, patch)) {
            is ClockResult.Failed -> failure(r)
            is ClockResult.Ok -> {
                val saved = engine.alarm(alarm.id)
                val good = saved != null && (hour == null || saved.hour == hour) && (minute == null || saved.minute == minute) && saved.enabled && saved.scheduledForMillis != 0L
                if (!good) fail(ClockError.VERIFY_FAILED, "The change could not be confirmed.")
                else ClockOutcome.Success(Done.UPDATED, saved, atMillis = saved!!.scheduledForMillis)
            }
        }
    }

    // ---- Ringing alarm --------------------------------------------------------------------------

    private fun snooze(): ClockOutcome {
        val id = ring.ringingId()
        if (id < 0L) return fail(ClockError.NOTHING_RINGING, "No alarm is ringing right now.")
        ring.requestSnooze(id)
        val ok = waitFor { engine.alarm(id)?.let { it.snoozedUntilMillis > clock().toInstant().toEpochMilli() } == true && ring.ringingId() != id }
        val alarm = engine.alarm(id)
        if (ok && alarm != null) return ClockOutcome.Success(Done.SNOOZED, alarm, atMillis = alarm.snoozedUntilMillis)
        val limit = engine.settings().maxSnoozes
        if (alarm != null && limit > 0 && alarm.snoozeCount >= limit) return fail(ClockError.SNOOZE_LIMIT, "The snooze limit for this alarm has been reached.")
        return fail(ClockError.VERIFY_FAILED, "The alarm did not snooze.")
    }

    private fun stop(): ClockOutcome {
        val id = ring.ringingId()
        if (id < 0L) return fail(ClockError.NOTHING_RINGING, "No alarm is ringing right now.")
        ring.requestStop(id)
        val ok = waitFor { ring.ringingId() != id }
        return if (ok) ClockOutcome.Success(Done.STOPPED, engine.alarm(id)) else fail(ClockError.VERIFY_FAILED, "The alarm did not stop.")
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        var waited = 0L
        while (true) {
            if (condition()) return true
            if (waited >= waitMillis) return false
            sleep(100L)
            waited += 100L
        }
    }

    private fun fail(error: ClockError, message: String) = ClockOutcome.Failure(error, message)
    private fun failure(r: ClockResult.Failed) = ClockOutcome.Failure(r.error, r.message)

    private companion object {
        val GENERIC_LABELS = setOf("alarm", "alarms", "अलार्म", "অ্যালার্ম", "ಅಲಾರಂ")
    }
}
