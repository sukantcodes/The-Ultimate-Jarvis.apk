package com.jarvis.assistant.home.adapters

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.jarvis.assistant.home.CommandResult
import com.jarvis.assistant.home.ConnectionType
import com.jarvis.assistant.home.DeviceAction
import com.jarvis.assistant.home.DeviceActionType
import com.jarvis.assistant.home.FailureReason
import com.jarvis.assistant.home.HomeDevice
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object BluetoothPermissions {
    /** Runtime permissions needed to connect (API 31+: BLUETOOTH_CONNECT; older: the install-time BLUETOOTH). */
    fun hasConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasScan(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun adapter(context: Context): BluetoothAdapter? =
        (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}

/** Hex helper shared by the BLE adapter and its tests: "A1 0F" / "a10f" -> bytes. Returns null for invalid input. */
fun hexToBytes(hex: String): ByteArray? {
    val clean = hex.filter { !it.isWhitespace() && it != ':' }
    if (clean.isEmpty() || clean.length % 2 != 0 || !clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Bluetooth Low Energy over GATT. Works only for a device whose characteristic and command bytes the user (or a built-in
 * profile) supplied in config: service_uuid, char_uuid, on_hex, off_hex. Power state is NOT reported by write-only
 * characteristics, so a write is "sent", never "confirmed", unless state_char_uuid is configured and read back.
 */
class BleGattAdapter(private val context: Context) : DeviceAdapter {

    override fun canHandle(device: HomeDevice) = device.connection == ConnectionType.BLE

    @SuppressLint("MissingPermission")
    override fun readState(device: HomeDevice): DeviceState {
        if (!BluetoothPermissions.hasConnect(context)) return DeviceState(false, null, "Bluetooth permission missing")
        return when (val s = session(device) { gatt -> null to gatt.services.isNotEmpty() }) {
            is Session.Failed -> DeviceState(false, null, s.message)
            is Session.Done -> DeviceState(true, null)
        }
    }

    @SuppressLint("MissingPermission")
    override fun execute(device: HomeDevice, action: DeviceAction): CommandResult {
        if (!BluetoothPermissions.hasConnect(context)) return CommandResult.Failure(FailureReason.PERMISSION, "Allow Nearby devices permission for JARVIS first.")
        if (!device.supports(action.required)) return CommandResult.Failure(FailureReason.UNSUPPORTED, "${device.name} does not support ${action.required.label.lowercase()}.")
        if (action.type == DeviceActionType.REFRESH) {
            val s = readState(device)
            return if (s.online) CommandResult.Success("${device.name} is reachable over Bluetooth.", true) else CommandResult.Failure(FailureReason.OFFLINE, s.detail.ifBlank { "${device.name} is out of range." })
        }
        if (action.type != DeviceActionType.POWER_ON && action.type != DeviceActionType.POWER_OFF) {
            return CommandResult.Failure(FailureReason.UNSUPPORTED, "This Bluetooth device only supports on/off commands.")
        }
        val on = action.type == DeviceActionType.POWER_ON
        val service = parseUuid(device.config["service_uuid"]); val char = parseUuid(device.config["char_uuid"])
        val payload = hexToBytes(device.config[if (on) "on_hex" else "off_hex"].orEmpty())
        if (service == null || char == null || payload == null) {
            return CommandResult.Failure(FailureReason.NOT_CONFIGURED, "${device.name} needs its service UUID, characteristic UUID and on/off bytes configured.")
        }
        val result = session(device) { gatt ->
            val c = gatt.getService(service)?.getCharacteristic(char)
            if (c == null) "Characteristic not found on device" to false
            else {
                val ok = writeAndWait(gatt, c, payload)
                (if (ok) null else "The device did not accept the write") to ok
            }
        }
        return when (result) {
            is Session.Failed -> CommandResult.Failure(if (result.timedOut) FailureReason.TIMEOUT else FailureReason.OFFLINE, "${device.name}: ${result.message}")
            is Session.Done -> if (result.ok) CommandResult.Success("Sent ${if (on) "ON" else "OFF"} to ${device.name}. It does not report state, so this is not confirmed.", false)
            else CommandResult.Failure(FailureReason.REJECTED, "${device.name}: ${result.message}")
        }
    }

    private sealed class Session {
        data class Done(val ok: Boolean, val message: String) : Session()
        data class Failed(val message: String, val timedOut: Boolean = false) : Session()
    }

    private var writeLatch: CountDownLatch? = null
    @Volatile private var writeStatus: Int = -1

    @SuppressLint("MissingPermission")
    private fun writeAndWait(gatt: BluetoothGatt, c: android.bluetooth.BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        val latch = CountDownLatch(1)
        writeLatch = latch; writeStatus = -1
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(c, payload, android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run { c.value = payload; gatt.writeCharacteristic(c) }
        }
        if (!started) return false
        return latch.await(WRITE_TIMEOUT_S, TimeUnit.SECONDS) && writeStatus == BluetoothGatt.GATT_SUCCESS
    }

    /** Connects, discovers services, runs [block] (returns an error message or null, and success), then always disconnects. */
    @SuppressLint("MissingPermission")
    private fun session(device: HomeDevice, block: (BluetoothGatt) -> Pair<String?, Boolean>): Session {
        val adapter = BluetoothPermissions.adapter(context) ?: return Session.Failed("This phone has no Bluetooth")
        if (!adapter.isEnabled) return Session.Failed("Bluetooth is turned off")
        val remote = runCatching { adapter.getRemoteDevice(device.address) }.getOrNull() ?: return Session.Failed("Invalid Bluetooth address")
        val connected = CountDownLatch(1); val discovered = CountDownLatch(1)
        val connectOk = java.util.concurrent.atomic.AtomicBoolean(false)
        val discoverOk = java.util.concurrent.atomic.AtomicBoolean(false)
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) { connectOk.set(true); connected.countDown() }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) { connected.countDown(); discovered.countDown() }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { discoverOk.set(status == BluetoothGatt.GATT_SUCCESS); discovered.countDown() }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: android.bluetooth.BluetoothGattCharacteristic, status: Int) { writeStatus = status; writeLatch?.countDown() }
        }
        val gatt = remote.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE) ?: return Session.Failed("Could not start a Bluetooth connection")
        try {
            if (!connected.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)) return Session.Failed("Connection timed out", timedOut = true)
            if (!connectOk.get()) return Session.Failed("The device is out of range or refused the connection")
            if (!gatt.discoverServices() || !discovered.await(CONNECT_TIMEOUT_S, TimeUnit.SECONDS) || !discoverOk.get()) return Session.Failed("Could not read the device's services", timedOut = true)
            val (error, ok) = block(gatt)
            return Session.Done(ok, error.orEmpty())
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private fun parseUuid(s: String?): UUID? = runCatching { UUID.fromString(s?.trim()) }.getOrNull()

    companion object {
        const val CONNECT_TIMEOUT_S = 10L
        const val WRITE_TIMEOUT_S = 5L
    }
}

