package com.jarvis.assistant.tools

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.jarvis.assistant.provider.AndroidKeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** RFC 7636 PKCE and RFC 7591 dynamic client registration for MCP OAuth servers. */
object McpOAuth {
    private const val REDIRECT_URI = "clozedcode.app://oauth/callback"
    private const val PREFS = "jarvis_mcp_oauth_pending"
    private val random = SecureRandom()

    suspend fun start(context: Context, serverId: String) = withContext(Dispatchers.IO) {
        val manager = McpManager.get(context)
        val server = manager.server(serverId) ?: throw IllegalStateException("MCP server no longer exists.")
        val origin = URIHelper.origin(server.url)
        val metadataUrl = origin.trimEnd('/') + "/.well-known/oauth-authorization-server"
        val metadata = getJson(metadataUrl)
        val authorizationEndpoint = metadata.get("authorization_endpoint")?.asString.orEmpty()
        val tokenEndpoint = metadata.get("token_endpoint")?.asString.orEmpty()
        val registrationEndpoint = metadata.get("registration_endpoint")?.asString.orEmpty()
        if (authorizationEndpoint.isBlank() || tokenEndpoint.isBlank() || registrationEndpoint.isBlank()) {
            throw IllegalStateException("This MCP server does not publish OAuth authorization, token and dynamic-registration endpoints. You can add a bearer token manually instead.")
        }
        listOf(authorizationEndpoint, tokenEndpoint, registrationEndpoint).forEach(::requireSecureEndpoint)
        val registration = JsonObject().apply {
            addProperty("client_name", "JARVIS Coding Agent")
            add("redirect_uris", JsonArray().apply { add(REDIRECT_URI) })
            add("grant_types", JsonArray().apply { add("authorization_code"); add("refresh_token") })
            add("response_types", JsonArray().apply { add("code") })
            addProperty("token_endpoint_auth_method", "none")
        }
        val registered = postJson(registrationEndpoint, registration)
        val clientId = registered.get("client_id")?.asString.orEmpty()
        if (clientId.isBlank()) throw IllegalStateException("OAuth dynamic registration returned no client_id.")
        manager.updateOAuthMetadata(serverId, clientId, authorizationEndpoint, tokenEndpoint, registrationEndpoint)

        val verifier = randomUrlToken(32)
        val challenge = Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomUrlToken(24)
        val secure = AndroidKeyVault(context.applicationContext)
        if (!secure.store("mcp_pending_pkce_verifier", verifier)) throw IllegalStateException("Secure storage is unavailable; the PKCE verifier was not saved.")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server_id", serverId)
            .putString("state", state)
            .putString("token_endpoint", tokenEndpoint)
            .putString("client_id", clientId)
            .putString("redirect_uri", REDIRECT_URI)
            .commit()

        val authorize = Uri.parse(authorizationEndpoint).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()
        withContext(Dispatchers.Main) {
            CustomTabsIntent.Builder().build().launchUrl(context, authorize)
        }
    }

    suspend fun complete(context: Context, callback: Uri) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expectedState = prefs.getString("state", null).orEmpty()
        val actualState = callback.getQueryParameter("state").orEmpty()
        if (expectedState.isBlank() || actualState != expectedState) {
            throw IllegalStateException("OAuth state validation failed. The callback was not accepted.")
        }
        callback.getQueryParameter("error")?.takeIf { it.isNotBlank() }?.let {
            throw IllegalStateException("MCP login was not completed: " + it)
        }
        val code = callback.getQueryParameter("code").orEmpty()
        if (code.isBlank()) throw IllegalStateException("OAuth callback did not contain an authorization code.")
        val verifierSecret = AndroidKeyVault(context.applicationContext).load("mcp_pending_pkce_verifier")
        val verifier = verifierSecret?.reveal().orEmpty()
        verifierSecret?.wipe()
        if (verifier.isBlank()) throw IllegalStateException("The secure PKCE verifier is missing. Start Login again.")
        val tokenEndpoint = prefs.getString("token_endpoint", null).orEmpty()
        val clientId = prefs.getString("client_id", null).orEmpty()
        val redirect = prefs.getString("redirect_uri", REDIRECT_URI).orEmpty()
        val serverId = prefs.getString("server_id", null).orEmpty()
        if (tokenEndpoint.isBlank() || clientId.isBlank() || serverId.isBlank()) throw IllegalStateException("OAuth login state is incomplete.")
        val form = listOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirect,
            "client_id" to clientId,
            "code_verifier" to verifier
        ).joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }
        val response = postForm(tokenEndpoint, form)
        val access = response.get("access_token")?.asString.orEmpty()
        if (access.isBlank()) throw IllegalStateException("OAuth token response did not contain an access_token.")
        val refresh = response.get("refresh_token")?.asString
        val expires = response.get("expires_in")?.asLong ?: 3600L
        McpManager.get(context).storeOAuthTokens(serverId, access, refresh, expires)
        AndroidKeyVault(context.applicationContext).delete("mcp_pending_pkce_verifier")
        prefs.edit().clear().commit()
    }


    private fun requireSecureEndpoint(value: String) {
        val uri = java.net.URI(value)
        val secure = uri.scheme == "https"
        val localHttp = uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "::1")
        if (!secure && !localHttp) throw IllegalStateException("OAuth endpoints must use HTTPS, except for localhost development servers.")
    }

    private fun randomUrlToken(bytes: Int): String {
        val data = ByteArray(bytes)
        random.nextBytes(data)
        return Base64Url.encode(data)
    }

    private suspend fun getJson(url: String): JsonObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw oauthHttpError(code, body)
            JsonParser.parseString(body).asJsonObject
        } finally { connection.disconnect() }
    }

    private suspend fun postJson(url: String, json: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(json.toString()) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw oauthHttpError(code, body)
            JsonParser.parseString(body).asJsonObject
        } finally { connection.disconnect() }
    }

    private suspend fun postForm(url: String, form: String): JsonObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(form) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw oauthHttpError(code, body)
            JsonParser.parseString(body).asJsonObject
        } finally { connection.disconnect() }
    }

    private fun oauthHttpError(code: Int, body: String): IllegalStateException = when (code) {
        401 -> IllegalStateException("HTTP 401: login required (tap Login under this server in Settings)")
        403 -> IllegalStateException("The server refused to register this app (403: approved clients only)")
        404 -> IllegalStateException("HTTP 404: OAuth endpoint not found.")
        else -> IllegalStateException("OAuth HTTP $code: ${body.take(240)}")
    }

    private object Base64Url {
        fun encode(bytes: ByteArray): String = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

    private object URIHelper {
        fun origin(url: String): String {
            val uri = java.net.URI(url)
            return uri.scheme + "://" + uri.rawAuthority
        }
    }
}
