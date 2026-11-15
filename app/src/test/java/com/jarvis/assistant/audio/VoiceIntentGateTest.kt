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

    @Test
    fun acceptsSunoJarvisAndHindiForms() {
        assertTrue(VoiceIntentGate.hasWakePrefix("Suno Jarvis"))
        assertTrue(VoiceIntentGate.hasWakePrefix("suno jarvis, open YouTube"))
        assertTrue(VoiceIntentGate.hasWakePrefix("Sun Jarvis"))
        assertTrue(VoiceIntentGate.hasWakePrefix("सुनो जार्विस"))
        assertTrue(VoiceIntentGate.hasWakePrefix("जार्विस"))
        assertTrue(VoiceIntentGate.isWakeOnly("Suno Jarvis"))
        assertTrue(VoiceIntentGate.stripWakePrefix("Suno Jarvis, open YouTube").contains("open youtube"))
    }

    @Test
    fun stillIgnoresJarvisInTheMiddleOfASentence() {
        assertFalse(VoiceIntentGate.hasWakePrefix("I told suno to Jarvis"))
        assertFalse(VoiceIntentGate.hasWakePrefix("jarvisx open it"))
    }
}
