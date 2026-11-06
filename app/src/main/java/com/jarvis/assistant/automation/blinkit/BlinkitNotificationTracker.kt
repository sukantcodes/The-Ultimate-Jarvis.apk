package com.jarvis.assistant.automation.blinkit

import android.content.Context

/**
 * Tracks only explicit Blinkit order-status notifications after the user asks JARVIS to monitor an order
 * or JARVIS has verified that it placed one. It never launches Blinkit or reads other apps' notifications.
 */
object BlinkitNotificationTracker {
    private const val PREFS = "jarvis_blinkit_order_monitor"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STAGE = "stage"
    private const val KEY_UPDATED_AT = "updated_at"

    fun parseStage(notificationText: String): OrderStage? {
        val text = notificationText.lowercase().replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return null

        return when {
            (text.contains("order") || text.contains("delivery")) &&
                (text.contains("cancelled") || text.contains("canceled")) -> OrderStage.CANCELLED
            (text.contains("order") || text.contains("delivery")) &&
                text.contains("delivered") -> OrderStage.DELIVERED
            text.contains("out for delivery") || text.contains("on the way") ||
                text.contains("rider is on the way") || text.contains("delivery partner is on the way") -> OrderStage.ON_THE_WAY
            text.contains("reached your location") || text.contains("at your doorstep") ||
                text.contains("arrived at your") -> OrderStage.ARRIVED
            text.contains("delivery partner assigned") || text.contains("rider assigned") ||
                text.contains("partner has been assigned") -> OrderStage.PARTNER_ASSIGNED
            text.contains("preparing your order") || text.contains("order is being prepared") ||
                text.contains("packing your order") || text.contains("getting your order ready") -> OrderStage.PREPARING
            text.contains("order placed") || text.contains("order confirmed") ||
                text.contains("we've received your order") || text.contains("we have received your order") -> OrderStage.PLACED
            else -> null
        }
    }

    fun shouldAdvance(previous: OrderStage, next: OrderStage): Boolean {
        if (previous == next) return false
        if (previous == OrderStage.DELIVERED || previous == OrderStage.CANCELLED) return false
        if (next == OrderStage.CANCELLED || next == OrderStage.DELIVERED) return true
        return rank(next) >= rank(previous)
    }

    private fun rank(stage: OrderStage): Int = when (stage) {
        OrderStage.UNKNOWN -> 0
        OrderStage.PLACED -> 1
        OrderStage.PREPARING -> 2
        OrderStage.PARTNER_ASSIGNED -> 3
        OrderStage.ON_THE_WAY -> 4
        OrderStage.ARRIVED -> 5
        OrderStage.DELIVERED, OrderStage.CANCELLED -> 6
    }

    fun beginTracking(context: Context, initialStage: OrderStage = OrderStage.PLACED) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_STAGE, initialStage.name)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun stopTracking(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, false)
            .apply()
    }

    /** Returns a short update only when an active, explicitly enabled order advances. */
    fun onNotification(context: Context, packageName: String, title: String, body: String): String? {
        if (!BlinkitLinks.isBlinkitPackage(packageName)) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return null
        val next = parseStage("$title $body") ?: return null
        val previous = runCatching {
            OrderStage.valueOf(prefs.getString(KEY_STAGE, OrderStage.PLACED.name) ?: OrderStage.PLACED.name)
        }.getOrDefault(OrderStage.PLACED)
        if (!shouldAdvance(previous, next)) return null

        prefs.edit()
            .putString(KEY_STAGE, next.name)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .putBoolean(KEY_ENABLED, next != OrderStage.DELIVERED && next != OrderStage.CANCELLED)
            .apply()

        return when (next) {
            OrderStage.PLACED -> "Blinkit confirms your order is placed."
            OrderStage.PREPARING -> "Blinkit says your order is being prepared."
            OrderStage.PARTNER_ASSIGNED -> "Blinkit has assigned a delivery partner."
            OrderStage.ON_THE_WAY -> "Your Blinkit order is on the way."
            OrderStage.ARRIVED -> "Your Blinkit delivery partner has reached your location."
            OrderStage.DELIVERED -> "Blinkit reports that your order has been delivered."
            OrderStage.CANCELLED -> "Blinkit reports that your order has been cancelled."
            OrderStage.UNKNOWN -> null
        }
    }

    fun isTracking(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun lastStage(context: Context): OrderStage =
        runCatching {
            OrderStage.valueOf(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_STAGE, OrderStage.UNKNOWN.name) ?: OrderStage.UNKNOWN.name
            )
        }.getOrDefault(OrderStage.UNKNOWN)
}
