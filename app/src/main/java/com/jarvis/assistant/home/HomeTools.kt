package com.jarvis.assistant.home

import android.content.Context
import com.jarvis.assistant.home.voice.ListFilter
import com.jarvis.assistant.home.voice.SmartDeviceIntentEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Smart-home tools offered to the AI (Gemini Live). They run the same [HomeAssistant] as the Voice Control page, on the real
 * registry and adapters, so JARVIS only says a device changed when the device itself confirmed it.
 */
object HomeTools {
    const val CONTROL = "home_control"
    const val LIST = "home_list_devices"
    const val SCENE = "home_run_scene"
    val NAMES: Set<String> = setOf(CONTROL, LIST, SCENE)
    const val TOOL_TIMEOUT_MS = 40_000L

    private fun obj(vararg props: Pair<String, JSONObject>) = JSONObject().put("type", "OBJECT").put("properties", JSONObject().also { o -> props.forEach { o.put(it.first, it.second) } })
    private fun p(type: String, description: String, vararg enum: String) = JSONObject().put("type", type).put("description", description).also { if (enum.isNotEmpty()) it.put("enum", JSONArray(enum.toList())) }
    private fun decl(name: String, description: String, params: JSONObject, required: List<String> = emptyList()) =
        JSONObject().put("name", name).put("description", description).put("parameters", params.also { if (required.isNotEmpty()) it.put("required", JSONArray(required)) })

    fun declarations(): List<JSONObject> = listOf(
        decl(CONTROL,
            "Controls the user's registered smart-home devices (lights, fans, plugs, speakers…) in JARVIS Home: turn on/off/toggle one device, a room, a group or a category, or connect a Bluetooth device. " +
                "Pass the user's request in their own words (English, Hindi or Hinglish). The result says whether the device itself confirmed the change; never say it worked unless success and verified are true.",
            obj("command" to p("STRING", "The user's request, e.g. 'turn on bedroom lights' or 'living room ka fan band karo'.")), listOf("command")),
        decl(LIST, "Lists the user's JARVIS Home devices by recorded connection status.",
            obj("filter" to p("STRING", "Which devices to list.", "all", "offline", "online"))),
        decl(SCENE, "Runs a saved JARVIS Home scene such as 'movie mode' or 'good night'. Only configured scenes can run.",
            obj("scene_name" to p("STRING", "Name of the scene.")), listOf("scene_name"))
    )

    /** Blocking; run off the main thread. */
    fun execute(context: Context, name: String, args: JSONObject): JSONObject {
        val app = context.applicationContext
        return try {
            when (name) {
                CONTROL -> {
                    val cmd = args.optString("command").trim()
                    if (cmd.isEmpty()) return fail("No command was given.")
                    val outcome = HomeAssistant.handle(app, cmd) ?: return fail("That does not match any device, room, group or scene in JARVIS Home. Ask the user which device they mean.")
                    toJson(outcome)
                }
                LIST -> {
                    val f = when (args.optString("filter").lowercase()) { "offline" -> ListFilter.OFFLINE; "online" -> ListFilter.ONLINE; else -> ListFilter.ALL }
                    toJson(HomeVoiceOutcome(true, true, SmartDeviceIntentEngine.describeList(f, HomeServices.registry(app).devices())))
                }
                SCENE -> {
                    val scene = args.optString("scene_name").trim()
                    val outcome = HomeAssistant.handle(app, "activate $scene mode") ?: return fail("No scene called \"$scene\" exists in JARVIS Home.")
                    toJson(outcome)
                }
                else -> fail("Unknown smart-home tool.")
            }
        } catch (e: Exception) {
            fail("The smart-home request failed: ${e.message}")
        }
    }

    private fun fail(msg: String) = JSONObject().put("success", false).put("verified", false).put("message", msg)
    private fun toJson(o: HomeVoiceOutcome) = JSONObject().put("success", o.success).put("verified", o.verified).put("message", o.spoken)
}
