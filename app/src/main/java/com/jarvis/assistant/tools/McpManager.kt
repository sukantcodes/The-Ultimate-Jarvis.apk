package com.jarvis.assistant.tools

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.provider.AndroidKeyVault
import com.jarvis.assistant.provider.ApiKeySecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val clientId: String = "",
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    val registrationEndpoint: String = "",
    val tokenExpiresAt: Long = 0L,
    val status: String = "Not connected"
)

data class McpToolRef(
    val serverId: String,
    val serverName: String,
    val originalName: String,
    val exposedName: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean = false
)

/** Streamable-HTTP JSON-RPC client and dynamic tool registry for configured MCP servers. */
class McpManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("jarvis_mcp_servers", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val vault = AndroidKeyVault(app)
    private val requestIds = AtomicLong(1L)
    private val initializedServers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val sessionIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val protocolVersions = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var cachedTools: List<McpToolRef> = emptyList()
    @Volatile private var refreshRequired: Boolean = true

    fun needsRefresh(): Boolean = refreshRequired

    fun servers(): List<McpServerConfig> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        val type = object : TypeToken<List<McpServerConfig>>() {}.type
        return runCatching { gson.fromJson<List<McpServerConfig>>(raw, type) }.getOrNull().orEmpty()
    }

    fun server(id: String): McpServerConfig? = servers().firstOrNull { it.id == id }

    fun save(config: McpServerConfig): String? {
        val uri = runCatching { URI(config.url.trim()) }.getOrNull()
            ?: return "Enter a valid MCP server URL."
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) return "MCP server URL must use HTTPS, or HTTP on localhost."
        if (uri.scheme == "http" && uri.host !in setOf("127.0.0.1", "localhost")) return "Unencrypted HTTP is allowed only for a local MCP server."
        val clean = config.copy(name = config.name.trim().ifBlank { uri.host }, url = config.url.trim().trimEnd('/'))
        val all = servers().toMutableList()
        val index = all.indexOfFirst { it.id == clean.id }
        if (index >= 0) {
            if (all[index].url != clean.url) resetProtocolSession(clean.id)
            all[index] = clean
        } else all += clean
        persist(all)
        refreshRequired = true
        return null
    }

    fun remove(id: String) {
        persist(servers().filterNot { it.id == id })
        vault.delete(accessKey(id))
        vault.delete(refreshKey(id))
        resetProtocolSession(id)
        cachedTools = cachedTools.filterNot { it.serverId == id }
        refreshRequired = true
    }

    fun setEnabled(id: String, enabled: Boolean) {
        update(id) { it.copy(enabled = enabled, status = if (enabled) "Needs refresh" else "Disabled") }
        if (!enabled) {
            resetProtocolSession(id)
            cachedTools = cachedTools.filterNot { it.serverId == id }
        }
        refreshRequired = true
    }

    fun setBearerToken(id: String, token: String): String? {
        if (token.isBlank()) return "Enter a bearer token."
        if (!vault.store(accessKey(id), token.trim())) return "Secure token storage is unavailable; the token was not saved."
        resetProtocolSession(id)
        update(id) { it.copy(tokenExpiresAt = Long.MAX_VALUE, status = "Token saved") }
        refreshRequired = true
        return null
    }

    fun clearBearerToken(id: String) {
        vault.delete(accessKey(id))
        vault.delete(refreshKey(id))
        resetProtocolSession(id)
        update(id) { it.copy(tokenExpiresAt = 0L, status = "Login required") }
        refreshRequired = true
    }

    fun tools(): List<McpToolRef> = cachedTools.toList()

    /** A broken or offline server is recorded independently and never blocks other enabled servers. */
    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        val discovered = ArrayList<McpToolRef>()
        for (server in servers().filter { it.enabled }) {
            try {
                val result = request(server, "tools/list", JsonObject())
                val list = result.getAsJsonArray("tools") ?: JsonArray()
                list.forEach { item ->
                    if (!item.isJsonObject) return@forEach
                    val tool = item.asJsonObject
                    val original = tool.get("name")?.asString.orEmpty()
                    if (original.isBlank()) return@forEach
                    val normalized = original.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').take(38).ifBlank { "tool" }
                    val serverPart = server.id.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(12).ifBlank { "server" }
                    val exposed = ("mcp_${serverPart}_${normalized}").take(64).trimEnd('_')
                    val schema = tool.getAsJsonObject("inputSchema") ?: tool.getAsJsonObject("input_schema") ?: JsonObject().apply {
                        addProperty("type", "object")
                        add("properties", JsonObject())
                    }
                    discovered += McpToolRef(
                        server.id,
                        server.name,
                        original,
                        exposed,
                        tool.get("description")?.asString.orEmpty().ifBlank { "MCP tool from ${server.name}." },
                        schema,
                        tool.getAsJsonObject("annotations")?.get("readOnlyHint")?.asBoolean == true
                    )
                }
                update(server.id) { it.copy(status = "${list.size()} tools available") }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                update(server.id) { it.copy(status = error.message ?: "Connection failed") }
            }
        }
        cachedTools = discovered.distinctBy { it.exposedName }
        refreshRequired = false
    }

    suspend fun call(tool: McpToolRef, arguments: JsonObject): String {
        val server = server(tool.serverId) ?: return "Error: MCP server was removed."
        if (!server.enabled) return "Error: MCP server is disabled."
        val params = JsonObject().apply {
            addProperty("name", tool.originalName)
            add("arguments", arguments)
        }
        val result = request(server, "tools/call", params)
        val content = result.getAsJsonArray("content") ?: return result.toString()
        if (result.get("isError")?.asBoolean == true) return "Error: " + content.joinToString("\n") { item ->
            if (item.isJsonObject) item.asJsonObject.get("text")?.asString.orEmpty() else item.toString()
        }
        return content.joinToString("\n") { item ->
            if (item.isJsonObject) item.asJsonObject.get("text")?.asString.orEmpty() else item.toString()
        }.ifBlank { "(MCP tool returned no text content.)" }
    }

    private suspend fun request(server: McpServerConfig, method: String, params: JsonObject): JsonObject {
        if (method != "initialize" && server.id !in initializedServers) {
            val initParams = JsonObject().apply {
                addProperty("protocolVersion", "2025-03-26")
                add("capabilities", JsonObject())
                add("clientInfo", JsonObject().apply {
                    addProperty("name", "JARVIS")
                    addProperty("version", "1.0")
                })
            }
            val initialized = try {
                requestOnce(server, "initialize", initParams)
            } catch (error: Exception) {
                val message = error.message.orEmpty().lowercase()
                if ("protocol" !in message && "version" !in message) throw error
                initParams.addProperty("protocolVersion", "2024-11-05")
                requestOnce(server, "initialize", initParams)
            }
            val negotiated = initialized.get("protocolVersion")?.asString.orEmpty()
            if (negotiated.isBlank()) throw IllegalStateException("MCP initialize response did not include a protocolVersion.")
            protocolVersions[server.id] = negotiated
            requestOnce(server, "notifications/initialized", JsonObject())
            initializedServers += server.id
        }
        return requestOnce(server, method, params)
    }

    private suspend fun requestOnce(server: McpServerConfig, method: String, params: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val token = accessToken(server)
        val connection = (URL(server.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            protocolVersions[server.id]?.let { setRequestProperty("MCP-Protocol-Version", it) }
            sessionIds[server.id]?.let { setRequestProperty("MCP-Session-Id", it) }
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            val body = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                if (method != "notifications/initialized") addProperty("id", requestIds.getAndIncrement())
                addProperty("method", method)
                add("params", params)
            }
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            connection.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionIds[server.id] = it }
            if (code == 202 || (method == "notifications/initialized" && code in 200..299)) return@withContext JsonObject()
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val safeResponse = token?.takeIf { it.isNotBlank() }?.let { response.replace(it, "[REDACTED]") } ?: response
            if (code !in 200..299) {
                val message = when (code) {
                    401 -> "HTTP 401: login required (tap Login under this server in Settings)"
                    403 -> "The server refused to register this app (403: approved clients only)"
                    404 -> "HTTP 404: MCP endpoint not found at ${server.url}"
                    else -> "HTTP $code: ${safeResponse.take(300)}"
                }
                throw IllegalStateException(message)
            }
            val jsonText = if (safeResponse.trimStart().startsWith("{")) safeResponse else {
                val eventData = safeResponse.lineSequence()
                    .filter { it.startsWith("data:") }
                    .joinToString("\n") { it.removePrefix("data:").trimStart() }
                    .trim()
                eventData.takeIf { it.startsWith("{") }
                    ?: throw IllegalStateException("MCP server returned neither a JSON-RPC response nor an SSE data event.")
            }
            val root = JsonParser.parseString(jsonText).asJsonObject
            if (root.has("error")) {
                val error = root.getAsJsonObject("error")
                throw IllegalStateException(error.get("message")?.asString ?: "MCP JSON-RPC error.")
            }
            root.getAsJsonObject("result") ?: throw IllegalStateException("MCP response has no result object.")
        } finally {
            connection.disconnect()
        }
    }

    private fun accessToken(server: McpServerConfig): String? {
        val current = vault.load(accessKey(server.id))
        val token = current?.reveal()
        current?.wipe()
        if (token.isNullOrBlank()) return null
        if (server.tokenExpiresAt == Long.MAX_VALUE || server.tokenExpiresAt > System.currentTimeMillis() + 60_000L) return token
        val refreshSecret = vault.load(refreshKey(server.id))
        val refresh = refreshSecret?.reveal()
        refreshSecret?.wipe()
        if (refresh.isNullOrBlank() || server.tokenEndpoint.isBlank() || server.clientId.isBlank()) return null
        return runCatching {
            val connection = (URL(server.tokenEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            val form = listOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to server.clientId
            ).joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }
            val body = try {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(form) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("OAuth refresh failed (HTTP $code).")
                JsonParser.parseString(text).asJsonObject
            } finally { connection.disconnect() }
            val access = body.get("access_token")?.asString.orEmpty()
            if (access.isBlank() || !vault.store(accessKey(server.id), access)) throw IllegalStateException("OAuth refresh returned no usable access token.")
            body.get("refresh_token")?.asString?.takeIf { it.isNotBlank() }?.let { vault.store(refreshKey(server.id), it) }
            val expires = body.get("expires_in")?.asLong ?: 3600L
            update(server.id) { it.copy(tokenExpiresAt = System.currentTimeMillis() + expires * 1000L, status = "Authenticated") }
            access
        }.getOrElse { token }
    }

    fun storeOAuthTokens(serverId: String, access: String, refresh: String?, expiresIn: Long) {
        if (!vault.store(accessKey(serverId), access)) throw IllegalStateException("Secure token storage is unavailable.")
        if (!refresh.isNullOrBlank()) vault.store(refreshKey(serverId), refresh)
        resetProtocolSession(serverId)
        update(serverId) { it.copy(tokenExpiresAt = System.currentTimeMillis() + expiresIn.coerceAtLeast(60L) * 1000L, status = "Authenticated") }
        refreshRequired = true
    }

    fun updateOAuthMetadata(serverId: String, clientId: String, authorization: String, token: String, registration: String) {
        update(serverId) { it.copy(clientId = clientId, authorizationEndpoint = authorization, tokenEndpoint = token, registrationEndpoint = registration, status = "Login required") }
    }

    fun updateStatus(serverId: String, status: String) = update(serverId) { it.copy(status = status.take(240)) }

    private fun update(id: String, transform: (McpServerConfig) -> McpServerConfig) {
        val all = servers().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return
        all[index] = transform(all[index])
        persist(all)
    }

    private fun persist(configs: List<McpServerConfig>) {
        prefs.edit().putString(KEY_SERVERS, gson.toJson(configs)).apply()
    }

    private fun resetProtocolSession(id: String) {
        initializedServers.remove(id)
        sessionIds.remove(id)
        protocolVersions.remove(id)
    }

    private fun accessKey(id: String) = "mcp_access_${id}"
    private fun refreshKey(id: String) = "mcp_refresh_${id}"

    companion object {
        private const val KEY_SERVERS = "servers_json"
        @Volatile private var instance: McpManager? = null
        fun get(context: Context): McpManager = instance ?: synchronized(this) {
            instance ?: McpManager(context).also { instance = it }
        }
    }
}