/**
 * Bluetooth Classic devices (speakers, headphones) that are already paired in Android. Android does not let an app open an
 * audio connection to a paired device on its own, so CONNECT is not offered here; status is read from the A2DP and headset
 * profiles. The UI sends the user to system Bluetooth settings to connect.
 */
class BluetoothClassicAdapter(private val context: Context) : DeviceAdapter {

    override fun canHandle(device: HomeDevice) = device.connection == ConnectionType.BLUETOOTH_CLASSIC

    @SuppressLint("MissingPermission")
    override fun readState(device: HomeDevice): DeviceState {
        if (!BluetoothPermissions.hasConnect(context)) return DeviceState(false, null, "Bluetooth permission missing")
        val adapter = BluetoothPermissions.adapter(context) ?: return DeviceState(false, null, "This phone has no Bluetooth")
        if (!adapter.isEnabled) return DeviceState(false, null, "Bluetooth is turned off")
        val connected = profileConnected(adapter, BluetoothProfile.A2DP, device.address) || profileConnected(adapter, BluetoothProfile.HEADSET, device.address)
        return DeviceState(connected, null, if (connected) "Connected" else "Paired, not connected")
    }

    override fun execute(device: HomeDevice, action: DeviceAction): CommandResult {
        if (action.type == DeviceActionType.REFRESH) {
            val s = readState(device)
            return if (s.online) CommandResult.Success("${device.name} is connected.", true) else CommandResult.Failure(FailureReason.OFFLINE, "${device.name}: ${s.detail}.")
        }
        return CommandResult.Failure(FailureReason.UNSUPPORTED, "Android does not let JARVIS connect ${device.name} by itself. Connect it from Bluetooth settings; JARVIS shows its live connection status.")
    }

    @SuppressLint("MissingPermission")
    private fun profileConnected(adapter: BluetoothAdapter, profile: Int, address: String): Boolean {
        val latch = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                result.set(runCatching { proxy.connectedDevices.any { it.address.equals(address, ignoreCase = true) } }.getOrDefault(false))
                runCatching { adapter.closeProfileProxy(p, proxy) }
                latch.countDown()
            }
            override fun onServiceDisconnected(p: Int) { latch.countDown() }
        }
        if (!adapter.getProfileProxy(context, listener, profile)) return false
        latch.await(3, TimeUnit.SECONDS)
        return result.get()
    }
}
