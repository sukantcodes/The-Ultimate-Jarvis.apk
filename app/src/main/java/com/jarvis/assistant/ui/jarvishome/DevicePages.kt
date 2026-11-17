package com.jarvis.assistant.ui.jarvishome

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.CommandResult
import com.jarvis.assistant.home.ConnectionType
import com.jarvis.assistant.home.DeviceAction
import com.jarvis.assistant.home.DeviceActionType
import com.jarvis.assistant.home.DeviceStatus
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.automation.AutomationTime
import com.jarvis.assistant.home.automation.Trigger
import com.jarvis.assistant.home.adapters.HomeSecrets
import java.time.ZonedDateTime

/** Overview, Favorites, All Devices, Rooms, Bluetooth and Wi-Fi pages, plus the device card and device control sheet. */
object DevicePages {

    private var query: String = ""
    private var filter: String = "All"

    // ---- Overview ----------------------------------------------------------------------------------

    fun overview(host: HomeHost, c: LinearLayout) {
        val ui = host.ui
        val devices = host.registry.devices()
        val online = devices.count { it.status == DeviceStatus.ONLINE }
        val offline = devices.count { it.status == DeviceStatus.OFFLINE }
        val unchecked = devices.size - online - offline

        nextScheduleBanner(host)?.let { c.addView(it) }

        val stats = LinearLayout(host.activity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Devices" to devices.size, "Online" to online, "Offline" to offline).forEach { (label, n) ->
            stats.addView(ui.glassCard(18f, padding = 12).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(8) }
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ui.text(n.toString(), 26f, bold = true))
                addView(ui.text(label, 12f, ui.muted))
            })
        }
        c.addView(stats)
        if (unchecked > 0 && devices.isNotEmpty()) c.addView(ui.text("$unchecked device(s) have not been checked yet.", 12f, ui.muted).apply { setPadding(ui.dp(4), 0, 0, ui.dp(12)) })

        val bt = devices.filter { it.connection.isBluetooth }
        val wifi = devices.filter { it.connection.isWifi }
        c.addView(ui.glassCard().apply {
            addView(ui.text("Connections", 16f, bold = true))
            addView(ui.text("Bluetooth: ${bt.size} registered, ${bt.count { it.status == DeviceStatus.ONLINE }} connected", 13f, ui.muted).apply { setPadding(0, ui.dp(6), 0, 0) })
            addView(ui.text("Wi-Fi / LAN / Hub: ${wifi.size} registered, ${wifi.count { it.status == DeviceStatus.ONLINE }} reachable", 13f, ui.muted))
            addView(LinearLayout(host.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.button("Check all devices") { refreshAll(host, devices) })
            })
        })

        if (devices.isEmpty()) {
            c.addView(ui.notice("No devices yet", "JARVIS Home shows only real devices. Add a Bluetooth, BLE or Wi-Fi device that JARVIS has an adapter for.", "Add a device") { host.navigate(HomePage.DISCOVERY) })
        } else {
            val favs = devices.filter { it.favorite }
            if (favs.isNotEmpty()) { c.addView(section(host, "Favorites", "View all") { host.navigate(HomePage.FAVORITES) }); deviceGrid(host, c, favs.take(6)) }
            val recent = devices.filter { it.lastUsedMillis > 0 }.sortedByDescending { it.lastUsedMillis }.take(4)
            if (recent.isNotEmpty()) { c.addView(section(host, "Recently used", null) {}); deviceGrid(host, c, recent) }
            if (favs.isEmpty() && recent.isEmpty()) { c.addView(section(host, "My devices", "View all") { host.navigate(HomePage.ALL_DEVICES) }); deviceGrid(host, c, devices.take(6)) }
        }

        val rooms = host.registry.rooms()
        if (rooms.isNotEmpty()) {
            c.addView(section(host, "Rooms", "Manage") { host.navigate(HomePage.ROOMS) })
            val row = LinearLayout(host.activity).apply { orientation = LinearLayout.HORIZONTAL }
            rooms.take(4).forEach { r -> row.addView(ui.chip("${r.name} · ${host.registry.devicesInRoom(r.id).size}", false) { host.navigate(HomePage.ROOMS) }) }
            c.addView(android.widget.HorizontalScrollView(host.activity).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }

        c.addView(section(host, "Quick actions", null) {})
        c.addView(LinearLayout(host.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.button("Voice", true) { host.navigate(HomePage.VOICE) })
            addView(ui.button("Scenes", true) { host.navigate(HomePage.SCENES) })
            addView(ui.button("+ Add device", true) { host.navigate(HomePage.DISCOVERY) })
        })
    }

    private fun nextScheduleBanner(host: HomeHost): TextView? {
        val now = ZonedDateTime.now()
        val next = host.automationStore.automations().filter { it.enabled && it.isConfigured }
            .mapNotNull { a -> AutomationTime.nextFire(a.trigger, now)?.let { a to it } }.minByOrNull { it.second } ?: return null
        val ui = host.ui
        return ui.text("Next: ${next.first.name} at ${"%02d:%02d".format(next.second.hour, next.second.minute)}", 13f, ui.muted).apply {
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = GradientDrawable().apply { cornerRadius = ui.dp(16).toFloat(); setColor(ui.chipBg) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(12) }
            isClickable = true
            setOnClickListener { host.navigate(HomePage.AUTOMATIONS) }
        }
    }

    private fun refreshAll(host: HomeHost, devices: List<HomeDevice>) {
        if (devices.isEmpty()) { host.toast("There are no devices to check."); return }
        host.toast("Checking ${devices.size} device(s)…")
        host.background({ devices.map { host.controller.refresh(it.id) } }) { results ->
            val ok = results.count { it is CommandResult.Success }
            host.toast("$ok of ${results.size} answered.")
        }
    }

    // ---- Lists -------------------------------------------------------------------------------------

    fun favorites(host: HomeHost, c: LinearLayout) {
        val favs = host.registry.devices().filter { it.favorite }
        val favRooms = host.registry.rooms().filter { it.favorite }
        if (favs.isEmpty() && favRooms.isEmpty()) {
            c.addView(host.ui.notice("No favorites yet", "Open a device and turn on Favorite, or star a room, to pin it here."))
            return
        }
        favRooms.forEach { r ->
            c.addView(section(host, "★ ${r.name}", "Room") { host.navigate(HomePage.ROOMS) })
            roomActions(host, c, r.id)
        }
        if (favs.isNotEmpty()) deviceGrid(host, c, favs)
    }

    fun all(host: HomeHost, c: LinearLayout) = searchableList(host, c, host.registry.devices(), "No devices registered", "Add a device from Device Discovery.")

    fun byConnection(host: HomeHost, c: LinearLayout, bluetooth: Boolean) {
        val list = host.registry.devices().filter { if (bluetooth) it.connection.isBluetooth else it.connection.isWifi }
        searchableList(host, c, list, if (bluetooth) "No Bluetooth devices" else "No Wi-Fi devices",
            if (bluetooth) "Pair a speaker in Android Bluetooth settings, then add it here, or scan for BLE devices." else "Add a Tasmota or Shelly device by address, or scan your network.")
    }

    private fun searchableList(host: HomeHost, c: LinearLayout, base: List<HomeDevice>, emptyTitle: String, emptyBody: String) {
        val ui = host.ui
        if (base.isEmpty()) { c.addView(ui.notice(emptyTitle, emptyBody, "Add a device") { host.navigate(HomePage.DISCOVERY) }); return }
        val search = EditText(host.activity).apply {
            setText(query); hint = "Search devices, rooms, brands"; setSingleLine(true)
            setTextColor(ui.primary); setHintTextColor(ui.muted)
            setPadding(ui.dp(16), 0, ui.dp(16), 0); minimumHeight = ui.dp(48)
            background = GradientDrawable().apply { cornerRadius = ui.dp(24).toFloat(); setColor(ui.chipBg) }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, _, _ -> query = v.text.toString().trim(); host.rerender(); true }
        }
        c.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(8) })
        val filters = listOf("All", "Online", "Offline", "Favorites")
        val row = LinearLayout(host.activity).apply { orientation = LinearLayout.HORIZONTAL }
        filters.forEach { f -> row.addView(ui.chip(f, f == filter) { filter = f; host.rerender() }) }
        c.addView(android.widget.HorizontalScrollView(host.activity).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        c.addView(ui.space(8))

        val q = query.lowercase()
        val list = base.filter { d ->
            when (filter) { "Online" -> d.status == DeviceStatus.ONLINE; "Offline" -> d.status == DeviceStatus.OFFLINE; "Favorites" -> d.favorite; else -> true }
        }.filter { d ->
            q.isEmpty() || listOf(d.name, d.manufacturer, d.category.label, d.connection.label, host.registry.room(d.roomId)?.name.orEmpty(), d.aliases.joinToString(" ")).any { it.lowercase().contains(q) }
        }
        if (list.isEmpty()) c.addView(ui.notice("Nothing matches", "No device matches your search and filter.", "Clear") { query = ""; filter = "All"; host.rerender() })
        else deviceGrid(host, c, list)
    }

    // ---- Rooms -------------------------------------------------------------------------------------

    fun rooms(host: HomeHost, c: LinearLayout) {
        val ui = host.ui
        c.addView(LinearLayout(host.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.button("＋ New room", true) {
                host.promptText("New room", "", "e.g. Living room") { name ->
                    if (name.isNotEmpty() && host.registry.createRoom(name) == null) host.toast("Could not create that room.")
                }
            })
            addView(ui.button("＋ New group") { newGroup(host) })
        })
        val rooms = host.registry.rooms()
        if (rooms.isEmpty()) c.addView(ui.notice("No rooms", "Create rooms such as Living room or Bedroom, then assign devices to them."))
        rooms.forEach { r ->
            val devices = host.registry.devicesInRoom(r.id)
            val card = ui.glassCard()
            card.addView(LinearLayout(host.activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(host.activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(ui.text(r.name, 18f, bold = true))
                    addView(ui.text("${devices.size} device(s)", 12f, ui.muted))
                })
                addView(ui.button(if (r.favorite) "★" else "☆") { host.registry.setRoomFavorite(r.id, !r.favorite) }.apply { contentDescription = "Favorite room" })
                addView(ui.button("Edit") { editRoom(host, r.id) })
            })
            if (devices.isNotEmpty()) roomActionsInto(host, card, r.id)
            c.addView(card)
            if (devices.isNotEmpty()) deviceGrid(host, c, devices)
        }
        val unassigned = host.registry.devices().filter { it.roomId == null }
        if (unassigned.isNotEmpty()) { c.addView(section(host, "No room", null) {}); deviceGrid(host, c, unassigned) }

        val groups = host.registry.groups()
        if (groups.isNotEmpty()) {
            c.addView(section(host, "Groups", null) {})
            groups.forEach { g ->
                val devs = g.deviceIds.mapNotNull { host.registry.device(it) }
                c.addView(ui.glassCard().apply {
                    addView(ui.text(g.name, 16f, bold = true))
                    addView(ui.text(devs.joinToString { it.name }.ifBlank { "Empty group" }, 12f, ui.muted))
                    addView(LinearLayout(host.activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(ui.button("All on") { runBatch(host, devs, DeviceActionType.POWER_ON) })
                        addView(ui.button("All off") { runBatch(host, devs, DeviceActionType.POWER_OFF) })
                        addView(ui.button("Delete") { host.confirm("Delete group?", "Devices stay registered.", "Delete") { host.registry.deleteGroup(g.id) } })
                    })
                })
            }
        }
    }

    private fun roomActions(host: HomeHost, c: LinearLayout, roomId: String) {
        val card = host.ui.glassCard(); roomActionsInto(host, card, roomId); c.addView(card)
    }

    private fun roomActionsInto(host: HomeHost, card: LinearLayout, roomId: String) {
        val ui = host.ui
        val devs = host.registry.devicesInRoom(roomId)
        val powerable = devs.count { it.supports(Capability.POWER) }
        card.addView(LinearLayout(host.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.button("All on ($powerable)") { runBatch(host, devs, DeviceActionType.POWER_ON) })
            addView(ui.button("All off ($powerable)") { runBatch(host, devs, DeviceActionType.POWER_OFF) })
        })
    }

    private fun editRoom(host: HomeHost, id: String) {
        val room = host.registry.room(id) ?: return
        host.chooseOne(room.name, listOf("Rename", "Delete room")) { which ->
            if (which == 0) host.promptText("Rename room", room.name, "Room name") { n -> if (n.isNotEmpty()) host.registry.renameRoom(id, n) }
            else host.confirm("Delete ${room.name}?", "Its devices stay registered and become unassigned.", "Delete") { host.registry.deleteRoom(id) }
        }
    }

    private fun newGroup(host: HomeHost) {
        val devices = host.registry.devices()
        if (devices.isEmpty()) { host.toast("Add devices first."); return }
        host.promptText("New group", "", "Group name") { name ->
            if (name.isEmpty()) return@promptText
            val checked = BooleanArray(devices.size)
            MaterialAlertDialogBuilder(host.activity, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
                .setTitle("Devices in $name")
                .setMultiChoiceItems(devices.map { it.name }.toTypedArray(), checked) { _, i, v -> checked[i] = v }
                .setPositiveButton("Save") { _, _ ->
                    val ids = devices.filterIndexed { i, _ -> checked[i] }.map { it.id }
                    if (ids.isEmpty()) host.toast("Pick at least one device.") else host.registry.saveGroup(name, ids)
                }.setNegativeButton("Cancel", null).show()
        }
    }

    /** Group/room control only runs on devices that support power; the rest are reported, not silently skipped. */
    private fun runBatch(host: HomeHost, devices: List<HomeDevice>, type: DeviceActionType) {
        val action = DeviceAction(type)
        val compatible = devices.filter { it.supports(action.required) }
        if (compatible.isEmpty()) { host.toast("None of these devices support ${action.required.label.lowercase()}."); return }
        val skipped = devices.size - compatible.size
        host.background({ host.controller.runOn(compatible, action) }) { results ->
            val bad = results.filter { it.result is CommandResult.Failure }
            host.toast(buildString {
                append("${results.size - bad.size} of ${results.size} done")
                if (skipped > 0) append("; $skipped skipped (no power control)")
                if (bad.isNotEmpty()) append(". ").append(bad.first().result.message)
            })
        }
    }

    // ---- Cards -------------------------------------------------------------------------------------

    fun section(host: HomeHost, title: String, action: String?, onAction: () -> Unit): View {
        val ui = host.ui
        return LinearLayout(host.activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            addView(ui.text(title, 17f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            if (action != null) addView(ui.text(action, 13f, ui.accent).apply { minimumHeight = ui.dp(44); gravity = Gravity.CENTER_VERTICAL; isClickable = true; setOnClickListener { onAction() } })
        }
    }

    fun deviceGrid(host: HomeHost, c: LinearLayout, devices: List<HomeDevice>) {
        val ui = host.ui
        devices.chunked(2).forEach { pair ->
            val row = LinearLayout(host.activity).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, d ->
                row.addView(deviceCard(host, d).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = if (i == 0) ui.dp(10) else 0; bottomMargin = ui.dp(10) }
                })
            }
            if (pair.size == 1) row.addView(android.view.View(host.activity), LinearLayout.LayoutParams(0, 1, 1f))
            c.addView(row)
        }
    }

    private fun deviceCard(host: HomeHost, d: HomeDevice): LinearLayout {
        val ui = host.ui
        val on = d.status == DeviceStatus.ONLINE && d.poweredOn == true
        val card = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)); minimumHeight = ui.dp(120) }
        com.jarvis.assistant.ui.glass.LiquidGlass.apply(card, 22f, if (on) ColorUtils.setAlphaComponent(ui.accent, 120) else 0)
        card.isClickable = true; card.isFocusable = true
        card.contentDescription = "${d.name}, ${ui.statusLabel(d)}"
        card.setOnClickListener { showControls(host, d.id) }

        val top = LinearLayout(host.activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ui.text(d.category.label, 11f, ui.muted).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        if (d.supports(Capability.POWER)) {
            top.addView(TextView(host.activity).apply {
                text = "⏻"; gravity = Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(if (on) ui.primary else ui.muted)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) ColorUtils.setAlphaComponent(ui.accent, 200) else ui.chipBg) }
                layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
                contentDescription = "Toggle ${d.name}"
                isClickable = true; isFocusable = true
                setOnClickListener {
                    alpha = 0.5f
                    val type = if (d.poweredOn == true) DeviceActionType.POWER_OFF else DeviceActionType.POWER_ON
                    host.background({ host.controller.run(d.id, DeviceAction(type)) }) { r -> alpha = 1f; if (r is CommandResult.Failure || !(r as CommandResult.Success).verified) host.toast(r.message) }
                }
            })
        }
        card.addView(top)
        card.addView(ui.space(14))
        card.addView(ui.text(d.name, 15f, bold = true, maxLines = 2))
        val roomName = host.registry.room(d.roomId)?.name ?: "No room"
        card.addView(ui.text("$roomName · ${d.connection.label}", 12f, ui.muted, maxLines = 1))
        card.addView(LinearLayout(host.activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(6), 0, 0)
            addView(ui.dot(ui.statusColor(d.status)))
            addView(ui.text(ui.statusLabel(d), 12f, ui.statusColor(d.status)))
        })
        return card
    }

    // ---- Device control sheet ----------------------------------------------------------------------

    fun showControls(host: HomeHost, id: String) {
        val d = host.registry.device(id) ?: return
        val ui = host.ui
        val body = LinearLayout(host.activity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        val dialog = MaterialAlertDialogBuilder(host.activity, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle(d.name).setView(android.widget.ScrollView(host.activity).apply { addView(body) })
            .setPositiveButton("Close", null).create()

        body.addView(ui.text("${d.manufacturer.ifBlank { "Unknown maker" }} · ${d.connection.label} · ${d.protocol.label}", 12f, ui.muted))
        body.addView(ui.text("Status: ${ui.statusLabel(d)}" + if (d.lastSeenMillis > 0) " · last seen ${DateUtils.getRelativeTimeSpanString(d.lastSeenMillis)}" else "", 13f, ui.statusColor(d.status)).apply { setPadding(0, ui.dp(6), 0, ui.dp(10)) })

        val row = LinearLayout(host.activity).apply { orientation = LinearLayout.HORIZONTAL }
        if (d.supports(Capability.POWER)) {
            row.addView(ui.button("Turn on", true) { act(host, d, DeviceAction(DeviceActionType.POWER_ON), dialog) })
            row.addView(ui.button("Turn off", true) { act(host, d, DeviceAction(DeviceActionType.POWER_OFF), dialog) })
        }
        if (d.supports(Capability.READ_STATE)) row.addView(ui.button("Check status") { act(host, d, DeviceAction(DeviceActionType.REFRESH), dialog) })
        if (d.connection == ConnectionType.BLUETOOTH_CLASSIC) {
            row.addView(ui.button("Bluetooth settings") { host.activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
            row.addView(ui.button("Check connection") { act(host, d, DeviceAction(DeviceActionType.REFRESH), dialog) })
        }
        if (row.childCount > 0) body.addView(android.widget.HorizontalScrollView(host.activity).apply { addView(row) })
        if (!d.supports(Capability.POWER) && d.connection != ConnectionType.BLUETOOTH_CLASSIC) {
            body.addView(ui.text("This device has no supported controls yet. Only capabilities JARVIS can really perform are shown.", 12f, ui.muted).apply { setPadding(0, ui.dp(6), 0, ui.dp(6)) })
        }

        if (d.supports(Capability.BRIGHTNESS)) {
            body.addView(ui.text("Brightness", 13f, bold = true).apply { setPadding(0, ui.dp(10), 0, 0) })
            body.addView(SeekBar(host.activity).apply {
                max = 100; progress = 50
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { act(host, d, DeviceAction(DeviceActionType.SET_BRIGHTNESS, progress), null) }
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)))
        }

        body.addView(ui.divider())
        body.addView(ui.switchRow("Favorite", null, d.favorite) { host.registry.setFavorite(d.id, it) })
        body.addView(ui.actionRow("Room", host.registry.room(d.roomId)?.name ?: "Not assigned") { dialog.dismiss(); pickRoom(host, d) })
        body.addView(ui.actionRow("Rename", d.name) { dialog.dismiss(); host.promptText("Rename device", d.name, "Device name") { host.registry.renameDevice(d.id, it) } })
        body.addView(ui.actionRow("Voice aliases", d.aliases.joinToString().ifBlank { "e.g. “my lamp”, “batti”" }) {
            dialog.dismiss(); host.promptText("Aliases (comma separated)", d.aliases.joinToString(", "), "lamp, batti") { host.registry.setAliases(d.id, it.split(',')) }
        })
        if (d.connection == ConnectionType.LAN_HTTP) {
            body.addView(ui.switchRow("Use Home Hub if the phone can't reach it", "Needs a Home Hub online and you signed in", d.config["via_hub"] == "true") { v ->
                host.registry.updateDevice(d.id) { it.copy(config = it.config + ("via_hub" to v.toString())) }
            })
        }

        body.addView(ui.divider())
        body.addView(ui.text("Diagnostics", 13f, bold = true).apply { setPadding(0, ui.dp(6), 0, ui.dp(2)) })
        body.addView(ui.text("Address: ${d.address}\nCapabilities: ${d.capabilities.joinToString { it.label }.ifBlank { "none" }}", 12f, ui.muted))
        host.registry.activity().filter { it.deviceId == d.id }.take(3).forEach { e ->
            body.addView(ui.text("${if (e.success) "✓" else "✗"} ${DateUtils.getRelativeTimeSpanString(e.timeMillis)}: ${e.summary}", 12f, ui.muted).apply { setPadding(0, ui.dp(2), 0, 0) })
        }
        body.addView(ui.button("Remove device") {
            dialog.dismiss()
            host.confirm("Remove ${d.name}?", "It stays on its network; JARVIS just forgets it.", "Remove") { HomeSecrets.remove(d.id); host.registry.removeDevice(d.id) }
        }.apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)) })
        dialog.show()
    }

    private fun act(host: HomeHost, d: HomeDevice, action: DeviceAction, dialog: androidx.appcompat.app.AlertDialog?) {
        host.toast("Sending…")
        host.background({ host.controller.run(d.id, action) }) { r ->
            host.toast(r.message + if (r is CommandResult.Success && !r.verified && action.type != DeviceActionType.REFRESH) " (not confirmed by the device)" else "")
            if (r is CommandResult.Success && dialog != null) dialog.dismiss()
        }
    }

    private fun pickRoom(host: HomeHost, d: HomeDevice) {
        val rooms = host.registry.rooms()
        val labels = listOf("No room") + rooms.map { it.name } + "＋ New room…"
        host.chooseOne("Room for ${d.name}", labels, if (d.roomId == null) 0 else rooms.indexOfFirst { it.id == d.roomId } + 1) { which ->
            when {
                which == 0 -> host.registry.assignRoom(d.id, null)
                which <= rooms.size -> host.registry.assignRoom(d.id, rooms[which - 1].id)
                else -> host.promptText("New room", "", "Room name") { n -> host.registry.createRoom(n)?.let { host.registry.assignRoom(d.id, it.id) } }
            }
        }
    }
}
