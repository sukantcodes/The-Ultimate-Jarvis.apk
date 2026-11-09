package com.jarvis.assistant.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * Saves provider profiles as JSON. Uses Gson's tree API only (no reflection), so release builds with R8 need no
 * extra keep rules. The JSON never contains an API key: keys live in the encrypted vault.
 */
object ProfileCodec {

    private const val VERSION = 1

    fun encode(profiles: List<ProviderProfile>): String {
        val root = JsonObject()
        root.addProperty("version", VERSION)
        root.add("profiles", JsonArray().apply { profiles.forEach { add(encodeProfile(it)) } })
        return root.toString()
    }

    fun decode(json: String?): List<ProviderProfile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return emptyList()
            root.asJsonObject.getAsJsonArrayOrNull("profiles")
                ?.mapNotNull { runCatching { decodeProfile(it) }.getOrNull() }
                .orEmpty()
        } catch (e: JsonParseException) {
            emptyList()
        }
    }

    private fun encodeProfile(p: ProviderProfile) = JsonObject().apply {
        addProperty("id", p.id)
        addProperty("name", p.name)
        addProperty("baseUrl", p.baseUrl)
        addProperty("protocol", p.protocol.id)
        addProperty("authMethod", p.authMethod.id)
        addProperty("authName", p.authName)
        addProperty("discoveryPath", p.discoveryPath)
        addProperty("realtimeUrl", p.realtimeUrl)
        addProperty("selectedModelId", p.selectedModelId)
        addProperty("chatModelId", p.chatModelId)
        addProperty("sttModelId", p.sttModelId)
        addProperty("sttSupport", p.sttSupport.name)
        addProperty("lastRefreshAt", p.lastRefreshAt)
        addProperty("lastRefreshStatus", p.lastRefreshStatus)
        add("settings", JsonObject().apply { p.settings.forEach { (k, v) -> addProperty(k, v) } })
        add("models", JsonArray().apply { p.models.forEach { add(encodeModel(it)) } })
    }

    private fun encodeModel(m: ModelInfo) = JsonObject().apply {
        addProperty("id", m.id)
        addProperty("displayName", m.displayName)
        addProperty("ownedBy", m.ownedBy)
        addProperty("contextWindow", m.contextWindow)
        addProperty("manual", m.manual)
        add("methods", JsonArray().apply { m.supportedMethods.forEach { add(it) } })
        add("caps", JsonObject().apply {
            m.capabilities.forEach { (cap, r) ->
                add(cap.name, JsonObject().apply {
                    addProperty("s", r.support.name)
                    addProperty("e", r.evidence.name)
                    if (r.note.isNotEmpty()) addProperty("n", r.note)
                })
            }
        })
    }

    private fun decodeProfile(element: JsonElement): ProviderProfile? {
        if (!element.isJsonObject) return null
        val o = element.asJsonObject
        val id = o.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
        val settings = LinkedHashMap<String, String>()
        o.getAsJsonObjectOrNull("settings")?.entrySet()?.forEach { (k, v) -> if (v.isJsonPrimitive) settings[k] = v.asString }
        return ProviderProfile(
            id = id,
            name = o.stringOrNull("name").orEmpty(),
            baseUrl = o.stringOrNull("baseUrl").orEmpty(),
            protocol = ProviderProtocol.fromId(o.stringOrNull("protocol")),
            authMethod = AuthMethod.fromId(o.stringOrNull("authMethod")),
            authName = o.stringOrNull("authName").orEmpty(),
            discoveryPath = o.stringOrNull("discoveryPath").orEmpty(),
            realtimeUrl = o.stringOrNull("realtimeUrl").orEmpty(),
            models = o.getAsJsonArrayOrNull("models")?.mapNotNull { runCatching { decodeModel(it) }.getOrNull() }.orEmpty(),
            selectedModelId = o.stringOrNull("selectedModelId").orEmpty(),
            chatModelId = o.stringOrNull("chatModelId").orEmpty(),
            sttModelId = o.stringOrNull("sttModelId").orEmpty(),
            sttSupport = enumOr(o.stringOrNull("sttSupport"), Support.UNKNOWN),
            lastRefreshAt = o.get("lastRefreshAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            lastRefreshStatus = o.stringOrNull("lastRefreshStatus").orEmpty(),
            settings = settings
        )
    }

    private fun decodeModel(element: JsonElement): ModelInfo? {
        if (!element.isJsonObject) return null
        val o = element.asJsonObject
        val id = o.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
        val caps = LinkedHashMap<Capability, CapabilityResult>()
        o.getAsJsonObjectOrNull("caps")?.entrySet()?.forEach { (name, value) ->
            val cap = Capability.values().firstOrNull { it.name == name } ?: return@forEach
            if (!value.isJsonObject) return@forEach
            val r = value.asJsonObject
            caps[cap] = CapabilityResult(
                enumOr(r.stringOrNull("s"), Support.UNKNOWN),
                enumOr(r.stringOrNull("e"), Evidence.UNVERIFIED),
                r.stringOrNull("n").orEmpty()
            )
        }
        return ModelInfo(
            id = id,
            displayName = o.stringOrNull("displayName").orEmpty(),
            ownedBy = o.stringOrNull("ownedBy").orEmpty(),
            contextWindow = o.get("contextWindow")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            supportedMethods = o.getAsJsonArrayOrNull("methods")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }.orEmpty(),
            capabilities = caps,
            manual = o.boolOrNull("manual") == true
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
