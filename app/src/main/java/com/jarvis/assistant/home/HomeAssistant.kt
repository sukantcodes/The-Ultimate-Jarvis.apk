package com.jarvis.assistant.home

import android.content.Context
import com.jarvis.assistant.home.automation.AutomationRun
import com.jarvis.assistant.home.voice.HomeIntent
import com.jarvis.assistant.home.voice.HomeSnapshot
import com.jarvis.assistant.home.voice.SmartDeviceIntentEngine

/** What a voice request came to. [spoken] is accurate to what really happened; [verified] only when devices confirmed it. */
data class HomeVoiceOutcome(val success: Boolean, val verified: Boolean, val spoken: String)

/** Runs a [HomeIntent] against the real controller and phrases the true result. Blocking: call off the main thread. */
object HomeAssistant {

    fun snapshot(context: Context): HomeSnapshot {
        val app = context.applicationContext
        val reg = HomeServices.registry(app)
        return HomeSnapshot(reg.devices(), reg.rooms(), reg.groups(), HomeServices.automationStore(app).automations().filter { it.isScene })
    }

    /** Null when [text] is not about the smart home. */
    fun handle(context: Context, text: String): HomeVoiceOutcome? {
        val app = context.applicationContext
        val intent = SmartDeviceIntentEngine.parse(text, snapshot(app)) ?: return null
        return execute(app, intent)
    }

    fun execute(context: Context, intent: HomeIntent): HomeVoiceOutcome {
        val app = context.applicationContext
        val controller = HomeServices.controller(app)
        return when (intent) {
            is HomeIntent.Clarify -> HomeVoiceOutcome(false, false, intent.question)
            is HomeIntent.ListDevices -> HomeVoiceOutcome(true, true, SmartDeviceIntentEngine.describeList(intent.filter, HomeServices.registry(app).devices()))
            is HomeIntent.Connect -> {
                val r = controller.run(intent.device, DeviceAction(DeviceActionType.CONNECT))
                if (r is CommandResult.Failure && r.reason == FailureReason.UNSUPPORTED && !intent.device.supports(Capability.CONNECT)) {
                    HomeVoiceOutcome(false, false, "I can't connect ${intent.device.name} myself — Android only allows that from Bluetooth settings. I can tell you whether it is connected.")
                } else HomeVoiceOutcome(r is CommandResult.Success, (r as? CommandResult.Success)?.verified == true, r.message)
            }
            is HomeIntent.RunScene -> describeRun(HomeServices.engine(app).run(intent.scene.id))
            is HomeIntent.Control -> {
                val results = controller.runOn(intent.devices, intent.action)
                summarize(intent.description, intent.action.type, results)
            }
        }
    }

    private fun describeRun(run: AutomationRun) = HomeVoiceOutcome(run.success, run.success && run.steps.isNotEmpty(), "${run.name}: ${run.summary}")

    internal fun summarize(label: String, type: DeviceActionType, results: List<BatchResult>): HomeVoiceOutcome {
        val ok = results.filter { it.result is CommandResult.Success }
        val bad = results.filter { it.result is CommandResult.Failure }
        val verb = when (type) { DeviceActionType.POWER_ON -> "on"; DeviceActionType.POWER_OFF -> "off"; else -> "toggled" }
        if (bad.isEmpty()) {
            val verified = ok.all { (it.result as CommandResult.Success).verified }
            val msg = if (verified) "$label ${if (results.size == 1) "is" else "are"} now $verb." else "I sent the command to $label, but the device did not confirm it."
            return HomeVoiceOutcome(true, verified, msg)
        }
        val reasons = bad.joinToString(" ") { it.result.message }
        return HomeVoiceOutcome(false, false, if (ok.isEmpty()) reasons else "${ok.size} worked, ${bad.size} did not. $reasons")
    }
}
