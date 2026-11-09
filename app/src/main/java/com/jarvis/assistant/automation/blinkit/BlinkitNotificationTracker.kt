package com.jarvis.assistant.automation.blinkit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Tracks only explicit Blinkit order-status notifications after the user asks JARVIS to monitor an order
 * or JARVIS has verified that it placed one. It never launches Blinkit or reads other apps' notifications.
 */
object BlinkitNotificationTracker {
    private const val PREFS = "jarvis_blinkit_order_monitor"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STAGE = "stage"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_ORDERS = "tracked_orders"
    private const val MAX_TRACKED_ORDERS = 20
    private const val ORDER_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L

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
                text.contains("order is confirmed") || text.contains("we've received your order") ||
                text.contains("we have received your order") -> OrderStage.PLACED
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

    /** Starts a separate tracking record. A verified external order ID is used when available. */
    fun beginTracking(context: Context, initialStage: OrderStage = OrderStage.PLACED, orderId: String? = null) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val orders = readOrders(prefs)
        val cleanOrderId = normalizeOrderId(orderId)
        val existing = cleanOrderId?.let { id ->
            (0 until orders.length()).firstOrNull { orders.optJSONObject(it)?.optString("order_id").orEmpty() == id }
        }
        val now = System.currentTimeMillis()
        if (existing != null) {
            orders.optJSONObject(existing)?.put("stage", initialStage.name)?.put("updated_at", now)
        } else {
            orders.put(JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("order_id", cleanOrderId.orEmpty())
                .put("stage", initialStage.name)
                .put("updated_at", now))
        }
        saveOrders(prefs, orders, now)
    }

    /**
     * Updates a status-query result only when its order can be identified. Without an order ID,
     * a stage may be updated only if exactly one order is being monitored.
     */
    fun updateLatestStage(context: Context, stage: OrderStage, orderId: String? = null) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val orders = readOrders(prefs)
        val activeIndices = (0 until orders.length()).filter { index ->
            val current = stageOf(orders.optJSONObject(index))
            current != OrderStage.DELIVERED && current != OrderStage.CANCELLED && current != OrderStage.UNKNOWN
        }
        val activeIds = activeIndices.map { orders.optJSONObject(it)?.optString("order_id").orEmpty() }
        val selected = resolveOrderIndex(activeIds, orderId)
        if (selected == null) {
            if (activeIds.isEmpty() && stage != OrderStage.DELIVERED && stage != OrderStage.CANCELLED && stage != OrderStage.UNKNOWN) {
                beginTracking(context, stage, orderId)
            }
            return
        }
        val target = orders.optJSONObject(activeIndices[selected]) ?: return
        val now = System.currentTimeMillis()
        target.put("stage", stage.name).put("updated_at", now)
        saveOrders(prefs, orders, now)
    }

    /** Stops one identified order; without an ID, stops only when exactly one order is active. */
    fun stopTracking(context: Context, orderId: String? = null) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val orders = readOrders(prefs)
        val activeIndices = (0 until orders.length()).filter { index ->
            val stage = stageOf(orders.optJSONObject(index))
            stage != OrderStage.DELIVERED && stage != OrderStage.CANCELLED && stage != OrderStage.UNKNOWN
        }
        val activeOrderIds = activeIndices.map { orders.optJSONObject(it)?.optString("order_id").orEmpty() }
        val selected = resolveOrderIndex(activeOrderIds, orderId) ?: return
        orders.remove(activeIndices[selected])
        saveOrders(prefs, orders, System.currentTimeMillis())
    }

    /** Returns an update only when the notification can be attributed to exactly one tracked order. */
    fun onNotification(context: Context, packageName: String, title: String, body: String): String? {
        if (!BlinkitLinks.isBlinkitPackage(packageName)) return null
        val next = parseStage("$title $body") ?: return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val orders = readOrders(prefs)
        val activeIndices = (0 until orders.length()).filter { index ->
            val stage = stageOf(orders.optJSONObject(index))
            stage != OrderStage.DELIVERED && stage != OrderStage.CANCELLED && stage != OrderStage.UNKNOWN
        }
        val activeIds = activeIndices.map { orders.optJSONObject(it)?.optString("order_id").orEmpty() }
        val orderId = extractOrderId("$title $body")
        val selected = resolveOrderIndex(activeIds, orderId) ?: return null
        val recordIndex = activeIndices[selected]
        val record = orders.optJSONObject(recordIndex) ?: return null
        val previous = stageOf(record)
        if (!shouldAdvance(previous, next)) return null

        val now = System.currentTimeMillis()
        record.put("stage", next.name).put("updated_at", now)
        saveOrders(prefs, orders, now)

        val appLabel = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { "Blinkit" }
        return when (next) {
            OrderStage.PLACED -> "$appLabel confirms your order is placed."
            OrderStage.PREPARING -> "$appLabel says your order is being prepared."
            OrderStage.PARTNER_ASSIGNED -> "$appLabel has assigned a delivery partner."
            OrderStage.ON_THE_WAY -> "Your $appLabel order is on the way."
            OrderStage.ARRIVED -> "Your $appLabel delivery partner has reached your location."
            OrderStage.DELIVERED -> "$appLabel reports that your order has been delivered."
            OrderStage.CANCELLED -> "$appLabel reports that your order has been cancelled."
            OrderStage.UNKNOWN -> null
        }
    }

    internal fun resolveOrderIndex(orderIds: List<String>, incomingOrderId: String?): Int? {
        val incoming = normalizeOrderId(incomingOrderId)
        if (incoming != null) return orderIds.indexOfFirst { normalizeOrderId(it) == incoming }.takeIf { it >= 0 }
        return 0.takeIf { orderIds.size == 1 }
    }

    private fun normalizeOrderId(value: String?): String? =
        value?.trim()?.uppercase(Locale.ROOT)?.filter { it.isLetterOrDigit() || it == '-' }
            ?.takeIf { it.length >= 4 }

    private fun extractOrderId(text: String): String? =
        Regex("""\border\s*(?:id|number|no\.?|#)\s*[:#-]?\s*([A-Z0-9-]{4,})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.let(::normalizeOrderId)

    private fun activeOrderIds(orders: JSONArray): List<String> =
        (0 until orders.length()).mapNotNull { orders.optJSONObject(it) }
            .filter {
                val stage = stageOf(it)
                stage != OrderStage.DELIVERED && stage != OrderStage.CANCELLED && stage != OrderStage.UNKNOWN
            }
            .map { it.optString("order_id") }

    private fun stageOf(item: JSONObject?): OrderStage {
        if (item == null) return OrderStage.UNKNOWN
        return runCatching {
            OrderStage.valueOf(item.optString("stage", OrderStage.UNKNOWN.name))
        }.getOrDefault(OrderStage.UNKNOWN)
    }

    private fun readOrders(prefs: android.content.SharedPreferences): JSONArray {
        val raw = prefs.getString(KEY_ORDERS, null)
        if (raw != null) return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        // Migrate the previous single-order preference record without losing its current stage.
        if (prefs.getBoolean(KEY_ENABLED, false)) {
            return JSONArray().put(JSONObject()
                .put("id", "legacy")
                .put("order_id", "")
                .put("stage", prefs.getString(KEY_STAGE, OrderStage.PLACED.name) ?: OrderStage.PLACED.name)
                .put("updated_at", prefs.getLong(KEY_UPDATED_AT, System.currentTimeMillis())))
        }
        return JSONArray()
    }

    private fun saveOrders(
        prefs: android.content.SharedPreferences,
        orders: JSONArray,
        now: Long
    ) {
        val retained = JSONArray()
        val cutoff = now - ORDER_RETENTION_MS
        for (i in 0 until orders.length()) {
            val item = orders.optJSONObject(i) ?: continue
            if (item.optLong("updated_at", now) >= cutoff) retained.put(item)
        }
        while (retained.length() > MAX_TRACKED_ORDERS) retained.remove(0)
        val latest = if (retained.length() > 0) retained.optJSONObject(retained.length() - 1) else null
        val active = (0 until retained.length()).any { index ->
            val stage = stageOf(retained.optJSONObject(index))
            stage != OrderStage.DELIVERED && stage != OrderStage.CANCELLED && stage != OrderStage.UNKNOWN
        }
        prefs.edit()
            .putString(KEY_ORDERS, retained.toString())
            .putBoolean(KEY_ENABLED, active)
            .putString(KEY_STAGE, latest?.optString("stage") ?: OrderStage.UNKNOWN.name)
            .putLong(KEY_UPDATED_AT, latest?.optLong("updated_at") ?: now)
            .apply()
    }

    fun isTracking(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return activeOrderIds(readOrders(prefs)).isNotEmpty()
    }

    fun lastStage(context: Context): OrderStage {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val orders = readOrders(prefs)
        if (orders.length() == 0) return OrderStage.UNKNOWN
        val latest = (0 until orders.length()).mapNotNull { orders.optJSONObject(it) }
            .maxByOrNull { it.optLong("updated_at", 0L) }
        return stageOf(latest)
    }
}
