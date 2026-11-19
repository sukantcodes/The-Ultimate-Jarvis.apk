package com.jarvis.assistant.udn

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.auth.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.min
import kotlin.random.Random

data class UdnState(
    val configured: Boolean = false,
    val signedIn: Boolean = false,
    val enrolled: Boolean = false,
    val deviceId: String? = null,
    val status: String? = null,
    val isParent: Boolean = false,
    val slot: Int? = null,
    val parentsExist: Boolean = false,
    val devices: List<UdnDevice> = emptyList(),
    val groups: List<UdnGroup> = emptyList(),
    val realtimeConnected: Boolean = false,
    val linkRunning: Boolean = false,
    val lastError: String? = null,
    val busy: Boolean = false
) {
    val authorized: Boolean get() = status == "authorized"
}

/**
 * Orchestrates this device's membership in the Universal Device Network: enrollment, heartbeat, capability sync,
 * receiving and executing commands (with duplicate protection), and, for parent devices, the registry and
 * sending commands to other devices.
 */
class UdnManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val settings = UdnSettings.get(app)
    private val api = UdnApi(app)
    private val dedup = UdnDedupStore.forContext(app)
    private val executionGate = Semaphore(3)

    private val _state = MutableStateFlow(
        UdnState(
            configured = SupabaseConfig.isConfigured, signedIn = AuthManager.isSignedIn,
            enrolled = settings.deviceId != null && UdnIdentity.hasKey(), deviceId = settings.deviceId, status = settings.lastKnownStatus
        )
    )
    val state: StateFlow<UdnState> = _state.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var linkJob: Job? = null
    private var realtime: UdnRealtime? = null
    private var lan: UdnLanListener? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var nudgeHeartbeat = false

    val selfId: String? get() = settings.deviceId

    // ------------------------------------------------------------------ enrollment

    suspend fun enroll(name: String? = null): UdnResult<Unit> {
        _state.update { it.copy(busy = true, lastError = null) }
        try {
            if (!AuthManager.isSignedIn) return fail("not_signed_in")
            UdnIdentity.ensureKey()
            val result = register(name ?: defaultName())
            if (result is UdnResult.Err && result.code == "device_revoked") {
                // The old identity was removed from the network. Start over with a brand-new key pair.
                UdnIdentity.reset(); settings.clearEnrollment()
                UdnIdentity.ensureKey()
                return register(name ?: defaultName()).also { if (it is UdnResult.Ok) afterEnroll() }
            }
            if (result is UdnResult.Ok) afterEnroll()
            return result
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun register(name: String): UdnResult<Unit> {
        val body = JSONObject().apply {
            put("public_key", UdnIdentity.publicKeyB64()); put("platform", "android"); put("name", name)
            put("manufacturer", Build.MANUFACTURER.take(60)); put("model", Build.MODEL.take(80))
            put("os_version", "Android ${Build.VERSION.RELEASE}"); put("app_version", BuildConfig.VERSION_NAME.take(40))
        }
        return when (val r = api.call("register", body, asDevice = null)) {
            is UdnResult.Ok -> {
                settings.deviceId = r.value.getString("device_id")
                settings.lastKnownStatus = r.value.optString("status")
                _state.update { it.copy(enrolled = true, deviceId = settings.deviceId, status = settings.lastKnownStatus, parentsExist = r.value.optBoolean("parents_exist")) }
                UdnResult.Ok(Unit)
            }
            is UdnResult.Err -> { setError(r); r }
        }
    }

    private suspend fun afterEnroll() {
        refreshSelf()
        pushCapabilities(force = true)
        if (_state.value.authorized) { refreshRegistry(); UdnLinkService.start(app) }
    }

    private fun defaultName(): String =
        Build.MODEL.takeIf { it.isNotBlank() }?.let { m -> if (Build.MANUFACTURER.isNotBlank() && !m.startsWith(Build.MANUFACTURER, true)) "${Build.MANUFACTURER} $m" else m }
            ?.replaceFirstChar { it.uppercase() }?.take(60) ?: "Android device"

    // ------------------------------------------------------------------ self + registry

    suspend fun refreshSelf(): UdnResult<JSONObject> {
        val id = settings.deviceId ?: return UdnResult.Err("not_enrolled", UdnErrors.describe("not_enrolled"))
        val r = api.call("me", asDevice = id)
        when (r) {
            is UdnResult.Ok -> {
                val dev = r.value.optJSONObject("device")
                val status = dev?.optString("status")
                settings.lastKnownStatus = status
                _state.update {
                    it.copy(
                        enrolled = true, deviceId = id, status = status, isParent = r.value.optBoolean("is_parent"),
                        slot = if (r.value.isNull("slot")) null else r.value.optInt("slot"),
                        parentsExist = r.value.optBoolean("parents_exist"), signedIn = true, lastError = null
                    )
                }
                if (status == "authorized") UdnLinkService.start(app)
            }
            is UdnResult.Err -> handleAuthError(r)
        }
        return r
    }

    suspend fun refreshRegistry(): UdnResult<Unit> {
        if (!_state.value.isParent) return UdnResult.Ok(Unit)
        return when (val r = api.call("list_devices")) {
            is UdnResult.Ok -> {
                val devices = r.value.optJSONArray("devices").toList { UdnDevice.fromJson(it) }
                val groups = r.value.optJSONArray("groups").toList { g ->
                    UdnGroup(g.getString("id"), g.optString("name"), g.optJSONArray("device_ids")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
                }
                _state.update { it.copy(devices = devices, groups = groups) }
                UdnResult.Ok(Unit)
            }
            is UdnResult.Err -> { handleAuthError(r); r }
        }
    }

    /** Devices as the voice layer sees them (this device flagged as self). */
    fun deviceRefs(): List<UdnDeviceRef> = _state.value.devices.filter { it.status != "revoked" }.map {
        UdnDeviceRef(it.id, it.name, it.manufacturer, it.model, it.platform, isSelf = it.id == settings.deviceId, authorized = it.isAuthorized)
    }

    private fun handleAuthError(r: UdnResult.Err) {
        when (r.code) {
            "device_revoked" -> {
                UdnIdentity.reset(); settings.clearEnrollment()
                UdnLinkService.stop(app)
                _state.update { UdnState(configured = it.configured, signedIn = it.signedIn, lastError = UdnErrors.describe("device_revoked")) }
            }
            "unknown_device" -> {
                settings.clearEnrollment()
                _state.update { it.copy(enrolled = false, deviceId = null, status = null, isParent = false, slot = null, devices = emptyList()) }
            }
            else -> setError(r)
        }
    }

    private fun setError(r: UdnResult.Err) { _state.update { it.copy(lastError = r.message) } }
    private fun <T> fail(code: String): UdnResult<T> =
        UdnResult.Err(code, UdnErrors.describe(code)).also { setError(it) }

    // ------------------------------------------------------------------ pairing and administration

    suspend fun claimFirstParent(): UdnResult<Unit> = simple("claim_first_parent") { refreshSelf(); refreshRegistry() }

    suspend fun createPairingCode(parent: Boolean): UdnResult<Pair<String, Long>> =
        when (val r = api.call("create_pairing", JSONObject().put("kind", if (parent) "parent" else "child"))) {
            is UdnResult.Ok -> UdnResult.Ok(r.value.getString("code") to (parseInstantMs(r.value.optString("expires_at")) ?: 0L))
            is UdnResult.Err -> { setError(r); r }
        }

    suspend fun redeemPairingCode(code: String): UdnResult<Unit> =
        simple("redeem_pairing", JSONObject().put("code", code)) { refreshSelf(); pushCapabilities(force = true) }

    suspend fun decideDevice(targetId: String, authorize: Boolean): UdnResult<Unit> =
        simple("decide_device", JSONObject().put("target", targetId).put("decision", if (authorize) "authorize" else "reject")) { refreshRegistry() }

    suspend fun revokeDevice(targetId: String): UdnResult<Unit> =
        simple("revoke", JSONObject().put("target", targetId)) {
            if (targetId == settings.deviceId) { UdnIdentity.reset(); settings.clearEnrollment(); UdnLinkService.stop(app); _state.update { UdnState(configured = it.configured, signedIn = it.signedIn) } }
            else refreshRegistry()
        }

    suspend fun renameDevice(targetId: String, name: String): UdnResult<Unit> =
        simple("rename", JSONObject().put("target", targetId).put("name", name)) { refreshRegistry() }

    suspend fun setPolicy(targetId: String, capability: String, policy: String): UdnResult<Unit> =
        simple("set_policy", JSONObject().put("target", targetId).put("capability", capability).put("policy", policy)) { refreshRegistry() }

    suspend fun createGroup(name: String): UdnResult<Unit> = simple("create_group", JSONObject().put("name", name)) { refreshRegistry() }
    suspend fun deleteGroup(id: String): UdnResult<Unit> = simple("delete_group", JSONObject().put("group_id", id)) { refreshRegistry() }
    suspend fun setGroupMember(groupId: String, deviceId: String, remove: Boolean): UdnResult<Unit> =
        simple("group_members", JSONObject().put("group_id", groupId).put("device_id", deviceId).put("remove", remove)) { refreshRegistry() }

    suspend fun commandHistory(deviceId: String?): UdnResult<List<UdnCommandRecord>> =
        when (val r = api.call("list_commands", JSONObject().apply { deviceId?.let { put("device_id", it) }; put("limit", 25) })) {
            is UdnResult.Ok -> UdnResult.Ok(r.value.optJSONArray("commands").toList { UdnCommandRecord.fromJson(it) })
            is UdnResult.Err -> r
        }

    suspend fun connectionHistory(deviceId: String): UdnResult<List<Triple<Long?, Long?, String?>>> =
        when (val r = api.call("list_sessions", JSONObject().put("device_id", deviceId))) {
            is UdnResult.Ok -> UdnResult.Ok(r.value.optJSONArray("sessions").toList { s ->
                Triple(parseInstantMs(s.optStringOrNull("started_at")), parseInstantMs(s.optStringOrNull("ended_at") ?: s.optStringOrNull("last_heartbeat_at")), s.optStringOrNull("connection_type"))
            })
            is UdnResult.Err -> r
        }

    suspend fun auditEvents(): UdnResult<List<UdnAuditEvent>> =
        when (val r = api.call("audit_list", JSONObject().put("limit", 50))) {
            is UdnResult.Ok -> UdnResult.Ok(r.value.optJSONArray("events").toList { e ->
                UdnAuditEvent(e.optLong("id"), e.optString("action"), e.optStringOrNull("actor_device_id"), e.optStringOrNull("target_device_id"),
                    parseInstantMs(e.optStringOrNull("created_at")), e.optJSONObject("detail"))
            })
            is UdnResult.Err -> r
        }

    private suspend fun simple(action: String, body: JSONObject = JSONObject(), after: suspend () -> Unit = {}): UdnResult<Unit> {
        _state.update { it.copy(busy = true) }
        try {
            return when (val r = api.call(action, body)) {
                is UdnResult.Ok -> { after(); _state.update { it.copy(lastError = null) }; UdnResult.Ok(Unit) }
                is UdnResult.Err -> { handleAuthError(r); r }
            }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    // ------------------------------------------------------------------ capabilities and heartbeat

    suspend fun pushCapabilities(force: Boolean = false) {
        val id = settings.deviceId ?: return
        val report = UdnHandlers.report(app)
        val hash = MessageDigest.getInstance("SHA-256").digest(report.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        if (!force && hash == settings.lastCapabilityHash) return
        if (api.call("update_capabilities", report, id) is UdnResult.Ok) settings.lastCapabilityHash = hash
    }

    private fun batteryInfo(): Pair<Int?, Boolean?> {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        return pct to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }

    suspend fun heartbeat(): UdnResult<Int> {
        val id = settings.deviceId ?: return UdnResult.Err("not_enrolled", UdnErrors.describe("not_enrolled"))
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val (pct, charging) = batteryInfo()
        val lanHost = UdnLan.localIpv4(app)
        val lanPort = lan?.port
        val body = JSONObject().apply {
            pct?.let { put("battery_pct", it) }; charging?.let { put("charging", it) }
            put("connection_type", UdnNetwork.typeOf(caps))
            if (lanHost != null && lanPort != null && lanPort >= 1024) { put("lan_host", lanHost); put("lan_port", lanPort) }
        }
        return when (val r = api.call("heartbeat", body, id)) {
            is UdnResult.Ok -> {
                val status = r.value.optString("status")
                val changed = status != _state.value.status
                settings.lastKnownStatus = status
                _state.update { it.copy(status = status, signedIn = true) }
                if (changed) refreshSelf()
                UdnResult.Ok(r.value.optInt("pending_commands", 0))
            }
            is UdnResult.Err -> { handleAuthError(r); r }
        }
    }

    // ------------------------------------------------------------------ background link

    /** Starts heartbeat, realtime wake-ups, LAN nudges, network-change handling and command polling. Idempotent. */
    fun startLink(scope: CoroutineScope) {
        if (linkJob?.isActive == true) return
        _state.update { it.copy(linkRunning = true) }
        linkJob = scope.launch {
            lan = UdnLanListener { nudge() }.also { it.start(this) }
            realtime = UdnRealtime(app, onWake = { kind, target ->
                if (kind == "command" && (target == null || target == settings.deviceId)) nudge()
                else if (kind == "registry") scope.launch { refreshSelf(); refreshRegistry() }
            }, onConnectionChanged = { up -> _state.update { it.copy(realtimeConnected = up) } }).also { it.start(this) }
            registerNetworkCallback()
            launch { heartbeatLoop() }
            launch { pollLoop(this) }
            refreshSelf(); pushCapabilities(); refreshRegistry()
        }
    }

    fun stopLink() {
        linkJob?.cancel(); linkJob = null
        realtime?.stop(); realtime = null
        lan?.stop(); lan = null
        networkCallback?.let { runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        _state.update { it.copy(linkRunning = false, realtimeConnected = false) }
    }

    /** Ask the poll loop to look for commands now (realtime wake, LAN nudge, heartbeat hint, network change). */
    fun nudge() { wake.trySend(Unit) }

    private fun registerNetworkCallback() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // A different Wi-Fi, mobile data or a recovered connection: reconnect now instead of waiting out a backoff.
                nudgeHeartbeat = true
                realtime?.reconnectNow()
                nudge()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); networkCallback = cb }
    }

    private suspend fun heartbeatLoop() {
        var failures = 0
        while (true) {
            if (settings.deviceId == null || _state.value.status == "revoked") { delay(UDN_HEARTBEAT_MS); continue }
            val r = heartbeat()
            var wait = UDN_HEARTBEAT_MS
            if (r is UdnResult.Ok) {
                failures = 0
                if (r.value > 0) nudge()
                if (_state.value.authorized) { pushCapabilities(); retryUnreported() }
            } else {
                failures++
                wait = min(60_000L, 3_000L * (1L shl min(failures, 4))) // 6 s, 12 s, 24 s, 48 s, 60 s
            }
            val jitter = Random.nextLong(0, 4_000)
            var waited = 0L
            while (waited < wait + jitter && !nudgeHeartbeat) { delay(500); waited += 500 }
            nudgeHeartbeat = false
        }
    }

    private suspend fun pollLoop(scope: CoroutineScope) {
        var failures = 0
        while (true) {
            if (!_state.value.authorized) { delay(5_000); continue }
            when (val r = api.call("poll_commands")) {
                is UdnResult.Ok -> {
                    failures = 0
                    val cmds = r.value.optJSONArray("commands").toList { UdnIncomingCommand.fromJson(it) }
                    cmds.forEach { c -> scope.launch { executionGate.withPermit { runCommand(c) } } }
                }
                is UdnResult.Err -> { failures++; handleAuthError(r) }
            }
            // With realtime up we only need the periodic safety poll; without it we poll quickly.
            val idle = if (failures > 0) min(60_000L, 2_000L * (1L shl min(failures, 5))) else if (_state.value.realtimeConnected) 60_000L else 12_000L
            kotlinx.coroutines.withTimeoutOrNull(idle) { wake.receive() }
        }
    }

    // ------------------------------------------------------------------ executing incoming commands

    private suspend fun runCommand(cmd: UdnIncomingCommand) {
        val seen = dedup.get(cmd.id)
        if (seen != null) { // redelivered: never execute twice, just make sure the server has the result
            if (seen.status != "running" && !seen.reported) report(cmd.id, seen.status, seen.result, seen.errorCode, seen.errorMessage)
            return
        }
        if (!dedup.begin(cmd.id)) return
        val now = System.currentTimeMillis()
        if (cmd.expiresMs in 1 until now) return finish(cmd.id, "rejected", null, "expired", UdnErrors.describe("expired"))
        api.call("ack", JSONObject().put("command_id", cmd.id))
        if (!settings.remoteControlEnabled) return finish(cmd.id, "rejected", null, "remote_disabled", "Remote control is switched off on this device.")
        val handler = UdnHandlers.find(cmd.capability) ?: return finish(cmd.id, "rejected", null, "unknown_capability", "This device doesn't know that command.")
        val availability = handler.availability(app)
        if (!availability.available) return finish(cmd.id, "failed", null, "capability_unavailable", availability.reason ?: "Not available on this device.")

        if (cmd.requiresConsent) {
            val left = (cmd.expiresMs - System.currentTimeMillis()).coerceIn(5_000L, 60_000L)
            val source = _state.value.devices.firstOrNull { it.id == cmd.sourceDeviceId }?.name ?: "One of your JARVIS devices"
            val answer = UdnConsent.request(app, cmd.id, "Allow remote ${UdnCapabilities.label(cmd.capability)}?", "$source wants to ${UdnCapabilities.label(cmd.capability)} on this device.", left)
            when (answer) {
                true -> Unit
                false -> return finish(cmd.id, "rejected", null, "consent_denied", UdnErrors.describe("consent_denied"))
                null -> return finish(cmd.id, "rejected", null, "consent_timeout", UdnErrors.describe("consent_timeout"))
            }
        }
        api.call("report_result", JSONObject().put("command_id", cmd.id).put("status", "running"))
        val result = try {
            withTimeout(30_000) { handler.execute(app, cmd.params) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            UdnHandlerResult.Failure("timeout", "The command took too long on the device.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Handler crashed: ${cmd.capability} ${e.javaClass.simpleName}")
            UdnHandlerResult.Failure("handler_error", "The device hit an error running the command.")
        }
        when (result) {
            is UdnHandlerResult.Success -> finish(cmd.id, "succeeded", result.data, null, null)
            is UdnHandlerResult.Failure -> finish(cmd.id, "failed", null, result.code, result.message)
        }
    }

    private suspend fun finish(id: String, status: String, result: JSONObject?, code: String?, message: String?) {
        dedup.finish(id, status, result, code, message)
        report(id, status, result, code, message)
    }

    private suspend fun report(id: String, status: String, result: JSONObject?, code: String?, message: String?) {
        val body = JSONObject().put("command_id", id).put("status", status)
        result?.let { body.put("result", it) }; code?.let { body.put("error_code", it) }; message?.let { body.put("error_message", it) }
        var delayMs = 1_000L
        repeat(5) {
            when (val r = api.call("report_result", body)) {
                is UdnResult.Ok -> { dedup.markReported(id); return }
                is UdnResult.Err -> {
                    // not_found / device_revoked will never succeed; everything else may after a backoff
                    if (r.code == "not_found" || r.code == "device_revoked") { dedup.markReported(id); return }
                }
            }
            delay(delayMs); delayMs = min(delayMs * 2, 15_000L)
        }
    }

    private suspend fun retryUnreported() {
        dedup.unreported().forEach { (id, e) -> report(id, e.status, e.result, e.errorCode, e.errorMessage) }
    }

    // ------------------------------------------------------------------ sending commands (parents)

    /**
     * Sends a command and waits for the device's real outcome. Network retries reuse the same command nonce, so the
     * server can never enqueue it twice. The returned record is terminal (succeeded/failed/rejected/expired/...).
     */
    suspend fun sendCommand(
        target: UdnDevice, capability: String, params: JSONObject = JSONObject(),
        confirmed: Boolean = false, ttlSeconds: Int = 45, onProgress: (String) -> Unit = {}
    ): UdnResult<UdnCommandRecord> {
        val nonce = UdnSigning.newNonce()
        val args = JSONObject().put("target", target.id).put("capability", capability).put("params", params)
            .put("command_nonce", nonce).put("confirmed", confirmed).put("ttl_seconds", ttlSeconds)
        var sent: UdnResult<JSONObject> = UdnResult.Err("network", UdnErrors.describe("network"))
        var backoff = 700L
        for (attempt in 0 until 3) {
            sent = api.call("send_command", args)
            val current = sent
            if (current is UdnResult.Ok || (current is UdnResult.Err && current.code != "network")) break
            if (attempt < 2) { delay(backoff); backoff *= 2 }
        }
        val s = sent
        if (s is UdnResult.Err) return s
        val commandId = (s as UdnResult.Ok).value.getString("command_id")
        onProgress("queued")
        target.lanHost?.let { host -> target.lanPort?.let { port -> UdnLan.nudge(app, host, port) } }

        val deadline = System.currentTimeMillis() + ttlSeconds * 1000L + 35_000L
        var polls = 0
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            delay(if (polls++ < 12) 1_000L else 2_000L)
            when (val st = api.call("command_status", JSONObject().put("command_id", commandId))) {
                is UdnResult.Ok -> {
                    val rec = UdnCommandRecord.fromJson(st.value.getJSONObject("command"))
                    if (rec.status != last) { last = rec.status; onProgress(rec.status) }
                    if (rec.isTerminal) return UdnResult.Ok(rec)
                }
                is UdnResult.Err -> if (st.code != "network" && st.code != "rate_limited") return st
            }
        }
        return UdnResult.Err("timed_out", UdnErrors.describe("timed_out"))
    }

    companion object {
        private const val TAG = "UdnManager"
        @Volatile private var instance: UdnManager? = null
        fun get(context: Context): UdnManager =
            instance ?: synchronized(this) { instance ?: UdnManager(context.applicationContext).also { instance = it } }
    }
}
