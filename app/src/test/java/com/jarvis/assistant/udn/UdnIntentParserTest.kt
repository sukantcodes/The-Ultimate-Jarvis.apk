package com.jarvis.assistant.udn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UdnIntentParserTest {

    private val oppo = UdnDeviceRef("1", "OPPO Phone", "OPPO", "CPH2455", "android", isSelf = true, authorized = true)
    private val pad = UdnDeviceRef("2", "Redmi Pad", "Xiaomi", "Redmi Pad SE", "android", isSelf = false, authorized = true)
    private val other = UdnDeviceRef("3", "Vivo Phone", "vivo", "V2250", "android", isSelf = false, authorized = true)
    private val laptop = UdnDeviceRef("4", "Office Laptop", "Dell", "XPS 13", "windows", isSelf = false, authorized = true)

    private fun parse(s: String) = UdnIntentParser.parse(s)

    @Test fun listsDevices() {
        assertEquals(UdnIntent.ListDevices, parse("Jarvis, show all my connected devices.")?.intent)
        assertEquals(UdnIntent.ListDevices, parse("show my devices")?.intent)
    }

    @Test fun batteryOfNamedDevice() {
        val p = parse("Jarvis, what's the battery level of my OPPO?")!!
        assertEquals(UdnIntent.Query(UdnQuery.BATTERY), p.intent)
        assertEquals(UdnTarget.Named("oppo"), p.target)
    }

    @Test fun opensChatboxOnNamedTablet() {
        val p = parse("Jarvis, open Chatbox on my Redmi Pad.")!!
        assertEquals(UdnIntent.OpenModule("chatbox"), p.intent)
        assertEquals(UdnTarget.Named("redmi pad"), p.target)
    }

    @Test fun cameraOnOtherPhone() {
        val p = parse("Jarvis, open the camera on my other phone.")!!
        assertEquals(UdnIntent.OpenCamera, p.intent)
        assertEquals(UdnTarget.Other, p.target)
    }

    @Test fun laptopOnline() {
        val p = parse("Jarvis, tell me if my laptop is online.")!!
        assertEquals(UdnIntent.Query(UdnQuery.ONLINE), p.intent)
        assertEquals(UdnTarget.Named("laptop"), p.target)
    }

    @Test fun listModulesOnTablet() {
        val p = parse("Jarvis, launch the available JARVIS modules on my tablet.")!!
        assertEquals(UdnIntent.ListModules, p.intent)
        assertEquals(UdnTarget.Named("tablet"), p.target)
    }

    @Test fun lockOtherDevice() {
        val p = parse("Jarvis, lock my other device.")!!
        assertEquals(UdnIntent.Lock, p.intent)
        assertEquals(UdnTarget.Other, p.target)
    }

    @Test fun routineOnAllDevices() {
        val p = parse("Jarvis, execute my morning routine on all authorized devices.")!!
        assertEquals(UdnIntent.Routine("morning"), p.intent)
        assertEquals(UdnTarget.All, p.target)
    }

    @Test fun stopListeningOnTablet() {
        val p = parse("Jarvis, stop the background listening service on my tablet.")!!
        assertEquals(UdnIntent.StopListening, p.intent)
        assertEquals(UdnTarget.Named("tablet"), p.target)
    }

    @Test fun disconnectOldDevice() {
        val p = parse("Jarvis, disconnect my old device from the network.")!!
        assertEquals(UdnIntent.Disconnect, p.intent)
        assertTrue(p.target is UdnTarget.Named)
    }

    @Test fun hinglish() {
        val a = parse("mere tablet par chatbox kholo")!!
        assertEquals(UdnIntent.OpenModule("chatbox"), a.intent)
        assertEquals(UdnTarget.Named("tablet"), a.target)
        val b = parse("mere laptop ki battery kitni hai")!!
        assertEquals(UdnIntent.Query(UdnQuery.BATTERY), b.intent)
        val c = parse("doosra phone lock karo")!!
        assertEquals(UdnIntent.Lock, c.intent)
        assertEquals(UdnTarget.Other, c.target)
        assertEquals(UdnIntent.ListDevices, parse("mere sabhi devices dikhao")?.intent)
    }

    @Test fun hindiScript() {
        val p = parse("मेरे टैबलेट पर चैटबॉक्स खोलो")!!
        assertEquals(UdnIntent.OpenModule("chatbox"), p.intent)
        assertEquals(UdnTarget.Named("tablet"), p.target)
    }

    @Test fun launchesAppOnDevice() {
        val p = parse("open YouTube on my tablet")!!
        assertEquals(UdnIntent.LaunchApp("youtube"), p.intent)
    }

    @Test fun volumeAndBrightness() {
        assertEquals(UdnIntent.Volume(40, null), parse("set the volume to 40 percent on my tablet")?.intent)
        assertEquals(UdnIntent.Brightness(null, -15), parse("lower the brightness on my redmi pad")?.intent)
        assertEquals(UdnIntent.Flashlight(false), parse("turn off the flashlight on my other phone")?.intent)
    }

    @Test fun ordinaryCommandsAreNotHijacked() {
        assertNull(parse("open youtube"))
        assertNull(parse("what's the weather today"))
        assertNull(parse("play some music"))
        assertNull(parse("call mom"))
        assertNull(parse("set an alarm for 7 am"))
        assertNull(parse("what's the battery level"))
        assertNull(parse("lock the screen"))
        assertNull(parse("tell me a joke about my tablet"))
    }

    @Test fun resolvesNamedDevices() {
        val devices = listOf(oppo, pad, other, laptop)
        assertEquals(UdnResolution.Single(pad), UdnIntentParser.resolve(UdnTarget.Named("redmi pad"), devices))
        assertEquals(UdnResolution.Single(pad), UdnIntentParser.resolve(UdnTarget.Named("tablet"), devices))
        assertEquals(UdnResolution.Single(laptop), UdnIntentParser.resolve(UdnTarget.Named("laptop"), devices))
        assertEquals(UdnResolution.ThisDevice, UdnIntentParser.resolve(UdnTarget.Named("oppo"), devices))
        assertTrue(UdnIntentParser.resolve(UdnTarget.Named("watch"), devices) is UdnResolution.NotFound)
    }

    @Test fun otherAndAll() {
        assertEquals(UdnResolution.Single(pad), UdnIntentParser.resolve(UdnTarget.Other, listOf(oppo, pad)))
        assertTrue(UdnIntentParser.resolve(UdnTarget.Other, listOf(oppo, pad, other)) is UdnResolution.Ambiguous)
        assertEquals(3, (UdnIntentParser.resolve(UdnTarget.All, listOf(oppo, pad, other, laptop)) as UdnResolution.Many).devices.size)
        assertTrue(UdnIntentParser.resolve(UdnTarget.Other, listOf(oppo)) is UdnResolution.NotFound)
    }

    @Test fun pendingDevicesAreNeverTargets() {
        val pending = pad.copy(authorized = false)
        assertTrue(UdnIntentParser.resolve(UdnTarget.Named("redmi pad"), listOf(oppo, pending)) is UdnResolution.NotFound)
        assertTrue(UdnIntentParser.resolve(UdnTarget.All, listOf(oppo, pending)) is UdnResolution.NotFound)
    }

    @Test fun ambiguousPhoneThenPick() {
        val devices = listOf(oppo, pad, other)
        val r = UdnIntentParser.resolve(UdnTarget.Named("phone"), devices)
        assertTrue(r is UdnResolution.Single || r is UdnResolution.Ambiguous)
        val picked = UdnIntentParser.pickCandidate("the second one", listOf(pad, other))
        assertEquals(other, picked)
        assertEquals(pad, UdnIntentParser.pickCandidate("redmi pad", listOf(pad, other)))
        assertNull(UdnIntentParser.pickCandidate("banana", listOf(pad, other)))
    }

    @Test fun yesNo() {
        assertEquals(true, UdnIntentParser.readYesNo("Yes, do it"))
        assertEquals(true, UdnIntentParser.readYesNo("haan kar do"))
        assertEquals(false, UdnIntentParser.readYesNo("no cancel"))
        assertEquals(false, UdnIntentParser.readYesNo("nahi"))
        assertNull(UdnIntentParser.readYesNo("open chatbox"))
    }

    @Test fun capabilityCatalogMatchesServerRules() {
        assertEquals(Sensitivity.HIGH, UdnCapabilities.sensitivityOf(UdnCapabilities.LOCATION_GET))
        assertEquals(Sensitivity.HIGH, UdnCapabilities.sensitivityOf(UdnCapabilities.CAMERA_OPEN))
        assertEquals(Sensitivity.HIGH, UdnCapabilities.sensitivityOf("unknown.capability"))
        assertTrue(UdnCapabilities.needsSourceConfirmation(UdnCapabilities.DEVICE_LOCK))
        assertTrue(!UdnCapabilities.needsSourceConfirmation(UdnCapabilities.DEVICE_BATTERY))
        assertNotNull(UdnModules.fromSpoken("jarvis home"))
        assertEquals("home", UdnModules.fromSpoken("open jarvis home")?.id)
    }
}
