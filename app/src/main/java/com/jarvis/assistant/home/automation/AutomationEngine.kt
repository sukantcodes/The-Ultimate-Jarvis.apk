package com.jarvis.assistant.home.automation

import com.jarvis.assistant.clock.KeyValueStore
import com.jarvis.assistant.home.CommandResult
import com.jarvis.assistant.home.DeviceStatus
import com.jarvis.assistant.home.HomeController
import com.jarvis.assistant.home.HomeDevice
import com.jarvis.assistant.home.HomeRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList

/** Computes when a time trigger fires next. Pure, so schedules can be tested without a clock or Android. */
object AutomationTime {
    /** The first matching moment strictly after [from], or null when the trigger is not time based. */
    fun nextFire(trigger: Trigger, from: ZonedDateTime): ZonedDateTime? {
        val t = trigger as? Trigger.Time ?: return null
        for (offset in 0..7) {
            val day = from.toLocalDate().plusDays(offset.toLong())
            if (t.days.isNotEmpty() && day.dayOfWeek.value !in t.days) continue
            val candidate = day.atTime(t.hour, t.minute).atZone(from.zone)
            if (candidate.isAfter(from)) return candidate
        }
        return null
    }
}

class AutomationStore(private val kv: KeyValueStore) {
    private val lock = Any()

    fun automations(): List<Automation> = synchronized(lock) {
        runCatching { kv.getString(KEY)?.let { raw -> JSONArray(raw).let { a -> (0 until a.length()).mapNotNull { runCatching { Automation.fromJson(a.getJSONObject(it)) }.getOrNull() } } } }.getOrNull() ?: emptyList()
    }

    fun get(id: String) = automations().firstOrNull { it.id == id }

    fun save(automation: Automation) = synchronized(lock) {
        val all = automations()
        val next = if (all.any { it.id == automation.id }) all.map { if (it.id == automation.id) automation else it } else all + automation
        kv.putString(KEY, JSONArray(next.map { it.toJson() }).toString())
    }

    fun exportJson(): String = JSONArray(automations().map { it.toJson() }).toString()

    /** Replaces all routines from a backup. Returns false and changes nothing if the text is not a valid backup. */
    fun importJson(raw: String): Boolean {
        val list = runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { Automation.fromJson(a.getJSONObject(it)) } } }.getOrNull() ?: return false
        synchronized(lock) { kv.putString(KEY, JSONArray(list.map { it.toJson() }).toString()) }
        return true
    }

    fun delete(id: String) = synchronized(lock) { kv.putString(KEY, JSONArray(automations().filterNot { it.id == id }.map { it.toJson() }).toString()) }

    fun runs(): List<AutomationRun> = synchronized(lock) {
        runCatching { kv.getString(RUNS)?.let { raw -> JSONArray(raw).let { a -> (0 until a.length()).map { AutomationRun.fromJson(a.getJSONObject(it)) } } } }.getOrNull() ?: emptyList()
    }

    fun addRun(run: AutomationRun) = synchronized(lock) {
        kv.putString(RUNS, JSONArray((listOf(run) + runs()).take(MAX_RUNS).map { it.toJson() }).toString())
    }

    fun lastRun(automationId: String): AutomationRun? = runs().firstOrNull { it.automationId == automationId }

    companion object { const val KEY = "home_automations_v1"; const val RUNS = "home_runs_v1"; const val MAX_RUNS = 100 }
}

/**
 * Runs routines: checks conditions against real reported device state, performs steps in order through [HomeController],
 * honours delays, and records exactly what happened. Delays up to [MAX_INLINE_DELAY_S] wait in place; longer ones are handed
 * to [continuation] (WorkManager on Android) so no process is held open. Blocking: call from a background thread.
 */
