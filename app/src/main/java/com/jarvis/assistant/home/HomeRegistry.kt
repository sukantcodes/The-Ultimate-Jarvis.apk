package com.jarvis.assistant.home

import com.jarvis.assistant.clock.KeyValueStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** One line of Device Activity. Never holds credentials or command payloads beyond a short summary. */
data class ActivityEvent(val timeMillis: Long, val kind: String, val deviceId: String?, val summary: String, val success: Boolean) {
    fun toJson() = JSONObject().put("t", timeMillis).put("k", kind).put("d", deviceId ?: JSONObject.NULL).put("s", summary).put("ok", success)

    companion object {
        fun fromJson(o: JSONObject) = ActivityEvent(o.optLong("t"), o.optString("k"), if (o.isNull("d")) null else o.optString("d"), o.optString("s"), o.optBoolean("ok"))
    }
}

sealed class AddResult {
    data class Added(val device: HomeDevice) : AddResult()
    data class Duplicate(val existing: HomeDevice) : AddResult()
}

/**
 * Source of truth for devices, rooms, groups and the activity log. Persisted through [KeyValueStore] (the same
 * commit-checked store the Clock uses), so a change only counts once it is saved. All methods are thread-safe.
 */
class HomeRegistry(private val kv: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) {

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var devices: List<HomeDevice> = emptyList()
    private var rooms: List<HomeRoom> = emptyList()
    private var groups: List<HomeGroup> = emptyList()
    private var activity: List<ActivityEvent> = emptyList()

    init { load() }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun devices(): List<HomeDevice> = synchronized(lock) { devices }
    fun rooms(): List<HomeRoom> = synchronized(lock) { rooms }
    fun groups(): List<HomeGroup> = synchronized(lock) { groups }
    fun activity(): List<ActivityEvent> = synchronized(lock) { activity }
    fun device(id: String): HomeDevice? = synchronized(lock) { devices.firstOrNull { it.id == id } }
    fun room(id: String?): HomeRoom? = synchronized(lock) { rooms.firstOrNull { it.id == id } }
    fun devicesInRoom(roomId: String): List<HomeDevice> = devices().filter { it.roomId == roomId }

    // ---- devices -------------------------------------------------------------------------------------------

    /** Adds a device unless one with the same connection type and address is already registered. */
    fun addDevice(device: HomeDevice): AddResult {
        val result = synchronized(lock) {
            val dupe = devices.firstOrNull { sameEndpoint(it, device) }
            if (dupe != null) return@synchronized AddResult.Duplicate(dupe)
            val withId = if (device.id.isBlank()) device.copy(id = newId()) else device
            devices = devices + withId
            persist()
            AddResult.Added(withId)
        }
        if (result is AddResult.Added) { log("added", result.device.id, "Added ${result.device.name}", true); notifyChanged() }
        return result
    }

    fun isRegistered(connection: ConnectionType, address: String): Boolean = synchronized(lock) {
        devices.any { it.connection == connection && it.address.equals(address, ignoreCase = true) }
    }

    fun updateDevice(id: String, change: (HomeDevice) -> HomeDevice): HomeDevice? {
        val updated = synchronized(lock) {
            val current = devices.firstOrNull { it.id == id } ?: return null
            val next = change(current).copy(id = current.id)
            devices = devices.map { if (it.id == id) next else it }
            persist()
            next
        }
        notifyChanged()
        return updated
    }

    fun renameDevice(id: String, name: String) = name.trim().takeIf { it.isNotEmpty() }?.let { n -> updateDevice(id) { it.copy(name = n.take(60)) } }
    fun setAliases(id: String, aliases: List<String>) = updateDevice(id) { it.copy(aliases = aliases.map { a -> a.trim() }.filter { a -> a.isNotEmpty() }.distinct().take(10)) }
    fun setFavorite(id: String, favorite: Boolean) = updateDevice(id) { it.copy(favorite = favorite) }
    fun assignRoom(id: String, roomId: String?) = updateDevice(id) { it.copy(roomId = roomId?.takeIf { r -> rooms().any { x -> x.id == r } }) }

    fun removeDevice(id: String): Boolean {
        val removed = synchronized(lock) {
            val d = devices.firstOrNull { it.id == id } ?: return false
            devices = devices.filterNot { it.id == id }
            groups = groups.map { g -> g.copy(deviceIds = g.deviceIds - id) }
            persist()
            d
        }
        log("removed", id, "Removed ${removed.name}", true)
        notifyChanged()
        return true
    }

    /** Records a state the device itself reported (or a connection fact). Never invents a state. */
    fun recordState(id: String, status: DeviceStatus, poweredOn: Boolean?) {
        val before = device(id) ?: return
        updateDevice(id) { it.copy(status = status, poweredOn = if (status == DeviceStatus.ONLINE) (poweredOn ?: it.poweredOn) else it.poweredOn, lastSeenMillis = if (status == DeviceStatus.ONLINE) clock() else it.lastSeenMillis) }
        if (before.status != status && status != DeviceStatus.UNKNOWN) {
            log(if (status == DeviceStatus.ONLINE) "connected" else "disconnected", id, "${before.name} is ${status.name.lowercase()}", status == DeviceStatus.ONLINE)
        }
    }

    fun markUsed(id: String) { updateDevice(id) { it.copy(lastUsedMillis = clock()) } }

    // ---- rooms ---------------------------------------------------------------------------------------------

