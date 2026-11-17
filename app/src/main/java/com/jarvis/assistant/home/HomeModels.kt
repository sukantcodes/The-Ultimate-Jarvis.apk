package com.jarvis.assistant.home

import org.json.JSONArray
import org.json.JSONObject

/** How a device is reached. Only what an adapter really implements is ever offered to the user. */
enum class ConnectionType(val label: String) {
    BLUETOOTH_CLASSIC("Bluetooth"),
    BLE("BLE"),
    LAN_HTTP("Wi-Fi / LAN"),
    CLOUD("Cloud"),
    HUB("Home Hub");

    val isBluetooth: Boolean get() = this == BLUETOOTH_CLASSIC || this == BLE
    val isWifi: Boolean get() = this == LAN_HTTP || this == CLOUD || this == HUB

    companion object {
        fun parse(value: String?): ConnectionType = values().firstOrNull { it.name == value } ?: LAN_HTTP
    }
}

enum class DeviceCategory(val label: String) {
    LIGHT("Light"), PLUG("Plug"), FAN("Fan"), AC("Air conditioner"), TV("TV"), SPEAKER("Speaker"),
    SENSOR("Sensor"), MOTOR("Motor"), CAMERA("Camera"), OTHER("Other");

    companion object {
        fun parse(value: String?): DeviceCategory = values().firstOrNull { it.name == value } ?: OTHER
    }
}

/** An action a device can really perform. A control is shown only when the device's adapter advertises it. */
enum class Capability(val label: String) {
    POWER("Power"), BRIGHTNESS("Brightness"), CONNECT("Connect"), READ_STATE("Status");

    companion object {
        fun parse(value: String?): Capability? = values().firstOrNull { it.name == value }
    }
}

/** What the app honestly knows about a device right now. UNKNOWN is never shown as online or offline. */
enum class DeviceStatus { ONLINE, OFFLINE, UNKNOWN }

enum class DeviceProtocol(val label: String) {
    TASMOTA("Tasmota (HTTP)"),
    SHELLY_GEN1("Shelly Gen1 (HTTP)"),
    GENERIC_HTTP("Custom HTTP URLs"),
    BLE_GATT("BLE GATT characteristic"),
    BT_CLASSIC_BONDED("Paired Bluetooth device");

    companion object {
        fun parse(value: String?): DeviceProtocol = values().firstOrNull { it.name == value } ?: GENERIC_HTTP
    }
}

/**
 * One registered device. [address] is an IP/host for LAN devices or a MAC for Bluetooth. [config] holds protocol
 * settings (URLs, GATT UUIDs). Credentials never go in [config]; they live in [HomeSecrets].
 */
data class HomeDevice(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val manufacturer: String = "",
    val category: DeviceCategory = DeviceCategory.OTHER,
    val connection: ConnectionType,
    val protocol: DeviceProtocol,
    val address: String,
    val roomId: String? = null,
    val favorite: Boolean = false,
    val capabilities: Set<Capability> = emptySet(),
    val config: Map<String, String> = emptyMap(),
    val status: DeviceStatus = DeviceStatus.UNKNOWN,
    val poweredOn: Boolean? = null,
    val lastSeenMillis: Long = 0L,
    val lastUsedMillis: Long = 0L
) {
    fun supports(c: Capability) = c in capabilities

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("aliases", JSONArray(aliases))
        put("manufacturer", manufacturer); put("category", category.name)
        put("connection", connection.name); put("protocol", protocol.name); put("address", address)
        put("roomId", roomId ?: JSONObject.NULL); put("favorite", favorite)
        put("capabilities", JSONArray(capabilities.map { it.name }))
        put("config", JSONObject(config as Map<*, *>))
        put("status", status.name); put("poweredOn", poweredOn ?: JSONObject.NULL)
        put("lastSeen", lastSeenMillis); put("lastUsed", lastUsedMillis)
    }

    companion object {
        fun fromJson(o: JSONObject): HomeDevice {
            val aliases = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val caps = o.optJSONArray("capabilities")?.let { a -> (0 until a.length()).mapNotNull { Capability.parse(a.getString(it)) }.toSet() } ?: emptySet()
            val cfg = HashMap<String, String>()
            o.optJSONObject("config")?.let { c -> c.keys().forEach { k -> cfg[k] = c.optString(k) } }
            return HomeDevice(
                id = o.getString("id"), name = o.getString("name"), aliases = aliases,
                manufacturer = o.optString("manufacturer"), category = DeviceCategory.parse(o.optString("category")),
                connection = ConnectionType.parse(o.optString("connection")), protocol = DeviceProtocol.parse(o.optString("protocol")),
                address = o.optString("address"), roomId = if (o.isNull("roomId")) null else o.optString("roomId"),
                favorite = o.optBoolean("favorite"), capabilities = caps, config = cfg,
                status = runCatching { DeviceStatus.valueOf(o.optString("status")) }.getOrDefault(DeviceStatus.UNKNOWN),
                poweredOn = if (o.isNull("poweredOn")) null else o.optBoolean("poweredOn"),
                lastSeenMillis = o.optLong("lastSeen"), lastUsedMillis = o.optLong("lastUsed")
            )
        }
    }
}

data class HomeRoom(val id: String, val name: String, val icon: String = "room", val favorite: Boolean = false) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("icon", icon).put("favorite", favorite)

    companion object {
        fun fromJson(o: JSONObject) = HomeRoom(o.getString("id"), o.getString("name"), o.optString("icon", "room"), o.optBoolean("favorite"))
    }
}

/** A user-made set of devices that is controlled together. */
data class HomeGroup(val id: String, val name: String, val deviceIds: List<String>) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("deviceIds", JSONArray(deviceIds))

    companion object {
        fun fromJson(o: JSONObject): HomeGroup {
            val ids = o.optJSONArray("deviceIds")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            return HomeGroup(o.getString("id"), o.getString("name"), ids)
        }
    }
}

enum class DeviceActionType { POWER_ON, POWER_OFF, TOGGLE, SET_BRIGHTNESS, CONNECT, REFRESH }

data class DeviceAction(val type: DeviceActionType, val value: Int? = null) {
    /** The capability a device must advertise to run this action. */
    val required: Capability
        get() = when (type) {
            DeviceActionType.POWER_ON, DeviceActionType.POWER_OFF, DeviceActionType.TOGGLE -> Capability.POWER
            DeviceActionType.SET_BRIGHTNESS -> Capability.BRIGHTNESS
            DeviceActionType.CONNECT -> Capability.CONNECT
            DeviceActionType.REFRESH -> Capability.READ_STATE
        }

    fun toJson() = JSONObject().put("type", type.name).put("value", value ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = DeviceAction(
            runCatching { DeviceActionType.valueOf(o.optString("type")) }.getOrDefault(DeviceActionType.REFRESH),
            if (o.isNull("value")) null else o.optInt("value")
        )
    }
}

/** Why a command did or did not work. [verified] is true only when the device's own state was read back. */
sealed class CommandResult {
    abstract val message: String

    data class Success(override val message: String, val verified: Boolean, val poweredOn: Boolean? = null) : CommandResult()
    data class Failure(val reason: FailureReason, override val message: String) : CommandResult()
}

enum class FailureReason { UNSUPPORTED, OFFLINE, PERMISSION, TIMEOUT, REJECTED, NOT_CONFIGURED, ERROR }
