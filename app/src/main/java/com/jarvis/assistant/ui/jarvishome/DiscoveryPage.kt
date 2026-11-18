package com.jarvis.assistant.ui.jarvishome

import android.Manifest
import android.os.Build
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.CommandResult
import com.jarvis.assistant.home.ConnectionType
import com.jarvis.assistant.home.DeviceCategory
import com.jarvis.assistant.home.DeviceProtocol
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.AddResult
import com.jarvis.assistant.home.HomeServices
import com.jarvis.assistant.home.adapters.DeviceDiscovery
import com.jarvis.assistant.home.adapters.FoundDevice
import com.jarvis.assistant.home.adapters.HomeSecrets
import com.jarvis.assistant.home.adapters.LanHttpAdapter
import com.jarvis.assistant.home.adapters.hexToBytes
import java.util.UUID

/**
 * Device Discovery: guided Add Device. Scans are started only by the user, time-boxed to 12 seconds, and stopped when the
 * user leaves the page. Devices JARVIS can see but has no adapter for are listed as such, never offered as controllable.
 */
object DiscoveryPage {

    private enum class Mode(val label: String) { PAIRED("Paired Bluetooth"), BLE("BLE"), LAN("Wi-Fi / LAN"), MANUAL("Add by address"), OTHER("Matter / MQTT") }

    private var mode = Mode.PAIRED
    private var scanning = false
    private var scanMessage: String? = null
    private val results = ArrayList<FoundDevice>()
    private var discovery: DeviceDiscovery? = null
    private const val SCAN_MS = 12_000L

    private fun discovery(host: HomeHost): DeviceDiscovery = discovery ?: HomeServices.discovery(host.activity).also { discovery = it }

    fun stopScans(host: HomeHost) { discovery?.stopAll(); scanning = false }
    fun release() { discovery?.stopAll(); discovery = null; scanning = false; results.clear(); scanMessage = null }

