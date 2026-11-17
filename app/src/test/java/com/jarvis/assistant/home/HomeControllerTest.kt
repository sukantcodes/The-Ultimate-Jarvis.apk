package com.jarvis.assistant.home

import org.junit.Assert.*
import org.junit.Test

class HomeControllerTest {
    private fun setup(result: (HomeDevice, DeviceAction) -> CommandResult): Triple<HomeRegistry, FakeAdapter, HomeController> {
        val reg = HomeRegistry(MemoryKv()) { 1L }
        val adapter = FakeAdapter(result)
        return Triple(reg, adapter, HomeController(reg, listOf(adapter)))
    }

    @Test fun unsupportedCapabilityNeverReachesAdapter() {
        val (reg, adapter, c) = setup { _, _ -> CommandResult.Success("ok", true, true) }
        reg.addDevice(dev("1", "Sensor", caps = setOf(Capability.READ_STATE)))
        val r = c.run("1", DeviceAction(DeviceActionType.POWER_ON))
        assertTrue(r is CommandResult.Failure && r.reason == FailureReason.UNSUPPORTED)
        assertTrue(adapter.calls.isEmpty())
    }

    @Test fun verifiedSuccessRecordsStateAndActivity() {
        val (reg, _, c) = setup { _, _ -> CommandResult.Success("Lamp is now on.", true, true) }
        reg.addDevice(dev("1", "Lamp"))
        c.run("1", DeviceAction(DeviceActionType.POWER_ON))
        assertEquals(DeviceStatus.ONLINE, reg.device("1")!!.status)
        assertEquals(true, reg.device("1")!!.poweredOn)
        assertTrue(reg.activity().any { it.kind == "command" && it.success })
    }

    @Test fun unverifiedSuccessDoesNotChangeKnownState() {
        val (reg, _, c) = setup { _, _ -> CommandResult.Success("sent", false) }
        reg.addDevice(dev("1", "Lamp"))
        c.run("1", DeviceAction(DeviceActionType.POWER_ON))
        assertEquals(DeviceStatus.UNKNOWN, reg.device("1")!!.status)
        assertNull(reg.device("1")!!.poweredOn)
    }

    @Test fun offlineFailureMarksOfflineAndLogsFailure() {
        val (reg, _, c) = setup { d, _ -> CommandResult.Failure(FailureReason.OFFLINE, "${d.name} unreachable") }
        reg.addDevice(dev("1", "Lamp"))
        c.run("1", DeviceAction(DeviceActionType.POWER_ON))
        assertEquals(DeviceStatus.OFFLINE, reg.device("1")!!.status)
        assertTrue(reg.activity().any { !it.success })
    }

    @Test fun hubFallbackOnlyForDevicesRoutedThroughIt() {
        val reg = HomeRegistry(MemoryKv()) { 1L }
        val adapter = FakeAdapter { d, _ -> CommandResult.Failure(FailureReason.OFFLINE, "${d.name} unreachable") }
        var hubCalls = 0
        val hub = object : RemoteDispatcher {
            override val isAvailable = true
            override fun dispatch(device: HomeDevice, action: DeviceAction): CommandResult { hubCalls++; return CommandResult.Success("via hub", true, true) }
        }
        val c = HomeController(reg, listOf(adapter), hub)
        reg.addDevice(dev("1", "Plain"))
        reg.addDevice(dev("2", "Routed").copy(config = mapOf("via_hub" to "true")))
        assertTrue(c.run("1", DeviceAction(DeviceActionType.POWER_ON)) is CommandResult.Failure)
        assertEquals(0, hubCalls)
        assertTrue(c.run("2", DeviceAction(DeviceActionType.POWER_ON)) is CommandResult.Success)
        assertEquals(1, hubCalls)
    }

    @Test fun hubOnlyDeviceWithoutRemoteFailsHonestly() {
        val (reg, _, c) = setup { _, _ -> CommandResult.Success("x", true) }
        reg.addDevice(dev("1", "Cloud lamp", connection = ConnectionType.HUB))
        val r = c.run("1", DeviceAction(DeviceActionType.POWER_ON))
        assertTrue(r is CommandResult.Failure && r.reason == FailureReason.OFFLINE)
    }
}
