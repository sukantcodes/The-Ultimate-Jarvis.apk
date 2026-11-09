package com.jarvis.assistant.automation.blinkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkitNotificationTrackerTest {
    @Test fun parsesOnlyRecognizableOrderProgressNotifications() {
        assertEquals(OrderStage.PLACED, BlinkitNotificationTracker.parseStage("Your order is confirmed"))
        assertEquals(OrderStage.PREPARING, BlinkitNotificationTracker.parseStage("We are preparing your order"))
        assertEquals(OrderStage.PARTNER_ASSIGNED, BlinkitNotificationTracker.parseStage("A delivery partner has been assigned"))
        assertEquals(OrderStage.ON_THE_WAY, BlinkitNotificationTracker.parseStage("Your order is out for delivery"))
        assertEquals(OrderStage.ARRIVED, BlinkitNotificationTracker.parseStage("Rider reached your location"))
        assertEquals(OrderStage.DELIVERED, BlinkitNotificationTracker.parseStage("Your order has been delivered"))
        assertEquals(OrderStage.CANCELLED, BlinkitNotificationTracker.parseStage("Your order was cancelled"))
    }

    @Test fun ignoresPromotionsAndUnclearNotifications() {
        assertEquals(null, BlinkitNotificationTracker.parseStage("Big sale today, get 50% off"))
        assertEquals(null, BlinkitNotificationTracker.parseStage("Your wallet balance has changed"))
        assertEquals(null, BlinkitNotificationTracker.parseStage(""))
    }

    @Test fun notificationsAreNotAssignedToAnArbitraryOrder() {
        assertEquals(0, BlinkitNotificationTracker.resolveOrderIndex(listOf("local-order-1"), null))
        assertEquals(null, BlinkitNotificationTracker.resolveOrderIndex(listOf("local-order-1", "local-order-2"), null))
        assertEquals(1, BlinkitNotificationTracker.resolveOrderIndex(listOf("BLK-12345", "BLK-67890"), "blk-67890"))
        assertEquals(null, BlinkitNotificationTracker.resolveOrderIndex(listOf("BLK-12345", "BLK-67890"), "BLK-99999"))
    }

    @Test fun stageCannotRegressOrChangeAfterTerminalState() {
        assertTrue(BlinkitNotificationTracker.shouldAdvance(OrderStage.PLACED, OrderStage.ON_THE_WAY))
        assertFalse(BlinkitNotificationTracker.shouldAdvance(OrderStage.ON_THE_WAY, OrderStage.PREPARING))
        assertFalse(BlinkitNotificationTracker.shouldAdvance(OrderStage.DELIVERED, OrderStage.PREPARING))
        assertFalse(BlinkitNotificationTracker.shouldAdvance(OrderStage.CANCELLED, OrderStage.ON_THE_WAY))
        assertTrue(BlinkitNotificationTracker.shouldAdvance(OrderStage.PREPARING, OrderStage.DELIVERED))
    }
}
