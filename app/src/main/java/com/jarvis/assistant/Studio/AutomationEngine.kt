package com.jarvis.assistant.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/** The Jarvis functions an automation can use. The Jarvis service provides the real implementation. */
interface ActionRunner {
    /** Runs one of Jarvis's existing tools (call_contact, send_whatsapp_message, set_volume and so on). */
    suspend fun runTool(name: String, args: JSONObject): JSONObject
    fun speak(text: String)
    suspend fun openApp(name: String): Boolean
    suspend fun batterySaver(on: Boolean): String
    suspend fun doNotDisturb(on: Boolean): String
}

data class RunResult(val success: Boolean, val message: String, val stepsDone: Int, val stepsTotal: Int)

class AutomationEngine(private val store: AutomationStore) {

    class Match(
        val rule: AutomationRule,
        val variables: Map<String, String>,
        val average: Double,
        val literals: Int,
        val phrase: String
    )

    private val compiled = HashMap<String, PhraseMatcher.Pattern?>()

    private fun compiledPattern(phrase: String): PhraseMatcher.Pattern? = synchronized(compiled) {
        if (compiled.containsKey(phrase)) {
            compiled[phrase]
        } else {
            val pattern = PhraseMatcher.compile(phrase)
            compiled[phrase] = pattern
            pattern
        }
    }

    /** Finds the voice automation that best fits what the user said, or null when none fits. Takes well under a millisecond. */
    fun matchVoice(text: String): Match? {
        val spoken = PhraseMatcher.tokenize(text) ?: return null
        var best: Match? = null
        for (rule in store.all()) {
            if (!rule.enabled || rule.trigger.type != TriggerType.VOICE) continue
            for (phrase in rule.trigger.phrases) {
                val pattern = compiledPattern(phrase) ?: continue
                val result = PhraseMatcher.match(pattern, spoken) ?: continue
                val candidate = Match(rule, result.variables, result.average, result.literals, phrase)
                if (best == null || isBetter(candidate, best)) best = candidate
            }
        }
        return best
    }

    private fun isBetter(candidate: Match, current: Match): Boolean {
        if (candidate.average != current.average) return candidate.average > current.average
        if (candidate.literals != current.literals) return candidate.literals > current.literals
        // A rule you made yourself wins over a built-in rule that fits equally well.
        return !candidate.rule.isDefault && current.rule.isDefault
    }

    /** Enabled rules for a system event (battery, charger, message). */
    fun rulesFor(type: TriggerType): List<AutomationRule> =
        store.all().filter { it.enabled && it.trigger.type == type }

    private class Outcome(val ok: Boolean, val message: String = "")

    /** Runs every step of [rule] in order, filling {variables}. A failed step is reported but does not stop the rest. */
    suspend fun run(rule: AutomationRule, variables: Map<String, String>, runner: ActionRunner): RunResult {
        val failures = ArrayList<String>()
        var done = 0
        for (action in rule.actions) {
            val params = HashMap<String, String>()
            for ((key, value) in action.params) params[key] = substitute(value, variables)
            val outcome = try {
                execute(action.type, params, runner)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome(false, e.message ?: e.javaClass.simpleName)
            }
            if (outcome.ok) {
                done++
            } else {
                failures.add((ActionCatalog.find(action.type)?.label ?: action.type) + ": " + outcome.message)
            }
        }
        val total = rule.actions.size
        return if (failures.isEmpty()) {
            RunResult(true, "Automation '" + rule.name + "' finished.", done, total)
        } else {
            RunResult(false, "Automation '" + rule.name + "' had a problem. " + failures.joinToString(" "), done, total)
        }
    }