    fun render(host: HomeHost, c: LinearLayout) {
        val ui = host.ui
        c.addView(ui.text("Find devices compatible with JARVIS", 13f, ui.muted).apply { setPadding(ui.dp(4), 0, 0, ui.dp(8)) })
        val row = LinearLayout(host.activity).apply { orientation = LinearLayout.HORIZONTAL }
        Mode.values().forEach { m -> row.addView(ui.chip(m.label, m == mode) { if (mode != m) { stopScans(host); results.clear(); scanMessage = null; mode = m; host.rerender() } }) }
        c.addView(android.widget.HorizontalScrollView(host.activity).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        c.addView(ui.space(10))

        when (mode) {
            Mode.PAIRED -> paired(host, c)
            Mode.BLE -> scanPanel(host, c, "Scan for BLE devices", "BLE devices need their GATT settings (service, characteristic, on/off bytes) before JARVIS can control them. Without those, only a connection check is possible.") { startBle(host) }
            Mode.LAN -> scanPanel(host, c, "Scan the network", "Finds devices announcing themselves on your Wi-Fi. JARVIS can control Tasmota and Shelly (Gen1) devices; anything else is listed but marked unsupported.") { startLan(host) }
            Mode.MANUAL -> manual(host, c)
            Mode.OTHER -> c.addView(ui.notice("Matter and MQTT",
                "Matter devices can be spotted during a network scan, but JARVIS cannot commission or control them yet. MQTT is not implemented yet either. They are not offered so that nothing pretends to work."))
        }
        if (mode == Mode.BLE || mode == Mode.LAN) results(host, c)
    }

    // ---- Paired Bluetooth ----------------------------------------------------------------------

    private fun paired(host: HomeHost, c: LinearLayout) {
        val ui = host.ui
        c.addView(ui.notice("Paired Bluetooth devices", "Speakers and headphones you paired in Android. JARVIS shows whether each is connected right now. Android only lets you connect them from Bluetooth settings.", "Show paired devices") {
            host.requirePermissions(connectPermissions()) { granted ->
                if (!granted) { scanMessage = "Allow Nearby devices so JARVIS can see your paired Bluetooth devices."; host.rerender(); return@requirePermissions }
                results.clear(); results.addAll(discovery(host).pairedBluetooth())
                scanMessage = if (results.isEmpty()) "No paired devices found. Pair one in Android Bluetooth settings first." else null
                host.rerender()
            }
        })
        scanMessage?.let { c.addView(ui.text(it, 13f, ui.warning).apply { setPadding(ui.dp(4), 0, 0, ui.dp(8)) }) }
        results(host, c)
    }

    private fun connectPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    private fun scanPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    // ---- Scanning ------------------------------------------------------------------------------

    private fun scanPanel(host: HomeHost, c: LinearLayout, title: String, body: String, start: () -> Unit) {
        val ui = host.ui
        c.addView(ui.notice(if (scanning) "Searching for devices…" else title, body, if (scanning) null else (if (results.isEmpty()) "Scan" else "Scan again")) { start() })
        scanMessage?.let { c.addView(ui.text(it, 13f, ui.warning).apply { setPadding(ui.dp(4), 0, 0, ui.dp(8)) }) }
    }

    private fun startBle(host: HomeHost) {
        host.requirePermissions(scanPermissions()) { granted ->
            if (!granted) { scanMessage = "Scanning needs the Nearby devices permission (older phones: Location)."; host.rerender(); return@requirePermissions }
            results.clear(); scanning = true; scanMessage = null; host.rerender()
            discovery(host).scanBle(SCAN_MS, { f -> results.add(f); host.rerender() }, { err -> scanning = false; scanMessage = err ?: if (results.isEmpty()) "No BLE devices found nearby." else null; host.rerender() })
        }
    }

    private fun startLan(host: HomeHost) {
        results.clear(); scanning = true; scanMessage = null; host.rerender()
        discovery(host).scanLan(SCAN_MS, { f ->
            if (results.none { it.address == f.address }) results.add(f)
            host.rerender()
            // Ask each host what it is; a Tasmota or Shelly answer upgrades the entry to a controllable device.
            host.background({ discovery(host).identify(f.address, HomeServices.http) }) { id ->
                if (id != null) { val i = results.indexOfFirst { it.address == f.address }; if (i >= 0) results[i] = id else results.add(id); host.rerender() }
                else { val i = results.indexOfFirst { it.address == f.address }; if (i >= 0 && results[i].note.startsWith("Checking")) { results[i] = results[i].copy(note = "No supported protocol found"); host.rerender() } }
            }
        }, { err -> scanning = false; scanMessage = err ?: if (results.isEmpty()) "No network devices found." else null; host.rerender() })
    }

    // ---- Results ---------------------------------------------------------------------------------

    private fun results(host: HomeHost, c: LinearLayout) {
        val ui = host.ui
        if (results.isEmpty()) return
        c.addView(PagesCommon.section(host, "Discovered devices"))
        results.forEach { f ->
            val already = host.registry.isRegistered(f.connection, f.address)
            c.addView(ui.glassCard().apply {
                addView(LinearLayout(host.activity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(LinearLayout(host.activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        addView(ui.text(f.name, 15f, bold = true, maxLines = 2))
                        addView(ui.text("${f.connection.label}${if (f.manufacturer.isNotBlank()) " · ${f.manufacturer}" else ""}", 12f, ui.muted))
                        if (f.detail.isNotBlank()) addView(ui.text(f.detail, 12f, ui.muted))
                        if (f.note.isNotBlank()) addView(ui.text(f.note, 12f, if (f.controllable) ui.success else ui.warning))
                    })
                    when {
                        already -> addView(ui.text("Added", 13f, ui.success))
                        f.controllable -> addView(ui.button("Add", true) { startAdd(host, f) })
                        else -> addView(ui.text("Unsupported", 12f, ui.muted))
                    }
                })
            })
        }
    }

    // ---- Manual add ------------------------------------------------------------------------------

    private fun manual(host: HomeHost, c: LinearLayout) {
        val ui = host.ui
        c.addView(ui.glassCard().apply {
            addView(ui.gradientText("ADD BY ADDRESS", 14f))
            addView(ui.text("Type the device's IP address. JARVIS asks it what it is and supports Tasmota and Shelly (Gen1) automatically.", 13f, ui.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })
            addView(LinearLayout(host.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.button("Identify by IP", true) {
                    host.promptText("Device address", "", "192.168.1.50") { ip ->
                        if (ip.isEmpty()) return@promptText
                        host.toast("Asking $ip…")
                        host.background({ discovery(host).identify(ip, HomeServices.http) }) { f ->
                            if (f == null) host.toast("$ip did not answer as a Tasmota or Shelly device. You can add it with custom URLs instead.") else startAdd(host, f)
                        }
                    }
                })
                addView(ui.button("Custom HTTP URLs") { customHttp(host) })
                addView(ui.button("BLE GATT device") { customBle(host) })
            })
        })
    }

