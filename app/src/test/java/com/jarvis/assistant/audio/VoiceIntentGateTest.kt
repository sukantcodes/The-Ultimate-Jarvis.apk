package com.jarvis.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceIntentGateTest {
    @Test
    fun requiresJarvisAtBeginningOfUtterance() {
        assertTrue(VoiceIntentGate.hasWakePrefix("Jarvis, open WhatsApp"))
        assertTrue(VoiceIntentGate.hasWakePrefix("Hey Jarvis do this"))
        assertTrue(VoiceIntentGate.hasWakePrefix("okay JARVIS: call Mom"))
        assertFalse(VoiceIntentGate.hasWakePrefix("I was talking to Jarvis yesterday"))
        assertFalse(VoiceIntentGate.hasWakePrefix("Please open WhatsApp"))
    }

    @Test
    fun identifiesWakeOnlyPhraseAndExtractsRequest() {
        assertTrue(VoiceIntentGate.isWakeOnly("Hey Jarvis"))
        assertTrue(VoiceIntentGate.isWakeOnly("Jarvis!"))
        assertFalse(VoiceIntentGate.isWakeOnly("Jarvis, play music"))
        assertTrue(VoiceIntentGate.stripWakePrefix("Hey Jarvis, please open Spotify").contains("please open spotify"))
    }

    @Test
    fun oneShotTokenAuthorizesOnlyWhenCallerSuppliesIt() {
        assertFalse(VoiceIntentGate.isAuthorized("call Mom"))
        assertTrue(VoiceIntentGate.isAuthorized("call Mom", oneShotWakeToken = true))
        assertTrue(VoiceIntentGate.isAuthorized("Jarvis, call Mom"))
    }
}