    fun createRoom(name: String, icon: String = "room"): HomeRoom? {
        val n = name.trim().take(40).takeIf { it.isNotEmpty() } ?: return null
        val room = synchronized(lock) {
            rooms.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let { return it }
            HomeRoom(newId(), n, icon).also { rooms = rooms + it; persist() }
        }
        notifyChanged()
        return room
    }

    fun renameRoom(id: String, name: String, icon: String? = null): Boolean {
        val n = name.trim().take(40).takeIf { it.isNotEmpty() } ?: return false
        synchronized(lock) {
            if (rooms.none { it.id == id }) return false
            rooms = rooms.map { if (it.id == id) it.copy(name = n, icon = icon ?: it.icon) else it }
            persist()
        }
        notifyChanged()
        return true
    }

    fun setRoomFavorite(id: String, favorite: Boolean) {
        synchronized(lock) { rooms = rooms.map { if (it.id == id) it.copy(favorite = favorite) else it }; persist() }
        notifyChanged()
    }

    /** Deleting a room keeps its devices; they simply become unassigned. */
    fun deleteRoom(id: String): Boolean {
        synchronized(lock) {
            if (rooms.none { it.id == id }) return false
            rooms = rooms.filterNot { it.id == id }
            devices = devices.map { if (it.roomId == id) it.copy(roomId = null) else it }
            persist()
        }
        notifyChanged()
        return true
    }

    // ---- groups --------------------------------------------------------------------------------------------

    fun saveGroup(name: String, deviceIds: List<String>, id: String? = null): HomeGroup? {
        val n = name.trim().take(40).takeIf { it.isNotEmpty() } ?: return null
        val g = synchronized(lock) {
            val valid = deviceIds.filter { d -> devices.any { it.id == d } }.distinct()
            val group = HomeGroup(id ?: newId(), n, valid)
            groups = if (groups.any { it.id == group.id }) groups.map { if (it.id == group.id) group else it } else groups + group
            persist()
            group
        }
        notifyChanged()
        return g
    }

    fun deleteGroup(id: String) {
        synchronized(lock) { groups = groups.filterNot { it.id == id }; persist() }
        notifyChanged()
    }

    // ---- activity ------------------------------------------------------------------------------------------

    fun log(kind: String, deviceId: String?, summary: String, success: Boolean) {
        synchronized(lock) {
            activity = (listOf(ActivityEvent(clock(), kind, deviceId, summary.take(160), success)) + activity).take(MAX_ACTIVITY)
            runCatching { kv.putString(KEY_ACTIVITY, JSONArray(activity.map { it.toJson() }).toString()) }
        }
        listeners.forEach { runCatching { it() } }
    }

    // ---- backup --------------------------------------------------------------------------------------------

    /** Devices, rooms and groups as JSON. Device passwords live in encrypted storage and are never part of a backup. */
    fun exportState(): String = synchronized(lock) {
        JSONObject().put("devices", JSONArray(devices.map { it.toJson() }))
            .put("rooms", JSONArray(rooms.map { it.toJson() })).put("groups", JSONArray(groups.map { it.toJson() })).toString()
    }

    /** Replaces the registry with a backup. Returns false (and changes nothing) if the text is not a valid backup. */
    fun importState(raw: String): Boolean {
        val parsed = runCatching {
            val o = JSONObject(raw)
            Triple(o.optJSONArray("devices").mapObjects { HomeDevice.fromJson(it) }, o.optJSONArray("rooms").mapObjects { HomeRoom.fromJson(it) }, o.optJSONArray("groups").mapObjects { HomeGroup.fromJson(it) })
        }.getOrNull() ?: return false
        synchronized(lock) {
            devices = parsed.first.map { it.copy(status = DeviceStatus.UNKNOWN, poweredOn = null) }
            rooms = parsed.second; groups = parsed.third
            persist()
        }
        log("restore", null, "Restored ${parsed.first.size} device(s) from a backup", true)
        notifyChanged()
        return true
    }

    // ---- storage -------------------------------------------------------------------------------------------

    private fun load() {
        synchronized(lock) {
            runCatching {
                kv.getString(KEY_STATE)?.let { raw ->
                    val o = JSONObject(raw)
                    devices = o.optJSONArray("devices").mapObjects { HomeDevice.fromJson(it) }
                    rooms = o.optJSONArray("rooms").mapObjects { HomeRoom.fromJson(it) }
                    groups = o.optJSONArray("groups").mapObjects { HomeGroup.fromJson(it) }
                }
            }
            runCatching { kv.getString(KEY_ACTIVITY)?.let { activity = JSONArray(it).mapObjects { o -> ActivityEvent.fromJson(o) } } }
        }
    }

    private fun persist() {
        val o = JSONObject()
            .put("devices", JSONArray(devices.map { it.toJson() }))
            .put("rooms", JSONArray(rooms.map { it.toJson() }))
            .put("groups", JSONArray(groups.map { it.toJson() }))
        kv.putString(KEY_STATE, o.toString())
    }

    private fun notifyChanged() { listeners.forEach { runCatching { it() } } }

    private fun sameEndpoint(a: HomeDevice, b: HomeDevice) =
        a.connection == b.connection && a.address.equals(b.address, ignoreCase = true) &&
            (a.config["relay"] ?: "") == (b.config["relay"] ?: "")

    private fun newId() = UUID.randomUUID().toString()

    private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { runCatching { f(getJSONObject(it)) }.getOrNull() }

    companion object {
        const val KEY_STATE = "home_state_v1"
        const val KEY_ACTIVITY = "home_activity_v1"
        const val MAX_ACTIVITY = 200
    }
}
