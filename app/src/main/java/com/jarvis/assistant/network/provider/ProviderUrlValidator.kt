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
        if (uri.scheme == null || uri.host == null || uri.host.isBlank()) {
            return Result.Error("Endpoint URL must include a scheme and a host (for example https://api.example.com).")
        }

        val requiredScheme = when (protocol) {
            ProviderProtocol.GEMINI_REALTIME -> "wss"
            else -> "https"
        }
        if (uri.scheme.lowercase() != requiredScheme) {
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

        val host = uri.host.lowercase()
        if (host.startsWith("localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return Result.Error("This endpoint is not reachable from a phone.")
        }
        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) {
            return Result.Error("Endpoint URL has an invalid port.")
        }

        // Normalise to scheme://host[:port], no trailing slash.
        val portPart = if (port != -1) ":$port" else ""
        val normalized = "${uri.scheme.lowercase()}://$host$portPart"
        return Result.Ok(normalized, host, uri.scheme.lowercase())
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
}
