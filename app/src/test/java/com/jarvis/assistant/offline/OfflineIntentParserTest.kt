package com.jarvis.assistant.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineIntentParserTest {
    private fun parse(s: String) = OfflineIntentParser.parse(s)

    @Test
    fun stripsWakeWordAndPoliteFiller() {
        assertEquals(OfflineIntent.LockScreen, parse("Hey Jarvis, please lock my phone."))
        assertEquals(OfflineIntent.LockScreen, parse("jarvis lock the screen"))
        assertEquals(OfflineIntent.UnlockScreen, parse("hey jarvis unlock my phone"))
    }

    @Test
    fun wakePhraseAloneIsNotACommand() {
        assertTrue(OfflineIntentParser.isOnlyWakePhrase("hey jarvis"))
        assertNull(parse("hey jarvis"))
    }

    @Test
    fun opensAppsAndSettings() {
        assertEquals(OfflineIntent.OpenApp("whatsapp", true), parse("hey jarvis open whatsapp"))
        assertEquals(OfflineIntent.OpenSettings("wifi", true), parse("turn on wifi"))
        assertEquals(OfflineIntent.OpenSettings("bluetooth", null), parse("open bluetooth settings"))
    }

    @Test
    fun timersAndAlarms() {
        assertEquals(OfflineIntent.SetTimer(300), parse("set a timer for five minutes"))
        assertEquals(OfflineIntent.SetAlarm(6, 30, false), parse("set an alarm for six thirty am"))
        assertEquals(OfflineIntent.SetAlarm(7, 0, true), parse("wake me up at seven"))
    }

    @Test
    fun messagesAndCalls() {
        assertEquals(OfflineIntent.SendSms("rahul", "i am late"), parse("send a message to rahul saying i am late"))
        assertEquals(OfflineIntent.CallContact("mom"), parse("call mom"))
        assertNull(parse("send a whatsapp message to rahul saying hi"))
    }

    @Test
    fun standbyAndStop() {
        assertEquals(OfflineIntent.GoStandby, parse("go to sleep"))
        assertEquals(OfflineIntent.StopSpeaking, parse("stop"))
    }

    @Test
    fun infoQuestions() {
        assertEquals(OfflineIntent.TellTime, parse("what time is it"))
        assertEquals(OfflineIntent.TellBattery, parse("how much battery is left"))
    }

    @Test
    fun openEndedQuestionsFallThroughToTheModel() {
        assertNull(parse("tell me a joke about cats"))
        assertNull(parse("what is the capital of france"))
    }
}