class AutomationEngine(
    private val registry: HomeRegistry,
    private val controller: HomeController,
    private val store: AutomationStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val continuation: (automationId: String, fromStep: Int, delaySeconds: Int) -> Unit = { _, _, _ -> }
) {
    private val lastState = HashMap<String, Boolean?>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun run(automationId: String, startStep: Int = 0, skipFirstDelay: Boolean = false): AutomationRun {
        val a = store.get(automationId)
            ?: return AutomationRun(automationId, "Unknown routine", clock(), false, "That routine no longer exists.", emptyList()).also { finish(it) }
        if (!a.isConfigured) return finish(AutomationRun(a.id, a.name, clock(), false, "Not configured yet. Choose devices and actions first.", emptyList()))
        if (startStep == 0) {
            val blocked = a.conditions.firstNotNullOfOrNull { unmet(it) }
            if (blocked != null) return finish(AutomationRun(a.id, a.name, clock(), false, "Skipped: $blocked", emptyList()))
        }
        val outcomes = ArrayList<StepOutcome>()
        var deferred: String? = null
        for ((index, step) in a.steps.withIndex()) {
            if (index < startStep) continue
            val wait = if (index == startStep && skipFirstDelay) 0 else step.delaySeconds
            if (wait > MAX_INLINE_DELAY_S) {
                continuation(a.id, index, wait)
                deferred = "Step ${index + 1} continues in ${wait}s."
                break
            }
            if (wait > 0) sleeper(wait * 1000L)
            outcomes += runStep(step)
        }
        val ok = outcomes.isNotEmpty() && outcomes.all { it.success } || (outcomes.isEmpty() && deferred != null)
        val failed = outcomes.count { !it.success }
        val summary = buildString {
            append(if (failed == 0) "Completed ${outcomes.size} step(s)." else "$failed of ${outcomes.size} step(s) failed.")
            if (deferred != null) append(' ').append(deferred)
        }
        return finish(AutomationRun(a.id, a.name, clock(), ok, summary, outcomes))
    }

    /** Called with a device's newly reported power state. Fires state-triggered routines only on a real change. */
    fun onDeviceState(deviceId: String, poweredOn: Boolean?) {
        if (poweredOn == null) return
        val previous = synchronized(lastState) { lastState.put(deviceId, poweredOn) }
        if (previous == null || previous == poweredOn) return
        store.automations().filter { it.enabled && (it.trigger as? Trigger.DeviceState)?.let { t -> t.deviceId == deviceId && t.becomesOn == poweredOn } == true }
            .forEach { run(it.id) }
    }

    private fun unmet(c: Condition): String? {
        val d = registry.device(c.deviceId) ?: return "a condition device was removed"
        if (c.online != null) {
            if (d.status == DeviceStatus.UNKNOWN) return "${d.name}'s connection state is unknown"
            if ((d.status == DeviceStatus.ONLINE) != c.online) return "${d.name} is ${d.status.name.lowercase()}"
        }
        if (c.poweredOn != null) {
            val on = d.poweredOn ?: return "${d.name} has not reported its power state"
            if (on != c.poweredOn) return "${d.name} is ${if (on) "on" else "off"}"
        }
        return null
    }

    private fun targets(step: AutomationStep): List<HomeDevice> = when {
        step.deviceId != null -> listOfNotNull(registry.device(step.deviceId))
        step.groupId != null -> registry.groups().firstOrNull { it.id == step.groupId }?.deviceIds.orEmpty().mapNotNull { registry.device(it) }
        step.roomId != null -> registry.devicesInRoom(step.roomId)
        else -> emptyList()
    }

    private fun runStep(step: AutomationStep): StepOutcome {
        val label = "${step.action.type.name.lowercase().replace('_', ' ')}"
        val all = targets(step)
        if (all.isEmpty()) return StepOutcome(label, false, "Target not found.")
        val compatible = all.filter { it.supports(step.action.required) }
        val skipped = all.size - compatible.size
        if (compatible.isEmpty()) return StepOutcome(label, false, "No selected device supports ${step.action.required.label.lowercase()}.")
        val results = controller.runOn(compatible, step.action)
        val failures = results.filter { it.result is CommandResult.Failure }
        val msg = if (failures.isEmpty()) "${results.size} device(s) ok" + (if (skipped > 0) ", $skipped skipped (unsupported)" else "")
        else failures.joinToString("; ") { it.result.message }.take(200)
        return StepOutcome(label, failures.isEmpty(), msg)
    }

    private fun finish(run: AutomationRun): AutomationRun {
        runCatching { store.addRun(run) }
        registry.log("automation", null, "${run.name}: ${run.summary}", run.success)
        listeners.forEach { runCatching { it() } }
        return run
    }

    companion object { const val MAX_INLINE_DELAY_S = 30 }
}
