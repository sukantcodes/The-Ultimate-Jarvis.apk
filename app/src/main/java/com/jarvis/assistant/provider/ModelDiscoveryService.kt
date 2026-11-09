package com.jarvis.assistant.provider

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.net.URLEncoder

sealed class DiscoveryOutcome {
    data class Success(val models: List<ModelInfo>, val pages: Int, val truncated: Boolean = false) : DiscoveryOutcome()
    data class Failure(val error: ProviderError) : DiscoveryOutcome()
}

/**
 * Asks the configured provider which models it offers. Authenticates with the profile's own method, follows
 * pagination (never off the provider's own host), and parses the common response shapes. It only ever returns
 * what the provider actually said; there is no built-in model list.
 */
class ModelDiscoveryService(private val transport: HttpTransport) {

    private class Page(val models: List<ModelInfo>, val nextToken: String?, val nextUrl: String?, val hasMore: Boolean)

    suspend fun discover(
        profile: ProviderProfile,
        auth: AuthSpec,
        extraHeaders: Map<String, String> = emptyMap()
    ): DiscoveryOutcome {
        val base = when (val check = ProviderUrl.validateBase(profile.baseUrl)) {
            is ProviderUrl.Check.Ok -> check.url
            is ProviderUrl.Check.Bad -> return DiscoveryOutcome.Failure(ProviderError(ProviderErrorKind.INVALID_ENDPOINT, check.reason))
        }
        val firstUrl = if (profile.discoveryPath.isBlank()) ProviderUrl.join(base, "models")
        else ProviderUrl.resolveSameHost(base, profile.discoveryPath)
            ?: return DiscoveryOutcome.Failure(
                ProviderError(
                    ProviderErrorKind.INVALID_ENDPOINT,
                    "The model-discovery path must be a path on this provider (like /api/tags) or an https address on the same host."
                )
            )
        val secretValue = auth.secret?.takeIf { !it.isBlank }?.reveal()

        val collected = LinkedHashMap<String, ModelInfo>()
        var token: String? = null
        var nextUrl: String? = null
        var pages = 0
        var truncated = false

        while (true) {
            if (pages >= MAX_PAGES || collected.size >= MAX_MODELS) {
                truncated = true
                break
            }
            val url = buildPageUrl(profile.protocol, nextUrl ?: firstUrl, token, pages == 0 && nextUrl == null)
            var request = HttpRequestSpec("GET", url, mapOf("Accept" to "application/json"), timeoutMs = REQUEST_TIMEOUT_MS)
            request = try {
                ProviderAuth.withExtraHeaders(ProviderAuth.apply(request, auth), extraHeaders)
            } catch (e: ProviderException) {
                return DiscoveryOutcome.Failure(e.error)
            }
            val response = try {
                transport.execute(request)
            } catch (e: TransportException) {
                return DiscoveryOutcome.Failure(ProviderErrors.fromTransport(e))
            }
            if (!response.isSuccessful) {
                return DiscoveryOutcome.Failure(ProviderErrors.fromHttp(response.code, response.body, secretValue, ErrorContext.DISCOVERY))
            }
            val page = try {
                parsePage(response.body, profile.protocol)
            } catch (e: JsonParseException) {
                null
            } ?: return DiscoveryOutcome.Failure(
                ProviderErrors.invalidResponse(
                    if (response.body.trimStart().startsWith("<")) "The address returned a web page, not a model list. Check the endpoint URL."
                    else "Expected a JSON model list."
                )
            )
            pages++
            val before = collected.size
            for (m in page.models) collected.putIfAbsent(m.id, m)
            val addedAny = collected.size > before

            token = null
            nextUrl = null
            val sameHostNext = page.nextUrl?.takeIf { sameHostHttps(base, it) }
            when {
                !addedAny -> break
                page.nextToken != null && profile.protocol == ProviderProtocol.GEMINI -> token = page.nextToken
                sameHostNext != null -> nextUrl = sameHostNext
                page.hasMore && page.nextToken != null -> token = page.nextToken
                else -> break
            }
        }

        if (collected.isEmpty()) {
            return DiscoveryOutcome.Failure(
                ProviderError(ProviderErrorKind.NO_MODELS, "The provider answered, but its model list is empty. Enter the model ID manually if you know it.")
            )
        }
        val sorted = collected.values.sortedBy { it.id.lowercase() }
        return DiscoveryOutcome.Success(sorted, pages, truncated)
    }

