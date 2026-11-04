package com.jarvis.assistant.automation.rapido

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Ride ledger that survives the app being restarted, so a submitted ride is never submitted again. */
class PrefsRideLedger(context: Context) : RideLedger {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_rapido_rides", Context.MODE_PRIVATE)

    private fun parse(fingerprint: String, raw: String?): RideLedgerEntry? {
        val parts = raw?.split("|") ?: return null
        if (parts.size != 2) return null
        val state = try { BookingState.valueOf(parts[0]) } catch (_: Exception) { return null }
        val at = parts[1].toLongOrNull() ?: return null
        return RideLedgerEntry(fingerprint, state, at)
    }

    override fun get(fingerprint: String): RideLedgerEntry? = parse(fingerprint, prefs.getString(fingerprint, null))

    override fun put(entry: RideLedgerEntry) {
        val editor = prefs.edit()
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        prefs.all.forEach { (key, value) ->
            val at = (value as? String)?.split("|")?.getOrNull(1)?.toLongOrNull()
            if (at != null && at < cutoff) editor.remove(key)
        }
        editor.putString(entry.fingerprint, entry.state.name + "|" + entry.at)
        // commit(): the "attempted" mark must be on disk before the Book button is tapped.
        editor.commit()
    }

    override fun remove(fingerprint: String) { prefs.edit().remove(fingerprint).commit() }

    override fun anyActive(since: Long): RideLedgerEntry? =
        prefs.all.mapNotNull { (k, v) -> parse(k, v as? String) }
            .filter { (it.state == BookingState.ATTEMPTED || it.state == BookingState.BOOKED) && it.at >= since }
            .maxByOrNull { it.at }

    override fun clear() { prefs.edit().clear().commit() }
}

/**
 * Keeps the trip being planned across an interruption (app killed, call came in, user paused). Only words the user
 * said and place labels Rapido itself showed are stored: never coordinates, payment details or captain details.
 */
class PrefsRapidoSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_rapido_session", Context.MODE_PRIVATE)

    fun save(session: RapidoSession, now: Long) {
        val r = session.request ?: return
        val json = JSONObject().apply {
            put("at", now)
            put("drop", r.drop ?: JSONObject.NULL)
            put("pickup", r.pickup ?: JSONObject.NULL)
            put("stops", JSONArray(r.stops))
            put("vehicle", r.vehicle?.name ?: JSONObject.NULL)
            put("tier", r.cabTier ?: JSONObject.NULL)
            put("priority", r.priority.name)
            put("budget", r.maxBudget ?: JSONObject.NULL)
            put("scheduled", r.scheduledText ?: JSONObject.NULL)
            put("note", r.pickupNote ?: JSONObject.NULL)
            put("pickupLabel", session.pickupLabel ?: JSONObject.NULL)
            put("dropLabel", session.dropLabel ?: JSONObject.NULL)
            put("stopLabels", JSONArray(session.stopLabels))
        }
        prefs.edit().putString("session", json.toString()).apply()
    }

    /** Restores a trip saved less than 30 minutes ago into an empty session. */
    fun restore(session: RapidoSession, now: Long) {
        if (session.request != null) return
        val raw = prefs.getString("session", null) ?: return
        try {
            val j = JSONObject(raw)
            if (now - j.optLong("at", 0L) > 30 * 60_000L) return
            fun str(k: String): String? = if (j.isNull(k)) null else j.optString(k).ifBlank { null }
            fun list(k: String): List<String> = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            session.request = RideRequest(
                drop = str("drop"), pickup = str("pickup"), stops = list("stops"),
                vehicle = str("vehicle")?.let { v -> VehicleCategory.values().firstOrNull { it.name == v } },
                cabTier = str("tier"),
                priority = str("priority")?.let { p -> RidePriority.values().firstOrNull { it.name == p } } ?: RidePriority.BALANCED,
                maxBudget = if (j.isNull("budget")) null else j.optDouble("budget").takeIf { !it.isNaN() },
                scheduledText = str("scheduled"), pickupNote = str("note")
            )
            session.pickupLabel = str("pickupLabel")
            session.dropLabel = str("dropLabel")
            session.stopLabels = list("stopLabels")
        } catch (e: Exception) {
            Log.w("RapidoSession", "Saved trip could not be restored: ${e.message}")
        }
    }

    fun clear() { prefs.edit().clear().apply() }
}

