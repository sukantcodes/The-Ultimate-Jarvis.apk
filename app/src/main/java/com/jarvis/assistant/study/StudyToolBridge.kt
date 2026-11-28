package com.jarvis.assistant.study

import android.content.Context
import com.google.gson.JsonObject
import com.jarvis.assistant.tools.PermissionPolicy
import com.jarvis.assistant.tools.Risk
import com.jarvis.assistant.tools.Tool
import com.jarvis.assistant.tools.ToolContext
import com.jarvis.assistant.tools.ToolParam
import com.jarvis.assistant.tools.ToolResult
import com.jarvis.assistant.tools.ToolSpec
import org.json.JSONObject

/**
 * Exposes [StudyTools] inside [com.jarvis.assistant.tools.ToolRegistry] so ChatBox (`ProviderChatEngine`),
 * background agents, and custom providers share the exact same Study Board control and inspection capabilities as Voice.
 */
object StudyToolBridge {

    fun all(context: Context): List<Tool> {
        val app = context.applicationContext
        val decls = StudyTools.declarations()
        return decls.map { decl ->
            val name = decl.getString("name")
            val desc = decl.getString("description")
            val paramsObj = decl.optJSONObject("parameters")
            val props = paramsObj?.optJSONObject("properties")
            val reqArr = paramsObj?.optJSONArray("required")
            val required = HashSet<String>()
            if (reqArr != null) {
                for (i in 0 until reqArr.length()) required += reqArr.optString(i)
            }
            val toolParams = ArrayList<ToolParam>()
            if (props != null) {
                val keys = props.keys()
                while (keys.hasNext()) {
                    val pName = keys.next()
                    val pObj = props.optJSONObject(pName) ?: continue
                    val pType = pObj.optString("type", "STRING").lowercase()
                    val pDesc = pObj.optString("description", "")
                    val enumArr = pObj.optJSONArray("enum")
                    val enumVals = ArrayList<String>()
                    if (enumArr != null) {
                        for (i in 0 until enumArr.length()) enumVals += enumArr.optString(i)
                    }
                    toolParams += ToolParam(
                        name = pName,
                        type = pType,
                        description = pDesc,
                        required = pName in required,
                        enumValues = enumVals
                    )
                }
            }
            val spec = ToolSpec(
                name = name,
                description = desc,
                params = toolParams,
                risk = Risk.WRITE,
                group = PermissionPolicy.APP_CONTROL_GROUP
            )
            object : Tool {
                override val spec: ToolSpec = spec

                override fun describe(args: JsonObject): String =
                    if (name == StudyTools.EXPLAIN) "Explain on Study Board: ${args.get("question")?.asString?.take(60).orEmpty()}"
                    else "Study Board action: ${args.get("action")?.asString.orEmpty()}"

                override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                    val jsonArgs = runCatching { JSONObject(args.toString()) }.getOrDefault(JSONObject())
                    val out = StudyTools.execute(app, name, jsonArgs)
                    return if (out.optBoolean("success", true)) {
                        ToolResult.Ok(out.toString())
                    } else {
                        ToolResult.Error(out.optString("message", "Study Board action failed."))
                    }
                }
            }
        }
    }
}
