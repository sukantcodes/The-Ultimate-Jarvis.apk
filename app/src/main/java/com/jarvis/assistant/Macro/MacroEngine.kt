package com.jarvis.assistant.macro

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class MacroStep(
    val tool: String,
    val args: JSONObject,
    /** MAYA stores optional/why at step level; older JARVIS macros stored optional inside args. */
    val optional: Boolean = false,
    val why: String = ""
)

data class Macro(
    val name: String,
    val aliases: List<String>,
    val description: String,
    /** Name of the single text input the macro needs (for example "image_prompt"), or empty when it needs none. */
    val param: String,
    val steps: List<MacroStep>,
    /** Optional natural-language intent from MAYA's v3 macro format. */
    val intent: String = "",
    /** MAYA v3 supports multiple named inputs; JARVIS currently accepts one spoken input. */
    val params: List<String> = emptyList()
)

data class StepResult(val ok: Boolean, val message: String = "")

data class MacroOutcome(val success: Boolean, val message: String, val output: String)

/** The phone actions a macro can use. The Jarvis service provides the real implementation. */
interface MacroActions {
    suspend fun openApp(name: String, packageHints: List<String>): StepResult
    suspend fun tapText(label: String): StepResult
    suspend fun typeText(text: String): StepResult
    suspend fun goBack(): StepResult
    suspend fun readScreenText(): StepResult
    fun screenText(): String
}

/**
 * Runs a macro step by step. It finds buttons by their labels (never by screen position), retries taps while
 * a screen is still loading, waits for real on-screen signals instead of guessing durations, skips steps that
 * are marked optional, and stops with a clear message that names the exact step that failed.
 */
class MacroEngine(private val actions: MacroActions) {

    suspend fun run(macro: Macro, input: String, onProgress: (String) -> Unit = {}): MacroOutcome {
        var output = ""
        val total = macro.steps.size
        for ((index, step) in macro.steps.withIndex()) {
            val optional = step.optional || step.args.optBoolean("_optional", false)
            val progressText = "Step " + (index + 1) + "/" + total + ": " + step.tool
            onProgress(if (step.why.isNotBlank()) "$progressText — ${step.why}" else progressText)
            val result = try {
                execute(macro, step, input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StepResult(false, "Error: " + (e.message ?: e.javaClass.simpleName))
            }
            if (step.tool == "read_screen_text" && result.ok) output = result.message
            if (!result.ok && !optional) {
                return MacroOutcome(
                    false,
                    "Stopped at step " + (index + 1) + " of " + total + " (" + step.tool + "): " + result.message,
                    output
                )
            }
        }
        return MacroOutcome(true, "Macro '" + macro.name + "' completed.", output)
    }

    private suspend fun execute(macro: Macro, step: MacroStep, input: String): StepResult {
        val args = step.args
        return when (step.tool) {
            "open_app_by_name" -> actions.openApp(args.optString("name", ""), labels(args.opt("package_hints")))
            "wait" -> {
                delay((args.optDouble("seconds", 1.0) * 1000).toLong())
                StepResult(true)
            }
            "tap_text" -> tap(labels(args.opt("text")), args.optInt("retries", 3))
            "type_text" -> type(fill(args.optString("text", ""), macro.param, input), labels(args.opt("focus")))
            "wait_for_screen_text" -> waitForText(
                labels(args.opt("text")),
                args.optString("mode", "appear"),
                args.optInt("timeout_seconds", 60),
                args.optInt("grace_seconds", 10)
            )
            "wait_for_stable_screen" -> waitForStable(
                args.optInt("min_seconds", 6),
                args.optInt("stable_seconds", 4),
                args.optInt("timeout_seconds", 90)
            )
            "go_back" -> actions.goBack()
            "read_screen_text" -> actions.readScreenText()
            else -> StepResult(false, "Unknown macro step '" + step.tool + "'")
        }
    }

    private fun labels(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).map { value.optString(it, "") }.filter { it.isNotBlank() }
        is String -> if (value.isBlank()) emptyList() else listOf(value)
        else -> emptyList()
    }

    private fun fill(template: String, param: String, input: String): String {
        var text = template.replace("{input}", input)
        if (param.isNotEmpty()) text = text.replace("{" + param + "}", input)
        return text
    }

    private suspend fun tap(options: List<String>, retries: Int): StepResult {
        if (options.isEmpty()) return StepResult(false, "No button label was given.")
        val attempts = retries.coerceIn(1, 5)
        var last = ""
        for (attempt in 1..attempts) {
            for (label in options) {
                val result = actions.tapText(label)
                if (result.ok) return StepResult(true, "Tapped '$label'.")
                last = result.message
            }
            if (attempt < attempts) delay(1_500L)
        }
        return StepResult(false, "Could not find or tap " + options.joinToString(" / ") { "'$it'" } + ". " + last)
    }

    private suspend fun type(text: String, focusLabels: List<String>): StepResult {
        var last = ""
        for (attempt in 1..3) {
            val result = actions.typeText(text)
            if (result.ok) return result
            last = result.message
            // The text box may not be focused yet: tap it by its label, then try again.
            for (label in focusLabels) {
                if (actions.tapText(label).ok) break
            }
            delay(1_200L)
        }
        return StepResult(false, "Could not type the text. $last")
    }

    private suspend fun waitForText(options: List<String>, mode: String, timeoutSec: Int, graceSec: Int): StepResult {
        if (options.isEmpty()) return StepResult(false, "No text to wait for was given.")
        val appear = mode != "disappear"
        val start = System.currentTimeMillis()
        val deadline = start + timeoutSec * 1000L
        var seen = false
        while (System.currentTimeMillis() < deadline) {
            val screen = actions.screenText().lowercase(Locale.ROOT)
            val present = options.any { screen.contains(it.lowercase(Locale.ROOT)) }
            if (present) seen = true
            if (appear && present) return StepResult(true, "Saw the expected text.")
            if (!appear) {
                if (seen && !present) return StepResult(true, "The progress text is gone.")
                // It may never have shown up (or already finished). After the grace period, carry on.
                if (!seen && System.currentTimeMillis() - start > graceSec * 1000L) {
                    return StepResult(true, "The progress text never appeared, so assuming it is already done.")
                }
            }
            delay(1_500L)
        }
        return StepResult(
            false,
            "Timed out after " + timeoutSec + " seconds waiting for " +
                options.joinToString(" / ") { "'$it'" } + (if (appear) " to appear." else " to disappear.")
        )
    }

    /** Waits until the screen stops changing, which means a streaming answer has finished. */
    private suspend fun waitForStable(minSec: Int, stableSec: Int, timeoutSec: Int): StepResult {
        val start = System.currentTimeMillis()
        var last = ""
        var stableSince = start
        while (System.currentTimeMillis() - start < timeoutSec * 1000L) {
            delay(2_000L)
            val current = actions.screenText()
            val now = System.currentTimeMillis()
            if (current != last) {
                last = current
                stableSince = now
            }
            if (now - start >= minSec * 1000L && now - stableSince >= stableSec * 1000L && current.isNotBlank()) {
                return StepResult(true, "The screen has settled.")
            }
        }
        return StepResult(true, "Stopped waiting for the screen to settle; continuing.")
    }
}
