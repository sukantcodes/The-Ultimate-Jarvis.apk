package com.jarvis.assistant.clock

import com.jarvis.assistant.clock.Kit.at
import com.jarvis.assistant.clock.Kit.millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

class ClockEngineTest {

    // 2026-11-02 is a Monday, 07:00 IST.
    private fun rig() = Kit.Rig()

    private fun <T> ClockResult<T>.ok(): T = (this as ClockResult.Ok).value
    private fun ClockResult<*>.failed(): ClockResult.Failed = this as ClockResult.Failed

    // ---- Create / read ----

    @Test fun createSchedulesAndSaves() {
        val r = rig()
        val res = r.engine.create(AlarmDraft(hour = 8, minute = 0, label = "Gym"))
        val alarm = res.ok()
        assertEquals(millis(at(2026, 11, 2, 8, 0)), r.scheduler.at(alarm.id))
        assertEquals(millis(at(2026, 11, 2, 8, 0)), (res as ClockResult.Ok).nextRingMillis)
        assertEquals(alarm, r.engine.alarm(alarm.id))
        assertEquals(alarm.scheduledForMillis, millis(at(2026, 11, 2, 8, 0)))
    }

    @Test fun createDisabledSchedulesNothing() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, enabled = false)).ok()
        assertNull(r.scheduler.at(a.id))
        assertFalse(a.enabled)
    }

    @Test fun createRejectsInvalidTimes() {
        val r = rig()
        assertEquals(ClockError.INVALID_TIME, r.engine.create(AlarmDraft(24, 0)).failed().error)
        assertEquals(ClockError.INVALID_TIME, r.engine.create(AlarmDraft(5, 60)).failed().error)
        assertEquals(ClockError.INVALID_TIME, r.engine.create(AlarmDraft(-1, 0)).failed().error)
        assertTrue(r.engine.alarms().isEmpty())
    }

    @Test fun createUsesSettingsDefaults() {
        val r = rig()
        r.engine.updateSettings {
            it.copy(defaultVibrate = false, defaultSnoozeMinutes = 5, defaultToneMode = ToneMode.CUSTOM_URI,
                defaultToneUri = "content://x/1", defaultToneTitle = "Cesium")
        }
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        assertFalse(a.vibrate)
        assertEquals(5, a.snoozeMinutes)
        assertEquals(ToneMode.CUSTOM_URI, a.toneMode)
        assertEquals("content://x/1", a.toneUri)
        assertEquals("Cesium", a.toneTitle)
    }

    @Test fun createExplicitToneOverridesDefault() {
        val r = rig()
        r.engine.updateSettings { it.copy(defaultToneMode = ToneMode.CUSTOM_URI, defaultToneUri = "content://x/1") }
        val a = r.engine.create(AlarmDraft(8, 0, toneMode = ToneMode.CUSTOM_URI, toneUri = "content://x/2", toneTitle = "Neon")).ok()
        assertEquals("content://x/2", a.toneUri)
        val silent = r.engine.create(AlarmDraft(9, 0, toneMode = ToneMode.SILENT)).ok()
        assertEquals(ToneMode.SILENT, silent.toneMode)
        assertNull(silent.toneUri)
    }

    @Test fun customToneWithoutUriFallsBackToSystemDefault() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, toneMode = ToneMode.CUSTOM_URI, toneUri = null)).ok()
        assertEquals(ToneMode.SYSTEM_DEFAULT, a.toneMode)
    }

    @Test fun labelIsTrimmedAndCapped() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, label = "  " + "x".repeat(200) + "  ")).ok()
        assertEquals(ClockEngine.MAX_LABEL, a.label.length)
    }

    @Test fun multipleAlarmsGetDistinctIds() {
        val r = rig()
        val ids = (1..5).map { r.engine.create(AlarmDraft(8, it)).ok().id }
        assertEquals(5, ids.toSet().size)
        assertEquals(5, r.engine.alarms().size)
        assertEquals(5, r.scheduler.held.size)
    }

    @Test fun dateOnlyOneTimeInPastIsRefusedAndNothingSaved() {
        val r = rig()
        val res = r.engine.create(AlarmDraft(6, 0, oneTimeDate = LocalDate.of(2026, 11, 1)))
        assertEquals(ClockError.TIME_IN_PAST, res.failed().error)
        assertTrue(r.engine.alarms().isEmpty())
        assertTrue(r.scheduler.held.isEmpty())
    }

    // ---- Android refuses ----

    @Test fun androidRefusalMeansNothingIsSavedAndFailureIsReported() {
        val r = rig()
        r.scheduler.reject = ScheduleOutcome.Rejected(ClockError.EXACT_ALARM_DENIED, "denied")
        val res = r.engine.create(AlarmDraft(8, 0))
        assertEquals(ClockError.EXACT_ALARM_DENIED, res.failed().error)
        assertTrue(r.engine.alarms().isEmpty())
    }

    @Test fun silentlyDroppedRegistrationIsCaughtByVerification() {
        val r = rig()
        r.scheduler.dropSilently = true
        val res = r.engine.create(AlarmDraft(8, 0))
        assertEquals(ClockError.VERIFY_FAILED, res.failed().error)
        assertTrue(r.engine.alarms().isEmpty())
    }

    @Test fun enablingWhileAndroidRefusesKeepsAlarmOffAndReports() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, enabled = false)).ok()
        r.scheduler.reject = ScheduleOutcome.Rejected(ClockError.SCHEDULE_FAILED, "no")
        val res = r.engine.setEnabled(a.id, true)
        assertEquals(ClockError.SCHEDULE_FAILED, res.failed().error)
        assertFalse(r.engine.alarm(a.id)!!.enabled)
    }

    @Test fun editRejectedByAndroidLeavesOldAlarmUntouched() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.scheduler.reject = ScheduleOutcome.Rejected(ClockError.EXACT_ALARM_DENIED, "no")
        r.engine.update(a.id, AlarmPatch(hour = 9))
        assertEquals(8, r.engine.alarm(a.id)!!.hour)
    }

    // ---- Update / enable / delete ----

    @Test fun updateTimeReschedules() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        val b = r.engine.update(a.id, AlarmPatch(hour = 9, minute = 15)).ok()
        assertEquals(9, b.hour)
        assertEquals(millis(at(2026, 11, 2, 9, 15)), r.scheduler.at(a.id))
        assertEquals(1, r.engine.alarms().size)
    }

    @Test fun updateLabelOnlyKeepsTimeAndTone() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, toneMode = ToneMode.CUSTOM_URI, toneUri = "content://x/2", toneTitle = "Neon")).ok()
        val b = r.engine.update(a.id, AlarmPatch(label = "College")).ok()
        assertEquals("College", b.label)
        assertEquals("content://x/2", b.toneUri)
        assertEquals("Neon", b.toneTitle)
        assertEquals(8, b.hour)
    }

    @Test fun updateInvalidTimeIsRefused() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        assertEquals(ClockError.INVALID_TIME, r.engine.update(a.id, AlarmPatch(hour = 25)).failed().error)
        assertEquals(8, r.engine.alarm(a.id)!!.hour)
    }

    @Test fun updateUnknownIdIsNotFound() {
        assertEquals(ClockError.NOT_FOUND, rig().engine.update(99, AlarmPatch(hour = 1)).failed().error)
    }

    @Test fun makingAnAlarmRepeatDropsItsFixedDate() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, oneTimeDate = LocalDate.of(2026, 11, 5))).ok()
        val b = r.engine.update(a.id, AlarmPatch(repeatDays = setOf(MONDAY))).ok()
        assertNull(b.oneTimeDate)
        assertEquals(setOf(MONDAY), b.repeatDays)
    }

    @Test fun tonePatchSwitchesToneAndSilent() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        val b = r.engine.update(a.id, AlarmPatch(toneMode = ToneMode.CUSTOM_URI, toneUri = "content://x/9", toneTitle = "Cesium")).ok()
        assertEquals("content://x/9", b.toneUri)
        val c = r.engine.update(a.id, AlarmPatch(toneMode = ToneMode.SILENT)).ok()
        assertEquals(ToneMode.SILENT, c.toneMode)
        assertNull(c.toneUri)
        val d = r.engine.update(a.id, AlarmPatch(toneMode = ToneMode.SYSTEM_DEFAULT)).ok()
        assertEquals(ToneMode.SYSTEM_DEFAULT, d.toneMode)
    }

    @Test fun disableCancelsAndEnableReschedules() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.engine.setEnabled(a.id, false).ok()
        assertNull(r.scheduler.at(a.id))
        assertFalse(r.engine.alarm(a.id)!!.enabled)
        r.engine.setEnabled(a.id, true).ok()
        assertEquals(millis(at(2026, 11, 2, 8, 0)), r.scheduler.at(a.id))
    }

    @Test fun deleteRemovesEverything() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.engine.snooze(a.id).ok()
        r.engine.delete(a.id).ok()
        assertNull(r.engine.alarm(a.id))
        assertTrue(r.scheduler.held.isEmpty())
        assertEquals(ClockError.NOT_FOUND, r.engine.delete(a.id).failed().error)
    }

    // ---- Next alarm ----

    @Test fun nextAlarmIsEarliestEnabled() {
        val r = rig()
        r.engine.create(AlarmDraft(9, 0))
        val early = r.engine.create(AlarmDraft(8, 0)).ok()
        r.engine.create(AlarmDraft(7, 30, enabled = false))
        val (a, t) = r.engine.nextAlarm()!!
        assertEquals(early.id, a.id)
        assertEquals(millis(at(2026, 11, 2, 8, 0)), t)
    }

    @Test fun nextAlarmNullWhenNone() {
        assertNull(rig().engine.nextAlarm())
    }

    // ---- Resolving which alarm ----

    @Test fun resolveByLabelIsCaseAndScriptInsensitive() {
        val r = rig()
        r.engine.create(AlarmDraft(8, 0, label = "College"))
        r.engine.create(AlarmDraft(9, 0, label = "কলেজ"))
        assertEquals(8, r.engine.resolve(AlarmQuery(label = "college")).ok().hour)
        assertEquals(9, r.engine.resolve(AlarmQuery(label = "কলেজ")).ok().hour)
    }

    @Test fun resolveByExactTime() {
        val r = rig()
        r.engine.create(AlarmDraft(6, 0))
        r.engine.create(AlarmDraft(18, 0))
        assertEquals(18, r.engine.resolve(AlarmQuery(hour = 18, minute = 0)).ok().hour)
    }

    @Test fun resolveWithoutAmPmIsAmbiguousWhenBothMatch() {
        val r = rig()
        r.engine.create(AlarmDraft(6, 0))
        r.engine.create(AlarmDraft(18, 0))
        val res = r.engine.resolve(AlarmQuery(hour = 6, minute = 0, hourIsAmbiguous = true)).failed()
        assertEquals(ClockError.AMBIGUOUS, res.error)
        assertEquals(2, res.candidates.size)
    }

    @Test fun resolveWithoutAmPmWorksWhenOnlyOneMatches() {
        val r = rig()
        r.engine.create(AlarmDraft(6, 0))
        r.engine.create(AlarmDraft(9, 0))
        assertEquals(6, r.engine.resolve(AlarmQuery(hour = 6, minute = 0, hourIsAmbiguous = true)).ok().hour)
    }

    @Test fun resolveNeverGuessesBetweenSeveral() {
        val r = rig()
        r.engine.create(AlarmDraft(8, 0, label = "College"))
        r.engine.create(AlarmDraft(9, 0, label = "College bus"))
        val res = r.engine.resolve(AlarmQuery(label = "college")).failed()
        assertEquals(ClockError.AMBIGUOUS, res.error)
        assertEquals(2, res.candidates.size)
    }

    @Test fun resolveNothingMatchesIsNotFound() {
        val r = rig()
        r.engine.create(AlarmDraft(8, 0, label = "Gym"))
        assertEquals(ClockError.NOT_FOUND, r.engine.resolve(AlarmQuery(label = "college")).failed().error)
    }

    @Test fun resolveNoAlarmsAtAll() {
        assertEquals(ClockError.NOT_FOUND, rig().engine.resolve(AlarmQuery(label = "x")).failed().error)
    }

    @Test fun emptyQueryAsksWhenSeveralAndPicksWhenOne() {
        val r = rig()
        val only = r.engine.create(AlarmDraft(8, 0)).ok()
        assertEquals(only.id, r.engine.resolve(AlarmQuery()).ok().id)
        r.engine.create(AlarmDraft(9, 0))
        assertEquals(ClockError.AMBIGUOUS, r.engine.resolve(AlarmQuery()).failed().error)
    }

    @Test fun destructiveResolveNeverPicksTheOnlyAlarmWithoutBeingTold() {
        val r = rig()
        r.engine.create(AlarmDraft(8, 0))
        assertEquals(ClockError.NEEDS_SELECTOR, r.engine.resolve(AlarmQuery(), requireSelector = true).failed().error)
    }

    @Test fun resolveByRepeatDay() {
        val r = rig()
        r.engine.create(AlarmDraft(8, 0, repeatDays = setOf(MONDAY)))
        r.engine.create(AlarmDraft(9, 0, repeatDays = setOf(WEDNESDAY)))
        assertEquals(9, r.engine.resolve(AlarmQuery(repeatsOn = WEDNESDAY)).ok().hour)
    }

    // ---- Ringing ----

    @Test fun oneTimeAlarmRingsOnceThenTurnsItselfOff() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.clock.set(at(2026, 11, 2, 8, 0))
        val out = r.engine.onTriggered(a.id, r.scheduler.at(a.id)!!, TriggerKind.REGULAR)
        assertTrue(out.ring)
        assertFalse(r.engine.alarm(a.id)!!.enabled)
    }

    @Test fun duplicateDeliveryDoesNotRingTwice() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, repeatDays = DayOfWeek.values().toSet())).ok()
        val trigger = r.scheduler.at(a.id)!!
        r.clock.set(at(2026, 11, 2, 8, 0))
        assertTrue(r.engine.onTriggered(a.id, trigger, TriggerKind.REGULAR).ring)
        assertFalse(r.engine.onTriggered(a.id, trigger, TriggerKind.REGULAR).ring)
    }

    @Test fun repeatingAlarmSchedulesItsNextDayAfterRinging() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, repeatDays = setOf(MONDAY, WEDNESDAY))).ok()
        r.clock.set(at(2026, 11, 2, 8, 0))
        r.engine.onTriggered(a.id, r.scheduler.at(a.id)!!, TriggerKind.REGULAR)
        assertEquals(millis(at(2026, 11, 4, 8, 0)), r.scheduler.at(a.id))
        assertTrue(r.engine.alarm(a.id)!!.enabled)
        assertEquals(millis(at(2026, 11, 4, 8, 0)), r.engine.alarm(a.id)!!.scheduledForMillis)
    }

    @Test fun repeatingAlarmKeepsGoingForWeeks() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(6, 0, repeatDays = setOf(MONDAY))).ok()
        // Created on Monday 07:00 after 06:00 has passed, so the first ring is Nov 9; after it rings, Nov 16 is next.
        assertEquals(millis(at(2026, 11, 9, 6, 0)), r.scheduler.at(a.id))
        var expected = at(2026, 11, 16, 6, 0)
        repeat(6) {
            r.clock.set(Kit.instant(r.scheduler.at(a.id)!!))
            val out = r.engine.onTriggered(a.id, r.scheduler.at(a.id)!!, TriggerKind.REGULAR)
            assertTrue(out.ring)
            assertEquals(millis(expected), r.scheduler.at(a.id))
            expected = expected.plusDays(7)
        }
    }

    @Test fun deletedOrDisabledAlarmDoesNotRing() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        val t = r.scheduler.at(a.id)!!
        r.engine.setEnabled(a.id, false).ok()
        assertFalse(r.engine.onTriggered(a.id, t, TriggerKind.REGULAR).ring)
        r.engine.delete(a.id).ok()
        assertFalse(r.engine.onTriggered(a.id, t, TriggerKind.REGULAR).ring)
    }

    // ---- Snooze / dismiss ----

    @Test fun snoozeSchedulesAfterTheAlarmsOwnSnoozeMinutes() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, snoozeMinutes = 7)).ok()
        r.clock.set(at(2026, 11, 2, 8, 0))
        r.engine.onTriggered(a.id, r.scheduler.at(a.id)!!, TriggerKind.REGULAR)
        val s = r.engine.snooze(a.id) as ClockResult.Ok
        assertEquals(millis(at(2026, 11, 2, 8, 7)), s.nextRingMillis)
        assertEquals(millis(at(2026, 11, 2, 8, 7)), r.scheduler.at(a.id, TriggerKind.SNOOZE))
        assertEquals(1, s.value.snoozeCount)
    }

    @Test fun snoozedAlarmRingsAgainEvenIfOneTimeAlarmTurnedItselfOff() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.clock.set(at(2026, 11, 2, 8, 0))
        r.engine.onTriggered(a.id, r.scheduler.at(a.id)!!, TriggerKind.REGULAR)
        r.engine.snooze(a.id).ok()
        r.clock.set(at(2026, 11, 2, 8, 10))
        val out = r.engine.onTriggered(a.id, r.scheduler.at(a.id, TriggerKind.SNOOZE)!!, TriggerKind.SNOOZE)
        assertTrue(out.ring)
        assertEquals(0L, r.engine.alarm(a.id)!!.snoozedUntilMillis)
    }

    @Test fun repeatedSnoozingKeepsCountingAndRingingAgain() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, snoozeMinutes = 5)).ok()
        r.clock.set(at(2026, 11, 2, 8, 0))
        r.engine.onTriggered(a.id, r.scheduler.at(a.id)!!, TriggerKind.REGULAR)
        for (i in 1..4) {
            val s = r.engine.snooze(a.id).ok()
            assertEquals(i, s.snoozeCount)
            r.clock.advanceMinutes(5)
            assertTrue(r.engine.onTriggered(a.id, r.scheduler.at(a.id, TriggerKind.SNOOZE)!!, TriggerKind.SNOOZE).ring)
        }
    }

    @Test fun snoozeLimitIsEnforcedButCanBeIgnoredForPushedAsideAlarms() {
        val r = rig()
        r.engine.updateSettings { it.copy(maxSnoozes = 2) }
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.clock.set(at(2026, 11, 2, 8, 0))
        r.engine.onTriggered(a.id, r.scheduler.at(a.id)!!, TriggerKind.REGULAR)
        r.engine.snooze(a.id).ok()
        r.engine.snooze(a.id).ok()
        assertEquals(ClockError.SNOOZE_LIMIT, r.engine.snooze(a.id).failed().error)
        r.engine.snooze(a.id, ignoreLimit = true).ok()
    }

    @Test fun snoozeRejectedByAndroidIsNotReportedAsSuccess() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.scheduler.reject = ScheduleOutcome.Rejected(ClockError.SCHEDULE_FAILED, "no")
        assertEquals(ClockError.SCHEDULE_FAILED, r.engine.snooze(a.id).failed().error)
        assertEquals(0L, r.engine.alarm(a.id)!!.snoozedUntilMillis)
    }

    @Test fun staleSnoozeDeliveryIsIgnored() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.engine.snooze(a.id).ok()
        assertFalse(r.engine.onTriggered(a.id, 12345L, TriggerKind.SNOOZE).ring)
    }

    @Test fun dismissCancelsSnoozeAndResetsCountButKeepsRepeatSchedule() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, repeatDays = setOf(MONDAY, WEDNESDAY))).ok()
        r.clock.set(at(2026, 11, 2, 8, 0))
        r.engine.onTriggered(a.id, r.scheduler.at(a.id)!!, TriggerKind.REGULAR)
        r.engine.snooze(a.id).ok()
        val d = r.engine.dismiss(a.id).ok()
        assertEquals(0, d.snoozeCount)
        assertEquals(0L, d.snoozedUntilMillis)
        assertNull(r.scheduler.at(a.id, TriggerKind.SNOOZE))
        assertEquals(millis(at(2026, 11, 4, 8, 0)), r.scheduler.at(a.id))
    }

    @Test fun editingTimeClearsPendingSnooze() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.engine.snooze(a.id).ok()
        r.engine.update(a.id, AlarmPatch(hour = 9)).ok()
        assertNull(r.scheduler.at(a.id, TriggerKind.SNOOZE))
        assertEquals(0L, r.engine.alarm(a.id)!!.snoozedUntilMillis)
    }

    @Test fun disablingClearsPendingSnooze() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.engine.snooze(a.id).ok()
        r.engine.setEnabled(a.id, false).ok()
        assertNull(r.scheduler.at(a.id, TriggerKind.SNOOZE))
    }

    // ---- Restart, time and zone changes ----

    @Test fun restartRestoresAllAlarmsFromStorage() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, label = "Gym")).ok()
        val b = r.engine.create(AlarmDraft(9, 0, repeatDays = setOf(MONDAY))).ok()
        r.engine.create(AlarmDraft(10, 0, enabled = false))
        val (engine2, scheduler2) = r.restartedEngine()
        val report = engine2.restoreAll(detectMissed = true)
        assertEquals(2, report.rescheduled)
        assertTrue(report.missed.isEmpty())
        assertEquals(millis(at(2026, 11, 2, 8, 0)), scheduler2.at(a.id))
        assertEquals(millis(at(2026, 11, 2, 9, 0)), scheduler2.at(b.id))
        assertEquals(3, engine2.alarms().size)
        assertEquals("Gym", engine2.alarm(a.id)!!.label)
    }

    @Test fun alarmMissedWhilePhoneWasOffIsReportedAndOneTimeSwitchesOff() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.clock.set(at(2026, 11, 2, 9, 30))
        val (engine2, scheduler2) = r.restartedEngine()
        val report = engine2.restoreAll(detectMissed = true)
        assertEquals(1, report.missed.size)
        assertEquals(a.id, report.missed[0].alarm.id)
        assertEquals(millis(at(2026, 11, 2, 8, 0)), report.missed[0].wasDueAtMillis)
        assertFalse(engine2.alarm(a.id)!!.enabled)
        assertNull(scheduler2.at(a.id))
    }

    @Test fun missedRepeatingAlarmMovesToNextDay() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, repeatDays = setOf(MONDAY, WEDNESDAY))).ok()
        r.clock.set(at(2026, 11, 2, 9, 30))
        val (engine2, scheduler2) = r.restartedEngine()
        val report = engine2.restoreAll(detectMissed = true)
        assertEquals(1, report.missed.size)
        assertEquals(millis(at(2026, 11, 4, 8, 0)), scheduler2.at(a.id))
        assertTrue(engine2.alarm(a.id)!!.enabled)
    }

    @Test fun pendingSnoozeSurvivesRestart() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.clock.set(at(2026, 11, 2, 8, 0))
        r.engine.onTriggered(a.id, r.scheduler.at(a.id)!!, TriggerKind.REGULAR)
        r.engine.snooze(a.id).ok()
        r.clock.set(at(2026, 11, 2, 8, 3))
        val (engine2, scheduler2) = r.restartedEngine()
        val report = engine2.restoreAll(detectMissed = true)
        assertTrue(report.missed.isEmpty())
        assertEquals(millis(at(2026, 11, 2, 8, 10)), scheduler2.at(a.id, TriggerKind.SNOOZE))
    }

    @Test fun snoozeThatExpiredWhileOffIsReportedMissed() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.clock.set(at(2026, 11, 2, 8, 0))
        r.engine.onTriggered(a.id, r.scheduler.at(a.id)!!, TriggerKind.REGULAR)
        r.engine.snooze(a.id).ok()
        r.clock.set(at(2026, 11, 2, 9, 0))
        val (engine2, _) = r.restartedEngine()
        val report = engine2.restoreAll(detectMissed = true)
        assertEquals(1, report.missed.size)
        assertEquals(0L, engine2.alarm(a.id)!!.snoozedUntilMillis)
    }

    @Test fun zoneChangeKeepsWallClockTime() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, repeatDays = DayOfWeek.values().toSet())).ok()
        val before = r.scheduler.at(a.id)!!
        r.clock.changeZone(Kit.NY)
        r.engine.restoreAll(detectMissed = false)
        val after = r.scheduler.at(a.id)!!
        val zdt = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(after), Kit.NY)
        assertEquals(8, zdt.hour)
        assertEquals(0, zdt.minute)
        assertTrue(after != before)
    }

    @Test fun manualClockChangeRecalculates() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        assertEquals(millis(at(2026, 11, 2, 8, 0)), r.scheduler.at(a.id))
        // The user sets the date forward two days by hand.
        r.clock.set(at(2026, 11, 4, 7, 0))
        r.engine.restoreAll(detectMissed = false)
        assertEquals(millis(at(2026, 11, 4, 8, 0)), r.scheduler.at(a.id))
        assertTrue(r.engine.alarm(a.id)!!.enabled)
    }

    @Test fun manualClockMovedBackwardsReschedulesToTheNewFuture() {
        val r = Kit.Rig(at(2026, 11, 2, 9, 0))
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        assertEquals(millis(at(2026, 11, 3, 8, 0)), r.scheduler.at(a.id))
        r.clock.set(at(2026, 11, 2, 6, 0))
        r.engine.restoreAll(detectMissed = false)
        assertEquals(millis(at(2026, 11, 2, 8, 0)), r.scheduler.at(a.id))
    }

    @Test fun datedAlarmThatPassedBecomesFinishedNotBroken() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, oneTimeDate = LocalDate.of(2026, 11, 3))).ok()
        r.clock.set(at(2026, 11, 5, 7, 0))
        val report = r.engine.restoreAll(detectMissed = false)
        assertTrue(report.failed.isEmpty())
        assertFalse(r.engine.alarm(a.id)!!.enabled)
        assertNull(r.scheduler.at(a.id))
    }

    @Test fun restoreReportsAlarmsAndroidRefused() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        val (engine2, scheduler2) = r.restartedEngine()
        scheduler2.reject = ScheduleOutcome.Rejected(ClockError.EXACT_ALARM_DENIED, "denied")
        val report = engine2.restoreAll(detectMissed = true)
        assertEquals(0, report.rescheduled)
        assertEquals(1, report.failed.size)
        assertEquals(a.id, report.failed[0].first.id)
        assertEquals(ClockError.EXACT_ALARM_DENIED, report.failed[0].second)
        // The alarm is kept so it can be fixed once permission is back.
        assertNotNull(engine2.alarm(a.id))
        assertTrue(engine2.alarm(a.id)!!.enabled)
    }

    // ---- Syncing between UI and AI ----

    @Test fun everyChangeNotifiesListenersSoUiAndAiStayInSync() {
        val r = rig()
        var count = 0
        val listener = { count++; Unit }
        r.engine.addChangeListener(listener)
        val a = r.engine.create(AlarmDraft(8, 0)).ok()
        r.engine.update(a.id, AlarmPatch(label = "x")).ok()
        r.engine.setEnabled(a.id, false).ok()
        r.engine.updateSettings { it.copy(defaultSnoozeMinutes = 3) }
        r.engine.delete(a.id).ok()
        assertEquals(5, count)
        r.engine.removeChangeListener(listener)
        r.engine.create(AlarmDraft(9, 0))
        assertEquals(5, count)
    }

    @Test fun failedChangesDoNotNotify() {
        val r = rig()
        var count = 0
        r.engine.addChangeListener { count++ }
        r.engine.create(AlarmDraft(25, 0))
        r.engine.delete(404)
        assertEquals(0, count)
    }

    @Test fun twoEnginesOnTheSameStorageSeeTheSameAlarms() {
        val r = rig()
        val a = r.engine.create(AlarmDraft(8, 0, label = "Shared")).ok()
        val (engine2, _) = r.restartedEngine()
        assertEquals("Shared", engine2.alarm(a.id)!!.label)
    }
}
