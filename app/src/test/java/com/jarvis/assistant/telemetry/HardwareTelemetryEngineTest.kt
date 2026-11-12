package com.jarvis.assistant.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareTelemetryEngineTest {

    @Test
    fun testNominalIronManSpeechReport() {
        val snapshot = FullTelemetrySnapshot(
            cpu = CpuMetrics(coreCount = 8, maxFreqMhz = 2840, currentFreqMhz = 1780, loadPercent = 18),
            memory = MemoryMetrics(
                totalBytes = 8L * 1024 * 1024 * 1024,
                availableBytes = 4L * 1024 * 1024 * 1024,
                usedBytes = 4L * 1024 * 1024 * 1024,
                usedFraction = 0.50f
            ),
            battery = BatteryMetrics(
                levelPercent = 82,
                isCharging = false,
                chargeType = "On Battery",
                voltageMv = 3950,
                temperatureCelsius = 31.5f,
                thermalStatus = ThermalStatus.NOMINAL
            ),
            storage = StorageMetrics(
                totalBytes = 128L * 1024 * 1024 * 1024,
                freeBytes = 64L * 1024 * 1024 * 1024,
                usedBytes = 64L * 1024 * 1024 * 1024,
                usedFraction = 0.50f
            ),
            network = NetworkMetrics(
                typeName = "Wi-Fi",
                isConnected = true,
                rxSpeedKbps = 1250L,
                txSpeedKbps = 320L
            )
        )

        val report = HardwareTelemetryEngine.generateIronManSpeechReport(snapshot)
        assertTrue(report.startsWith("All systems operational, Sir."))
        assertTrue(report.contains("CPU load is at 18% across 8 cores"))
        assertTrue(report.contains("memory utilization is 50% with 4.0 gigabytes free"))
        assertTrue(report.contains("Battery stands at 82%, operating at a cool 31.5 degrees Celsius on battery power"))
        assertTrue(report.contains("Internal storage has 64.0 gigabytes available"))
        assertFalse(report.contains("Warning Sir: Battery thermal levels are elevated"))
    }

    @Test
    fun testOverheatThermalWarningSpeechReport() {
        val snapshot = FullTelemetrySnapshot(
            cpu = CpuMetrics(coreCount = 8, maxFreqMhz = 2840, currentFreqMhz = 2840, loadPercent = 92),
            memory = MemoryMetrics(
                totalBytes = 8L * 1024 * 1024 * 1024,
                availableBytes = 1L * 1024 * 1024 * 1024,
                usedBytes = 7L * 1024 * 1024 * 1024,
                usedFraction = 0.88f
            ),
            battery = BatteryMetrics(
                levelPercent = 95,
                isCharging = true,
                chargeType = "AC Fast Charging",
                voltageMv = 4350,
                temperatureCelsius = 43.8f,
                thermalStatus = ThermalStatus.CRITICAL_OVERHEAT
            ),
            storage = StorageMetrics(
                totalBytes = 128L * 1024 * 1024 * 1024,
                freeBytes = 12L * 1024 * 1024 * 1024,
                usedBytes = 116L * 1024 * 1024 * 1024,
                usedFraction = 0.90f
            ),
            network = NetworkMetrics(
                typeName = "Cellular 5G/LTE",
                isConnected = true,
                rxSpeedKbps = 4800L,
                txSpeedKbps = 1100L
            )
        )

        val report = HardwareTelemetryEngine.generateIronManSpeechReport(snapshot)
        assertTrue(report.startsWith("Warning Sir: Battery thermal levels are elevated at 43.8 degrees Celsius"))
        assertTrue(report.contains("on AC fast charging"))
    }

    @Test
    fun testShortStatusFormat() {
        val snapshot = FullTelemetrySnapshot(
            cpu = CpuMetrics(coreCount = 8, maxFreqMhz = 2400, currentFreqMhz = 1400, loadPercent = 25),
            memory = MemoryMetrics(
                totalBytes = 6L * 1024 * 1024 * 1024,
                availableBytes = 3L * 1024 * 1024 * 1024,
                usedBytes = 3L * 1024 * 1024 * 1024,
                usedFraction = 0.50f
            ),
            battery = BatteryMetrics(
                levelPercent = 65,
                isCharging = false,
                chargeType = "On Battery",
                voltageMv = 3800,
                temperatureCelsius = 29.2f,
                thermalStatus = ThermalStatus.NOMINAL
            ),
            storage = StorageMetrics(
                totalBytes = 64L * 1024 * 1024 * 1024,
                freeBytes = 32L * 1024 * 1024 * 1024,
                usedBytes = 32L * 1024 * 1024 * 1024,
                usedFraction = 0.50f
            ),
            network = NetworkMetrics(
                typeName = "Wi-Fi",
                isConnected = true,
                rxSpeedKbps = 0L,
                txSpeedKbps = 0L
            )
        )

        val shortStatus = HardwareTelemetryEngine.getShortStatus(snapshot)
        assertEquals("CPU 25% • RAM 50% • 29.2°C", shortStatus)
    }
}
