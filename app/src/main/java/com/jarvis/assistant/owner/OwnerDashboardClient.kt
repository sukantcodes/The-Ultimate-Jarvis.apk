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

    suspend fun trends(days: Int): OwnerResult<TrendsData> =
        call(JsonObject().apply { addProperty("action", "trends"); addProperty("days", days) }) { b ->
            val sec = b.obj("security") ?: JsonObject()
            TrendsData(
                days = b.long("days").toInt(),
                daily = (b.arr("daily") ?: JsonArray()).map {
                    val o = it.asJsonObject
                    DayPoint(o.str("day") ?: "", o.long("sign_ins").toInt(), o.long("app_opens").toInt(), o.long("new_devices").toInt())
                },
                models = b.arr("models").toCounts(),
                androidVersions = b.arr("android_versions").toCounts(),
                appVersions = b.arr("app_versions").toCounts(),
                providers = b.arr("providers").toCounts(),
                lockedNow = sec.long("locked_now"),
                failedAttempts24h = sec.long("failed_attempts_24h"),
                activeDashboardSessions = sec.long("active_dashboard_sessions")
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
                    badge = if (r.str("status") == "recently_active") "ACTIVE 24H" else "INACTIVE",
                    tone = if (r.str("status") == "recently_active") OwnerPalette.GREEN else OwnerPalette.GREY,
                    detail = "Model\n$model\n\nAndroid version\n${r.str("android_version") ?: "?"}\n\n" +
                        "JARVIS version\n${r.str("app_version") ?: "?"}\n\nDevice (install) ID\n${r.str("install_id") ?: "—"}\n\n" +
                        "Account ID\n${r.str("user_id") ?: "—"}\n\nFirst registered\n${fmt(r.str("first_seen_at"))}\n\n" +
                        "Last active\n${fmt(r.str("last_seen_at"))}\n\nMode\nFree (no Pro entitlement backend)"
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
                    badge = "${r.get("request_count")?.asIntOrNull() ?: 0}×",
                    tone = OwnerPalette.CYAN,
                    detail = "IP address\n${r.str("ip") ?: "—"}\n\nAccount ID\n${r.str("user_id") ?: "—"}\n\n" +
                        "Device ID\n${dev?.str("install_id") ?: r.str("device_id") ?: "—"}\n\nFirst observed\n${fmt(r.str("first_seen_at"))}\n\n" +
                        "Last observed\n${fmt(r.str("last_seen_at"))}\n\nObserved requests\n${r.get("request_count")?.asIntOrNull() ?: 0}\n\n" +
                        "IP addresses are approximate network identifiers, not locations."
                )
            }
        }

    suspend fun events(page: Int, type: String): OwnerResult<OwnerPage> =
        call(JsonObject().apply {
            addProperty("action", "events"); addProperty("page", page); addProperty("page_size", PAGE_SIZE)
            if (type.isNotBlank()) addProperty("event_type", type)
        }) { b -> b.toPage { it.toEventRow() } }

    suspend fun blocks(): OwnerResult<OwnerPage> =
        call(JsonObject().apply { addProperty("action", "blocks") }) { b ->
            val items = b.arr("items") ?: JsonArray()
            OwnerPage(items.map { e ->
                val r = e.asJsonObject
                val type = r.str("block_type") ?: "?"
                val target = r.str("target_value") ?: "—"
                OwnerRow(target, type.uppercase() + " BLOCK", (r.str("reason") ?: "").ifBlank { "No reason supplied" } + " · " + fmt(r.str("created_at")), "BLOCKED", OwnerPalette.RED, "BLOCK|" + type + "|" + target)
            }, b.get("total")?.asIntOrNull() ?: items.size(), 0, PAGE_SIZE)
        }

    suspend fun users(page: Int, search: String): OwnerResult<OwnerPage> =
        call(JsonObject().apply {
            addProperty("action", "users"); addProperty("page", page); addProperty("page_size", PAGE_SIZE)
            if (search.isNotBlank()) addProperty("search", search.trim())
        }) { b ->
            b.toPage { r ->
                val email = r.str("email") ?: "No email available"
                val id = r.str("user_id") ?: "—"
                val devs = r.arr("devices") ?: JsonArray()
                val ips = r.arr("ips") ?: JsonArray()
                val deviceText = devs.mapNotNull { it.asJsonObject.str("device_model") }.distinct().joinToString(", ").ifBlank { "No device reported" }
                val ipText = ips.mapNotNull { it.asString }.distinct().joinToString(", ").ifBlank { "No IP observed" }
                val blocked = r.get("blocked")?.asBoolean == true
                OwnerRow(email, devs.size().toString() + " device(s) · " + ips.size() + " IP(s)", "Account " + shortId(id) + " · " + if (blocked) "BLOCKED" else "ALLOWED", if (blocked) "BLOCKED" else "USER", if (blocked) OwnerPalette.RED else OwnerPalette.GREEN, "Email\n" + email + "\n\nUser ID\n" + id + "\n\nDevices\n" + deviceText + "\n\nObserved IPs\n" + ipText + "\n\nCreated\n" + fmt(r.str("created_at")) + "\n\nLast sign in\n" + fmt(r.str("last_sign_in_at")))
            }
        }

    suspend fun setBlock(type: String, target: String, reason: String = "", blocked: Boolean): OwnerResult<Boolean> =
        call(JsonObject().apply {
            addProperty("action", if (blocked) "block" else "unblock")
            addProperty("block_type", type); addProperty("target_value", target)
            if (reason.isNotBlank()) addProperty("reason", reason)
        }) { true }
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

    private fun JsonArray?.toCounts(): List<LabelCount> =
        this?.map { val o = it.asJsonObject; LabelCount(o.str("label") ?: "?", o.long("count").toInt()) } ?: emptyList()

    private fun JsonArray?.toEventRows(): List<OwnerRow> =
        this?.map { it.asJsonObject.toEventRow() } ?: emptyList()

    private fun JsonObject.toEventRow(): OwnerRow {
        val type = str("event_type")
        return OwnerRow(
            title = when (type) { "sign_in" -> "Sign-in"; "app_open" -> "App opened"; else -> "Sign-in" },
            line2 = "Account ${shortId(str("user_id"))} · ${obj("app_devices")?.str("device_model") ?: "device"}",
            line3 = fmt(str("created_at")),
            tone = if (type == "app_open") OwnerPalette.BLUE else OwnerPalette.VIOLET,
            badge = if (type == "app_open") "OPEN" else "SIGN-IN",
            detail = "Event\n${if (type == "app_open") "App opened" else "Sign-in"}\n\nAccount ID\n${str("user_id") ?: "—"}\n\n" +
                "Device\n${obj("app_devices")?.str("device_model") ?: "—"}\n\nTime\n${fmt(str("created_at"))}"
        )
    }

    private fun shortId(id: String?): String = id?.take(8)?.let { "$it…" } ?: "—"

    private fun fmt(iso: String?): String =
        iso?.let { runCatching {
            java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
        }.getOrNull() } ?: "—"
}