    private fun substitute(template: String, variables: Map<String, String>): String =
        Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}").replace(template) { match ->
            variables[match.groupValues[1].lowercase()] ?: ""
        }.trim()

    private fun percent(value: String): Int? =
        Regex("\\d{1,3}").find(value)?.value?.toIntOrNull()?.coerceIn(0, 100)

    private fun fromTool(result: JSONObject): Outcome {
        val ok = result.optBoolean("success", !result.has("error"))
        val message = result.optString("message", result.optString("error", ""))
        return Outcome(ok, message)
    }

    private suspend fun execute(type: String, p: Map<String, String>, runner: ActionRunner): Outcome {
        return when (type) {
            "speak" -> {
                if (p["text"].orEmpty().isBlank()) Outcome(false, "There is nothing to say.")
                else {
                    runner.speak(p["text"].orEmpty())
                    Outcome(true)
                }
            }
            "call_contact" -> {
                if (p["contact"].orEmpty().isBlank()) return Outcome(false, "No contact was given.")
                val args = JSONObject().put("contact_name", p["contact"])
                if (!p["sim"].isNullOrBlank()) args.put("sim", p["sim"])
                val result = runner.runTool("call_contact", args)
                val outcome = fromTool(result)
                if (outcome.message.startsWith("TWO_SIMS")) {
                    runner.speak("This phone has two SIMs. Please say the command again with SIM 1 or SIM 2.")
                }
                outcome
            }
            "whatsapp_message" -> {
                if (p["contact"].orEmpty().isBlank()) return Outcome(false, "No contact was given.")
                if (p["message"].orEmpty().isBlank()) return Outcome(false, "There is no message to send.")
                val askFirst = p["ask_first"] != "false"
                fromTool(
                    runner.runTool(
                        "send_whatsapp_message",
                        JSONObject().put("recipient_name", p["contact"]).put("message", p["message"]).put("confirmed", !askFirst)
                    )
                )
            }
            "whatsapp_call" -> {
                if (p["contact"].orEmpty().isBlank()) return Outcome(false, "No contact was given.")
                val askFirst = p["ask_first"] != "false"
                fromTool(
                    runner.runTool(
                        "whatsapp_call",
                        JSONObject().put("recipient_name", p["contact"])
                            .put("call_type", p["call_type"].takeUnless { it.isNullOrBlank() } ?: "voice")
                            .put("confirmed", !askFirst)
                    )
                )
            }
            "open_app" -> {
                val app = p["app"].orEmpty()
                if (app.isBlank()) Outcome(false, "No app name was given.")
                else if (runner.openApp(app)) Outcome(true)
                else Outcome(false, "Could not open $app.")
            }
            "media" -> fromTool(
                runner.runTool("media_playback_control", JSONObject().put("action", p["action"].takeUnless { it.isNullOrBlank() } ?: "pause"))
            )
            "play_youtube" -> {
                if (p["query"].orEmpty().isBlank()) Outcome(false, "Nothing to play.")
                else fromTool(runner.runTool("search_and_play_youtube", JSONObject().put("query", p["query"])))
            }
            "volume", "brightness" -> {
                val mode = p["mode"].takeUnless { it.isNullOrBlank() } ?: "set"
                val tool = if (type == "volume") "set_volume" else "set_brightness"
                val args = JSONObject().put("action", mode)
                if (mode == "set") {
                    val level = percent(p["percent"].orEmpty()) ?: return Outcome(false, "No number between 0 and 100 was given.")
                    args.put("percentage", level)
                } else {
                    percent(p["percent"].orEmpty())?.let { args.put("percentage", it) }
                }
                fromTool(runner.runTool(tool, args))
            }
            "flashlight" -> fromTool(runner.runTool("set_flashlight", JSONObject().put("enabled", p["state"] != "off")))
            "settings_page" -> fromTool(
                runner.runTool("open_settings_page", JSONObject().put("page", p["page"].takeUnless { it.isNullOrBlank() } ?: "main"))
            )
            "battery_saver" -> {
                val message = runner.batterySaver(p["state"] != "off")
                runner.speak(message)
                Outcome(true, message)
            }
            "do_not_disturb" -> {
                val message = runner.doNotDisturb(p["state"] != "off")
                Outcome(true, message)
            }
            "lock_screen" -> fromTool(runner.runTool("lock_device", JSONObject()))
            "go_background" -> fromTool(runner.runTool("go_to_background", JSONObject()))
            "run_macro" -> {
                if (p["name"].orEmpty().isBlank()) Outcome(false, "No macro name was given.")
                else fromTool(runner.runTool("run_macro", JSONObject().put("name", p["name"]).put("input", p["input"].orEmpty())))
            }
            "wait" -> {
                val seconds = (p["seconds"].orEmpty().toDoubleOrNull() ?: 1.0).coerceIn(0.0, 600.0)
                delay((seconds * 1000).toLong())
                Outcome(true)
            }
            else -> Outcome(false, "Unknown action '$type'.")
        }
    }
}
