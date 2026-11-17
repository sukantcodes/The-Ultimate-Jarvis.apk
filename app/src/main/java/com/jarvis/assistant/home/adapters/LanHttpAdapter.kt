package com.jarvis.assistant.home.adapters

import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.CommandResult
import com.jarvis.assistant.home.ConnectionType
import com.jarvis.assistant.home.DeviceAction
import com.jarvis.assistant.home.DeviceActionType
import com.jarvis.assistant.home.DeviceProtocol
import com.jarvis.assistant.home.FailureReason
import com.jarvis.assistant.home.HomeDevice
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Minimal HTTP seam so the adapter is unit-testable without a network. */
interface HttpTransport {
    /** Returns the response body for a successful GET, or throws [IOException]. A non-2xx answer throws [HttpStatusException]. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String
}

class HttpStatusException(val code: Int) : IOException("HTTP $code")

class OkHttpTransport(client: OkHttpClient = OkHttpClient()) : HttpTransport {
    private val http = client.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()

    override fun get(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpStatusException(r.code)
            return r.body?.string().orEmpty()
        }
    }
}

/**
 * Local Wi-Fi control over documented HTTP APIs:
 *  - Tasmota:  GET /cm?cmnd=Power%20On | Off | Toggle | Power ; Dimmer <0-100>
 *  - Shelly Gen1 relay: GET /relay/<n>?turn=on|off|toggle ; GET /relay/<n>
 *  - Custom HTTP: user-supplied on/off/status URLs (config keys url_on, url_off, url_status, status_on_regex)
 * Power state is reported as verified only when the device's own response says so.
 */
class LanHttpAdapter(private val http: HttpTransport) : DeviceAdapter {

    override fun canHandle(device: HomeDevice) = device.connection == ConnectionType.LAN_HTTP

    override fun readState(device: HomeDevice): DeviceState = try {
        when (device.protocol) {
            DeviceProtocol.TASMOTA -> DeviceState(true, parseTasmotaPower(http.get(base(device) + "/cm?cmnd=Power", auth(device))))
            DeviceProtocol.SHELLY_GEN1 -> DeviceState(true, parseShellyIsOn(http.get(base(device) + "/relay/${relay(device)}", auth(device))))
            DeviceProtocol.GENERIC_HTTP -> {
                val url = device.config["url_status"].orEmpty()
                if (url.isBlank()) DeviceState(reachable(device), null, "No status URL configured")
                else {
                    val body = http.get(url, auth(device))
                    val rx = device.config["status_on_regex"].orEmpty()
                    DeviceState(true, if (rx.isBlank()) null else runCatching { Regex(rx).containsMatchIn(body) }.getOrNull())
                }
            }
            else -> DeviceState(false, null, "Not a LAN protocol")
        }
    } catch (e: IOException) {
        DeviceState(false, null, e.message.orEmpty())
    }

    override fun execute(device: HomeDevice, action: DeviceAction): CommandResult {
        if (!device.supports(action.required)) {
            return CommandResult.Failure(FailureReason.UNSUPPORTED, "${device.name} does not support ${action.required.label.lowercase()}.")
        }
        return try {
            when (action.type) {
                DeviceActionType.REFRESH -> {
                    val s = readState(device)
                    if (s.online) CommandResult.Success("${device.name} is online.", true, s.poweredOn)
                    else CommandResult.Failure(FailureReason.OFFLINE, "${device.name} did not answer on the network.")
                }
                DeviceActionType.POWER_ON, DeviceActionType.POWER_OFF, DeviceActionType.TOGGLE -> power(device, action.type)
                DeviceActionType.SET_BRIGHTNESS -> brightness(device, (action.value ?: return CommandResult.Failure(FailureReason.REJECTED, "No brightness level given.")).coerceIn(0, 100))
                DeviceActionType.CONNECT -> CommandResult.Failure(FailureReason.UNSUPPORTED, "Wi-Fi devices do not need a connect step.")
            }
        } catch (e: SocketTimeoutException) {
            CommandResult.Failure(FailureReason.TIMEOUT, "${device.name} took too long to answer.")
        } catch (e: HttpStatusException) {
            CommandResult.Failure(if (e.code == 401 || e.code == 403) FailureReason.PERMISSION else FailureReason.REJECTED, "${device.name} refused the command (HTTP ${e.code}).")
        } catch (e: IOException) {
            CommandResult.Failure(FailureReason.OFFLINE, "${device.name} could not be reached on the network.")
        }
    }

