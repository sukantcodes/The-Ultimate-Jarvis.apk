package com.jarvis.assistant.home

import com.jarvis.assistant.home.adapters.DeviceAdapter

/** Sends a command through the Home Hub / cloud route. Implemented by the Supabase remote client. */
interface RemoteDispatcher {
    val isAvailable: Boolean
    fun dispatch(device: HomeDevice, action: DeviceAction): CommandResult
}

data class BatchResult(val device: HomeDevice, val result: CommandResult)

/**
 * The one place that turns "do X to device Y" into a real command. It checks the device advertises the capability,
 * prefers local control, falls back to the Home Hub only for devices the user routed through it, records the device's own
 * reported state, and writes the outcome to Device Activity. Blocking: call from a background thread.
 */
class HomeController(
    private val registry: HomeRegistry,
    private val adapters: List<DeviceAdapter>,
    private val remote: RemoteDispatcher? = null,
    private val stateListener: (HomeDevice, Boolean?) -> Unit = { _, _ -> }
) {

    fun run(deviceId: String, action: DeviceAction): CommandResult {
        val device = registry.device(deviceId) ?: return CommandResult.Failure(FailureReason.REJECTED, "That device is not registered.")
        return run(device, action)
    }

    fun run(device: HomeDevice, action: DeviceAction): CommandResult {
        if (!device.supports(action.required)) {
            val r = CommandResult.Failure(FailureReason.UNSUPPORTED, "${device.name} does not support ${action.required.label.lowercase()}.")
            registry.log("command", device.id, r.message, false)
            return r
        }
        val result = execute(device, action)
        record(device, action, result)
        return result
    }

    /** Applies [action] to every device that supports it. Devices that do not are reported as skipped, not silently dropped. */
    fun runOn(devices: List<HomeDevice>, action: DeviceAction): List<BatchResult> =
        devices.distinctBy { it.id }.map { BatchResult(it, run(it, action)) }

    fun refresh(deviceId: String): CommandResult = run(deviceId, DeviceAction(DeviceActionType.REFRESH))

    private fun execute(device: HomeDevice, action: DeviceAction): CommandResult {
        val viaHubOnly = device.connection == ConnectionType.HUB || device.connection == ConnectionType.CLOUD
        if (viaHubOnly) return viaRemote(device, action)
        val adapter = adapters.firstOrNull { it.canHandle(device) }
            ?: return CommandResult.Failure(FailureReason.UNSUPPORTED, "JARVIS has no adapter for ${device.name}.")
        val local = try {
            adapter.execute(device, action)
        } catch (e: Exception) {
            CommandResult.Failure(FailureReason.ERROR, "${device.name}: ${e.message ?: "unexpected error"}")
        }
        val offline = local is CommandResult.Failure && (local.reason == FailureReason.OFFLINE || local.reason == FailureReason.TIMEOUT)
        if (offline && device.config["via_hub"] == "true") {
            val r = viaRemote(device, action)
            if (r is CommandResult.Success) return r
        }
        return local
    }

    private fun viaRemote(device: HomeDevice, action: DeviceAction): CommandResult {
        val r = remote
        if (r == null || !r.isAvailable) return CommandResult.Failure(FailureReason.OFFLINE, "Remote control needs you to be signed in and a Home Hub online.")
        return try { r.dispatch(device, action) } catch (e: Exception) { CommandResult.Failure(FailureReason.ERROR, "Remote command failed: ${e.message}") }
    }

    private fun record(device: HomeDevice, action: DeviceAction, result: CommandResult) {
        when (result) {
            is CommandResult.Success -> {
                if (result.verified) registry.recordState(device.id, DeviceStatus.ONLINE, result.poweredOn)
                if (action.type != DeviceActionType.REFRESH) registry.markUsed(device.id)
                if (action.type != DeviceActionType.REFRESH) registry.log("command", device.id, result.message, true)
                if (result.verified) stateListener(device, result.poweredOn)
            }
            is CommandResult.Failure -> {
                if (result.reason == FailureReason.OFFLINE || result.reason == FailureReason.TIMEOUT) registry.recordState(device.id, DeviceStatus.OFFLINE, null)
                registry.log("command", device.id, result.message, false)
            }
        }
    }
}
