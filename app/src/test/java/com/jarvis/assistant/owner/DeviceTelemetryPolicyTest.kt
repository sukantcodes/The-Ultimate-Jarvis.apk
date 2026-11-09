package com.jarvis.assistant.owner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTelemetryPolicyTest {
    @Test
    fun successfulResponsesAllowAccess() {
        assertTrue(DeviceTelemetry.isAccessAllowedForStatusCode(200))
        assertTrue(DeviceTelemetry.isAccessAllowedForStatusCode(204))
    }

    @Test
    fun authenticationAndPolicyDenialsDoNotAllowAccess() {
        assertFalse(DeviceTelemetry.isAccessAllowedForStatusCode(401))
        assertFalse(DeviceTelemetry.isAccessAllowedForStatusCode(403))
    }

    @Test
    fun transientServerAndRateLimitResponsesFailOpen() {
        assertTrue(DeviceTelemetry.isAccessAllowedForStatusCode(429))
        assertTrue(DeviceTelemetry.isAccessAllowedForStatusCode(500))
        assertTrue(DeviceTelemetry.isAccessAllowedForStatusCode(503))
    }

    @Test
    fun unexpectedClientErrorsDoNotAllowAccess() {
        assertFalse(DeviceTelemetry.isAccessAllowedForStatusCode(400))
        assertFalse(DeviceTelemetry.isAccessAllowedForStatusCode(404))
    }
}
