package com.jarvis.assistant.provider

import java.net.URLEncoder

/**
 * Attaches the endpoint API key to a request using the configured authentication method, and keeps the key
 * out of anything that could be logged or shown.
 */
object ProviderAuth {

    private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val RESERVED = setOf("host", "content-length", "content-type", "transfer-encoding", "connection", "accept")

    /** Returns an explanation when the authentication settings are unusable, else null. */
    fun validateSettings(method: AuthMethod, name: String): String? {
        if (!method.needsName) return null
        val clean = name.trim()
        if (clean.isEmpty()) {
            return if (method == AuthMethod.CUSTOM_HEADER) "Enter the header name the provider expects."
            else "Enter the query parameter name the provider expects."
        }
        if (method == AuthMethod.CUSTOM_HEADER) {
            if (!TOKEN.matches(clean)) return "That isn't a valid HTTP header name."
            if (clean.lowercase() in RESERVED) return "\"$clean\" is reserved and can't carry an API key."
        } else if (clean.any { it.isWhitespace() || it == '&' || it == '=' || it == '#' }) {
            return "That isn't a valid query parameter name."
        }
        return null
    }

    /** A key must be printable ASCII to travel in a header; this also blocks header injection. */
    fun validateKeyText(key: String): String? {
        val k = key.trim()
        if (k.isEmpty()) return "Enter the endpoint API key."
        if (k.any { it.code < 0x20 || it.code > 0x7E }) {
            return "The key contains characters that can't be sent. Re-copy it without extra spaces or line breaks."
        }
        return null
    }

    /** Returns [request] with credentials attached. Throws [ProviderException] when the key is needed but absent. */
    fun apply(request: HttpRequestSpec, auth: AuthSpec): HttpRequestSpec {
        if (auth.method == AuthMethod.NONE) return request
        val secret = auth.secret
        if (secret == null || secret.isBlank) {
            throw ProviderException(ProviderError(ProviderErrorKind.INVALID_CONFIG, "No API key is saved for this provider."))
        }
        validateSettings(auth.method, auth.name)?.let {
            throw ProviderException(ProviderError(ProviderErrorKind.INVALID_CONFIG, it))
        }
        val key = secret.reveal()
        return when (auth.method) {
            AuthMethod.BEARER -> request.copy(headers = request.headers + ("Authorization" to "Bearer $key"))
            AuthMethod.X_API_KEY -> request.copy(headers = request.headers + ("x-api-key" to key))
            AuthMethod.X_GOOG_API_KEY -> request.copy(headers = request.headers + ("x-goog-api-key" to key))
            AuthMethod.CUSTOM_HEADER -> request.copy(headers = request.headers + (auth.name.trim() to key))
            AuthMethod.QUERY_PARAM -> {
                val joiner = if (request.url.contains('?')) "&" else "?"
                val encoded = URLEncoder.encode(auth.name.trim(), "UTF-8") + "=" + URLEncoder.encode(key, "UTF-8")
                request.copy(url = request.url + joiner + encoded)
            }
            AuthMethod.NONE -> request
        }
    }

    /** Adds static provider headers (for example an attribution header). They can never replace credentials. */
    fun withExtraHeaders(request: HttpRequestSpec, extra: Map<String, String>): HttpRequestSpec {
        if (extra.isEmpty()) return request
        val existing = request.headers.keys.map { it.lowercase() }.toSet()
        val merged = request.headers.toMutableMap()
        for ((k, v) in extra) if (k.lowercase() !in existing) merged[k] = v
        return request.copy(headers = merged)
    }

    /** Parses "Name: value" lines. Returns the valid headers and a list of problems for the rest. */
    fun parseExtraHeaders(text: String): Pair<Map<String, String>, List<String>> {
        val headers = LinkedHashMap<String, String>()
        val problems = ArrayList<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) {
                problems += "\"${line.take(40)}\" should look like Name: value"
                return@forEach
            }
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when {
                !TOKEN.matches(name) -> problems += "\"$name\" isn't a valid header name"
                name.lowercase() in RESERVED || name.lowercase() in setOf("authorization", "x-api-key", "x-goog-api-key") ->
                    problems += "\"$name\" is reserved; set credentials under Authentication"
                value.any { it.code < 0x20 && it != '\t' } -> problems += "\"$name\" has an invalid value"
                else -> headers[name] = value
            }
        }
        return headers to problems
    }

    /** Removes every occurrence of the key from [text]. Providers sometimes echo a key back in an error message. */
    fun redact(text: String, secretValue: String?): String {
        if (secretValue.isNullOrBlank() || secretValue.length < 4) return text
        return text.replace(secretValue, "***")
    }

    /** Last four characters only, and only for keys long enough that this reveals little. */
    fun hint(secret: ApiKeySecret?): String {
        if (secret == null || secret.isBlank) return ""
        val value = secret.reveal()
        return if (value.length >= 12) "••••••••" + value.takeLast(4) else "••••••••"
    }
}