internal class McpTool(private val manager: McpManager, private val ref: McpToolRef) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = ref.exposedName,
        description = ref.description,
        params = run {
            val properties = ref.inputSchema.getAsJsonObject("properties") ?: JsonObject()
            val required = ref.inputSchema.getAsJsonArray("required")?.mapNotNull { it.takeIf { v -> v.isJsonPrimitive }?.asString }.orEmpty().toSet()
            properties.entrySet().map { (name, value) ->
                val obj = value.takeIf { it.isJsonObject }?.asJsonObject
                ToolParam(name, obj?.get("type")?.asString ?: "string", obj?.get("description")?.asString.orEmpty(), name in required)
            }
        },
        risk = Risk.NETWORK,
        group = "mcp"
    )

    override fun describe(args: JsonObject): String = "Call ${ref.serverName} MCP tool ${ref.originalName} with arguments ${args.toString().take(1200)}."

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
        ToolResult.Ok(manager.call(ref, args))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolResult.Error(error.message ?: "MCP call failed.")
    }
}


/** Permission-gated handshake used to discover tools from enabled MCP servers. */
class McpRefreshTool(private val manager: McpManager) : Tool {
    override val spec = ToolSpec("mcp_refresh", "Connect to enabled MCP servers and refresh their tool catalog.", risk = Risk.NETWORK, group = "mcp")
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = try {
        manager.refreshAll()
        ToolResult.Ok("Loaded ${manager.tools().size} MCP tools from enabled servers.")
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolResult.Error(error.message ?: "MCP tool discovery failed.")
    }
}

object McpTools {
    fun all(context: Context): List<Tool> {
        val manager = McpManager.get(context)
        return manager.tools().map { McpTool(manager, it) }
    }
}
