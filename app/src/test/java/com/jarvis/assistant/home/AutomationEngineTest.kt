package com.jarvis.assistant.home

import com.jarvis.assistant.home.automation.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AutomationEngineTest {
    private val reg = HomeRegistry(MemoryKv()) { 1L }
    private val adapter = FakeAdapter { d, a -> CommandResult.Success("ok", true, a.type == DeviceActionType.POWER_ON) }
    private val controller = HomeController(reg, listOf(adapter))
    private val store = AutomationStore(MemoryKv())
    private val sleeps = ArrayList<Long>()
    private val continuations = ArrayList<Triple<String, Int, Int>>()
    private val engine = AutomationEngine(reg, controller, store, { 99L }, { sleeps += it }, { id, step, delay -> continuations += Triple(id, step, delay) })

    private fun step(id: String, type: DeviceActionType = DeviceActionType.POWER_OFF, delay: Int = 0) = AutomationStep(deviceId = id, action = DeviceAction(type), delaySeconds = delay)

    @Test fun templateWithoutStepsRefusesToRun() {
        store.save(Automation(id = "a", name = "Good Night", isScene = true, templateKey = "good_night"))
        val run = engine.run("a")
        assertFalse(run.success)
        assertTrue(run.summary.contains("Not configured"))
        assertTrue(adapter.calls.isEmpty())
    }

    @Test fun runsStepsInOrderAndRecordsHistory() {
        reg.addDevice(dev("1", "A")); reg.addDevice(dev("2", "B"))
        store.save(Automation(id = "a", name = "All off", isScene = true, steps = listOf(step("1"), step("2"))))
        val run = engine.run("a")
        assertTrue(run.success)
        assertEquals(listOf("1", "2"), adapter.calls.map { it.first })
        assertEquals(run, store.lastRun("a"))
        assertTrue(reg.activity().any { it.kind == "automation" })
    }

    @Test fun unmetConditionSkipsWithoutTouchingDevices() {
        reg.addDevice(dev("1", "A", status = DeviceStatus.ONLINE, on = false)); reg.addDevice(dev("2", "B"))
        store.save(Automation(id = "a", name = "x", isScene = false, conditions = listOf(Condition("1", poweredOn = true)), steps = listOf(step("2"))))
        val run = engine.run("a")
        assertFalse(run.success)
        assertTrue(run.summary.startsWith("Skipped"))
        assertTrue(adapter.calls.isEmpty())
    }

    @Test fun unknownStateNeverSatisfiesCondition() {
        reg.addDevice(dev("1", "A")); reg.addDevice(dev("2", "B"))
        store.save(Automation(id = "a", name = "x", isScene = false, conditions = listOf(Condition("1", poweredOn = true)), steps = listOf(step("2"))))
        assertTrue(engine.run("a").summary.contains("has not reported"))
    }

    @Test fun shortDelayWaitsInPlaceLongDelayIsHandedOff() {
        reg.addDevice(dev("1", "A")); reg.addDevice(dev("2", "B"))
        store.save(Automation(id = "a", name = "x", isScene = false, steps = listOf(step("1", delay = 5), step("2", delay = 600))))
        engine.run("a")
        assertEquals(listOf(5_000L), sleeps)
        assertEquals(listOf(Triple("a", 1, 600)), continuations)
        assertEquals(listOf("1"), adapter.calls.map { it.first })
        engine.run("a", startStep = 1, skipFirstDelay = true)
        assertEquals(listOf("1", "2"), adapter.calls.map { it.first })
    }

    @Test fun unsupportedTargetsAreReportedNotSilentlyDropped() {
        reg.addDevice(dev("1", "Sensor", caps = setOf(Capability.READ_STATE)))
        store.save(Automation(id = "a", name = "x", isScene = false, steps = listOf(step("1"))))
        val run = engine.run("a")
        assertFalse(run.success)
        assertTrue(run.steps[0].message.contains("supports"))
    }

    @Test fun failedStepMakesRunFail() {
        val bad = FakeAdapter { d, _ -> CommandResult.Failure(FailureReason.OFFLINE, "${d.name} offline") }
        val e = AutomationEngine(reg, HomeController(reg, listOf(bad)), store, { 1L }, { }, { _, _, _ -> })
        reg.addDevice(dev("1", "A"))
        store.save(Automation(id = "a", name = "x", isScene = false, steps = listOf(step("1"))))
        val run = e.run("a")
        assertFalse(run.success)
        assertTrue(run.summary.contains("failed"))
    }

    @Test fun stateTriggerFiresOnlyOnRealChange() {
        reg.addDevice(dev("1", "Door")); reg.addDevice(dev("2", "Lamp"))
        store.save(Automation(id = "a", name = "x", isScene = false, trigger = Trigger.DeviceState("1", true), steps = listOf(step("2", DeviceActionType.POWER_ON))))
        engine.onDeviceState("1", false)   // first sighting: no fire
        engine.onDeviceState("1", false)   // unchanged: no fire
        engine.onDeviceState("1", null)    // unknown: ignored
        assertTrue(adapter.calls.isEmpty())
        engine.onDeviceState("1", true)    // real change to on: fires
        assertEquals(listOf("2"), adapter.calls.map { it.first })
    }

    @Test fun disabledStateRoutineDoesNotFire() {
        reg.addDevice(dev("1", "Door")); reg.addDevice(dev("2", "Lamp"))
        store.save(Automation(id = "a", name = "x", isScene = false, enabled = false, trigger = Trigger.DeviceState("1", true), steps = listOf(step("2"))))
        engine.onDeviceState("1", false); engine.onDeviceState("1", true)
        assertTrue(adapter.calls.isEmpty())
    }

    @Test fun roomAndGroupTargetsExpand() {
        val room = reg.createRoom("Bed")!!
        reg.addDevice(dev("1", "A", room = room.id)); reg.addDevice(dev("2", "B", room = room.id)); reg.addDevice(dev("3", "C"))
        val g = reg.saveGroup("G", listOf("3"))!!
        store.save(Automation(id = "a", name = "x", isScene = false, steps = listOf(
            AutomationStep(roomId = room.id, action = DeviceAction(DeviceActionType.POWER_OFF)),
            AutomationStep(groupId = g.id, action = DeviceAction(DeviceActionType.POWER_OFF)))))
        engine.run("a")
        assertEquals(setOf("1", "2", "3"), adapter.calls.map { it.first }.toSet())
    }

    @Test fun automationJsonRoundTrips() {
        val a = Automation(id = "a", name = "n", isScene = false, trigger = Trigger.Time(7, 30, setOf(1, 3), false),
            conditions = listOf(Condition("1", true, null)), steps = listOf(step("1", delay = 4)))
        assertEquals(a, Automation.fromJson(a.toJson()))
    }

    // ---- schedule maths ----
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)

    @Test fun nextFireLaterToday() {
        val n = AutomationTime.nextFire(Trigger.Time(22, 0), at(2026, 11, 17, 16, 0))!!
        assertEquals(at(2026, 11, 17, 22, 0), n)
    }

    @Test fun nextFireRollsToTomorrowWhenPassedOrEqual() {
        assertEquals(at(2026, 11, 18, 8, 0), AutomationTime.nextFire(Trigger.Time(8, 0), at(2026, 11, 17, 8, 0))!!)
    }

    @Test fun nextFireHonoursWeekdays() {
        // 2026-11-17 is a Tuesday (2). Only Fridays (5): 2026-11-20.
        assertEquals(at(2026, 11, 20, 6, 30), AutomationTime.nextFire(Trigger.Time(6, 30, setOf(5)), at(2026, 11, 17, 12, 0))!!)
    }

    @Test fun nextFireSameWeekdayNextWeekWhenTodayPassed() {
        assertEquals(at(2026, 11, 24, 6, 30), AutomationTime.nextFire(Trigger.Time(6, 30, setOf(2)), at(2026, 11, 17, 12, 0))!!)
    }

    @Test fun nonTimeTriggersHaveNoNextFire() {
        assertNull(AutomationTime.nextFire(Trigger.Manual, at(2026, 11, 17, 12, 0)))
    }
}
