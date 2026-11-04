package com.jarvis.assistant.clock

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** A fake Android alarm service: remembers what it was asked to hold, and can be told to refuse. */
class FakeScheduler : AlarmSchedulerPort {
    data class Entry(val alarmId: Long, val kind: TriggerKind, val at: Long)

    val held = LinkedHashMap<Pair<Long, TriggerKind>, Long>()
    var reject: ScheduleOutcome.Rejected? = null
    /** When true, schedule() says yes but nothing is kept, like a registration that silently vanished. */
    var dropSilently = false
    val log = ArrayList<String>()

    override fun schedule(alarm: AlarmItem, triggerAtMillis: Long, kind: TriggerKind): ScheduleOutcome {
        reject?.let { return it }
        log += "schedule ${alarm.id} $kind $triggerAtMillis"
        if (!dropSilently) held[alarm.id to kind] = triggerAtMillis
        return ScheduleOutcome.Scheduled
    }

    override fun cancel(alarmId: Long, kind: TriggerKind) {
        log += "cancel $alarmId $kind"
        held.remove(alarmId to kind)
    }

    override fun isScheduled(alarmId: Long, kind: TriggerKind): Boolean = held.containsKey(alarmId to kind)

    fun at(id: Long, kind: TriggerKind = TriggerKind.REGULAR): Long? = held[id to kind]
}

/** A clock the test can move. */
class TestClock(start: ZonedDateTime) {
    var now: ZonedDateTime = start
    fun get(): ZonedDateTime = now
    fun advanceMinutes(minutes: Long) { now = now.plusMinutes(minutes) }
    fun set(zdt: ZonedDateTime) { now = zdt }
    fun changeZone(zone: ZoneId) { now = now.withZoneSameInstant(zone) }
}

object Kit {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    val NY: ZoneId = ZoneId.of("America/New_York")

    /** 2026-11-02 is a Monday. */
    fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, zone: ZoneId = IST): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    fun millis(z: ZonedDateTime): Long = z.toInstant().toEpochMilli()

    class Rig(start: ZonedDateTime = at(2026, 11, 2, 7, 0)) {
        val kv = MemoryKeyValueStore()
        val scheduler = FakeScheduler()
        val clock = TestClock(start)
        val engine = ClockEngine(AlarmStore(kv), ClockSettingsStore(kv), scheduler) { clock.get() }

        /** A second engine over the same storage, as after the app process was killed and started again. */
        fun restartedEngine(newScheduler: FakeScheduler = FakeScheduler()): Pair<ClockEngine, FakeScheduler> =
            ClockEngine(AlarmStore(kv), ClockSettingsStore(kv), newScheduler) { clock.get() } to newScheduler
    }

    fun instant(ms: Long): ZonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), IST)
}
