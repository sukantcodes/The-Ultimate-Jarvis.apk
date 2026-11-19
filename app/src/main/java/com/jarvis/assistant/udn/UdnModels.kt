package com.jarvis.assistant.udn

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/** Devices count as online while their last heartbeat is within three 45 s beats; one missed beat is not "offline". */
const val UDN_ONLINE_WINDOW_MS = 135_000L
const val UDN_HEARTBEAT_MS = 45_000L

data class UdnCapability(
    val capability: String,
    val available: Boolean,
    val reason: String?,
    val osPermission: String?,
    val permissionGranted: Boolean?,
    val policy: String
)

data class UdnDevice(
    val id: String,
    val platform: String,
    val name: String,
    val manufacturer: String?,
    val model: String?,
    val osVersion: String?,
    val appVersion: String?,
    val status: String,
    val batteryPct: Int?,
    val charging: Boolean?,
    val connection: String?,
    val lanHost: String?,
    val lanPort: Int?,
    val lastSeenMs: Long?,
    val parentSlot: Int?,
    val online: Boolean,
    val fingerprint: String?,
    val capabilities: List<UdnCapability>
) {
    val isParent: Boolean get() = parentSlot != null
    val isAuthorized: Boolean get() = status == "authorized"

    fun capability(id: String): UdnCapability? = capabilities.firstOrNull { it.capability == id }

    companion object {
        fun fromJson(o: JSONObject): UdnDevice = UdnDevice(
            id = o.getString("id"),
            platform = o.optString("platform", "android"),
            name = o.optString("name", "Device"),
            manufacturer = o.optStringOrNull("manufacturer"),
            model = o.optStringOrNull("model"),
            osVersion = o.optStringOrNull("os_version"),
            appVersion = o.optStringOrNull("app_version"),
            status = o.optString("status", "pending"),
            batteryPct = if (o.isNull("battery_pct")) null else o.optInt("battery_pct"),
            charging = if (o.isNull("battery_charging")) null else o.optBoolean("battery_charging"),
            connection = o.optStringOrNull("connection_type"),
            lanHost = o.optStringOrNull("lan_host"),
            lanPort = if (o.isNull("lan_port")) null else o.optInt("lan_port"),
            lastSeenMs = parseInstantMs(o.optStringOrNull("last_seen_at")),
            parentSlot = if (o.isNull("parent_slot") || !o.has("parent_slot")) null else o.optInt("parent_slot"),
            online = o.optBoolean("online", false),
            fingerprint = o.optStringOrNull("key_fingerprint"),
            capabilities = o.optJSONArray("capabilities").toList { c ->
                UdnCapability(
                    capability = c.getString("capability"),
                    available = c.optBoolean("available", false),
                    reason = c.optStringOrNull("reason"),
                    osPermission = c.optStringOrNull("os_permission"),
                    permissionGranted = if (c.isNull("permission_granted")) null else c.optBoolean("permission_granted"),
                    policy = c.optString("policy", "default")
                )
            }
        )
    }
}

data class UdnGroup(val id: String, val name: String, val deviceIds: List<String>)

data class UdnCommandRecord(
    val id: String,
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val capability: String,
    val status: String,
    val createdMs: Long?,
    val completedMs: Long?,
    val result: JSONObject?,
    val errorCode: String?,
    val errorMessage: String?
) {
    val isTerminal: Boolean get() = status in TERMINAL

    companion object {
        val TERMINAL = setOf("succeeded", "failed", "rejected", "expired", "cancelled", "timed_out")

        fun fromJson(o: JSONObject) = UdnCommandRecord(
            id = o.getString("id"),
            sourceDeviceId = o.optString("source_device_id"),
            targetDeviceId = o.optString("target_device_id"),
            capability = o.optString("capability"),
            status = o.optString("status"),
            createdMs = parseInstantMs(o.optStringOrNull("created_at")),
            completedMs = parseInstantMs(o.optStringOrNull("completed_at")),
            result = o.optJSONObject("result"),
            errorCode = o.optStringOrNull("error_code"),
            errorMessage = o.optStringOrNull("error_message")
        )
    }
}

data class UdnAuditEvent(val id: Long, val action: String, val actor: String?, val target: String?, val createdMs: Long?, val detail: JSONObject?)

/** A command delivered to this device by the server. */
data class UdnIncomingCommand(
    val id: String,
    val sourceDeviceId: String,
    val capability: String,
    val params: JSONObject,
    val requiresConsent: Boolean,
    val expiresMs: Long,
    val deliveryCount: Int
) {
    companion object {
        fun fromJson(o: JSONObject) = UdnIncomingCommand(
            id = o.getString("id"),
            sourceDeviceId = o.optString("source_device_id"),
            capability = o.getString("capability"),
            params = o.optJSONObject("params") ?: JSONObject(),
            requiresConsent = o.optBoolean("requires_consent", false),
            expiresMs = parseInstantMs(o.optStringOrNull("expires_at")) ?: 0L,
            deliveryCount = o.optInt("delivery_count", 1)
        )
    }
}

/** Outcome of an API call. [code] is the server error code (or a local one such as "network"). */
sealed class UdnResult<out T> {
    data class Ok<T>(val value: T) : UdnResult<T>()
    data class Err(val code: String, val message: String, val detail: JSONObject? = null, val httpStatus: Int = 0) : UdnResult<Nothing>()
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

inline fun <T> JSONArray?.toList(map: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(map(it)) }
    return out
}

fun parseInstantMs(s: String?): Long? =
    if (s.isNullOrBlank()) null else runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

/** User-facing wording for server error codes, so spoken and written replies stay specific and honest. */
object UdnErrors {
    fun describe(code: String, message: String? = null): String = when (code) {
        "target_offline" -> "that device is offline"
        "target_not_authorized" -> "that device has not been authorized yet"
        "target_not_found" -> "I can't find that device on your network"
        "capability_unavailable" -> message?.takeIf { it.isNotBlank() } ?: "that device doesn't support it"
        "capability_unsupported" -> message?.takeIf { it.isNotBlank() } ?: "that device type doesn't support it"
        "permission_missing" -> "the required Android permission hasn't been granted on that device"
        "denied_by_policy" -> "your policy for that device blocks it"
        "confirmation_required" -> "it needs your confirmation first"
        "parent_required" -> "only a parent device can do that"
        "rate_limited" -> "too many requests, please wait a moment"
        "device_revoked" -> "this device was removed from the network"
        "device_not_authorized" -> "this device isn't authorized yet"
        "network" -> "the network is unreachable"
        "not_signed_in" -> "you're not signed in to JARVIS"
        "not_configured" -> "cloud access isn't configured in this build"
        "not_enrolled" -> "this device hasn't joined the network yet"
        "stale_request" -> "this device's clock is too far off"
        "self_target" -> "that's this device"
        "expired" -> "the command expired before the device picked it up"
        "timed_out", "no_result" -> "the device didn't report a result in time"
        "consent_denied" -> "the person at that device declined"
        "consent_timeout" -> "nobody approved it on that device in time"
        else -> message?.takeIf { it.isNotBlank() } ?: code.replace('_', ' ')
    }
}