    private fun sameHostHttps(base: String, candidate: String): Boolean =
        candidate.startsWith("https://", ignoreCase = true) && ProviderUrl.sameHost(base, candidate)

    private fun buildPageUrl(protocol: ProviderProtocol, url: String, token: String?, first: Boolean): String {
        var out = url
        if (protocol == ProviderProtocol.GEMINI) {
            if (first && !out.contains("pageSize=")) out = addQuery(out, "pageSize", "1000")
            if (token != null) out = addQuery(out, "pageToken", token)
        } else if (token != null) {
            out = addQuery(out, "after", token)
        }
        return out
    }

    private fun addQuery(url: String, name: String, value: String): String =
        url + (if (url.contains('?')) "&" else "?") + URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")

    /** Returns null when the body is JSON but not recognisably a model list. */
    private fun parsePage(body: String, protocol: ProviderProtocol): Page? {
        val text = body.trim()
        if (text.isEmpty() || text.startsWith("<")) return null
        val root = JsonParser.parseString(text)
        val entries: List<JsonElement>
        var nextToken: String? = null
        var nextUrl: String? = null
        var hasMore = false
        var lastId: String? = null

        if (root.isJsonArray) {
            entries = root.asJsonArray.toList()
        } else if (root.isJsonObject) {
            val obj = root.asJsonObject
            val array = listOf("data", "models", "result", "results", "items").firstNotNullOfOrNull { key ->
                obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            } ?: return null
            entries = array.toList()
            nextToken = obj.stringOrNull("nextPageToken") ?: obj.stringOrNull("next_page_token")
            nextUrl = obj.stringOrNull("next") ?: obj.stringOrNull("next_url")
                ?: obj.getAsJsonObjectOrNull("links")?.stringOrNull("next")
            hasMore = obj.boolOrNull("has_more") == true
            lastId = obj.stringOrNull("last_id")
        } else {
            return null
        }

        val models = entries.mapNotNull { parseEntry(it, protocol) }
        if (hasMore && nextToken == null) nextToken = lastId ?: models.lastOrNull()?.id
        return Page(models, nextToken, nextUrl, hasMore)
    }

    private fun parseEntry(element: JsonElement, protocol: ProviderProtocol): ModelInfo? {
        if (element.isJsonPrimitive) {
            val id = element.asString.trim().takeIf { it.isNotEmpty() } ?: return null
            return ModelInfo(id = normaliseId(id, protocol))
        }
        if (!element.isJsonObject) return null
        val obj: JsonObject = element.asJsonObject
        val rawId = listOf("id", "name", "model").firstNotNullOfOrNull { obj.stringOrNull(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?: return null
        val id = normaliseId(rawId, protocol)
        val display = (obj.stringOrNull("displayName") ?: obj.stringOrNull("display_name"))?.trim().orEmpty()
        val methods = obj.getAsJsonArrayOrNull("supportedGenerationMethods")
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }.orEmpty()
        val caps = if (protocol == ProviderProtocol.GEMINI) CapabilityDeriver.fromGemini(methods) else CapabilityDeriver.fromOpenAi(obj)
        return ModelInfo(
            id = id,
            displayName = if (display == id) "" else display,
            ownedBy = (obj.stringOrNull("owned_by") ?: obj.stringOrNull("owner") ?: obj.stringOrNull("organization")).orEmpty(),
            contextWindow = CapabilityDeriver.contextWindowOf(obj),
            supportedMethods = methods,
            capabilities = caps
        )
    }

    private fun normaliseId(raw: String, protocol: ProviderProtocol): String =
        if (protocol == ProviderProtocol.GEMINI) raw.removePrefix("models/") else raw

    companion object {
        const val MAX_PAGES = 30
        const val MAX_MODELS = 5000
        const val REQUEST_TIMEOUT_MS = 15_000L

        /**
         * Keeps probe results the user already paid for when a model list is refreshed, unless the provider now
         * states the capability itself.
         */
        fun mergeKnown(previous: List<ModelInfo>, fresh: List<ModelInfo>): List<ModelInfo> {
            val old = previous.associateBy { it.id }
            return fresh.map { model ->
                val before = old[model.id] ?: return@map model
                val merged = LinkedHashMap(model.capabilities)
                for ((cap, result) in before.capabilities) {
                    val now = merged[cap]
                    if (result.evidence == Evidence.PROBE && (now == null || now.evidence != Evidence.METADATA)) merged[cap] = result
                }
                model.copy(capabilities = merged)
            }
        }
    }
}
