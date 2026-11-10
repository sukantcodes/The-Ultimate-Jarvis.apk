package com.jarvis.assistant.automation.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTaskVerifierTest {
    private fun toggle(label: String, checked: Boolean) =
        LocalTaskVerifier.ObservedElement(label, true, checked, 900, 300)
    private fun setting(label: String) =
        LocalTaskVerifier.ObservedElement(label, false, false, 300, 300)

    @Test fun verifiesRequestedEnabledStateFromUniqueVisibleToggle() {
        val result = LocalTaskVerifier.verifyToggle(
            "Enable Wireless Debugging in Android Settings",
            listOf(setting("Wireless Debugging"), toggle("Switch", true))
        )
        assertTrue(result.applicable); assertTrue(result.verified)
    }

    @Test fun rejectsToggleWhenActualStateDoesNotMatchGoal() {
        val result = LocalTaskVerifier.verifyToggle(
            "Turn off Bluetooth", listOf(setting("Bluetooth"), toggle("Bluetooth", true))
        )
        assertTrue(result.applicable); assertFalse(result.verified)
    }

    @Test fun rejectsAmbiguousMatchingSettingsInsteadOfGuessing() {
        val result = LocalTaskVerifier.verifyToggle(
            "Enable Bluetooth",
            listOf(setting("Bluetooth"), setting("Bluetooth scanning"), toggle("Switch one", true), toggle("Switch two", true))
        )
        assertTrue(result.applicable); assertFalse(result.verified)
    }

    @Test fun rejectsWhenMatchingLabelHasNoAssociatedToggle() {
        val result = LocalTaskVerifier.verifyToggle("Enable NFC", listOf(setting("NFC")))
        assertTrue(result.applicable); assertFalse(result.verified)
    }

    @Test fun ignoresNonToggleGoals() {
        val result = LocalTaskVerifier.verifyToggle(
            "Open YouTube and play a video", listOf(toggle("Wi-Fi", true))
        )
        assertFalse(result.applicable); assertFalse(result.verified)
    }
}
