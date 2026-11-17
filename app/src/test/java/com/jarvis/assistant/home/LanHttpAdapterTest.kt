package com.jarvis.assistant.home

import com.jarvis.assistant.home.adapters.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class LanHttpAdapterTest {
    private class FakeHttp(val handler: (String) -> String) : HttpTransport {
        val urls = ArrayList<String>()
        override fun get(url: String, headers: Map<String, String>): String { urls += url; return handler(url) }
    }

    private fun tasmota(extra: Map<String, String> = emptyMap()) = dev("1", "Plug").copy(protocol = DeviceProtocol.TASMOTA, address = "192.168.1.50",
        capabilities = LanHttpAdapter.capabilitiesFor(DeviceProtocol.TASMOTA), config = extra)

    @Test fun tasmotaOnIsVerifiedFromDeviceReply() {
        val http = FakeHttp { """{"POWER":"ON"}""" }
        val r = LanHttpAdapter(http).execute(tasmota(), DeviceAction(DeviceActionType.POWER_ON))
        assertTrue(r is CommandResult.Success && r.verified && r.poweredOn == true)
        assertEquals("http://192.168.1.50/cm?cmnd=Power%20On", http.urls.single())
    }

    @Test fun deviceThatAnswersOppositeIsAFailure() {
        val r = LanHttpAdapter(FakeHttp { """{"POWER":"OFF"}""" }).execute(tasmota(), DeviceAction(DeviceActionType.POWER_ON))
        assertTrue(r is CommandResult.Failure)
    }

    @Test fun shellyUsesRelayIndexAndReadsIsOn() {
        val http = FakeHttp { """{"ison":false,"has_timer":false}""" }
        val d = dev("2", "Shelly").copy(protocol = DeviceProtocol.SHELLY_GEN1, address = "192.168.1.51", capabilities = LanHttpAdapter.capabilitiesFor(DeviceProtocol.SHELLY_GEN1), config = mapOf("relay" to "1"))
        val r = LanHttpAdapter(http).execute(d, DeviceAction(DeviceActionType.POWER_OFF))
        assertTrue(r is CommandResult.Success && r.verified && r.poweredOn == false)
        assertEquals("http://192.168.1.51/relay/1?turn=off", http.urls.single())
    }

    @Test fun unreachableDeviceIsOffline() {
        val r = LanHttpAdapter(FakeHttp { throw IOException("no route") }).execute(tasmota(), DeviceAction(DeviceActionType.POWER_ON))
        assertTrue(r is CommandResult.Failure && r.reason == FailureReason.OFFLINE)
    }

    @Test fun timeoutIsReportedAsTimeout() {
        val r = LanHttpAdapter(FakeHttp { throw SocketTimeoutException() }).execute(tasmota(), DeviceAction(DeviceActionType.POWER_ON))
        assertTrue(r is CommandResult.Failure && r.reason == FailureReason.TIMEOUT)
    }

    @Test fun http401IsPermission() {
        val r = LanHttpAdapter(FakeHttp { throw HttpStatusException(401) }).execute(tasmota(), DeviceAction(DeviceActionType.POWER_ON))
        assertTrue(r is CommandResult.Failure && r.reason == FailureReason.PERMISSION)
    }

    @Test fun genericWithoutStatusIsNeverClaimedConfirmed() {
        val d = dev("3", "Pump").copy(protocol = DeviceProtocol.GENERIC_HTTP, address = "10.0.0.9", capabilities = setOf(Capability.POWER),
            config = mapOf("url_on" to "http://10.0.0.9/on", "url_off" to "http://10.0.0.9/off"))
        val r = LanHttpAdapter(FakeHttp { "OK" }).execute(d, DeviceAction(DeviceActionType.POWER_ON))
        assertTrue(r is CommandResult.Success && !r.verified)
    }

    @Test fun genericWithStatusRegexIsVerified() {
        val d = dev("3", "Pump").copy(protocol = DeviceProtocol.GENERIC_HTTP, address = "10.0.0.9", capabilities = setOf(Capability.POWER, Capability.READ_STATE),
            config = mapOf("url_on" to "http://10.0.0.9/on", "url_off" to "http://10.0.0.9/off", "url_status" to "http://10.0.0.9/s", "status_on_regex" to "state=on"))
        val http = FakeHttp { if (it.endsWith("/s")) "state=on" else "OK" }
        val r = LanHttpAdapter(http).execute(d, DeviceAction(DeviceActionType.POWER_ON))
        assertTrue(r is CommandResult.Success && r.verified && r.poweredOn == true)
    }

    @Test fun genericMissingUrlIsNotConfigured() {
        val d = dev("3", "Pump").copy(protocol = DeviceProtocol.GENERIC_HTTP, address = "10.0.0.9", capabilities = setOf(Capability.POWER))
        val r = LanHttpAdapter(FakeHttp { "OK" }).execute(d, DeviceAction(DeviceActionType.POWER_ON))
        assertTrue(r is CommandResult.Failure && r.reason == FailureReason.NOT_CONFIGURED)
    }

    @Test fun brightnessUnsupportedOnShelly() {
        val d = dev("2", "Shelly").copy(protocol = DeviceProtocol.SHELLY_GEN1, address = "x", capabilities = setOf(Capability.POWER, Capability.BRIGHTNESS))
        val r = LanHttpAdapter(FakeHttp { "{}" }).execute(d, DeviceAction(DeviceActionType.SET_BRIGHTNESS, 40))
        assertTrue(r is CommandResult.Failure && r.reason == FailureReason.UNSUPPORTED)
    }

    @Test fun tasmotaBrightnessIsConfirmedByReadback() {
        val http = FakeHttp { if (it.endsWith("Dimmer")) """{"Dimmer":40}""" else """{"Dimmer":40}""" }
        val r = LanHttpAdapter(http).execute(tasmota(), DeviceAction(DeviceActionType.SET_BRIGHTNESS, 40))
        assertTrue(r is CommandResult.Success && r.verified)
    }

    @Test fun readStateOfDeadHostIsOffline() {
        val s = LanHttpAdapter(FakeHttp { throw IOException("x") }).readState(tasmota())
        assertFalse(s.online)
    }

    @Test fun parsersHandleGarbage() {
        assertNull(LanHttpAdapter.parseTasmotaPower("not json"))
        assertNull(LanHttpAdapter.parseTasmotaPower("""{"Other":1}"""))
        assertEquals(true, LanHttpAdapter.parseTasmotaPower("""{"POWER1":"ON"}"""))
        assertNull(LanHttpAdapter.parseShellyIsOn("{}"))
    }

    @Test fun probeIdentifiesTasmotaAndShellyAndRejectsOthers() {
        val tas = LanProbe.identify("10.0.0.2", FakeHttp { """{"Status":{"FriendlyName":["Kitchen plug"]},"StatusFWR":{"Version":"13.0"}}""" })!!
        assertEquals(DeviceProtocol.TASMOTA, tas.protocol); assertEquals("Kitchen plug", tas.name)
        val sh = LanProbe.identify("10.0.0.3", FakeHttp { if (it.endsWith("/shelly")) """{"type":"SHSW-1"}""" else throw HttpStatusException(404) })!!
        assertEquals(DeviceProtocol.SHELLY_GEN1, sh.protocol)
        assertNull(LanProbe.identify("10.0.0.4", FakeHttp { throw HttpStatusException(404) }))
    }

    @Test fun hexParsing() {
        assertArrayEquals(byteArrayOf(0x01, 0xA0.toByte()), hexToBytes("01 a0"))
        assertNull(hexToBytes("0"))
        assertNull(hexToBytes("zz"))
        assertNull(hexToBytes(""))
    }
}
