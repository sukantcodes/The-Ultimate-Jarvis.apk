package com.jarvis.assistant.network.provider

import android.net.Uri

/**
 * Strict URL validation for custom provider endpoints. Only HTTPS (and WSS for realtime) is
 * accepted, userinfo/credentials are rejected, and the scheme/host are normalised so the same
 * host is used consistently for both HTTP discovery and WebSocket connections.
 */
object ProviderUrlValidator {

    sealed class Result {
        data class Ok(val normalizedBase: String, val host: String, val scheme: String) : Result()
        data class Error(val reason: String) : Result()
    }

    fun validate(baseUrl: String, protocol: ProviderProtocol): Result {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return Result.Error("Endpoint URL is empty.")
        if (trimmed.any { it.isWhitespace() }) return Result.Error("Endpoint URL must not contain spaces.")
        if (trimmed.length > 2048) return Result.Error("Endpoint URL is too long.")

        val uri = try {
            Uri.parse(trimmed)
        } catch (e: Exception) {
            return Result.Error("Endpoint URL is not a valid address.")
        }
        val scheme = uri.scheme
        val host = uri.host
        if (scheme == null || host == null || host.isBlank()) {
            return Result.Error("Endpoint URL must include a scheme and a host (for example https://api.example.com).")
        }

        val requiredScheme = when (protocol) {
            ProviderProtocol.GEMINI_REALTIME -> "wss"
            else -> "https"
        }
        if (scheme.lowercase() != requiredScheme) {
            return Result.Error(
                if (requiredScheme == "wss") "The realtime protocol needs a secure wss:// WebSocket endpoint."
                else "Only secure https:// endpoints are accepted."
            )
        }
        if (!uri.userInfo.isNullOrEmpty()) {
            return Result.Error("Endpoint URL must not contain a username or password. Put credentials in the API key field.")
        }
        if (uri.query != null || uri.fragment != null) {
            return Result.Error("Endpoint URL must not contain a query string or fragment.")
        }

        val loweredHost = host.lowercase()
        if (loweredHost.startsWith("localhost") || loweredHost.endsWith(".local") || loweredHost.endsWith(".internal")) {
            return Result.Error("This endpoint is not reachable from a phone.")
        }
        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) {
            return Result.Error("Endpoint URL has an invalid port.")
        }

        // Retain any reverse-proxy/API prefix, e.g. /openai/v1.
        val portPart = if (port != -1) ":$port" else ""
        val path = (uri.encodedPath ?: "").trimEnd('/').let {
            if (it.isBlank() || it == "/") "" else if (it.startsWith("/")) it else "/$it"
        }
        val normalized = "${scheme.lowercase()}://$loweredHost$portPart$path"
        return Result.Ok(normalized, loweredHost, scheme.lowercase())
    }

    /** Whether a stored endpoint is valid for the given protocol (used on app restart). */
    fun isValid(baseUrl: String, protocol: ProviderProtocol): Boolean =
        validate(baseUrl, protocol) is Result.Ok

    fun httpBase(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
            trimmed.startsWith("https://") -> trimmed
            else -> trimmed
        }
    }

    fun wsBase(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.startsWith("wss://") -> trimmed
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            else -> trimmed
        }
    }

    /** Append an API path without duplicating a version segment already present in the base URL. */
    fun appendApiPath(baseUrl: String, apiPath: String): String {
        val base = httpBase(baseUrl).trimEnd('/')
        val path = "/" + apiPath.trim().trimStart('/')
        val lowerBase = base.lowercase()
        val lowerPath = path.lowercase()
        return when {
            lowerBase.endsWith("/v1") && lowerPath.startsWith("/v1/") -> base + path.substring(3)
            lowerBase.endsWith("/v1beta") && lowerPath.startsWith("/v1beta/") -> base + path.substring(7)
            else -> base + path
        }
    }
}
