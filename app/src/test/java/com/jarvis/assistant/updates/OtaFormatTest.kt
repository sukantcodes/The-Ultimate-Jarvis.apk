package com.jarvis.assistant.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class OtaFormatTest {
    @Test
    fun percentIsClampedAndSafeForUnknownSize() {
        assertEquals(0, OtaFormat.percent(0, 0))
        assertEquals(0, OtaFormat.percent(50, -1))
        assertEquals(50, OtaFormat.percent(75_000_000, 150_000_000))
        assertEquals(100, OtaFormat.percent(200, 100))
        assertEquals(0, OtaFormat.percent(-5, 100))
    }

    @Test
    fun byteAndSpeedFormatting() {
        assertEquals("150.0 MB", OtaFormat.bytes(150L * 1024 * 1024))
        assertEquals("1.50 GB", OtaFormat.bytes((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("512 KB", OtaFormat.bytes(512L * 1024))
        assertEquals("12 B", OtaFormat.bytes(12))
        assertEquals("3.0 MB/s", OtaFormat.speed(3.0 * 1024 * 1024))
        assertEquals("— MB/s", OtaFormat.speed(0.0))
    }

    @Test
    fun etaFormatting() {
        assertEquals("", OtaFormat.eta(null))
        assertEquals("42s left", OtaFormat.eta(42))
        assertEquals("2m 5s left", OtaFormat.eta(125))
        assertEquals("1h 1m left", OtaFormat.eta(3700))
    }

    @Test
    fun publishedDateConvertsToTheGivenZoneAndFallsBack() {
        assertEquals(
            "22 Nov 2026, 17:30",
            OtaFormat.published("2026-11-22T12:00:00.123456+00:00", ZoneId.of("Asia/Kolkata"), Locale.US)
        )
        assertEquals("2026-11-22", OtaFormat.published("2026-11-22 not a timestamp", ZoneId.of("UTC"), Locale.US))
        assertEquals("", OtaFormat.published("", ZoneId.of("UTC"), Locale.US))
    }

    @Test
    fun shortHash() {
        assertEquals("ABCDEF01…23456789", OtaFormat.shortHash("ABCDEF01" + "0".repeat(48) + "23456789"))
        assertEquals("AB", OtaFormat.shortHash("AB"))
    }

    @Test
    fun speedMeterSmoothsAndHandlesRestarts() {
        val meter = SpeedMeter(smoothing = 0.5)
        assertEquals(0.0, meter.sample(0, 0), 0.0)
        // 1 MB in one second.
        assertEquals(1_000_000.0, meter.sample(1_000_000, 1_000), 1.0)
        // Another 3 MB in a second: smoothed halfway between 1 MB/s and 3 MB/s.
        assertEquals(2_000_000.0, meter.sample(4_000_000, 2_000), 1.0)
        // Calls closer together than 250 ms keep the last value.
        assertEquals(2_000_000.0, meter.sample(4_100_000, 2_100), 1.0)
        // A download that restarts from zero resets the meter rather than going negative.
        assertEquals(0.0, meter.sample(10, 3_000), 0.0)
        assertNull(meter.etaSeconds(1_000_000))
    }

    @Test
    fun etaFromMeter() {
        val meter = SpeedMeter(smoothing = 1.0)
        meter.sample(0, 0)
        meter.sample(2_000_000, 1_000)
        assertEquals(50L, meter.etaSeconds(100_000_000))
        assertNull(meter.etaSeconds(0))
    }

    @Test
    fun historyRoundTripsAndIsCapped() {
        var list = emptyList<OtaHistoryEntry>()
        for (i in 1..40) list = OtaHistoryCodec.append(list, OtaHistoryEntry(i.toLong(), 1000L + i, "v$i", "Antigravity", "installed", "ok"))
        assertEquals(OtaHistoryCodec.MAX_ENTRIES, list.size)
        assertEquals(11L, list.first().timeMs)
        assertEquals(40L, list.last().timeMs)
        val decoded = OtaHistoryCodec.decode(OtaHistoryCodec.encode(list))
        assertEquals(list, decoded)
    }

    @Test
    fun historyDecodeToleratesGarbage() {
        assertTrue(OtaHistoryCodec.decode(null).isEmpty())
        assertTrue(OtaHistoryCodec.decode("").isEmpty())
        assertTrue(OtaHistoryCodec.decode("{not an array").isEmpty())
        assertEquals(1, OtaHistoryCodec.decode("[{\"t\":1,\"c\":2,\"n\":\"x\",\"b\":\"b\",\"e\":\"e\",\"d\":\"d\"}, 5]").size)
    }
}
