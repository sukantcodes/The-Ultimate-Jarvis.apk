package com.jarvis.assistant.udn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdnReplyComposerTest {
    private fun device(name: String = "Redmi Pad", online: Boolean = true, battery: Int? = null, seenAgoMs: Long = 0) = UdnDevice(
        "d1", "android", name, "Xiaomi", "Pad", "Android 14", "1.0", "authorized", battery, null, "wifi", null, null,
        System.currentTimeMillis() - seenAgoMs, null, online, "fp", emptyList()
    )

    private fun record(status: String, result: JSONObject? = null, code: String? = null, msg: String? = null) =
        UdnCommandRecord("c1", "s", "d1", "x.y", status, null, null, result, code, msg)

    @Test fun successIsOnlyReportedForSucceededRecords() {
        val ok = UdnReplyComposer.outcome(UdnLang.EN, device(), UdnCapabilities.DEVICE_BATTERY, record("succeeded", JSONObject().put("percent", 64).put("charging", false)))
        assertEquals("Redmi Pad is at 64%.", ok)
        for (status in listOf("failed", "rejected", "expired", "timed_out", "cancelled", "queued", "delivered", "running")) {
            val text = UdnReplyComposer.outcome(UdnLang.EN, device(), UdnCapabilities.DEVICE_LOCK, record(status, code = status))
            assertFalse("status $status must not sound like success: $text", text.contains("is locked"))
            assertTrue(text.startsWith("That didn't work on Redmi Pad"))
        }
    }

    @Test fun launchNotConfirmedIsNotReportedAsOpened() {
        val t = UdnReplyComposer.outcome(UdnLang.EN, device(), UdnCapabilities.MODULE_OPEN,
            record("succeeded", JSONObject().put("label", "Chatbox").put("launched", false).put("method", "notification")))
        assertFalse(t.startsWith("Opened"))
        assertTrue(t.contains("tap-to-open"))
        val opened = UdnReplyComposer.outcome(UdnLang.EN, device(), UdnCapabilities.MODULE_OPEN, record("succeeded", JSONObject().put("label", "Chatbox").put("launched", true)))
        assertEquals("Opened Chatbox on Redmi Pad.", opened)
    }

    @Test fun offlineDeviceReportsLastSeenAndLastBattery() {
        val t = UdnReplyComposer.offline(UdnLang.EN, device(online = false, battery = 54, seenAgoMs = 3 * 3600_000L))
        assertTrue(t, t.contains("offline") && t.contains("3 hours ago") && t.contains("54%"))
    }

    @Test fun consentDeniedAndTimeoutAreExplained() {
        assertTrue(UdnReplyComposer.outcome(UdnLang.EN, device(), UdnCapabilities.CAMERA_OPEN, record("rejected", code = "consent_denied")).contains("declined"))
        assertTrue(UdnReplyComposer.outcome(UdnLang.EN, device(), UdnCapabilities.CAMERA_OPEN, record("rejected", code = "consent_timeout")).contains("in time"))
    }

    @Test fun languagesAreDetected() {
        assertEquals(UdnLang.EN, UdnReplyComposer.detectLanguage("open chatbox on my tablet"))
        assertEquals(UdnLang.HINGLISH, UdnReplyComposer.detectLanguage("mere tablet par chatbox kholo"))
        assertEquals(UdnLang.HI, UdnReplyComposer.detectLanguage("मेरे टैबलेट पर चैटबॉक्स खोलो"))
    }

    @Test fun hinglishAndHindiRepliesAreLocalised() {
        val rec = record("succeeded", JSONObject().put("percent", 64).put("charging", false))
        assertEquals("Redmi Pad ki battery 64% hai.", UdnReplyComposer.outcome(UdnLang.HINGLISH, device(), UdnCapabilities.DEVICE_BATTERY, rec))
        assertTrue(UdnReplyComposer.outcome(UdnLang.HI, device(), UdnCapabilities.DEVICE_BATTERY, rec).contains("बैटरी"))
    }

    @Test fun deviceListNamesEachDevice() {
        val text = UdnReplyComposer.listDevices(UdnLang.EN, listOf(device("Redmi Pad"), device("Old Phone", online = false)), "other")
        assertTrue(text, text.startsWith("You have 2 devices, 1 online"))
        assertTrue(text.contains("Old Phone") && text.contains("offline"))
    }
}
