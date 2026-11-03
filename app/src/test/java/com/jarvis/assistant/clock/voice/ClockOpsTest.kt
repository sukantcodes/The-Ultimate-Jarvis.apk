package com.jarvis.assistant.clock.voice

import com.jarvis.assistant.clock.AlarmDraft
import com.jarvis.assistant.clock.AlarmItem
import com.jarvis.assistant.clock.ClockError
import com.jarvis.assistant.clock.ClockResult
import com.jarvis.assistant.clock.Kit
import com.jarvis.assistant.clock.ScheduleOutcome
import com.jarvis.assistant.clock.TriggerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class ClockOpsTest {

    /** A ringing alarm as the service would handle it: Stop and Snooze go through the engine like the real service does. */
    private class FakeRing(private val rig: Kit.Rig) : RingPort {
        var ringing = -1L
        var ignoreRequests = false
        override fun ringingId() = ringing
        override fun requestSnooze(id: Long) {
            if (ignoreRequests) return
            if (rig.engine.snooze(id) is ClockResult.Ok) ringing = -1L
        }
        override fun requestStop(id: Long) {
            if (ignoreRequests) return
            rig.engine.dismiss(id); ringing = -1L
        }
    }

    private class Env {
        val rig = Kit.Rig() // Monday 2026-11-02 07:00 IST
        val ring = FakeRing(rig)
        val ops = ClockOps(rig.engine, ring, { rig.clock.get() }, sleep = { rig.clock.advanceMinutes(0) }, waitMillis = 0L)
        val dialog = ClockDialog(ops)
        fun add(h: Int, m: Int, label: String = "", days: Set<DayOfWeek> = emptySet()): AlarmItem =
            (rig.engine.create(AlarmDraft(h, m, label, days)) as ClockResult.Ok).value
        fun say(text: String) = dialog.handle(text)?.outcome
    }

    @Test fun setAlarmWithMeridiemCreatesVerifiedAlarm() {
        val e = Env()
        val out = e.say("set alarm for 6 am") as ClockOutcome.Success
        assertEquals(Done.CREATED, out.kind)
        assertEquals(6, out.alarm!!.hour)
        assertEquals(1, e.rig.engine.alarms().size)
        assertEquals(Kit.millis(Kit.at(2026, 11, 3, 6, 0)), out.atMillis)
        assertEquals(out.atMillis, e.rig.scheduler.at(out.alarm!!.id))
    }

    @Test fun missingAmPmAsksAndCreatesNothing() {
        val e = Env()
        val out = e.say("set alarm for 6")
        assertTrue(out is ClockOutcome.Ask && out.question == Question.AM_PM)
        assertEquals(0, e.rig.engine.alarms().size)
        assertTrue(e.rig.scheduler.held.isEmpty())
    }

    @Test fun answeringPmCompletesTheAlarm() {
        val e = Env()
        e.say("set alarm for 6")
        val out = e.say("pm") as ClockOutcome.Success
        assertEquals(18, out.alarm!!.hour)
        assertFalse(e.dialog.hasPending)
    }

    @Test fun answeringInHindiCompletesTheAlarm() {
        val e = Env()
        e.say("alarm lagao 6 baje")
        val out = e.say("subah") as ClockOutcome.Success
        assertEquals(6, out.alarm!!.hour)
    }

    @Test fun noTimeAsksForTime() {
        val e = Env()
        val out = e.say("set an alarm")
        assertTrue(out is ClockOutcome.Ask && out.question == Question.TIME)
        val done = e.say("7:30 am") as ClockOutcome.Success
        assertEquals(7, done.alarm!!.hour); assertEquals(30, done.alarm!!.minute)
    }

    @Test fun repeatingAndLabelled() {
        val e = Env()
        val out = e.say("set alarm every monday at 6 am called College") as ClockOutcome.Success
        assertEquals(setOf(DayOfWeek.MONDAY), out.alarm!!.repeatDays)
        assertEquals("College", out.alarm!!.label)
        // Monday 06:00 has passed at 07:00 on Monday, so the next ring is next Monday.
        assertEquals(Kit.millis(Kit.at(2026, 11, 9, 6, 0)), out.atMillis)
    }

    @Test fun weekdayNamedOnItsOwnDayAfterTheTimeMeansNextWeek() {
        val e = Env()
        val out = e.say("alarm on monday at 6 am") as ClockOutcome.Success
        assertEquals(Kit.millis(Kit.at(2026, 11, 9, 6, 0)), out.atMillis)
    }

    @Test fun tomorrowAlarm() {
        val e = Env()
        val out = e.say("wake me up tomorrow at 5:30 am") as ClockOutcome.Success
        assertEquals(Kit.millis(Kit.at(2026, 11, 3, 5, 30)), out.atMillis)
    }

    @Test fun androidRefusalIsReportedAndNothingSaved() {
        val e = Env()
        e.rig.scheduler.reject = ScheduleOutcome.Rejected(ClockError.EXACT_ALARM_DENIED, "denied")
        val out = e.say("set alarm for 6 am")
        assertTrue(out is ClockOutcome.Failure && out.error == ClockError.EXACT_ALARM_DENIED)
        assertEquals(0, e.rig.engine.alarms().size)
    }

    @Test fun silentlyDroppedRegistrationIsNotReportedAsSuccess() {
        val e = Env()
        e.rig.scheduler.dropSilently = true
        val out = e.say("set alarm for 6 am")
        assertTrue(out is ClockOutcome.Failure)
        assertEquals(0, e.rig.engine.alarms().size)
    }

    @Test fun disableByPartOfDay() {
        val e = Env()
        e.add(6, 0); e.add(21, 0)
        val out = e.say("disable morning alarm") as ClockOutcome.Success
        assertEquals(Done.DISABLED, out.kind)
        assertFalse(e.rig.engine.alarm(out.alarm!!.id)!!.enabled)
        assertTrue(e.rig.engine.alarms().first { it.hour == 21 }.enabled)
        assertNull(e.rig.scheduler.at(out.alarm!!.id))
    }

    @Test fun enableByTime() {
        val e = Env()
        val a = e.add(6, 0)
        e.rig.engine.setEnabled(a.id, false)
        val out = e.say("enable 6 am alarm") as ClockOutcome.Success
        assertEquals(Done.ENABLED, out.kind)
        assertTrue(e.rig.engine.alarm(a.id)!!.enabled)
        assertNotNull(e.rig.scheduler.at(a.id))
    }

    @Test fun deleteByLabel() {
        val e = Env()
        e.add(6, 0, "College"); e.add(7, 0, "Gym")
        val out = e.say("delete college alarm") as ClockOutcome.Success
        assertEquals(Done.DELETED, out.kind)
        assertEquals(listOf("Gym"), e.rig.engine.alarms().map { it.label })
    }

    @Test fun deleteWithTwoMatchesAsksAndDeletesNothingUntilAnswered() {
        val e = Env()
        e.add(6, 0, "Gym"); e.add(18, 0, "Walk")
        val ask = e.say("delete 6 alarm") as ClockOutcome.Ask
        assertEquals(Question.WHICH_ALARM, ask.question)
        assertEquals(2, e.rig.engine.alarms().size)
        val out = e.say("2") as ClockOutcome.Success
        assertEquals(Done.DELETED, out.kind)
        assertEquals(listOf("Gym"), e.rig.engine.alarms().map { it.label })
    }

    @Test fun deleteWithNoPointerAmongSeveralAsks() {
        val e = Env()
        e.add(6, 0, "A"); e.add(7, 0, "B")
        val ask = e.say("delete alarm")
        assertTrue(ask is ClockOutcome.Ask)
        assertEquals(2, e.rig.engine.alarms().size)
    }

    @Test fun deleteAllIsNeverDoneOnAGuess() {
        val e = Env()
        e.add(6, 0); e.add(7, 0)
        assertTrue(e.say("delete all alarms") is ClockOutcome.Ask)
        assertEquals(2, e.rig.engine.alarms().size)
    }

    @Test fun answerByNameToWhichAlarm() {
        val e = Env()
        e.add(6, 0, "Gym"); e.add(18, 0, "Walk")
        e.say("delete 6 alarm")
        val out = e.say("the walk alarm") as ClockOutcome.Success
        assertEquals(Done.DELETED, out.kind)
        assertEquals(listOf("Gym"), e.rig.engine.alarms().map { it.label })
    }

    @Test fun changeTimeOfOnlyAlarmNeedsAmPm() {
        val e = Env()
        val a = e.add(6, 0)
        val ask = e.say("change alarm to 7:30")
        assertTrue(ask is ClockOutcome.Ask && ask.question == Question.AM_PM)
        assertEquals(6, e.rig.engine.alarm(a.id)!!.hour)
        val out = e.say("am") as ClockOutcome.Success
        assertEquals(Done.UPDATED, out.kind)
        assertEquals(7, e.rig.engine.alarm(a.id)!!.hour); assertEquals(30, e.rig.engine.alarm(a.id)!!.minute)
        assertEquals(1, e.rig.engine.alarms().size)
    }

    @Test fun changeAmongSeveralAsksWhich() {
        val e = Env()
        e.add(6, 0); e.add(8, 0)
        val ask = e.say("change alarm to 7:30 am")
        assertTrue(ask is ClockOutcome.Ask && ask.question == Question.WHICH_ALARM)
    }

    @Test fun listAndNext() {
        val e = Env()
        assertEquals(emptyList<AlarmItem>(), (e.say("show all alarms") as ClockOutcome.Success).alarms)
        e.add(21, 0, "Night"); e.add(8, 0, "Work")
        val list = e.say("show all alarms") as ClockOutcome.Success
        assertEquals(listOf(8, 21), list.alarms.map { it.hour })
        val next = e.say("next alarm") as ClockOutcome.Success
        assertEquals(Done.NEXT, next.kind)
        assertEquals("Work", next.alarm!!.label)
    }

    @Test fun snoozeAndStopNeedARingingAlarm() {
        val e = Env()
        val s = e.say("snooze")
        assertTrue(s is ClockOutcome.Failure && s.error == ClockError.NOTHING_RINGING)
    }

    @Test fun snoozeRingingAlarmIsVerified() {
        val e = Env()
        val a = e.add(7, 1)
        e.rig.clock.advanceMinutes(1)
        e.rig.engine.onTriggered(a.id, Kit.millis(Kit.at(2026, 11, 2, 7, 1)), TriggerKind.REGULAR)
        e.ring.ringing = a.id
        val out = e.say("snooze") as ClockOutcome.Success
        assertEquals(Done.SNOOZED, out.kind)
        assertEquals(Kit.millis(Kit.at(2026, 11, 2, 7, 11)), out.atMillis)
        assertNotNull(e.rig.scheduler.at(a.id, TriggerKind.SNOOZE))
    }

    @Test fun snoozeThatTheServiceDidNotDoIsNotReportedAsDone() {
        val e = Env()
        val a = e.add(7, 1)
        e.ring.ringing = a.id
        e.ring.ignoreRequests = true
        assertTrue(e.say("snooze") is ClockOutcome.Failure)
    }

    @Test fun stopWhileRinging() {
        val e = Env()
        val a = e.add(7, 1)
        e.ring.ringing = a.id
        val out = e.say("stop alarm") as ClockOutcome.Success
        assertEquals(Done.STOPPED, out.kind)
        assertEquals(-1L, e.ring.ringingId())
    }

    @Test fun turnOffWithNoNameWhileRingingStopsTheRingingAlarm() {
        val e = Env()
        val a = e.add(7, 1); e.add(9, 0)
        e.ring.ringing = a.id
        val out = e.say("disable alarm") as ClockOutcome.Success
        assertEquals(Done.STOPPED, out.kind)
        assertTrue(e.rig.engine.alarms().all { it.enabled })
    }

    @Test fun openSettings() {
        val e = Env()
        assertEquals(Done.OPEN_SETTINGS, (e.say("open clock settings") as ClockOutcome.Success).kind)
    }

    @Test fun notAClockSentenceIsLeftAlone() {
        val e = Env()
        assertNull(e.dialog.handle("what is the weather today"))
    }

    @Test fun bengaliCommandCreatesAlarmAndReportsBengali() {
        val e = Env()
        val reply = e.dialog.handle("সকাল ৬টায় অ্যালার্ম সেট করো")!!
        assertEquals("bn", reply.language)
        assertEquals(6, (reply.outcome as ClockOutcome.Success).alarm!!.hour)
    }

    @Test fun kannadaCommandCreatesAlarm() {
        val e = Env()
        val reply = e.dialog.handle("ಬೆಳಿಗ್ಗೆ 6 ಗಂಟೆಗೆ ಅಲಾರಂ ಇಡು")!!
        assertEquals("kn", reply.language)
        assertEquals(6, (reply.outcome as ClockOutcome.Success).alarm!!.hour)
    }

    @Test fun hindiUnicodeLabelAlarmCanBeDeletedByItsName() {
        val e = Env()
        e.add(6, 0, "कॉलेज"); e.add(7, 0, "Gym")
        val out = e.say("कॉलेज अलार्म हटाओ")
        assertTrue(out is ClockOutcome.Success && out.kind == Done.DELETED)
        assertEquals(listOf("Gym"), e.rig.engine.alarms().map { it.label })
    }

    @Test fun negativeAnswerCancelsPendingQuestion() {
        val e = Env()
        e.say("set alarm for 6")
        val out = e.say("no") as ClockOutcome.Success
        assertEquals(Done.CANCELLED, out.kind)
        assertEquals(0, e.rig.engine.alarms().size)
    }

    @Test fun staleQuestionExpires() {
        var t = 0L
        val e = Env()
        val dialog = ClockDialog(e.ops) { t }
        dialog.handle("set alarm for 6")
        assertTrue(dialog.hasPending)
        t += 4 * 60_000L
        assertFalse(dialog.hasPending)
    }
}
