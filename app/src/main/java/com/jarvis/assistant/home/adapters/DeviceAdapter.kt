package com.jarvis.assistant.home.adapters

import com.jarvis.assistant.home.CommandResult
import com.jarvis.assistant.home.DeviceAction
import com.jarvis.assistant.home.HomeDevice

/** Reads the live state a device reports about itself. [poweredOn] is null when the device does not report power. */
data class DeviceState(val online: Boolean, val poweredOn: Boolean?, val detail: String = "")

/**
 * One way of talking to devices. Each adapter owns discovery, identity, connection, status and command execution for its
 * protocol, and advertises only the capabilities it can really carry out. Blocking: call from a background thread.
 */
interface DeviceAdapter {
    fun canHandle(device: HomeDevice): Boolean
    fun readState(device: HomeDevice): DeviceState
    fun execute(device: HomeDevice, action: DeviceAction): CommandResult
}
