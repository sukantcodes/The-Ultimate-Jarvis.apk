package com.jarvis.assistant.tools

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.JsonObject
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Executes only user-installed HTTPS REST manifests. No downloaded code, redirects, local hosts or shell execution. */
class ConnectorTools(context: Context) : Tool {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "chatbox-workspace/connectors")
    override val spec: ToolSpec
        get() = ToolSpec(
            "connector_request",
            "Call one installed ChatBox HTTPS REST connector. Installed connectors: ${installed().joinToString { it.optString("name") + " (" + it.optString("slug") + ")" }.ifBlank { "none" }}. GET sends input as a query parameter; POST sends input as JSON.",
            listOf(
                ToolParam("connector", required = true, description = "Installed connector name or slug."),
                ToolParam("input", description = "Query value for GET or JSON object text for POST.")
            ),
            Risk.NETWORK, "connectors"
        )

    override fun isDestructive(args: JsonObject, ctx: ToolContext): Boolean =
        manifest(arg(args, "connector"))?.optString("method") == "POST"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = arg(args, "connector").orEmpty()
        val manifest = manifest(name) ?: return ToolResult.Error("That connector is not installed and enabled.")
        val base = manifest.optString("baseUrl").toHttpUrlOrNull()
            ?: return ToolResult.Error("The connector base URL is invalid.")
        val endpoint = manifest.optString("path")
        if (base.scheme != "https" || base.port != 443 || base.username.isNotEmpty() || base.password.isNotEmpty() ||
            base.querySize > 0 || base.fragment != null || endpoint.isBlank() || !endpoint.startsWith("/") ||
            endpoint.contains("..") || endpoint.contains('\\')
        ) return ToolResult.Error("Connector URL or path failed security validation.")
        val url = (base.toString().trimEnd('/') + endpoint).toHttpUrlOrNull()
            ?: return ToolResult.Error("Connector endpoint is invalid.")
        val input = arg(args, "input").orEmpty().take(20_000)
        val requestUrl = if (manifest.optString("method") == "GET" && input.isNotBlank()) {
            url.newBuilder().addQueryParameter("input", input).build()
        } else url
        val builder = Request.Builder().url(requestUrl)
            .header("Accept", "application/json, text/plain")
            .header("User-Agent", "JARVIS-ChatBox/1.0")
        val token = runCatching { encryptedPrefs().getString("token:${manifest.optString("slug")}", null) }.getOrNull()
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        when (manifest.optString("method")) {
            "GET" -> builder.get()
            "POST" -> {
                val body = if (input.isBlank()) "{}" else input
                if (runCatching { JSONObject(body) }.isFailure) return ToolResult.Error("POST input must be a valid JSON object.")
                builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            }
            else -> return ToolResult.Error("Unsupported connector method.")
        }
        return try {
            client.newCall(builder.build()).execute().use { response ->
                if (response.code in 300..399) return ToolResult.Error("Connector redirects are blocked. Use the final HTTPS endpoint directly.")
                if (!response.isSuccessful) return ToolResult.Error("Connector returned HTTP ${response.code}.")
                val body = response.body ?: return ToolResult.Error("Connector returned an empty response.")
                if (body.contentLength() > MAX_BYTES) return ToolResult.Error("Connector response is too large.")
                val bytes = body.bytes()
                if (bytes.size > MAX_BYTES) return ToolResult.Error("Connector response is too large.")
                ToolResult.Ok("Connector: ${manifest.optString("name")}\nHTTP ${response.code}\n" + bytes.toString(Charsets.UTF_8).take(MAX_OUTPUT))
            }
        } catch (e: Exception) {
            ToolResult.Error(e.message ?: "Connector request failed.")
        }
    }

    private fun installed(): List<JSONObject> = directory.listFiles { f -> f.isFile && f.extension.equals("json", true) }
        ?.filter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        ?.mapNotNull { file ->
            runCatching { JSONObject(file.readText()) }.getOrNull()?.takeIf {
                it.optInt("schemaVersion") == 1 && it.optString("status") == "installed" && it.optBoolean("executionEnabled")
            }
        }.orEmpty()

    private fun manifest(value: String?): JSONObject? {
        val key = value?.trim()?.lowercase().orEmpty()
        if (key.isBlank()) return null
        return installed().firstOrNull {
            it.optString("slug").equals(key, true) || it.optString("name").equals(value?.trim(), true)
        }
    }

    private fun arg(args: JsonObject, key: String): String? =
        args.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun encryptedPrefs() = EncryptedSharedPreferences.create(
        app, "jarvis_connector_secrets",
        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val values = InetAddress.getAllByName(hostname).toList()
                    if (values.isEmpty() || values.any { !publicAddress(it) }) throw IOException("Private or restricted connector host.")
                    return values
                }
            }).build()
    }

    private fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        return when (address) {
            is Inet4Address -> {
                val a = b[0]; val c = b[1]
                !(a == 0 || a == 10 || a == 100 && c in 64..127 || a == 127 ||
                    a == 169 && c == 254 || a == 172 && c in 16..31 || a == 192 && c == 168 ||
                    a == 198 && c in 18..19 || a >= 224)
            }
            is Inet6Address -> !(b[0] and 0xfe == 0xfc || b[0] == 0xff || b[0] == 0xfe && (b[1] and 0xc0) == 0x80)
            else -> false
        }
    }

    companion object {
        private const val MAX_BYTES = 256 * 1024L
        private const val MAX_OUTPUT = 24_000
    }
}