/** The six Rapido tools: what the AI is told about them, and how a call is run. */
object RapidoTools {
    private const val TAG = "RapidoTools"

    val NAMES: Set<String> = setOf(
        "rapido_location_status", "rapido_find_rides", "rapido_prepare_booking",
        "rapido_book", "rapido_status", "rapido_cancel_ride"
    )

    /** One call may legitimately take a while (searching, retries); the agent has its own, shorter budgets. */
    const val TOOL_TIMEOUT_MS = 170_000L

    private val session = RapidoSession()
    @Volatile private var gate: RideGate? = null
    @Volatile private var store: PrefsRapidoSessionStore? = null

    private fun gate(context: Context): RideGate = gate ?: synchronized(this) {
        gate ?: RideGate({ System.currentTimeMillis() }, PrefsRideLedger(context)).also { gate = it }
    }

    private fun store(context: Context): PrefsRapidoSessionStore = store ?: synchronized(this) {
        store ?: PrefsRapidoSessionStore(context).also { store = it }
    }

    // ---- What the AI sees ---------------------------------------------------------------------------

    private fun prop(type: String, description: String) =
        JSONObject().put("type", type).put("description", description)

    private fun tool(name: String, description: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", props)
                if (required.isNotEmpty()) put("required", JSONArray(required))
            })
        }

    fun declarations(): List<JSONObject> = listOf(
        tool(
            "rapido_location_status",
            "Says whether the phone's location is usable for a Rapido pickup: precise, approximate, permission denied, GPS off, or no fix. Never returns coordinates. " +
                "Use it before a ride from 'here' / 'my location', or when Rapido complains about location. With open_settings=true it opens the right Android settings page; it never switches location on by itself.",
            JSONObject().put("open_settings", prop("BOOLEAN", "Open the Android settings page that fixes the problem."))
        ),
        tool(
            "rapido_find_rides",
            "Sets up a trip in the Rapido app (pickup, drop, stops) and READS every ride option (Bike, Auto, Cab types) with fare, pickup time and visible extras (toll, tax, surge) from the screen, then recommends one. Works for English and Hinglish. " +
                "Pass the user's own words in 'request' (e.g. 'Indiranagar se Koramangala bike' or 'airport tak sasta auto 300 ke neeche'). Explicit fields override the sentence. " +
                "Without pickup, Rapido's current location is used. If several places match, the status is AMBIGUOUS: ask the user, then call again with 'choice'. " +
                "Call again with refresh=true to re-read stale fares. Report only what the result says; never invent fares, times or availability. This does NOT book anything.",
            JSONObject()
                .put("request", prop("STRING", "The user's own words for the ride."))
                .put("drop", prop("STRING", "Optional: destination, to override the sentence."))
                .put("pickup", prop("STRING", "Optional: pickup place. Omit for the current location."))
                .put("stops", JSONObject().put("type", "ARRAY").put("description", "Optional extra stops in order.").put("items", JSONObject().put("type", "STRING")))
                .put("vehicle", prop("STRING", "Optional: bike, auto or cab."))
                .put("cab_tier", prop("STRING", "Optional: economy, premium or xl."))
                .put("priority", prop("STRING", "Optional: cheapest, fastest or balanced."))
                .put("max_budget", prop("NUMBER", "Optional: highest fare in rupees."))
                .put("scheduled_time", prop("STRING", "Optional: the user's words for a LATER ride. Jarvis cannot schedule rides; this only makes the tool say so honestly."))
                .put("pickup_note", prop("STRING", "Optional: note for the captain. Jarvis cannot send notes; the result says so."))
                .put("choice", prop("INTEGER", "After an AMBIGUOUS result: the number of the place the user picked."))
                .put("refresh", prop("BOOLEAN", "Re-read the fares of the same trip and report what changed.")),
            listOf("request")
        ),
        tool(
            "rapido_prepare_booking",
            "Selects one ride type in Rapido, re-reads its fare, checks the route on the screen and stores a summary. It NEVER books. " +
                "You must read the summary (pickup, drop, ride type, fare, payment) to the user and wait for them to clearly say to book it in a NEW message. Does not change the address.",
            JSONObject()
                .put("option", prop("STRING", "The ride name exactly as listed (e.g. 'Auto', 'Cab Economy'). Omit to use the recommendation."))
                .put("vehicle", prop("STRING", "Optional: bike, auto or cab, when option is omitted."))
                .put("payment", prop("STRING", "Optional: payment method the user asked for (e.g. 'cash', 'UPI'). Chosen only through Rapido's own selector and verified on screen."))
        ),
        tool(
            "rapido_book",
            "Books the prepared Rapido ride. It only works when the user's LATEST words, in a NEW message after they heard the summary from rapido_prepare_booking, clearly tell you to book, " +
                "the screen still matches the summary and no other ride is live. The check uses the user's real transcript, not any argument. Call it at most once per ride. " +
                "BOOKED means a captain is assigned; REQUESTED means Rapido is still searching. Anything else: do not say it is booked and do not call it again; tell the user what it says.",
            JSONObject()
        ),
        tool(
            "rapido_status",
            "Reads the live ride from the Rapido screen: searching, captain assigned (name, vehicle number, arrival), arrived, trip in progress, completed, or no captain found. " +
                "Also use it after the user finished a login, OTP or payment check, or when a booking result was uncertain. Never reads or speaks the ride-start OTP.",
            JSONObject()
        ),
        tool(
            "rapido_cancel_ride",
            "Cancels the live Rapido ride in TWO steps. First call: reads the ride state and any cancellation fee, taps nothing; tell the user and ask. " +
                "Second call: only after the user, in a NEW message, clearly says to cancel. Cannot cancel a trip that already started. If Rapido asks for a reason, pass 'reason' with what the user said.",
            JSONObject().put("reason", prop("STRING", "Optional: the cancellation reason the user chose."))
        )
    )

    // ---- Running a call -----------------------------------------------------------------------------

    private fun goalFor(name: String, args: JSONObject): String = when (name) {
        "rapido_location_status" -> "Rapido: check location"
        "rapido_find_rides" -> "Rapido: find rides " + args.optString("request", args.optString("drop", "")).take(70)
        "rapido_prepare_booking" -> "Rapido: prepare booking"
        "rapido_book" -> "Rapido: book confirmed ride"
        "rapido_status" -> "Rapido: check ride status"
        else -> "Rapido: cancel ride"
    }

    private fun doubleOrNull(args: JSONObject, key: String): Double? =
        if (args.has(key) && !args.isNull(key)) args.optDouble(key).takeIf { !it.isNaN() } else null

    private fun intOrNull(args: JSONObject, key: String): Int? =
        if (args.has(key) && !args.isNull(key)) args.optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null

    private fun strings(args: JSONObject, key: String): List<String>? {
        val array = args.optJSONArray(key) ?: return null
        return (0 until array.length()).map { array.optString(it, "") }.filter { it.isNotBlank() }
    }

    private fun directive(r: RapidoResult): String? = when (r.status) {
        RapidoStatus.PREPARED ->
            "Read this ride summary to the user in one or two short sentences (pickup, drop, ride type, fare, payment) and ask them to say clearly if you should book it. Do not call rapido_book until they answer in a new message."
        RapidoStatus.BOOKED -> "A captain is assigned and verified. Say so once, briefly, with the captain's name and vehicle number if present. Never say the OTP."
        RapidoStatus.REQUESTED -> "The request is in but no captain is assigned yet. Say exactly that. Use rapido_status in a little while."
        RapidoStatus.CANCEL_PREPARED -> "Tell the user the ride state and any fee in one or two sentences and ask if they want to cancel. Do not call rapido_cancel_ride again until they answer in a new message."
        RapidoStatus.RIDE_CANCELLED -> "The cancellation is verified. Say so once, briefly."
        RapidoStatus.AMBIGUOUS -> "Read the numbered places to the user, ask which one, then call rapido_find_rides again with the same request and choice=<number>."
        RapidoStatus.NEEDS_SIGN_IN, RapidoStatus.NEEDS_USER, RapidoStatus.NEEDS_LOCATION ->
            "The user must act on the phone (login, OTP, CAPTCHA, payment check, map pin or location). Tell them exactly that. Do not type, tap or try to get around it. Wait for them to say they are done."
        RapidoStatus.REFUSED ->
            "Nothing was booked or cancelled. Tell the user why in one sentence. Do not call the tool again until the user has heard the summary and confirmed in a new message."
        RapidoStatus.NOT_VERIFIED ->
            "The result is unverified. Do not claim success and do not repeat the action; tell the user what could and could not be confirmed."
        RapidoStatus.UNSUPPORTED ->
            "Say plainly what Jarvis cannot do here and offer the safest alternative from the message. Do not pretend it was done."
        RapidoStatus.ACTIVE_RIDE -> "A ride is already live. Do not start another; use rapido_status to tell the user where it is."
        else -> if (!r.success) "The step failed. Tell the user the real reason briefly. You may retry once with a different approach if it makes sense; never pretend it worked." else null
    }

    private fun toJson(r: RapidoResult): JSONObject = JSONObject().apply {
        put("success", r.success)
        put("status", r.status)
        put("message", r.message)
        put("verified", r.verified)
        r.extra.forEach { (k, v) ->
            when (v) {
                is String, is Number, is Boolean -> put(k, v)
                is Collection<*> -> put(k, JSONArray(v.map { it.toString() }))
                null -> Unit
                else -> put(k, v.toString())
            }
        }
        directive(r)?.let { put("system_directive", it) }
    }

    /** Runs one Rapido tool. Never throws: any failure becomes an honest result. */
    suspend fun execute(
        context: Context,
        name: String,
        args: JSONObject,
        userUtterance: String,
        onProgress: (String) -> Unit = {}
    ): JSONObject {
        val goal = goalFor(name, args)
        try {
            AgentTaskEngine.begin(goal, "rapido")
        } catch (e: Exception) {
            Log.w(TAG, "Task overlay could not start: ${e.message}")
        }
        val result = try {
            store(context).restore(session, System.currentTimeMillis())
            val agent = RapidoAgent(AndroidRapidoDriver(context, onProgress), gate(context), session)
            run(context, agent, name, args, userUtterance).also {
                try { store(context).save(session, System.currentTimeMillis()) } catch (_: Exception) {}
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$name failed: ${e.message}", e)
            RapidoResult(false, RapidoStatus.ERROR, "Rapido automation hit an unexpected problem: ${e.message ?: "unknown"}. Nothing was booked or cancelled.")
        }
        finishTask(result)
        return toJson(result)
    }

    private suspend fun run(context: Context, agent: RapidoAgent, name: String, args: JSONObject, utterance: String): RapidoResult = when (name) {
        "rapido_location_status" -> {
            val r = agent.locationStatus()
            if (args.optBoolean("open_settings", false) && !r.success) openFixSettings(context, r.extra["access"]?.toString())
            r
        }
        "rapido_find_rides" -> {
            val said = args.optString("request", "").ifBlank { args.optString("drop", "") }
            val request = RapidoQueryParser.merge(
                base = RapidoQueryParser.parse(said),
                drop = args.optString("drop", "").takeIf { it.isNotBlank() && args.optString("request", "").isNotBlank() },
                pickup = args.optString("pickup", "").takeIf { it.isNotBlank() },
                stops = strings(args, "stops"),
                vehicle = RapidoQueryParser.vehicleFromText(args.optString("vehicle", "")),
                cabTier = args.optString("cab_tier", "").takeIf { it.isNotBlank() },
                priority = RapidoQueryParser.priorityFromText(args.optString("priority", "")),
                maxBudget = doubleOrNull(args, "max_budget"),
                scheduledText = args.optString("scheduled_time", "").takeIf { it.isNotBlank() },
                pickupNote = args.optString("pickup_note", "").takeIf { it.isNotBlank() }
            )
            agent.findRides(request, intOrNull(args, "choice"), args.optBoolean("refresh", false))
        }
        "rapido_prepare_booking" -> agent.prepareBooking(
            optionName = args.optString("option", "").takeIf { it.isNotBlank() },
            vehicle = RapidoQueryParser.vehicleFromText(args.optString("vehicle", "")),
            payment = args.optString("payment", "").takeIf { it.isNotBlank() },
            utterance = utterance
        )
        "rapido_book" -> agent.book(utterance)
        "rapido_status" -> agent.status()
        else -> agent.cancelRide(utterance, args.optString("reason", "").takeIf { it.isNotBlank() })
    }

    /** Opens the Android page that fixes a location problem. It only navigates; the user flips the switch. */
    private fun openFixSettings(context: Context, access: String?) {
        val intent = when (access) {
            LocationAccess.SERVICES_OFF.name -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            LocationAccess.PERMISSION_DENIED.name ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            else -> null
        } ?: return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Settings page could not be opened: ${e.message}")
        }
    }

    private fun finishTask(result: RapidoResult) {
        try {
            when {
                result.success && result.verified -> {
                    AgentTaskEngine.recordCompletionEvidence(AgentTaskEngine.CompletionEvidence.VERIFIED_GOAL, result.message.take(160))
                    AgentTaskEngine.finish(AgentTaskEngine.State.COMPLETED)
                }
                result.status in setOf(RapidoStatus.NEEDS_SIGN_IN, RapidoStatus.NEEDS_USER, RapidoStatus.NEEDS_LOCATION, RapidoStatus.PREPARED,
                    RapidoStatus.CANCEL_PREPARED, RapidoStatus.AMBIGUOUS) ->
                    AgentTaskEngine.finish(AgentTaskEngine.State.BLOCKED, result.message.take(160))
                result.status == RapidoStatus.CANCELLED -> Unit
                else -> AgentTaskEngine.finish(AgentTaskEngine.State.FAILED, result.message.take(160))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Task overlay could not finish: ${e.message}")
        }
    }

    // ---- Guards for the generic tools --------------------------------------------------------------

    /** Is a Rapido page on screen, and which one? Used to block direct taps on Book/Cancel/pay and typing into security screens. */
    private fun guardContext(): Pair<Boolean, RapidoScreen?> {
        val service = com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService.instance ?: return false to null
        val root = service.getActiveRootNode() ?: return false to null
        // Fast path: taps in unrelated apps never pay for a full screen read.
        if (!RapidoApp.isRapidoPackage(root.packageName?.toString())) return false to null
        val screen = RapidoScreenReader.classify(RapidoNodeReader.read(root))
        return true to screen
    }

    fun blockGenericClick(target: String, description: String, byPositionOrId: Boolean): String? {
        val (context, screen) = guardContext()
        return RapidoGuards.genericClickBlock(context, screen, target, description, byPositionOrId)
    }

    fun blockGenericType(): String? {
        val (context, screen) = guardContext()
        return RapidoGuards.genericTypeBlock(context, screen)
    }
}
