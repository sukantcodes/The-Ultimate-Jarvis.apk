package com.jarvis.assistant.udn

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * Routes spoken or typed network commands. The same router serves the always-on voice service (so it works while
 * another JARVIS screen or another app is in front, and in the permitted background mode) and the Chatbox.
 *
 * It never reports success because a command was merely sent: the reply is composed from the result the target
 * device reported (see [UdnReplyComposer]).
 *
 * Confirmation rules: a spoken instruction for a medium-sensitivity action (lock, volume, brightness, flashlight) is
 * itself the sending parent's confirmation. High-sensitivity actions (location, camera) and removing a device need an
 * explicit spoken "yes"; camera and location additionally need Allow on the target device.
 */
object UdnVoiceRouter {
    private const val PENDING_MS = 30_000L

    private sealed class Pending(val expiresAt: Long = System.currentTimeMillis() + PENDING_MS) {
        class Confirm(val capability: String, val params: JSONObject, val devices: List<UdnDevice>) : Pending()
        class Choose(val intent: UdnIntent, val candidates: List<UdnDeviceRef>) : Pending()
    }

    @Volatile private var pending: Pending? = null

    private fun livePending(): Pending? = pending?.takeIf { System.currentTimeMillis() < it.expiresAt }

    /** Fast, synchronous check used to decide whether this utterance belongs to the network layer. */
    fun matches(context: Context, text: String): Boolean {
        val mgr = UdnManager.get(context)
        if (!mgr.state.value.enrolled) return false
        when (val p = livePending()) {
            is Pending.Confirm -> if (UdnIntentParser.readYesNo(text) != null) return true
            is Pending.Choose -> if (UdnIntentParser.pickCandidate(text, p.candidates) != null) return true
            null -> Unit
        }
        val parsed = UdnIntentParser.parse(text) ?: return false
        val target = parsed.target ?: return true // network-level request such as "show my devices"
        return UdnIntentParser.resolve(target, mgr.deviceRefs()) !is UdnResolution.ThisDevice
    }

    suspend fun handle(context: Context, text: String): String {
        val mgr = UdnManager.get(context)
        val lang = UdnReplyComposer.detectLanguage(text)
        val state = mgr.state.value
        if (!state.enrolled) return UdnReplyComposer.notJoined(lang)
        if (!state.authorized) return UdnReplyComposer.notAuthorized(lang)
        if (!state.isParent) return UdnReplyComposer.parentOnly(lang)

        mgr.refreshRegistry()

        val p = livePending()
        pending = null
        when (p) {
            is Pending.Confirm -> when (UdnIntentParser.readYesNo(text)) {
                true -> return run(context, mgr, lang, p.devices, p.capability, p.params, confirmed = true)
                false -> return UdnReplyComposer.cancelled(lang)
                null -> Unit
            }
            is Pending.Choose -> UdnIntentParser.pickCandidate(text, p.candidates)?.let { picked ->
                val dev = mgr.state.value.devices.firstOrNull { it.id == picked.id }
                if (dev != null) return proceed(context, mgr, lang, p.intent, listOf(dev))
            }
            null -> Unit
        }

        val parsed = UdnIntentParser.parse(text) ?: return UdnReplyComposer.notFound(lang, text.take(40))
        if (parsed.intent == UdnIntent.ListDevices) return UdnReplyComposer.listDevices(lang, mgr.state.value.devices, mgr.selfId)

        val refs = mgr.deviceRefs()
        val target = parsed.target ?: return UdnReplyComposer.notFound(lang, text.take(40))
        val devices: List<UdnDevice> = when (val r = UdnIntentParser.resolve(target, refs)) {
            is UdnResolution.Single -> listOfNotNull(mgr.state.value.devices.firstOrNull { it.id == r.device.id })
            is UdnResolution.Many -> r.devices.mapNotNull { d -> mgr.state.value.devices.firstOrNull { it.id == d.id } }
            is UdnResolution.Ambiguous -> {
                pending = Pending.Choose(parsed.intent, r.candidates)
                return UdnReplyComposer.which(lang, r.candidates.map { it.name })
            }
            is UdnResolution.NotFound -> return UdnReplyComposer.notFound(lang, r.phrase)
            UdnResolution.ThisDevice -> return UdnReplyComposer.notFound(lang, "another device") // matches() prevents this
        }
        if (devices.isEmpty()) return UdnReplyComposer.noDevices(lang)
        return proceed(context, mgr, lang, parsed.intent, devices)
    }

