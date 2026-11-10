package com.jarvis.assistant.provider

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Endpoint validation and URL building for custom providers.
 *
 * Only HTTPS (and WSS for real-time) is accepted: the app's network security config blocks cleartext
 * traffic anyway, and sending an API key over plain HTTP would expose it. URLs may not carry credentials,
 * queries or fragments; the key has its own field and authentication method.
 */
object ProviderUrl {

    sealed class Check {
        data class Ok(val url: String) : Check()
        data class Bad(val reason: String) : Check()
    }

    private val TRAILING_PATHS = listOf("/chat/completions", "/completions", "/models")
    private val VERSION_SEGMENT = Regex("^v\\d+(alpha|beta)?\\d*$", RegexOption.IGNORE_CASE)

    /** Validates a provider base URL and returns it normalised (lower-case host, no trailing slash or pasted path). */
    fun validateBase(raw: String): Check = validate(raw, "https", allowPastedEndpointPath = true)

    /** Validates an optional real-time WebSocket URL. */
    fun validateRealtime(raw: String): Check = validate(raw, "wss", allowPastedEndpointPath = false)

    private fun validate(raw: String, scheme: String, allowPastedEndpointPath: Boolean): Check {
        val text = raw.trim()
        if (text.isEmpty()) return Check.Bad("Enter the provider's endpoint URL.")
        if (text.any { it.isWhitespace() }) return Check.Bad("The URL can't contain spaces.")
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return Check.Bad("That doesn't look like a valid URL.")
        }
        val given = uri.scheme?.lowercase(Locale.ROOT)
            ?: return Check.Bad("Start the URL with $scheme://")
        if (given != scheme) {
            return Check.Bad(
                when (given) {
                    "http", "ws" -> "Plain $given:// is blocked to protect your API key. Use $scheme:// instead."
                    else -> "Unsupported URL scheme \"$given\". Use $scheme://"
                }
            )
        }
        val host = uri.host
        if (host.isNullOrBlank()) return Check.Bad("The URL has no host name.")
        if (uri.userInfo != null) return Check.Bad("Don't put a user name or key inside the URL. Use the API key field.")
        if (uri.rawQuery != null) return Check.Bad("Remove the ?query part. Query-parameter keys are set under Authentication.")
        if (uri.rawFragment != null) return Check.Bad("Remove the #fragment from the URL.")

        var path = uri.rawPath.orEmpty().trimEnd('/')
        if (allowPastedEndpointPath) {
            // People often paste the full chat or models URL. Keep only the base.
            var changed = true
            while (changed) {
                changed = false
                for (suffix in TRAILING_PATHS) {
                    if (path.endsWith(suffix, ignoreCase = true)) {
                        path = path.substring(0, path.length - suffix.length).trimEnd('/')
                        changed = true
                    }
                }
            }
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host.lowercase(Locale.ROOT)
        return Check.Ok("$scheme://$hostPart$port$path")
    }

    fun join(base: String, path: String): String = base.trimEnd('/') + "/" + path.trimStart('/')

    /**
     * Resolves a discovery path override. Relative paths are joined to [base]. Absolute URLs are accepted only
     * when they stay on the same host and use https, so a key can never be sent to a different address.
     */
    fun resolveSameHost(base: String, pathOrUrl: String): String? {
        val text = pathOrUrl.trim()
        if (text.isEmpty()) return null
        if (text.contains("://")) {
            val ok = (validate(text, "https", allowPastedEndpointPath = false) as? Check.Ok)?.url ?: return null
            if (!sameHost(base, ok)) return null
            return text.trimEnd('/')
        }
        if (text.any { it.isWhitespace() }) return null
        return join(base, text)
    }

    fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase(Locale.ROOT)
    } catch (e: Exception) {
        null
    }

    fun sameHost(a: String, b: String): Boolean = try {
        val ua = URI(a)
        val ub = URI(b)
        ua.host.equals(ub.host, ignoreCase = true) &&
            effectivePort(ua) == effectivePort(ub)
    } catch (e: Exception) {
        false
    }

    private fun effectivePort(uri: URI): Int =
        if (uri.port > 0) uri.port else if (uri.scheme.equals("https", true) || uri.scheme.equals("wss", true)) 443 else 80

    /** Gemini model resource name: `models/<id>`. */
    fun geminiModelName(id: String): String {
        val clean = id.trim().removePrefix("/")
        return if (clean.startsWith("models/") || clean.startsWith("tunedModels/")) clean else "models/$clean"
    }

    /**
     * WebSocket address for the Gemini Live protocol: [override] when given, else derived from the REST base
     * (`https://host/prefix/v1beta` -> `wss://host/prefix/ws/google.ai.generativelanguage.v1beta....`).
     */
    fun geminiLiveUrl(base: String, override: String): Check {
        if (override.isNotBlank()) return validateRealtime(override)
        val uri = try {
            URI(base)
        } catch (e: Exception) {
            return Check.Bad("The endpoint URL is not valid.")
        }
        val host = uri.host ?: return Check.Bad("The endpoint URL has no host name.")
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val version = segments.lastOrNull()?.takeIf { VERSION_SEGMENT.matches(it) }
        val prefix = (if (version != null) segments.dropLast(1) else segments).joinToString("/")
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val prefixPath = if (prefix.isEmpty()) "" else "/$prefix"
        val ver = version ?: "v1beta"
        return Check.Ok(
            "wss://${host.lowercase(Locale.ROOT)}$port$prefixPath/ws/google.ai.generativelanguage.$ver.GenerativeService.BidiGenerateContent"
        )
    }

    /** Removes query values so a URL is safe to show or log even when the key travels as a query parameter. */
    fun redactQuery(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val params = url.substring(q + 1).split('&').joinToString("&") { part ->
            val eq = part.indexOf('=')
            if (eq < 0) part else part.substring(0, eq + 1) + "***"
        }
        return url.substring(0, q + 1) + params
    }
}