    private fun customHttp(host: HomeHost) {
        form(host, "Custom HTTP device", listOf(
            Field("name", "Name", "Garden pump"), Field("address", "Address", "192.168.1.60"),
            Field("url_on", "Turn-on URL", "http://192.168.1.60/on"), Field("url_off", "Turn-off URL", "http://192.168.1.60/off"),
            Field("url_status", "Status URL (optional)", "http://192.168.1.60/status", optional = true),
            Field("status_on_regex", "Text meaning “on” (regex, optional)", "\"state\":\\s*\"on\"", optional = true)
        )) { v ->
            val on = v.getValue("url_on"); val off = v.getValue("url_off")
            if (!on.startsWith("http") || !off.startsWith("http")) { host.toast("URLs must start with http:// or https://"); return@form }
            val hasStatus = !v["url_status"].isNullOrBlank() && !v["status_on_regex"].isNullOrBlank()
            val cfg = mapOf("url_on" to on, "url_off" to off, "url_status" to v["url_status"].orEmpty(), "status_on_regex" to v["status_on_regex"].orEmpty())
            finishAdd(host, FoundDevice(v.getValue("name"), v.getValue("address"), ConnectionType.LAN_HTTP, protocol = DeviceProtocol.GENERIC_HTTP), cfg, LanHttpAdapter.capabilitiesFor(DeviceProtocol.GENERIC_HTTP, hasStatus), v.getValue("name"), null)
        }
    }

    private fun customBle(host: HomeHost) {
        form(host, "BLE GATT device", listOf(
            Field("name", "Name", "Desk lamp"), Field("address", "Bluetooth address", "AA:BB:CC:DD:EE:FF"),
            Field("service_uuid", "Service UUID", "0000ffe0-0000-1000-8000-00805f9b34fb"), Field("char_uuid", "Characteristic UUID", "0000ffe1-0000-1000-8000-00805f9b34fb"),
            Field("on_hex", "Bytes to turn on (hex)", "01"), Field("off_hex", "Bytes to turn off (hex)", "00")
        )) { v ->
            val valid = runCatching { UUID.fromString(v.getValue("service_uuid")); UUID.fromString(v.getValue("char_uuid")) }.isSuccess &&
                hexToBytes(v.getValue("on_hex")) != null && hexToBytes(v.getValue("off_hex")) != null &&
                android.bluetooth.BluetoothAdapter.checkBluetoothAddress(v.getValue("address").uppercase())
            if (!valid) { host.toast("Check the address, the two UUIDs and the hex bytes."); return@form }
            val cfg = mapOf("service_uuid" to v.getValue("service_uuid"), "char_uuid" to v.getValue("char_uuid"), "on_hex" to v.getValue("on_hex"), "off_hex" to v.getValue("off_hex"))
            finishAdd(host, FoundDevice(v.getValue("name"), v.getValue("address").uppercase(), ConnectionType.BLE, protocol = DeviceProtocol.BLE_GATT), cfg, setOf(Capability.POWER, Capability.READ_STATE), v.getValue("name"), null)
        }
    }

    // ---- Add flow --------------------------------------------------------------------------------

    private fun startAdd(host: HomeHost, f: FoundDevice) {
        when (f.protocol) {
            DeviceProtocol.TASMOTA, DeviceProtocol.SHELLY_GEN1 -> form(host, "Add ${f.name}", listOf(
                Field("name", "Name", f.name, initial = f.name),
                Field("auth_user", "Username (only if the device asks for one)", "admin", optional = true),
                Field("password", "Password (stored encrypted)", "", optional = true, password = true)
            )) { v ->
                val cfg = HashMap<String, String>()
                v["auth_user"]?.takeIf { it.isNotBlank() }?.let { cfg["auth_user"] = it }
                finishAdd(host, f, cfg, LanHttpAdapter.capabilitiesFor(f.protocol!!), v.getValue("name"), v["password"])
            }
            DeviceProtocol.BT_CLASSIC_BONDED -> form(host, "Add ${f.name}", listOf(Field("name", "Name", f.name, initial = f.name))) { v ->
                finishAdd(host, f, emptyMap(), setOf(Capability.READ_STATE), v.getValue("name"), null)
            }
            DeviceProtocol.BLE_GATT -> form(host, "Add ${f.name}", listOf(
                Field("name", "Name", f.name, initial = f.name),
                Field("service_uuid", "Service UUID (needed to control it)", "0000ffe0-0000-1000-8000-00805f9b34fb", optional = true),
                Field("char_uuid", "Characteristic UUID", "0000ffe1-0000-1000-8000-00805f9b34fb", optional = true),
                Field("on_hex", "Bytes to turn on (hex)", "01", optional = true), Field("off_hex", "Bytes to turn off (hex)", "00", optional = true)
            )) { v ->
                val keys = listOf("service_uuid", "char_uuid", "on_hex", "off_hex")
                val filled = keys.filter { !v[it].isNullOrBlank() }
                val complete = filled.size == keys.size &&
                    runCatching { UUID.fromString(v.getValue("service_uuid")); UUID.fromString(v.getValue("char_uuid")) }.isSuccess &&
                    hexToBytes(v.getValue("on_hex")) != null && hexToBytes(v.getValue("off_hex")) != null
                if (filled.isNotEmpty() && !complete) { host.toast("Fill all four GATT fields correctly, or leave them all empty."); return@form }
                val caps = if (complete) setOf(Capability.POWER, Capability.READ_STATE) else setOf(Capability.READ_STATE)
                finishAdd(host, f, if (complete) keys.associateWith { v.getValue(it) } else emptyMap(), caps, v.getValue("name"), null)
            }
            else -> host.toast("JARVIS has no adapter for this device.")
        }
    }

