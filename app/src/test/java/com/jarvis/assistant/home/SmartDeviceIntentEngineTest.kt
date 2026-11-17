package com.jarvis.assistant.home

import com.jarvis.assistant.home.automation.Automation
import com.jarvis.assistant.home.voice.HomeIntent
import com.jarvis.assistant.home.voice.HomeSnapshot
import com.jarvis.assistant.home.voice.ListFilter
import com.jarvis.assistant.home.voice.SmartDeviceIntentEngine
import org.junit.Assert.*
import org.junit.Test

class SmartDeviceIntentEngineTest {
    private val bed = HomeRoom("r1", "Bedroom")
    private val living = HomeRoom("r2", "Living room")
    private val devices = listOf(
        dev("1", "Bedroom light", DeviceCategory.LIGHT, "r1"),
        dev("2", "Bedroom lamp", DeviceCategory.LIGHT, "r1", aliases = listOf("reading lamp")),
        dev("3", "Living room fan", DeviceCategory.FAN, "r2"),
        dev("4", "Living room light", DeviceCategory.LIGHT, "r2"),
        dev("5", "Garden motor", DeviceCategory.MOTOR, null, aliases = listOf("pump")),
        dev("6", "Bluetooth speaker", DeviceCategory.SPEAKER, null, connection = ConnectionType.BLUETOOTH_CLASSIC, caps = setOf(Capability.READ_STATE), status = DeviceStatus.OFFLINE)
    )
    private val movie = Automation(id = "s1", name = "Movie mode", isScene = true)
    private val snap = HomeSnapshot(devices, listOf(bed, living), emptyList(), listOf(movie))

    private fun parse(t: String) = SmartDeviceIntentEngine.parse(t, snap)

    @Test fun exactDeviceOff() {
        val i = parse("Jarvis, switch off the living room fan") as HomeIntent.Control
        assertEquals(DeviceActionType.POWER_OFF, i.action.type)
        assertEquals(listOf("3"), i.devices.map { it.id })
    }

    @Test fun roomAndCategoryTurnsOnAllLightsInRoom() {
        val i = parse("turn on bedroom lights") as HomeIntent.Control
        assertEquals(setOf("1", "2"), i.devices.map { it.id }.toSet())
        assertEquals(DeviceActionType.POWER_ON, i.action.type)
    }

    @Test fun aliasWorks() {
        val i = parse("turn on reading lamp") as HomeIntent.Control
        assertEquals(listOf("2"), i.devices.map { it.id })
    }

    @Test fun hinglishOff() {
        val i = parse("living room ka fan band karo") as HomeIntent.Control
        assertEquals(DeviceActionType.POWER_OFF, i.action.type)
        assertEquals(listOf("3"), i.devices.map { it.id })
    }

    @Test fun hinglishAliasOn() {
        val i = parse("pump chalu karo") as HomeIntent.Control
        assertEquals(listOf("5"), i.devices.map { it.id })
        assertEquals(DeviceActionType.POWER_ON, i.action.type)
    }

    @Test fun devanagariOff() {
        val s = snap.copy(devices = devices + dev("7", "Hall fan", DeviceCategory.FAN, aliases = listOf("पंखा")))
        val i = SmartDeviceIntentEngine.parse("पंखा बंद करो", s) as HomeIntent.Control
        assertEquals(listOf("7"), i.devices.map { it.id })
        assertEquals(DeviceActionType.POWER_OFF, i.action.type)
    }

    @Test fun sceneByName() {
        val i = parse("activate movie mode") as HomeIntent.RunScene
        assertEquals("s1", i.scene.id)
    }

    @Test fun listOffline() {
        val i = parse("show all offline devices") as HomeIntent.ListDevices
        assertEquals(ListFilter.OFFLINE, i.filter)
    }

    @Test fun listConnected() {
        val i = parse("what devices are connected?") as HomeIntent.ListDevices
        assertEquals(ListFilter.ONLINE, i.filter)
    }

    @Test fun connectBluetoothSpeaker() {
        val i = parse("connect to my bluetooth speaker") as HomeIntent.Connect
        assertEquals("6", i.device.id)
    }

    @Test fun lightsInTwoRoomsAsksWhichRoom() {
        assertTrue(parse("turn on the light") is HomeIntent.Clarify)
    }

    @Test fun unrelatedCommandsAreNotHijacked() {
        assertNull(parse("turn on flashlight"))
        assertNull(parse("set volume to 40 percent"))
        assertNull(parse("open settings"))
        assertNull(parse("set an alarm for 6 am"))
    }

    @Test fun everythingInMyRoomAsksWhichRoomWhenSeveral() {
        assertTrue(parse("switch everything off in my room") is HomeIntent.Clarify)
    }

    @Test fun everythingInMyRoomUsesTheOnlyRoom() {
        val s = snap.copy(rooms = listOf(bed))
        val i = SmartDeviceIntentEngine.parse("switch everything off in my room", s) as HomeIntent.Control
        assertEquals(setOf("1", "2"), i.devices.map { it.id }.toSet())
    }

    @Test fun listDescriptionNeverInventsStatus() {
        val text = SmartDeviceIntentEngine.describeList(ListFilter.ONLINE, devices)
        assertTrue(text.startsWith("No connected devices"))
        assertEquals("1 offline: Bluetooth speaker. 5 device(s) have not been checked yet.", SmartDeviceIntentEngine.describeList(ListFilter.OFFLINE, devices))
    }
}
