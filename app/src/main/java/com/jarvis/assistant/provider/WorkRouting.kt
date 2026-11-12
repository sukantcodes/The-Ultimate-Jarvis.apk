package com.jarvis.assistant.provider

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** The kinds of work whose provider and model the user can choose separately. */
enum class WorkCategory(val id: String, val label: String) {
    CHAT("chat", "Normal ChatBox conversations"),
    FILE_ANALYSIS("file_analysis", "ChatBox file analysis"),
    IMAGE("image", "Image understanding"),
    CODING("coding", "Coding and debugging"),
    VOICE("voice", "Voice conversations"),
    TASK_PLANNING("task_planning", "Autonomous task planning"),
    TASK_EXECUTION("task_execution", "Task execution"),
    WEB_RESEARCH("web_research", "Web research"),
    GITHUB("github", "GitHub operations"),
    DOCUMENT_GENERATION("document_generation", "Document generation");

    companion object {
        fun fromId(id: String?): WorkCategory? = values().firstOrNull { it.id == id }
    }
}

/**
 * Where one category of work goes. [providerId] is a custom provider's id, [GEMINI] for the built-in Gemini
 * engine, [OFFLINE_TTS] for on-device neural voice, or blank for "whichever custom provider is active".
 * A blank [modelId] means that provider's own model.
 */
data class Route(val providerId: String = "", val modelId: String = "") {
    val isGemini: Boolean get() = providerId == GEMINI
    val isOfflineTts: Boolean get() = providerId == OFFLINE_TTS
    companion object {
        const val GEMINI = "gemini"
        const val OFFLINE_TTS = "offline_tts"
    }
}

/** Saved routes. Plain data only (no secrets). A category with no saved route follows the active custom provider. */
class RoutingStore(private val store: KeyValueStore) {
    private val lock = Any()

    fun get(category: WorkCategory): Route? = synchronized(lock) { read()[category] }

    fun set(category: WorkCategory, route: Route) = synchronized(lock) {
        val all = read().toMutableMap(); all[category] = route; write(all)
    }

    fun clear(category: WorkCategory) = synchronized(lock) {
        val all = read().toMutableMap(); all.remove(category); write(all)
    }

    /** Drops routes that point at a deleted provider so they fall back to the active provider instead of failing. */
    fun forgetProvider(providerId: String) = synchronized(lock) {
        write(read().filterValues { it.providerId != providerId })
    }

    fun all(): Map<WorkCategory, Route> = synchronized(lock) { read() }

    private fun read(): Map<WorkCategory, Route> {
        val text = store.getString(KEY) ?: return emptyMap()
        return try {
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) return emptyMap()
            val out = LinkedHashMap<WorkCategory, Route>()
            for ((k, v) in root.asJsonObject.entrySet()) {
                val cat = WorkCategory.fromId(k) ?: continue
                if (!v.isJsonObject) continue
                val o = v.asJsonObject
                out[cat] = Route(o.get("p")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(), o.get("m")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
            }
            out
        } catch (e: JsonParseException) { emptyMap() }
    }

    private fun write(map: Map<WorkCategory, Route>) {
        val root = JsonObject()
        map.forEach { (c, r) -> root.add(c.id, JsonObject().apply { addProperty("p", r.providerId); addProperty("m", r.modelId) }) }
        store.putString(KEY, root.toString())
    }

    companion object { const val KEY = "work_routes_json" }
}
