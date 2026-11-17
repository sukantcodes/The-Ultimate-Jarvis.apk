package com.jarvis.assistant.home.remote

import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.auth.AuthResult
import com.jarvis.assistant.auth.SupabaseConfig
import com.jarvis.assistant.home.CommandResult
import com.jarvis.assistant.home.DeviceAction
import com.jarvis.assistant.home.FailureReason
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.RemoteDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

data class HubInfo(val id: String, val name: String, val lastSeen: Instant?) {
    /** A hub counts as online only if it checked in recently; there is no guess beyond that. */
    fun isOnline(now: Instant = Instant.now()): Boolean = lastSeen != null && now.epochSecond - lastSeen.epochSecond <= ONLINE_WINDOW_S

    companion object { const val ONLINE_WINDOW_S = 90L }
}

/**
 * Remote control through the existing Supabase project: registry sync, Home Hub presence and expiring, deduplicated commands.
 * Uses the signed-in user's own token and the publishable key under Row Level Security; there is no service_role key here.
 * Blocking calls: run off the main thread.
 */
class SupabaseRemote(http: OkHttpClient) {

    private val client = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    val isConfigured: Boolean get() = SupabaseConfig.isConfigured
    val isSignedIn: Boolean get() = AuthManager.session.value != null

    private fun token(): String? = when (val r = runBlocking { AuthManager.validAccessToken() }) {
        is AuthResult.Success -> r.value
        is AuthResult.Failure -> null
    }

    private fun request(path: String, token: String): Request.Builder = Request.Builder().url(SupabaseConfig.url + "/rest/v1/" + path)
        .header("apikey", SupabaseConfig.publishableKey).header("Authorization", "Bearer $token")

    private fun call(req: Request): String = client.newCall(req).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw IOException("Supabase ${r.code}: ${body.take(120)}")
        body
    }

    fun hubs(): List<HubInfo> {
        val t = token() ?: throw IOException("Sign in to use remote control.")
        val arr = JSONArray(call(request("home_hubs?select=id,name,last_seen_at&order=last_seen_at.desc", t).get().build()))
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); HubInfo(o.getString("id"), o.getString("name"), o.optString("last_seen_at").takeIf { s -> s.isNotBlank() && s != "null" }?.let { s -> runCatching { Instant.parse(s) }.getOrNull() }) }
    }

    /** Pushes the device's identity and last known state. Credentials and secrets are never part of the payload. */
    fun syncDevice(device: HomeDevice, roomName: String?) {
        val t = token() ?: throw IOException("Sign in to use remote control.")
        val uid = AuthManager.session.value?.userId ?: throw IOException("Not signed in.")
        val row = JSONObject().put("id", device.id).put("owner_id", uid).put("name", device.name).put("manufacturer", device.manufacturer)
            .put("category", device.category.name).put("connection", device.connection.name).put("protocol", device.protocol.name)
            .put("address", device.address).put("room_name", roomName ?: JSONObject.NULL)
            .put("capabilities", JSONArray(device.capabilities.map { it.name }))
            .put("config", JSONObject(device.config.filterKeys { !it.contains("pass", true) && !it.contains("secret", true) && !it.contains("token", true) } as Map<*, *>))
            .put("status", device.status.name).put("powered_on", device.poweredOn ?: JSONObject.NULL)
            .put("updated_at", Instant.now().toString())
        call(request("home_devices?on_conflict=id", t).header("Prefer", "resolution=merge-duplicates").post(JSONArray().put(row).toString().toRequestBody(json)).build())
    }

    /** Queues one expiring command with a fresh unique id, then waits for the hub's acknowledgement. */
    fun sendAndAwait(device: HomeDevice, action: DeviceAction, roomName: String?, waitSeconds: Long = 20): CommandResult {
        val t = token() ?: return CommandResult.Failure(FailureReason.PERMISSION, "Sign in to JARVIS to control devices remotely.")
        val online = try { hubs().filter { it.isOnline() } } catch (e: IOException) { return CommandResult.Failure(FailureReason.OFFLINE, "Could not reach the cloud: ${e.message}") }
        if (online.isEmpty()) return CommandResult.Failure(FailureReason.OFFLINE, "No Home Hub is online, so ${device.name} cannot be reached remotely.")
        return try {
            syncDevice(device, roomName)
            val uid = AuthManager.session.value?.userId ?: return CommandResult.Failure(FailureReason.PERMISSION, "Not signed in.")
            val id = UUID.randomUUID().toString()
            val now = Instant.now()
            val row = JSONObject().put("id", id).put("owner_id", uid).put("device_id", device.id).put("action", action.toJson())
                .put("status", "pending").put("created_at", now.toString()).put("expires_at", now.plusSeconds(waitSeconds + 10).toString())
            call(request("home_commands", t).post(row.toString().toRequestBody(json)).build())
            await(id, t, waitSeconds, device.name)
        } catch (e: IOException) {
            CommandResult.Failure(FailureReason.ERROR, "Remote command failed: ${e.message}")
        }
    }

    private fun await(id: String, token: String, waitSeconds: Long, name: String): CommandResult {
        val deadline = System.currentTimeMillis() + waitSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            val arr = JSONArray(call(request("home_commands?id=eq.$id&select=status,verified,result_message", token).get().build()))
            val o = arr.optJSONObject(0)
            when (o?.optString("status")) {
                "succeeded" -> return CommandResult.Success(o.optString("result_message").ifBlank { "$name responded." }, o.optBoolean("verified"))
                "failed" -> return CommandResult.Failure(FailureReason.REJECTED, o.optString("result_message").ifBlank { "$name could not run the command." })
                "expired" -> return CommandResult.Failure(FailureReason.TIMEOUT, "No Home Hub picked up the command in time.")
            }
            Thread.sleep(1000)
        }
        return CommandResult.Failure(FailureReason.TIMEOUT, "The Home Hub has not confirmed the command for $name yet. It may still run until it expires.")
    }

    fun dispatcher(): RemoteDispatcher = object : RemoteDispatcher {
        override val isAvailable: Boolean get() = isConfigured && isSignedIn
        override fun dispatch(device: HomeDevice, action: DeviceAction): CommandResult = sendAndAwait(device, action, null)
    }
}