    private fun power(device: HomeDevice, type: DeviceActionType): CommandResult {
        val word = when (type) { DeviceActionType.POWER_ON -> "on"; DeviceActionType.POWER_OFF -> "off"; else -> "toggle" }
        val wantOn: Boolean? = when (type) { DeviceActionType.POWER_ON -> true; DeviceActionType.POWER_OFF -> false; else -> null }
        return when (device.protocol) {
            DeviceProtocol.TASMOTA -> {
                val on = parseTasmotaPower(http.get(base(device) + "/cm?cmnd=Power%20" + word.replaceFirstChar { it.uppercase() }, auth(device)))
                finish(device, on, wantOn)
            }
            DeviceProtocol.SHELLY_GEN1 -> {
                val on = parseShellyIsOn(http.get(base(device) + "/relay/${relay(device)}?turn=$word", auth(device)))
                finish(device, on, wantOn)
            }
            DeviceProtocol.GENERIC_HTTP -> {
                val url = (if (type == DeviceActionType.POWER_OFF) device.config["url_off"] else device.config["url_on"]).orEmpty()
                if (type == DeviceActionType.TOGGLE || url.isBlank()) {
                    return CommandResult.Failure(FailureReason.NOT_CONFIGURED, "${device.name} has no ${if (type == DeviceActionType.POWER_OFF) "off" else "on"} URL configured.")
                }
                http.get(url, auth(device))
                val status = readState(device)
                if (status.poweredOn != null) finish(device, status.poweredOn, wantOn)
                else CommandResult.Success("Sent ${word.uppercase()} to ${device.name}; the device does not report its state, so this is not confirmed.", false)
            }
            else -> CommandResult.Failure(FailureReason.UNSUPPORTED, "Unsupported protocol.")
        }
    }

    private fun brightness(device: HomeDevice, level: Int): CommandResult = when (device.protocol) {
        DeviceProtocol.TASMOTA -> {
            http.get(base(device) + "/cm?cmnd=Dimmer%20$level", auth(device))
            val s = runCatching { JSONObject(http.get(base(device) + "/cm?cmnd=Dimmer", auth(device))).optInt("Dimmer", -1) }.getOrDefault(-1)
            if (s == level) CommandResult.Success("${device.name} brightness is $level%.", true)
            else CommandResult.Success("Sent brightness $level% to ${device.name}; it did not confirm.", false)
        }
        else -> CommandResult.Failure(FailureReason.UNSUPPORTED, "${device.name} does not support brightness over this protocol.")
    }

    private fun finish(device: HomeDevice, reported: Boolean?, wanted: Boolean?): CommandResult = when {
        reported == null -> CommandResult.Success("Sent the command to ${device.name}; it did not report its state.", false)
        wanted != null && reported != wanted -> CommandResult.Failure(FailureReason.ERROR, "${device.name} reports it is ${if (reported) "on" else "off"} instead.")
        else -> CommandResult.Success("${device.name} is now ${if (reported) "on" else "off"}.", true, reported)
    }

    private fun reachable(device: HomeDevice) = try { http.get(base(device)); true } catch (e: HttpStatusException) { true } catch (e: IOException) { false }

    private fun base(d: HomeDevice): String {
        val raw = d.address.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        require("http://$raw".toHttpUrlOrNull() != null) { "Invalid device address" }
        return "http://$raw"
    }

    private fun relay(d: HomeDevice) = (d.config["relay"]?.toIntOrNull() ?: 0).coerceIn(0, 7)

    private fun auth(d: HomeDevice): Map<String, String> {
        val user = d.config["auth_user"].orEmpty()
        val pass = HomeSecrets.get(d.id)
        if (user.isBlank() || pass.isNullOrBlank()) return emptyMap()
        val token = android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)
        return mapOf("Authorization" to "Basic $token")
    }

    companion object {
        /** Tasmota answers {"POWER":"ON"} (or POWER1 on multi-relay). Returns null when no state is present. */
        fun parseTasmotaPower(body: String): Boolean? = runCatching {
            val o = JSONObject(body)
            val key = o.keys().asSequence().firstOrNull { it.startsWith("POWER") } ?: return null
            when (o.optString(key).uppercase()) { "ON" -> true; "OFF" -> false; else -> null }
        }.getOrNull()

        /** Shelly Gen1 relay status contains "ison": true|false. */
        fun parseShellyIsOn(body: String): Boolean? = runCatching {
            val o = JSONObject(body)
            if (o.has("ison")) o.getBoolean("ison") else null
        }.getOrNull()

        fun capabilitiesFor(protocol: DeviceProtocol, hasStatusUrl: Boolean = false): Set<Capability> = when (protocol) {
            DeviceProtocol.TASMOTA -> setOf(Capability.POWER, Capability.BRIGHTNESS, Capability.READ_STATE)
            DeviceProtocol.SHELLY_GEN1 -> setOf(Capability.POWER, Capability.READ_STATE)
            DeviceProtocol.GENERIC_HTTP -> if (hasStatusUrl) setOf(Capability.POWER, Capability.READ_STATE) else setOf(Capability.POWER)
            else -> emptySet()
        }
    }
}
