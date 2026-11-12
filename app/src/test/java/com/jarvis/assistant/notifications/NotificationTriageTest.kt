package com.jarvis.assistant.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTriageTest {

    @Test
    fun testUrgentMessageDetection() {
        assertTrue(MessageNotificationParser.isUrgent("Rahul", "Please call me back immediately, it's urgent!"))
        assertTrue(MessageNotificationParser.isUrgent("Doctor", "This is an emergency alert regarding results"))
        assertTrue(MessageNotificationParser.isUrgent("Boss", "Need you now asap for production issue"))
        assertFalse(MessageNotificationParser.isUrgent("Alex", "Hey, are you free this evening for coffee?"))
        assertFalse(MessageNotificationParser.isUrgent("Priya", "Did you check the latest photo on Instagram?"))
    }

    @Test
    fun testSpamAndPromotionDetection() {
        assertTrue(MessageNotificationParser.isSpam("Dominos", "Flat 50% off on all medium pizzas today! Use code PIZZA50"))
        assertTrue(MessageNotificationParser.isSpam("Store", "Congratulations you've won cashback up to ₹500!"))
        assertTrue(MessageNotificationParser.isSpam("Brand", "Mega Sale starts now! Click here to claim your discount"))
        assertFalse(MessageNotificationParser.isSpam("Aman", "Hey Rahul, let's catch up at 6 PM."))
        assertFalse(MessageNotificationParser.isSpam("Mom", "Have you had lunch yet?"))
    }

    @Test
    fun testCleaningWhitespaceAndUnicodeHandles() {
        assertEquals("Rahul Kumar", MessageNotificationParser.clean("Rahul Kumar  (2 messages)"))
        assertEquals("John Doe", MessageNotificationParser.clean("  John Doe (5)  "))
        assertEquals("Tech Group", MessageNotificationParser.clean("\u200eTech Group\u200f"))
    }

    @Test
    fun testSummaryDetection() {
        assertTrue(MessageNotificationParser.isSummary("WhatsApp", "3 new messages from 2 chats"))
        assertTrue(MessageNotificationParser.isSummary("WhatsApp", "Checking for new messages"))
        assertTrue(MessageNotificationParser.isSummary("WhatsApp", "Backup in progress"))
        assertFalse(MessageNotificationParser.isSummary("Rahul", "Hey let's meet tomorrow"))
    }

    @Test
    fun testAppEnumDefinitions() {
        assertEquals("WhatsApp", MessageApp.WHATSAPP.displayName)
        assertEquals("Telegram", MessageApp.TELEGRAM.displayName)
        assertEquals("Instagram", MessageApp.INSTAGRAM.displayName)
        assertEquals("Messages", MessageApp.SMS.displayName)
        assertEquals("Slack", MessageApp.SLACK.displayName)
        assertEquals("Discord", MessageApp.DISCORD.displayName)
    }
}
