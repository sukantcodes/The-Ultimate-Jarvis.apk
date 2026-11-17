package com.jarvis.assistant.home

import org.junit.Assert.*
import org.junit.Test

class HomeRegistryTest {
    private var now = 1_000L
    private fun registry(kv: MemoryKv = MemoryKv()) = HomeRegistry(kv) { now }

    @Test fun addsAndPersists() {
        val kv = MemoryKv()
        val r = registry(kv)
        assertTrue(r.addDevice(dev("1", "Lamp")) is AddResult.Added)
        assertEquals(listOf("Lamp"), registry(kv).devices().map { it.name })
    }

    @Test fun detectsDuplicateEndpoint() {
        val r = registry()
        r.addDevice(dev("1", "Lamp", address = "10.0.0.5"))
        val second = r.addDevice(dev("2", "Other name", address = "10.0.0.5"))
        assertTrue(second is AddResult.Duplicate)
        assertEquals(1, r.devices().size)
    }

    @Test fun sameAddressDifferentRelayIsNotDuplicate() {
        val r = registry()
        r.addDevice(dev("1", "Relay 0", address = "10.0.0.5").copy(config = mapOf("relay" to "0")))
        assertTrue(r.addDevice(dev("2", "Relay 1", address = "10.0.0.5").copy(config = mapOf("relay" to "1"))) is AddResult.Added)
    }

    @Test fun deletingRoomKeepsDevices() {
        val r = registry()
        val room = r.createRoom("Bedroom")!!
        r.addDevice(dev("1", "Lamp", room = room.id))
        r.deleteRoom(room.id)
        assertEquals(1, r.devices().size)
        assertNull(r.devices()[0].roomId)
    }

    @Test fun assignRoomIgnoresUnknownRoom() {
        val r = registry()
        r.addDevice(dev("1", "Lamp"))
        r.assignRoom("1", "nope")
        assertNull(r.device("1")!!.roomId)
    }

    @Test fun removingDeviceDropsItFromGroups() {
        val r = registry()
        r.addDevice(dev("1", "A")); r.addDevice(dev("2", "B"))
        val g = r.saveGroup("Both", listOf("1", "2"))!!
        r.removeDevice("1")
        assertEquals(listOf("2"), r.groups().first { it.id == g.id }.deviceIds)
    }

    @Test fun recordStateOnlyChangesWhatWasReported() {
        val r = registry()
        r.addDevice(dev("1", "Lamp"))
        assertEquals(DeviceStatus.UNKNOWN, r.device("1")!!.status)
        now = 5_000
        r.recordState("1", DeviceStatus.ONLINE, true)
        assertEquals(true, r.device("1")!!.poweredOn)
        assertEquals(5_000L, r.device("1")!!.lastSeenMillis)
        r.recordState("1", DeviceStatus.OFFLINE, null)
        assertEquals(DeviceStatus.OFFLINE, r.device("1")!!.status)
        assertEquals(true, r.device("1")!!.poweredOn)
        assertEquals(5_000L, r.device("1")!!.lastSeenMillis)
    }

    @Test fun activityIsCappedNewestFirst() {
        val r = registry()
        repeat(HomeRegistry.MAX_ACTIVITY + 20) { r.log("k", null, "e$it", true) }
        assertEquals(HomeRegistry.MAX_ACTIVITY, r.activity().size)
        assertEquals("e${HomeRegistry.MAX_ACTIVITY + 19}", r.activity().first().summary)
    }

    @Test fun backupRoundTripResetsLiveState() {
        val a = registry()
        a.addDevice(dev("1", "Lamp", status = DeviceStatus.ONLINE, on = true).copy(config = mapOf("auth_user" to "x")))
        val raw = a.exportState()
        val b = registry()
        assertTrue(b.importState(raw))
        assertEquals(DeviceStatus.UNKNOWN, b.device("1")!!.status)
        assertNull(b.device("1")!!.poweredOn)
        assertFalse(raw.contains("password"))
    }

    @Test fun invalidBackupChangesNothing() {
        val r = registry()
        r.addDevice(dev("1", "Lamp"))
        assertFalse(r.importState("not json"))
        assertEquals(1, r.devices().size)
    }
}
