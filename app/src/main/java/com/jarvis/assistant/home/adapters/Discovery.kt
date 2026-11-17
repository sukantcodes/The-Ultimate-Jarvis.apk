package com.jarvis.assistant.home.adapters

import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.jarvis.assistant.home.ConnectionType
import com.jarvis.assistant.home.DeviceCategory
import com.jarvis.assistant.home.DeviceProtocol
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A device found while scanning. [protocol] is null when JARVIS can see it but has no adapter to control it. */
data class FoundDevice(
    val name: String,
    val address: String,
    val connection: ConnectionType,
    val manufacturer: String = "",
    val detail: String = "",
    val protocol: DeviceProtocol? = null,
    val category: DeviceCategory = DeviceCategory.OTHER,
    val note: String = ""
) {
    val controllable: Boolean get() = protocol != null
}

/** Identifies what a LAN host is by asking it, using only documented endpoints. Returns null when it is neither. */
object LanProbe {
    fun identify(host: String, http: HttpTransport): FoundDevice? {
        val h = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (h.isEmpty()) return null
        runCatching {
            val o = JSONObject(http.get("http://$h/cm?cmnd=Status%200"))
            val status = o.optJSONObject("Status")
            val name = status?.optJSONArray("FriendlyName")?.optString(0)?.takeIf { it.isNotBlank() } ?: status?.optString("DeviceName").orEmpty().ifBlank { "Tasmota device" }
            val fw = o.optJSONObject("StatusFWR")?.optString("Version").orEmpty()
            return FoundDevice(name, h, ConnectionType.LAN_HTTP, "Tasmota", if (fw.isBlank()) "Tasmota firmware" else "Tasmota $fw", DeviceProtocol.TASMOTA, DeviceCategory.PLUG)
        }
        runCatching {
            val o = JSONObject(http.get("http://$h/shelly"))
            val type = o.optString("type")
            if (type.isNotBlank()) return FoundDevice("Shelly $type", h, ConnectionType.LAN_HTTP, "Shelly", "Shelly $type (Gen1 API)", DeviceProtocol.SHELLY_GEN1, DeviceCategory.PLUG)
        }
        return null
    }
}

/** Bluetooth Classic (already paired), BLE scan and Wi-Fi/LAN (mDNS) discovery. Scans are time-boxed, never continuous. */
class DeviceDiscovery(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var bleCallback: ScanCallback? = null
    @Volatile private var nsdListeners: List<NsdManager.DiscoveryListener> = emptyList()
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null

    @SuppressLint("MissingPermission")
    fun pairedBluetooth(): List<FoundDevice> {
        if (!BluetoothPermissions.hasConnect(context)) return emptyList()
        val adapter = BluetoothPermissions.adapter(context) ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map { d ->
                val classic = d.type != android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE
                FoundDevice(
                    name = d.name ?: d.address, address = d.address,
                    connection = if (classic) ConnectionType.BLUETOOTH_CLASSIC else ConnectionType.BLE,
                    detail = "Paired in Android",
                    protocol = if (classic) DeviceProtocol.BT_CLASSIC_BONDED else null,
                    category = if (classic) DeviceCategory.SPEAKER else DeviceCategory.OTHER,
                    note = if (classic) "Status only" else "Needs GATT settings to control"
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Scans for BLE advertisers for [durationMs]. [onFound] fires once per address, on the main thread. */
    @SuppressLint("MissingPermission")
    fun scanBle(durationMs: Long, onFound: (FoundDevice) -> Unit, onDone: (String?) -> Unit) {
        stopBle()
        if (!BluetoothPermissions.hasScan(context)) { onDone("Nearby devices permission is needed to scan."); return }
        val scanner = BluetoothPermissions.adapter(context)?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) { onDone("Turn Bluetooth on to scan."); return }
        val seen = ConcurrentHashMap<String, Boolean>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address ?: return
                if (seen.putIfAbsent(addr, true) != null) return
                val name = runCatching { result.scanRecord?.deviceName ?: result.device.name }.getOrNull()
                main.post { onFound(FoundDevice(name ?: "Unnamed BLE device", addr, ConnectionType.BLE, detail = "Signal ${result.rssi} dBm", protocol = DeviceProtocol.BLE_GATT, note = "Needs GATT settings to control")) }
            }
            override fun onScanFailed(errorCode: Int) { main.post { onDone("Bluetooth scan failed (code $errorCode).") } }
        }
        bleCallback = cb
        runCatching { scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), cb) }
            .onFailure { onDone("Could not start scanning: ${it.message}"); return }
        main.postDelayed({ if (bleCallback === cb) { stopBle(); onDone(null) } }, durationMs)
    }

    @SuppressLint("MissingPermission")
    fun stopBle() {
        val cb = bleCallback ?: return
        bleCallback = null
        runCatching { BluetoothPermissions.adapter(context)?.bluetoothLeScanner?.stopScan(cb) }
    }

    /** mDNS scan of the local network. Matter devices are listed but marked as not controllable yet. */
    fun scanLan(durationMs: Long, onFound: (FoundDevice) -> Unit, onDone: (String?) -> Unit) {
        stopLan()
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) { onDone("This phone cannot scan the network."); return }
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("jarvis-home").apply { setReferenceCounted(false); acquire() }
        }
        val seen = ConcurrentHashMap<String, Boolean>()
        val resolveQueue = java.util.ArrayDeque<NsdServiceInfo>()
        val resolving = java.util.concurrent.atomic.AtomicBoolean(false)

        fun pump() {
            if (!resolving.compareAndSet(false, true)) return
            val next = synchronized(resolveQueue) { resolveQueue.poll() }
            if (next == null) { resolving.set(false); return }
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { resolving.set(false); pump() }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolving.set(false)
                    @Suppress("DEPRECATION") val host = info.host?.hostAddress
                    if (host != null && seen.putIfAbsent(host + info.serviceType, true) == null) {
                        val matter = info.serviceType.contains("matter")
                        main.post {
                            onFound(FoundDevice(
                                name = info.serviceName, address = host, connection = ConnectionType.LAN_HTTP,
                                detail = if (matter) "Matter device" else "Network service on port ${info.port}",
                                protocol = null,
                                note = if (matter) "Matter control is not supported yet" else "Checking for supported firmware…"
                            ))
                        }
                    }
                    pump()
                }
            })
        }

        val listeners = listOf("_http._tcp.", "_matter._tcp.").map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onServiceFound(info: NsdServiceInfo) { synchronized(resolveQueue) { resolveQueue.add(info) }; pump() }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            } to type
        }
        nsdListeners = listeners.map { it.first }
        var started = 0
        listeners.forEach { (l, type) -> if (runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }.isSuccess) started++ }
        if (started == 0) { stopLan(); onDone("Could not start network discovery."); return }
        main.postDelayed({ stopLan(); onDone(null) }, durationMs)
    }

    fun stopLan() {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        nsdListeners.forEach { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        nsdListeners = emptyList()
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    fun stopAll() { stopBle(); stopLan() }

    /** Asks a discovered or manually typed host what it is. Blocking; call off the main thread. */
    fun identify(host: String, http: HttpTransport): FoundDevice? = try { LanProbe.identify(host, http) } catch (e: IOException) { null }
}
