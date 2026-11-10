package com.jarvis.assistant.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Describes the same tools to different providers and reads their tool requests back, so all share one executor. */
object ToolSchemas {

    private fun parameters(spec: ToolSpec, geminiTypes: Boolean) = JsonObject().apply {
        addProperty("type", if (geminiTypes) "OBJECT" else "object")
        add("properties", JsonObject().apply {
            spec.params.forEach { p ->
                add(p.name, JsonObject().apply {
                    addProperty("type", if (geminiTypes) p.type.uppercase() else p.type)
                    if (p.description.isNotBlank()) addProperty("description", p.description)
                    if (p.enumValues.isNotEmpty()) add("enum", JsonArray().apply { p.enumValues.forEach { add(it) } })
                    if (p.type == "array") add("items", JsonObject().apply { addProperty("type", if (geminiTypes) "STRING" else "string") })
                })
            }
        })
        val required = spec.params.filter { it.required }
        if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach { add(it.name) } })
    }

    /** OpenAI-compatible `tools` array. */
    fun toOpenAi(specs: List<ToolSpec>): JsonArray = JsonArray().apply {
        specs.forEach { s ->
            add(JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", s.name); addProperty("description", s.description)
                    add("parameters", parameters(s, false))
                })
            })
        }
    }

    /** Gemini `functionDeclarations` array (used by generateContent and the Live API). */
    fun toGemini(specs: List<ToolSpec>): JsonArray = JsonArray().apply {
        specs.forEach { s ->
            add(JsonObject().apply {
                addProperty("name", s.name); addProperty("description", s.description)
                if (s.params.isNotEmpty()) add("parameters", parameters(s, true))
            })
        }
    }

    /** Reads `tool_calls` from an OpenAI-compatible assistant message. Malformed arguments become an empty object, never a crash. */
    fun parseOpenAiCalls(message: JsonObject, source: String): List<ToolCall> {
        val arr = message.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val fn = o.get("function")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = fn.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            ToolCall(o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: "call_${System.nanoTime()}", name, argsOf(fn.get("arguments")), source)
        }
    }

    /** Reads `functionCall` parts (generateContent) or a Live API `toolCall.functionCalls` list. */
    fun parseGeminiCalls(functionCalls: JsonArray, source: String): List<ToolCall> = functionCalls.mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
        ToolCall(o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: "gcall_${System.nanoTime()}", name, argsOf(o.get("args")), source)
    }

    private fun argsOf(e: JsonElement?): JsonObject = try {
        when {
            e == null || e.isJsonNull -> JsonObject()
            e.isJsonObject -> e.asJsonObject
            e.isJsonPrimitive -> JsonParser.parseString(e.asString).takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            else -> JsonObject()
        }
    } catch (ex: Exception) { JsonObject() }
}
