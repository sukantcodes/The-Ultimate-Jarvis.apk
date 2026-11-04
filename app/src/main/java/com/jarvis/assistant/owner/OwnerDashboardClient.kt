package com.jarvis.assistant.owner

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.auth.AuthResult
import com.jarvis.assistant.auth.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Talks to the "owner-dashboard" Edge Function. The password is sent over TLS to the server and is never
 * stored, logged or kept in a field; verification and every authorization decision happen server-side.
 * Request and response bodies are never logged.
 */
object OwnerDashboardClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private class Raw(val code: Int, val body: JsonObject?)

    // ── Session ─────────────────────────────────────────────────────────────────────────────────

    suspend fun verify(password: CharArray): OwnerResult<VerifiedSession> {
        val payload = JsonObject().apply {
            addProperty("action", "verify")
            addProperty("password", String(password))
        }
        val raw = post(payload, sessionToken = null)
        java.util.Arrays.fill(password, '\u0000')
        return when (raw) {
            is OwnerResult.Ok -> {
                val r = raw.value
                when (r.code) {
                    200 -> {
                        val token = r.body?.str("token")
                        val exp = r.body?.str("expires_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                        if (token.isNullOrBlank() || exp == null) OwnerResult.Error
                        else OwnerResult.Ok(VerifiedSession(token, exp)).also { OwnerSession.start(token, exp) }
                    }
                    401 -> OwnerResult.Denied
                    429 -> OwnerResult.Locked(r.body?.get("retry_after")?.asIntOrNull() ?: 60)
                    503 -> OwnerResult.Unavailable
                    else -> OwnerResult.Error
                }
            }
            else -> raw.asFailure()
        }
    }

    /** Revokes the session on the server (best effort) and always clears it locally. */
    suspend fun endSession() {
        // Drop local access immediately; the server-side revoke then runs with the captured token.
        val token = OwnerSession.token()
        OwnerSession.clear()
        if (token != null) post(JsonObject().apply { addProperty("action", "logout") }, sessionToken = token)
    }

    // ── Data ────────────────────────────────────────────────────────────────────────────────────

    suspend fun overview(): OwnerResult<OverviewData> =
        call(JsonObject().apply { addProperty("action", "summary") }) { b ->
            val c = b.obj("counts") ?: JsonObject()
            val modes = b.obj("modes") ?: JsonObject()
            OverviewData(
                totalAccounts = c.long("total_accounts"),
                accountsActive30d = c.long("accounts_active_30d"),
                totalDevices = c.long("total_devices"),
                devicesActive24h = c.long("devices_active_24h"),
                devicesActive7d = c.long("devices_active_7d"),
                signins24h = c.long("signins_24h"),
                signins7d = c.long("signins_7d"),
                appOpens24h = c.long("app_opens_24h"),
                distinctIps30d = c.long("distinct_ips_30d"),
                freeUsers = modes.long("free"),
                proUsers = modes.get("pro")?.takeIf { !it.isJsonNull }?.asLong,
                proNote = modes.str("note") ?: "",
                presenceNote = b.obj("presence")?.str("note") ?: "",
                ipRetentionDays = b.obj("retention")?.get("ip_days")?.asIntOrNull() ?: 0,
                recentSignins = b.arr("recent_signins").toEventRows(),
                recentActivity = b.arr("recent_activity").toEventRows()
            )
        }

    suspend fun devices(page: Int, search: String, activeWithinDays: Int): OwnerResult<OwnerPage> =
        call(JsonObject().apply {
            addProperty("action", "devices"); addProperty("page", page); addProperty("page_size", PAGE_SIZE)
            if (search.isNotBlank()) addProperty("search", search.trim())
            if (activeWithinDays > 0) addProperty("active_within_days", activeWithinDays)
        }) { b ->
            b.toPage { r ->
                val model = r.str("device_model") ?: "Unknown device"
                OwnerRow(
                    title = model,
                    line2 = "Android ${r.str("android_version") ?: "?"} · JARVIS ${r.str("app_version") ?: "?"}",
                    line3 = "Device ${shortId(r.str("install_id"))} · Account ${shortId(r.str("user_id"))}\n" +
                        "First seen ${fmt(r.str("first_seen_at"))} · Last active ${fmt(r.str("last_seen_at"))}\n" +
                        "Mode: Free (no Pro backend)",
                    badge = if (r.str("status") == "recently_active") "ACTIVE 24H" else "INACTIVE"
                )
            }
        }

    suspend fun ips(page: Int, search: String): OwnerResult<OwnerPage> =
        call(JsonObject().apply {
            addProperty("action", "ips"); addProperty("page", page); addProperty("page_size", PAGE_SIZE)
            if (search.isNotBlank()) addProperty("search", search.trim())
        }) { b ->
            b.toPage { r ->
                val dev = r.obj("app_devices")
                OwnerRow(
                    title = r.str("ip") ?: "—",
                    line2 = "Account ${shortId(r.str("user_id"))} · ${dev?.str("device_model") ?: "device"}",
                    line3 = "First ${fmt(r.str("first_seen_at"))} · Last ${fmt(r.str("last_seen_at"))}",
                    badge = "${r.get("request_count")?.asIntOrNull() ?: 0}×"
                )
            }
        }

    suspend fun events(page: Int, type: String): OwnerResult<OwnerPage> =
        call(JsonObject().apply {
            addProperty("action", "events"); addProperty("page", page); addProperty("page_size", PAGE_SIZE)
            if (type.isNotBlank()) addProperty("event_type", type)
        }) { b -> b.toPage { it.toEventRow() } }

    // ── Plumbing ────────────────────────────────────────────────────────────────────────────────

    const val PAGE_SIZE = 20

    private suspend fun <T> call(payload: JsonObject, parse: (JsonObject) -> T): OwnerResult<T> =
        when (val raw = post(payload, sessionToken = OwnerSession.token() ?: return OwnerResult.SessionExpired)) {
            is OwnerResult.Ok -> {
                val r = raw.value
                when {
                    r.code == 200 && r.body != null ->
                        try { OwnerResult.Ok(parse(r.body)) } catch (_: Exception) { OwnerResult.Error }
                    r.code == 401 -> { OwnerSession.clear(); OwnerResult.SessionExpired }
                    else -> OwnerResult.Error
                }
            }
            else -> raw.asFailure()
        }

    private suspend fun post(payload: JsonObject, sessionToken: String?): OwnerResult<Raw> {
        if (!SupabaseConfig.isConfigured) return OwnerResult.Unavailable
        val access = when (val t = AuthManager.validAccessToken()) {
            is AuthResult.Success -> t.value
            is AuthResult.Failure -> return OwnerResult.NotSignedIn
        }
        return withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(SupabaseConfig.url + "/functions/v1/owner-dashboard")
                    .header("apikey", SupabaseConfig.publishableKey)
                    .header("Authorization", "Bearer $access")
                    .apply { if (sessionToken != null) header("x-dashboard-session", sessionToken) }
                    .post(payload.toString().toRequestBody(jsonType))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                    OwnerResult.Ok(Raw(resp.code, obj))
                }
            } catch (_: IOException) {
                OwnerResult.Network
            } catch (_: Exception) {
                OwnerResult.Error
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> OwnerResult<*>.asFailure(): OwnerResult<T> = when (this) {
        is OwnerResult.Ok -> OwnerResult.Error
        else -> this as OwnerResult<T>
    }

    // ── JSON helpers ────────────────────────────────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

    private fun JsonObject.long(key: String): Long =
        get(key)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asLong }.getOrNull() } ?: 0L

    private fun JsonElement.asIntOrNull(): Int? = runCatching { asInt }.getOrNull()

    /** Child object, or null when the key is missing or JSON null (a plain getAsJsonObject would throw). */
    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.toPage(row: (JsonObject) -> OwnerRow): OwnerPage {
        val items = arr("items") ?: JsonArray()
        return OwnerPage(
            rows = items.map { row(it.asJsonObject) },
            total = get("total")?.asIntOrNull() ?: 0,
            page = get("page")?.asIntOrNull() ?: 0,
            pageSize = get("page_size")?.asIntOrNull() ?: PAGE_SIZE,
            note = str("note") ?: ""
        )
    }

    private fun JsonArray?.toEventRows(): List<OwnerRow> =
        this?.map { it.asJsonObject.toEventRow() } ?: emptyList()

    private fun JsonObject.toEventRow(): OwnerRow {
        val type = str("event_type")
        return OwnerRow(
            title = when (type) { "sign_in" -> "Sign-in"; "app_open" -> "App opened"; else -> "Sign-in" },
            line2 = "Account ${shortId(str("user_id"))} · ${obj("app_devices")?.str("device_model") ?: "device"}",
            line3 = fmt(str("created_at"))
        )
    }

    private fun shortId(id: String?): String = id?.take(8)?.let { "$it…" } ?: "—"

    private fun fmt(iso: String?): String =
        iso?.let { runCatching {
            java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
        }.getOrNull() } ?: "—"
}
