package com.jarvis.assistant.home

import com.jarvis.assistant.clock.KeyValueStore
import com.jarvis.assistant.home.adapters.DeviceAdapter
import com.jarvis.assistant.home.adapters.DeviceState

class MemoryKv : KeyValueStore {
    val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

fun dev(
    id: String, name: String, category: DeviceCategory = DeviceCategory.LIGHT, room: String? = null,
    caps: Set<Capability> = setOf(Capability.POWER, Capability.READ_STATE), connection: ConnectionType = ConnectionType.LAN_HTTP,
    address: String = "10.0.0.$id", status: DeviceStatus = DeviceStatus.UNKNOWN, on: Boolean? = null, aliases: List<String> = emptyList()
) = HomeDevice(id = id, name = name, aliases = aliases, category = category, connection = connection, protocol = DeviceProtocol.TASMOTA,
    address = address, roomId = room, capabilities = caps, status = status, poweredOn = on)

/** An adapter whose devices behave exactly as the test says. */
class FakeAdapter(var result: (HomeDevice, DeviceAction) -> CommandResult) : DeviceAdapter {
    val calls = ArrayList<Pair<String, DeviceActionType>>()
    override fun canHandle(device: HomeDevice) = true
    override fun readState(device: HomeDevice) = DeviceState(true, null)
    override fun execute(device: HomeDevice, action: DeviceAction): CommandResult { calls += device.id to action.type; return result(device, action) }
}