    /** Step 2 and 3: what kind of device is it, which room is it in. Then it is saved and its real status is read. */
    private fun finishAdd(host: HomeHost, f: FoundDevice, config: Map<String, String>, caps: Set<Capability>, name: String, password: String?) {
        val categories = DeviceCategory.values().toList()
        host.chooseOne("What is it?", categories.map { it.label }, categories.indexOf(f.category).coerceAtLeast(0)) { ci ->
            val rooms = host.registry.rooms()
            host.chooseOne("Which room?", listOf("No room") + rooms.map { it.name } + "＋ New room…") { ri ->
                fun save(roomId: String?) {
                    val device = HomeDevice(
                        id = UUID.randomUUID().toString(), name = name.take(60), manufacturer = f.manufacturer, category = categories[ci],
                        connection = f.connection, protocol = f.protocol ?: DeviceProtocol.GENERIC_HTTP, address = f.address,
                        roomId = roomId, capabilities = caps, config = config
                    )
                    when (val r = host.registry.addDevice(device)) {
                        is AddResult.Duplicate -> host.toast("Already added as “${r.existing.name}”.")
                        is AddResult.Added -> {
                            if (!password.isNullOrBlank()) HomeSecrets.put(r.device.id, password)
                            host.toast("Added ${r.device.name}. Checking its status…")
                            host.background({ host.controller.refresh(r.device.id) }) { res ->
                                host.toast(if (res is CommandResult.Success) "${r.device.name} is reachable." else res.message)
                                host.navigate(HomePage.ALL_DEVICES)
                            }
                        }
                    }
                }
                when {
                    ri == 0 -> save(null)
                    ri <= rooms.size -> save(rooms[ri - 1].id)
                    else -> host.promptText("New room", "", "Room name") { n -> save(host.registry.createRoom(n)?.id) }
                }
            }
        }
    }

    // ---- Form dialog -----------------------------------------------------------------------------

    private data class Field(val key: String, val label: String, val hint: String, val optional: Boolean = false, val password: Boolean = false, val initial: String = "")

    private fun form(host: HomeHost, title: String, fields: List<Field>, onSubmit: (Map<String, String>) -> Unit) {
        val ui = host.ui
        val body = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val inputs = fields.associate { f ->
            body.addView(ui.text(f.label, 12f, ui.muted).apply { setPadding(0, ui.dp(8), 0, 0) })
            val e = EditText(host.activity).apply {
                setText(f.initial); hint = f.hint; setSingleLine(true); setTextColor(ui.primary); setHintTextColor(ui.muted)
                inputType = if (f.password) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
            body.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            f.key to e
        }
        MaterialAlertDialogBuilder(host.activity, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle(title).setView(android.widget.ScrollView(host.activity).apply { addView(body) })
            .setPositiveButton("Next") { _, _ ->
                val values = inputs.mapValues { it.value.text.toString().trim() }
                val missing = fields.firstOrNull { !it.optional && values.getValue(it.key).isEmpty() }
                if (missing != null) host.toast("${missing.label} is required.") else onSubmit(values)
            }
            .setNegativeButton("Cancel", null).show()
    }
}

/** Small shared view helpers for the page files. */
internal object PagesCommon {
    fun section(host: HomeHost, title: String) = host.ui.gradientText(title.uppercase(), 14f).apply { setPadding(host.ui.dp(4), host.ui.dp(10), 0, host.ui.dp(8)) }
}