    private fun commandFor(intent: UdnIntent): Pair<String, JSONObject>? = when (intent) {
        is UdnIntent.Query -> when (intent.kind) {
            UdnQuery.BATTERY -> UdnCapabilities.DEVICE_BATTERY to JSONObject()
            UdnQuery.CONNECTIVITY -> UdnCapabilities.DEVICE_CONNECTIVITY to JSONObject()
            UdnQuery.INFO -> UdnCapabilities.DEVICE_INFO to JSONObject()
            UdnQuery.ONLINE -> null
        }
        is UdnIntent.OpenModule -> UdnCapabilities.MODULE_OPEN to JSONObject().put("module", intent.moduleId)
        UdnIntent.ListModules -> UdnCapabilities.MODULE_LIST to JSONObject()
        is UdnIntent.LaunchApp -> UdnCapabilities.APP_LAUNCH to JSONObject().put("app", intent.app)
        UdnIntent.Lock -> UdnCapabilities.DEVICE_LOCK to JSONObject()
        UdnIntent.StopListening -> UdnCapabilities.LISTENING_STOP to JSONObject()
        is UdnIntent.Volume -> UdnCapabilities.SETTINGS_VOLUME to JSONObject().apply { intent.level?.let { put("level", it) }; intent.delta?.let { put("delta", it) } }
        is UdnIntent.Brightness -> UdnCapabilities.SETTINGS_BRIGHTNESS to JSONObject().apply { intent.level?.let { put("level", it) }; intent.delta?.let { put("delta", it) } }
        is UdnIntent.Flashlight -> UdnCapabilities.SETTINGS_FLASHLIGHT to JSONObject().put("on", intent.on)
        UdnIntent.Location -> UdnCapabilities.LOCATION_GET to JSONObject()
        UdnIntent.OpenCamera -> UdnCapabilities.CAMERA_OPEN to JSONObject()
        is UdnIntent.Notify -> UdnCapabilities.NOTIFY_SHOW to JSONObject().put("text", intent.text)
        is UdnIntent.Routine -> UdnCapabilities.ROUTINE_RUN to JSONObject().put("name", intent.name)
        UdnIntent.Disconnect, UdnIntent.ListDevices, is UdnIntent.Message -> null
    }

    private suspend fun proceed(context: Context, mgr: UdnManager, lang: UdnLang, intent: UdnIntent, devices: List<UdnDevice>): String {
        if (intent is UdnIntent.Message) {
            return coroutineScope {
                devices.map { d -> async { sendMessageTo(mgr, lang, d, intent.text) } }.awaitAll().joinToString(" ")
            }
        }
        if (intent is UdnIntent.Query && intent.kind == UdnQuery.ONLINE) {
            return devices.joinToString(" ") { UdnReplyComposer.online(lang, it) }
        }
        if (intent == UdnIntent.Disconnect) {
            val names = devices.map { it.name }
            pending = Pending.Confirm("disconnect", JSONObject(), devices)
            return UdnReplyComposer.confirm(lang, UdnReplyComposer.actionPhrase("disconnect"), names)
        }
        val (capability, params) = commandFor(intent) ?: return UdnReplyComposer.notFound(lang, "that command")
        if (UdnCapabilities.sensitivityOf(capability) == Sensitivity.HIGH) {
            pending = Pending.Confirm(capability, params, devices)
            return UdnReplyComposer.confirm(lang, UdnReplyComposer.actionPhrase(capability), devices.map { it.name })
        }
        return run(context, mgr, lang, devices, capability, params, confirmed = true)
    }

    private suspend fun run(
        context: Context, mgr: UdnManager, lang: UdnLang, devices: List<UdnDevice>,
        capability: String, params: JSONObject, confirmed: Boolean
    ): String {
        if (capability == "disconnect") {
            val parts = ArrayList<String>()
            for (d in devices) parts += (
                when (val r = mgr.revokeDevice(d.id)) {
                    is UdnResult.Ok -> when (lang) {
                        UdnLang.EN -> "${d.name} has been removed from your network."
                        UdnLang.HI -> "${d.name} को आपके नेटवर्क से हटा दिया गया।"
                        UdnLang.HINGLISH -> "${d.name} ko aapke network se hata diya gaya."
                    }
                    is UdnResult.Err -> UdnReplyComposer.failure(lang, d, r.code, r.message)
                })
            return parts.joinToString(" ")
        }
        return coroutineScope {
            devices.map { d -> async { runOne(mgr, lang, d, capability, params, confirmed) } }.awaitAll().joinToString(" ")
        }
    }

    private suspend fun sendMessageTo(mgr: UdnManager, lang: UdnLang, requested: UdnDevice, text: String): String {
        val d = mgr.state.value.devices.firstOrNull { it.id == requested.id } ?: requested
        if (!d.isAuthorized) return UdnReplyComposer.online(lang, d)
        return when (val sent = mgr.sendMessage(listOf(d.id), text)) {
            is UdnResult.Err -> UdnReplyComposer.failure(lang, d, sent.code, sent.message)
            is UdnResult.Ok -> {
                val commandId = sent.value.firstOrNull()
                val rec = if (commandId != null && d.online) mgr.awaitCommand(commandId, 20_000L) else null
                UdnReplyComposer.messageResult(lang, d, rec?.status, rec?.errorCode)
            }
        }
    }

    private suspend fun runOne(mgr: UdnManager, lang: UdnLang, requested: UdnDevice, capability: String, params: JSONObject, confirmed: Boolean): String {
        val d = mgr.state.value.devices.firstOrNull { it.id == requested.id } ?: requested
        if (!d.isAuthorized) return UdnReplyComposer.online(lang, d)
        if (!d.online) return UdnReplyComposer.offline(lang, d)
        val cap = d.capability(capability)
        if (cap == null || !cap.available) return UdnReplyComposer.unavailable(lang, d, cap?.reason ?: "it hasn't reported that capability")
        if (cap.permissionGranted == false) return UdnReplyComposer.unavailable(lang, d, cap.reason ?: "the required Android permission isn't granted")
        if (cap.policy == "deny") return UdnReplyComposer.failure(lang, d, "denied_by_policy", null)
        val ttl = if (UdnCapabilities.sensitivityOf(capability) == Sensitivity.HIGH || cap.policy == "confirm") 90 else 45
        return when (val r = mgr.sendCommand(d, capability, params, confirmed = confirmed, ttlSeconds = ttl)) {
            is UdnResult.Ok -> UdnReplyComposer.outcome(lang, d, capability, r.value)
            is UdnResult.Err -> UdnReplyComposer.failure(lang, d, r.code, r.message)
        }
    }
}
