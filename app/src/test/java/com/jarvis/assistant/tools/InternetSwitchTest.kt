package com.jarvis.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetSwitchTest {
    private class MapStore : PermissionPolicy.SettingsStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    @Test fun internet_is_allowed_by_default() {
        val p = PermissionPolicy(MapStore())
        assertTrue(p.isInternetAllowed())
        assertEquals(Mode.ALLOW, p.modeFor(Risk.NETWORK, "web"))
    }

    @Test fun switching_it_off_denies_network_tools_even_under_auto_approve() {
        val p = PermissionPolicy(MapStore())
        p.setAutoApproveAll(true)
        p.setInternetAllowed(false)
        assertFalse(p.isInternetAllowed())
        assertEquals(Mode.DENY, p.modeFor(Risk.NETWORK, "web"))
        assertEquals(Mode.ALLOW, p.modeFor(Risk.READ, "files"))
        p.setInternetAllowed(true)
        assertEquals(Mode.ALLOW, p.modeFor(Risk.NETWORK, "web"))
    }
}
