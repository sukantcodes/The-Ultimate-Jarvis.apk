package com.jarvis.assistant.automation.rapido

import java.util.Locale

/** Everything the agent needs from the phone. The Android implementation lives in AndroidRapidoDriver. */
interface RapidoDriver {
    fun isRapidoInstalled(): Boolean
    fun foregroundPackage(): String?
    /** Visible nodes of the active window, or null when the screen cannot be read. */
    suspend fun snapshot(): List<RapidoNode>?
    /** Brings the Rapido app to the front and reports whether it really is in front. */
    suspend fun launchApp(): Boolean
    suspend fun click(node: RapidoNode): Boolean
    /** Types into the field and returns true only when the text was read back from it. */
    suspend fun typeInto(node: RapidoNode, text: String): Boolean
    suspend fun scroll(down: Boolean): Boolean
    suspend fun back(): Boolean
    suspend fun pause(ms: Long)
    fun now(): Long
    fun cancelled(): Boolean
    fun progress(message: String)
    /** A fresh reading of what location access the phone offers. Never exposes coordinates. */
    suspend fun locationState(): LocationState
}

object RapidoStatus {
    const val OK = "OK"
    const val NEEDS_SIGN_IN = "NEEDS_SIGN_IN"
    const val NEEDS_USER = "NEEDS_USER"
    const val NEEDS_LOCATION = "NEEDS_LOCATION"
    const val OFFLINE = "OFFLINE"
    const val ERROR = "ERROR"
    const val TIMEOUT = "TIMEOUT"
    const val CANCELLED = "CANCELLED"
    const val NOT_FOUND = "NOT_FOUND"
    const val AMBIGUOUS = "AMBIGUOUS"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val NOT_VERIFIED = "NOT_VERIFIED"
    const val LAUNCH_FAILED = "LAUNCH_FAILED"
    const val SCREEN_UNREADABLE = "SCREEN_UNREADABLE"
    const val WRONG_SCREEN = "WRONG_SCREEN"
    const val REFUSED = "REFUSED"
    const val UNSUPPORTED = "UNSUPPORTED"
    const val ACTIVE_RIDE = "ACTIVE_RIDE"
    const val NO_DRIVERS = "NO_DRIVERS"
    const val PREPARED = "PREPARED"
    const val REQUESTED = "REQUESTED"
    const val BOOKED = "BOOKED"
    const val CANCEL_PREPARED = "CANCEL_PREPARED"
    const val RIDE_CANCELLED = "RIDE_CANCELLED"
    const val STATUS = "STATUS"
}

data class RapidoResult(
    val success: Boolean,
    val status: String,
    val message: String,
    val verified: Boolean = false,
    val extra: Map<String, Any?> = emptyMap()
)

/** Which place field a candidate list belongs to. */
enum class PlaceField { PICKUP, DROP, STOP }

/** Remembers the trip in progress so follow-up commands refer to real things and an interruption loses nothing. */
class RapidoSession {
    @Volatile var request: RideRequest? = null
    @Volatile var options: List<FareOption> = emptyList()
    @Volatile var optionsAt: Long = 0L
    /** The place labels Rapido itself offered and the agent tapped; the only place text trusted as "what was entered". */
    @Volatile var pickupLabel: String? = null
    @Volatile var dropLabel: String? = null
    @Volatile var stopLabels: List<String> = emptyList()
    @Volatile var candidates: List<String> = emptyList()
    @Volatile var candidateField: PlaceField? = null
    @Volatile var lastFingerprint: String? = null

    @Synchronized fun resetRoute() {
        pickupLabel = null; dropLabel = null; stopLabels = emptyList(); candidates = emptyList(); candidateField = null
        options = emptyList(); optionsAt = 0L; lastFingerprint = null
    }

    /** Same trip as the one already set up in the Rapido app? */
    fun sameRoute(r: RideRequest): Boolean {
        val d = dropLabel ?: return false
        val drop = r.drop ?: return false
        if (RideText.containment(drop, d) < 0.6) return false
        val wantPickup = r.pickup
        if (!wantPickup.isNullOrBlank()) {
            val p = pickupLabel ?: return false
            if (RideText.containment(wantPickup, p) < 0.6) return false
        }
        return r.stops.size == stopLabels.size
    }
}

class RapidoAgent(
    private val driver: RapidoDriver,
    private val gate: RideGate,
    private val session: RapidoSession = RapidoSession()
) {
    companion object {
        const val FIND_BUDGET_MS = 110_000L
        const val PREPARE_BUDGET_MS = 40_000L
        const val BOOK_SUBMIT_MS = 45_000L
        const val DRIVER_WAIT_MS = 60_000L
        const val CANCEL_BUDGET_MS = 40_000L
        const val FARE_STALE_MS = 150_000L
        const val MAX_BACKS = 4
    }

    private class Deadline(private val driver: RapidoDriver, budgetMs: Long) {
        private val end = driver.now() + budgetMs
        fun left(): Long = end - driver.now()
        fun expired(): Boolean = left() <= 0
    }

    private class Waited(val screen: RapidoScreen, val nodes: List<RapidoNode>, val code: String? = null)

    // ---- Waiting and recovery -------------------------------------------------------------------

    /**
     * Reads the screen until it is one of [accept] or a state only the user can fix (sign-in, security check, location
     * off, a live ride). Along the way it waits out loading, taps "Try again" on error and offline pages with growing
     * pauses, dismisses harmless pop-ups, and reopens the app when the page stays broken.
     */
    private suspend fun waitFor(deadline: Deadline, timeoutMs: Long, accept: Set<RapidoScreen>): Waited {
        val limit = Deadline(driver, minOf(timeoutMs, deadline.left()))
        var recoveries = 0
        var dismissals = 0
        var unknownStreak = 0
        var last = Waited(RapidoScreen.UNKNOWN, emptyList(), "TIMEOUT")
        while (!limit.expired()) {
            if (driver.cancelled()) return Waited(last.screen, last.nodes, "CANCELLED")
            val nodes = driver.snapshot()
            if (nodes == null) {
                last = Waited(RapidoScreen.UNKNOWN, emptyList(), "UNREADABLE")
                driver.pause(500)
                continue
            }
            val screen = RapidoScreenReader.classify(nodes)
            last = Waited(screen, nodes)
            if (screen in accept) return last
            when (screen) {
                RapidoScreen.SIGN_IN, RapidoScreen.CHALLENGE, RapidoScreen.LOCATION_OFF, RapidoScreen.NO_DRIVERS,
                RapidoScreen.TRIP_COMPLETED -> return last
                RapidoScreen.SEARCHING_DRIVER, RapidoScreen.DRIVER_ASSIGNED, RapidoScreen.DRIVER_ARRIVED, RapidoScreen.ON_TRIP ->
                    if (RapidoScreen.SEARCHING_DRIVER !in accept) return last else driver.pause(600)
                RapidoScreen.LOADING -> driver.pause(450)
                RapidoScreen.OFFLINE, RapidoScreen.ERROR -> {
                    if (recoveries >= 3) return last
                    recoveries++
                    driver.progress("Rapido had a problem; retrying ($recoveries of 3)")
                    driver.pause(1_200L * recoveries)
                    val retry = RapidoScreenReader.retryButton(nodes)
                    if (retry != null) driver.click(retry)
                    else if (recoveries == 2) driver.launchApp()
                    driver.pause(900)
                }
                else -> {
                    val dismiss = RapidoScreenReader.dismissButton(nodes)
                    if (dismiss != null && dismissals < 3) {
                        dismissals++
                        driver.click(dismiss)
                        driver.pause(500)
                    } else {
                        unknownStreak++
                        if (unknownStreak == 8) driver.launchApp()
                        driver.pause(450)
                    }
                }
            }
        }
        return Waited(last.screen, last.nodes, last.code ?: "TIMEOUT")
    }

    /** Converts a screen the agent cannot work with into an honest result for the user. */
    private fun stuck(w: Waited, doing: String): RapidoResult = when {
        w.code == "CANCELLED" -> RapidoResult(false, RapidoStatus.CANCELLED, "Stopped while $doing, as you asked.")
        w.screen == RapidoScreen.SIGN_IN -> RapidoResult(false, RapidoStatus.NEEDS_SIGN_IN,
            "Rapido is asking you to log in. Please log in yourself (Jarvis never types OTPs or passwords), then tell me to continue.")
        w.screen == RapidoScreen.CHALLENGE -> RapidoResult(false, RapidoStatus.NEEDS_USER,
            "Rapido is asking for ${RapidoScreenReader.challengeKind(w.nodes)}. Please complete it yourself; Jarvis does not enter or bypass security checks. Tell me to continue afterwards.")
        w.screen == RapidoScreen.LOCATION_OFF -> RapidoResult(false, RapidoStatus.NEEDS_LOCATION,
            "Rapido says location is off or not allowed. Turn on the phone's location and allow it for Rapido, then tell me to continue, or give me the pickup address.")
        w.screen == RapidoScreen.OFFLINE -> RapidoResult(false, RapidoStatus.OFFLINE,
            "Rapido cannot load because the phone seems to be offline. Check the internet and tell me to try again.")
        w.screen == RapidoScreen.ERROR -> RapidoResult(false, RapidoStatus.ERROR,
            "Rapido kept showing an error while $doing, even after retries.")
        RapidoScreenReader.isActiveRide(w.screen) -> RapidoResult(false, RapidoStatus.ACTIVE_RIDE,
            "A Rapido ride is already in progress (${RapidoScreenReader.stageOf(w.screen).name.lowercase(Locale.ROOT).replace('_', ' ')}). I did not start another. Use rapido_status, or ask me to cancel it.",
            extra = mapOf("stage" to RapidoScreenReader.stageOf(w.screen).name))
        w.screen == RapidoScreen.NO_DRIVERS -> RapidoResult(false, RapidoStatus.NO_DRIVERS,
            "Rapido says no captains are available right now.")
        w.code == "UNREADABLE" -> RapidoResult(false, RapidoStatus.SCREEN_UNREADABLE,
            "The screen could not be read. Make sure the Jarvis accessibility service is on and the Rapido screen is visible.")
        else -> RapidoResult(false, RapidoStatus.TIMEOUT,
            "Rapido did not reach the expected screen while $doing within the time limit (last screen seen: ${w.screen.name.lowercase(Locale.ROOT).replace('_', ' ')}).")
    }

    private fun launchFailure(): RapidoResult =
        if (driver.cancelled()) RapidoResult(false, RapidoStatus.CANCELLED, "Stopped, as you asked.")
        else if (!driver.isRapidoInstalled()) RapidoResult(false, RapidoStatus.LAUNCH_FAILED,
            "The Rapido app is not installed on this phone. Install it from the Play Store and log in; Jarvis cannot book without it.")
        else RapidoResult(false, RapidoStatus.LAUNCH_FAILED, "Could not bring the Rapido app to the front. Open Rapido once yourself, then try again.")

    private suspend fun openApp(): Boolean {
        for (attempt in 1..2) {
            if (driver.cancelled()) return false
            if (RapidoApp.isRapidoPackage(driver.foregroundPackage())) return true
            if (driver.launchApp()) return true
            driver.pause(700)
        }
        return false
    }

    // ---- Location -------------------------------------------------------------------------------

    suspend fun locationStatus(): RapidoResult {
        val state = driver.locationState()
        val extra = LinkedHashMap<String, Any?>()
        extra["access"] = state.access.name
        extra["usable"] = state.usable
        state.accuracyMeters?.let { extra["accuracy_meters"] = it }
        state.areaLabel?.let { extra["area"] = it }
        return RapidoResult(state.usable, if (state.usable) RapidoStatus.OK else RapidoStatus.NEEDS_LOCATION,
            LocationClassifier.explain(state), verified = true, extra = extra)
    }

    // ---- Finding rides --------------------------------------------------------------------------

    /**
     * Sets up the trip in Rapido (pickup, drop, stops), reads every ride option with its fare and pickup time, and
     * recommends one. Safe to call again: when the same trip is already on screen it only re-reads, so interrupted work
     * resumes from the last verified step.
     */
    suspend fun findRides(request: RideRequest, choice: Int?, refresh: Boolean): RapidoResult {
        if (request.drop.isNullOrBlank() && session.dropLabel == null) {
            return RapidoResult(false, RapidoStatus.NOT_FOUND, "Where do you want to go? Tell me the drop location.")
        }
        val deadline = Deadline(driver, FIND_BUDGET_MS)
        driver.progress("Opening Rapido")
        if (!openApp()) return launchFailure()

        var w = waitFor(deadline, 15_000, setOf(RapidoScreen.HOME, RapidoScreen.RIDE_OPTIONS, RapidoScreen.LOCATION_SEARCH, RapidoScreen.MAP_PICKER))
        if (w.screen !in setOf(RapidoScreen.HOME, RapidoScreen.RIDE_OPTIONS, RapidoScreen.LOCATION_SEARCH, RapidoScreen.MAP_PICKER)) {
            // Rapido may open on a screen this reader does not know; a bounded number of "back" taps usually reaches home.
            if (w.screen == RapidoScreen.UNKNOWN) w = backToKnown(deadline, w) else return stuck(w, "opening Rapido")
            if (w.screen !in setOf(RapidoScreen.HOME, RapidoScreen.RIDE_OPTIONS, RapidoScreen.LOCATION_SEARCH, RapidoScreen.MAP_PICKER)) return stuck(w, "opening Rapido")
        }

        val prior = session.options
        val sameTrip = session.sameRoute(request) && !request.drop.isNullOrBlank()
        val resuming = request.drop.isNullOrBlank() && session.dropLabel != null
        val effective = if (resuming) request.copy(drop = session.dropLabel) else request
        session.request = effective

        if (w.screen == RapidoScreen.RIDE_OPTIONS && (sameTrip || resuming)) {
            driver.progress("Reading the fares already on screen")
        } else {
            if (!(sameTrip && w.screen == RapidoScreen.RIDE_OPTIONS)) {
                val routed = setRoute(effective, choice, deadline, w)
                if (routed != null) return routed
            }
        }

        val shown = waitFor(deadline, 30_000, setOf(RapidoScreen.RIDE_OPTIONS))
        if (shown.screen != RapidoScreen.RIDE_OPTIONS) return stuck(shown, "loading the ride options")
        val options = readOptions(shown.nodes)
        if (options.isEmpty()) {
            return RapidoResult(false, RapidoStatus.NOT_FOUND,
                "Rapido's ride options are on screen but I could not read any fare from them. Nothing was guessed.")
        }
        session.options = options
        session.optionsAt = driver.now()
        val paymentMethods = RapidoScreenReader.paymentMethods(shown.nodes)
        return describeFares(effective, options, prior, paymentMethods, refresh)
    }

    private suspend fun backToKnown(deadline: Deadline, from: Waited): Waited {
        var w = from
        for (i in 1..MAX_BACKS) {
            if (driver.cancelled() || deadline.expired()) break
            if (!driver.back()) break
            driver.pause(700)
            val nodes = driver.snapshot() ?: continue
            val screen = RapidoScreenReader.classify(nodes)
            w = Waited(screen, nodes)
            if (screen in setOf(RapidoScreen.HOME, RapidoScreen.RIDE_OPTIONS, RapidoScreen.LOCATION_SEARCH, RapidoScreen.MAP_PICKER)) return w
            if (RapidoScreenReader.isActiveRide(screen) || screen == RapidoScreen.SIGN_IN || screen == RapidoScreen.CHALLENGE) return w
        }
        return w
    }

    private suspend fun readOptions(first: List<RapidoNode>): List<FareOption> {
        val found = LinkedHashMap<String, FareOption>()
        fun absorb(nodes: List<RapidoNode>) {
            RapidoScreenReader.fareOptions(nodes).forEach { o -> if (found.keys.none { it.substringBefore('|') == o.key.substringBefore('|') }) found[o.key] = o.copy(position = found.size) }
        }
        absorb(first)
        // A long list can hide cards below the fold; one careful scroll reveals them.
        if (found.size < 4 && driver.scroll(true)) {
            driver.pause(600)
            val next = driver.snapshot()
            if (next != null && RapidoScreenReader.classify(next) == RapidoScreen.RIDE_OPTIONS) absorb(next)
            driver.scroll(false)
            driver.pause(400)
        }
        return found.values.toList()
    }

    private fun describeFares(request: RideRequest, options: List<FareOption>, prior: List<FareOption>, payments: List<String>, refresh: Boolean): RapidoResult {
        val rec = RapidoRanker.recommend(options, request)
        val sb = StringBuilder("Rapido shows ${options.size} ride option${if (options.size == 1) "" else "s"} (read from the screen):\n")
        sb.append(RapidoRanker.compare(options)).append('\n')
        if (refresh && prior.isNotEmpty()) {
            sb.append(RapidoRanker.diff(prior, options)?.let { "Since the last read: $it.\n" } ?: "No fare changes since the last read.\n")
        }
        rec.option?.let { sb.append("Recommended: ${it.name} (${rec.reason}).\n") } ?: sb.append(rec.reason).append('\n')
        if (request.scheduled) {
            sb.append("Note: you asked for \"${request.scheduledText}\". Scheduling a later ride is not automated by Jarvis; these are fares for a ride right now, and booking will be refused until you ask for a ride now.\n")
        }
        if (request.stops.isNotEmpty() && session.stopLabels.size != request.stops.size) {
            sb.append("Note: the extra stop(s) were not added.\n")
        }
        val extra = LinkedHashMap<String, Any?>()
        extra["options"] = options.map { RapidoRanker.describe(it) }
        rec.option?.let { extra["recommended"] = it.name }
        extra["recommended_over_budget"] = rec.overBudget
        if (payments.isNotEmpty()) extra["payment_methods_shown"] = payments
        session.pickupLabel?.let { extra["pickup"] = it }
        session.dropLabel?.let { extra["drop"] = it }
        extra["fares_read_at_ms"] = session.optionsAt
        return RapidoResult(true, RapidoStatus.OK, sb.toString().trim(), verified = true, extra = extra)
    }

    // ---- Setting pickup, drop and stops ----------------------------------------------------------

    /** Returns null when the route is set and the fare screen is (or is becoming) visible; otherwise the result to hand back. */
    private suspend fun setRoute(request: RideRequest, choice: Int?, deadline: Deadline, start: Waited): RapidoResult? {
        var w = start
        val resumingPick = choice != null && session.candidates.isNotEmpty() && w.screen == RapidoScreen.LOCATION_SEARCH
        if (!resumingPick) {
            session.resetRoute()
            // A fare screen or map pin belongs to an earlier trip; the search screens can be used as they are.
            if (w.screen == RapidoScreen.RIDE_OPTIONS || w.screen == RapidoScreen.MAP_PICKER) {
                w = backToHome(deadline, w)
                if (w.screen != RapidoScreen.HOME && w.screen != RapidoScreen.LOCATION_SEARCH) return stuck(w, "going back to the Rapido home screen")
            }
        }

        // Pickup: only when the user named one. Otherwise Rapido's own current-location default is used.
        if (!request.pickup.isNullOrBlank() && session.pickupLabel == null) {
            val r = enterPlace(request.pickup, PlaceField.PICKUP, w, if (resumingPick && session.candidateField == PlaceField.PICKUP) choice else null, deadline)
            if (r.error != null) return r.error
            session.pickupLabel = r.label
            w = r.after
        } else if (request.usesCurrentLocation) {
            val loc = driver.locationState()
            if (!loc.usable && loc.access != LocationAccess.UNAVAILABLE) {
                // Rapido can still use its own location; this is only a warning the caller adds to the message.
                driver.progress("Phone location is ${loc.access.name.lowercase(Locale.ROOT).replace('_', ' ')}")
            }
        }

        if (session.dropLabel == null && !request.drop.isNullOrBlank()) {
            val r = enterPlace(request.drop, PlaceField.DROP, w, if (resumingPick && session.candidateField == PlaceField.DROP) choice else null, deadline)
            if (r.error != null) return r.error
            session.dropLabel = r.label
            w = r.after
        }

        if (request.stops.isNotEmpty()) {
            val labels = ArrayList<String>()
            for (stop in request.stops) {
                val add = RapidoScreenReader.addStopButton(w.nodes)
                    ?: return RapidoResult(false, RapidoStatus.UNSUPPORTED,
                        "I could not find an \"Add stop\" control in Rapido for this trip, so I did not book anything. Rapido may not offer stops for this ride type or app version. " +
                            "You can add the stop yourself in Rapido, or I can look at fares for the direct trip.")
                driver.click(add)
                driver.pause(600)
                val snap = driver.snapshot()?.let { Waited(RapidoScreenReader.classify(it), it) } ?: return stuck(Waited(RapidoScreen.UNKNOWN, emptyList(), "UNREADABLE"), "adding a stop")
                val r = enterPlace(stop, PlaceField.STOP, snap, null, deadline)
                if (r.error != null) return r.error
                labels += r.label ?: stop
                w = r.after
            }
            session.stopLabels = labels
        }
        return null
    }

    private suspend fun backToHome(deadline: Deadline, from: Waited): Waited {
        var w = from
        for (i in 1..MAX_BACKS) {
            if (w.screen == RapidoScreen.HOME || w.screen == RapidoScreen.LOCATION_SEARCH || driver.cancelled() || deadline.expired()) break
            if (RapidoScreenReader.isActiveRide(w.screen)) break
            driver.back()
            driver.pause(700)
            val nodes = driver.snapshot() ?: continue
            w = Waited(RapidoScreenReader.classify(nodes), nodes)
        }
        return w
    }

    private class Placed(val label: String?, val after: Waited, val error: RapidoResult?)

    /** Types a place, picks the matching suggestion (or asks), and handles the map-pin confirmation. */
    private suspend fun enterPlace(text: String, field: PlaceField, from: Waited, choice: Int?, deadline: Deadline): Placed {
        fun fail(r: RapidoResult) = Placed(null, from, r)
        val what = when (field) { PlaceField.PICKUP -> "pickup"; PlaceField.DROP -> "drop"; PlaceField.STOP -> "stop" }
        var w = from

        // Resuming after the user picked one of several suggestions: the list is still on screen.
        if (choice != null && session.candidates.isNotEmpty() && w.screen == RapidoScreen.LOCATION_SEARCH) {
            val wanted = session.candidates.getOrNull(choice - 1)
                ?: return fail(RapidoResult(false, RapidoStatus.AMBIGUOUS, "Choose a number from 1 to ${session.candidates.size}.", extra = mapOf("choices" to session.candidates)))
            val node = RapidoScreenReader.suggestions(w.nodes).firstOrNull { RideText.containment(wanted, it.label) >= 0.9 }
                ?: return fail(RapidoResult(false, RapidoStatus.NOT_VERIFIED, "The suggestion list changed, so I did not pick anything. Tell me the $what place again."))
            driver.click(node)
            session.candidates = emptyList(); session.candidateField = null
            return afterPick(wanted, what, deadline)
        }

        // Use the search field when it is already open; otherwise tap the box and wait for an input to appear.
        var inputs: List<RapidoNode> = w.nodes
        var field0: RapidoNode? = if (w.screen == RapidoScreen.LOCATION_SEARCH) RapidoScreenReader.placeField(inputs, field == PlaceField.PICKUP) else null
        if (field0 == null) {
            val box = when (field) {
                PlaceField.PICKUP -> RapidoScreenReader.pickupBox(w.nodes) ?: RapidoScreenReader.whereToBox(w.nodes)
                else -> RapidoScreenReader.whereToBox(w.nodes)
            } ?: return fail(RapidoResult(false, RapidoStatus.NOT_FOUND, "I could not find the $what search box in Rapido. Open Rapido's home screen and try again."))
            if (!driver.click(box)) return fail(RapidoResult(false, RapidoStatus.ERROR, "The tap on the $what box could not be sent."))
            val open = Deadline(driver, 8_000)
            while (!open.expired() && field0 == null) {
                if (driver.cancelled()) return fail(RapidoResult(false, RapidoStatus.CANCELLED, "Stopped while entering the $what, as you asked."))
                driver.pause(500)
                val snap = driver.snapshot() ?: continue
                val cls = RapidoScreenReader.classify(snap)
                if (cls == RapidoScreen.SIGN_IN || cls == RapidoScreen.CHALLENGE || cls == RapidoScreen.LOCATION_OFF || RapidoScreenReader.isActiveRide(cls)) return fail(stuck(Waited(cls, snap), "opening the $what search"))
                inputs = snap
                field0 = RapidoScreenReader.placeField(snap, field == PlaceField.PICKUP)
            }
        }
        if (field0 == null) return fail(RapidoResult(false, RapidoStatus.NOT_FOUND, "The $what search field did not open in Rapido."))
        val opened = Waited(RapidoScreen.LOCATION_SEARCH, inputs)
        if (!driver.typeInto(field0, text)) {
            return fail(RapidoResult(false, RapidoStatus.ERROR,
                "I could not type \"$text\" into Rapido's $what field (the text was not read back from the field). Nothing was changed. I can retry, or you can type it yourself."))
        }

        // Wait for suggestions to appear for what was typed.
        var suggestions: List<RapidoNode> = emptyList()
        var nodes = opened.nodes
        val limit = Deadline(driver, 12_000)
        while (!limit.expired()) {
            if (driver.cancelled()) return fail(RapidoResult(false, RapidoStatus.CANCELLED, "Stopped while entering the $what, as you asked."))
            driver.pause(600)
            nodes = driver.snapshot() ?: continue
            suggestions = RapidoScreenReader.suggestions(nodes)
            if (suggestions.isNotEmpty()) break
        }
        if (suggestions.isEmpty()) {
            return fail(RapidoResult(false, RapidoStatus.NOT_FOUND, "Rapido showed no places for \"$text\". Try a nearby landmark or a fuller address."))
        }

        val pick = choosePlace(text, suggestions)
        if (pick.node == null) {
            val shown = pick.shown.take(4)
            session.candidates = shown.map { it.label.replace('\n', ' ').trim() }
            session.candidateField = field
            val list = session.candidates.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
            return fail(RapidoResult(false, RapidoStatus.AMBIGUOUS,
                if (pick.shown.isEmpty()) "None of Rapido's suggestions looks like \"$text\"." else "More than one place matches \"$text\" for the $what:\n$list\nAsk the user which one, then call again with that number as choice.",
                extra = mapOf("choices" to session.candidates, "field" to what)))
        }
        val label = pick.node.label.replace('\n', ' ').trim()
        if (!driver.click(pick.node)) return fail(RapidoResult(false, RapidoStatus.ERROR, "The tap on the $what suggestion could not be sent."))
        return afterPick(label, what, deadline)
    }

    private suspend fun afterPick(label: String, what: String, deadline: Deadline): Placed {
        driver.pause(800)
        val next = waitFor(deadline, 15_000, setOf(RapidoScreen.RIDE_OPTIONS, RapidoScreen.MAP_PICKER, RapidoScreen.HOME, RapidoScreen.LOCATION_SEARCH))
        if (next.screen == RapidoScreen.MAP_PICKER) {
            // Rapido wants the pin confirmed. Confirm only when the address under the pin is the place that was chosen.
            val pinVisible = RapidoScreenReader.placeVisible(next.nodes, label.substringBefore(',').take(40))
            val button = RapidoScreenReader.confirmPlaceButton(next.nodes)
            if (!pinVisible || button == null) {
                return Placed(null, next, RapidoResult(false, RapidoStatus.NEEDS_USER,
                    "Rapido is showing the map pin for the $what, and I cannot confirm it sits on \"$label\". Move the pin to the right spot on the map yourself (Jarvis cannot drag the map), then tell me to continue.",
                    extra = mapOf("field" to what)))
            }
            driver.click(button)
            driver.pause(900)
            val after = waitFor(deadline, 15_000, setOf(RapidoScreen.RIDE_OPTIONS, RapidoScreen.HOME, RapidoScreen.LOCATION_SEARCH))
            return Placed(label, after, null)
        }
        if (next.screen in setOf(RapidoScreen.SIGN_IN, RapidoScreen.CHALLENGE, RapidoScreen.LOCATION_OFF) || RapidoScreenReader.isActiveRide(next.screen)) {
            return Placed(null, next, stuck(next, "setting the $what"))
        }
        return Placed(label, next, null)
    }

    private class Pick(val node: RapidoNode?, val shown: List<RapidoNode>)

    /** Picks a suggestion only when it is clearly the one asked for; otherwise returns the plausible ones to ask about. */
    private fun choosePlace(text: String, suggestions: List<RapidoNode>): Pick {
        val q = RideText.normalize(text)
        fun head(n: RapidoNode) = RideText.normalize(n.label.lineSequence().first().substringBefore(','))
        val scored = suggestions.map { it to RideText.containment(text, it.label) }
        val plausible = scored.filter { it.second >= 0.6 }.map { it.first }
        if (plausible.isEmpty()) return Pick(null, emptyList())
        if (plausible.size == 1) return Pick(plausible.first(), plausible)
        val exact = plausible.filter { head(it) == q }
        if (exact.size == 1) return Pick(exact.first(), plausible)
        // Several labels for the very same place (the app repeating a result) are not an ambiguity.
        val distinct = plausible.map { RideText.normalize(it.label) }.distinct()
        if (distinct.size == 1) return Pick(plausible.first(), plausible)
        return Pick(null, plausible)
    }

    // ---- Preparing a booking -----------------------------------------------------------------------

    /**
     * Selects the chosen ride type in Rapido, re-reads the fare, checks the route on screen and stores a summary. It
     * NEVER books. The summary must be read to the user, who must then say so in a new message.
     */
    suspend fun prepareBooking(optionName: String?, vehicle: VehicleCategory?, payment: String?, utterance: String): RapidoResult {
        val req = session.request ?: return RapidoResult(false, RapidoStatus.NOT_FOUND, "No trip is set up yet. Ask rapido_find_rides first.")
        if (req.scheduled) {
            return RapidoResult(false, RapidoStatus.UNSUPPORTED,
                "You asked for a ride \"${req.scheduledText}\". Jarvis cannot schedule a later ride, and booking now would not be what you asked for, so nothing was booked. Ask for a ride right now if that is what you want.")
        }
        val deadline = Deadline(driver, PREPARE_BUDGET_MS)
        val w = waitFor(deadline, 15_000, setOf(RapidoScreen.RIDE_OPTIONS))
        if (w.screen != RapidoScreen.RIDE_OPTIONS) return stuck(w, "preparing the booking")
        var nodes = w.nodes
        val fresh = RapidoScreenReader.fareOptions(nodes)
        if (fresh.isEmpty()) return RapidoResult(false, RapidoStatus.NOT_VERIFIED, "The fares could not be read, so nothing was prepared.")

        // 1. Which option.
        val chosen = pickOption(fresh, optionName, vehicle, req)
            ?: return RapidoResult(false, RapidoStatus.NOT_FOUND, "I could not match the ride type you want to what Rapido shows now: ${fresh.joinToString { it.name }}.")
        if (!chosen.available) return RapidoResult(false, RapidoStatus.UNAVAILABLE, "${chosen.name} is shown but not available right now.")
        val earlier = session.options.firstOrNull { RideText.normalize(it.name) == RideText.normalize(chosen.name) }
        val fareChanged = earlier != null && (earlier.fare != chosen.fare || earlier.fareMax != chosen.fareMax)
        session.options = fresh
        session.optionsAt = driver.now()

        // 2. The route on screen must be the trip the user asked for.
        val dropWanted = session.dropLabel ?: req.drop.orEmpty()
        if (!RapidoScreenReader.placeVisible(nodes, dropWanted.substringBefore(',').take(40))) {
            return RapidoResult(false, RapidoStatus.NOT_VERIFIED,
                "I cannot see the destination \"$dropWanted\" on Rapido's fare screen, so I cannot verify the trip. Nothing was prepared. Look at the screen or ask me to find rides again.")
        }

        // 3. Select the option and prove it is selected.
        val card = RapidoScreenReader.cardNode(nodes, chosen)
        if (!chosen.selected) {
            if (card == null || !driver.click(card)) {
                return RapidoResult(false, RapidoStatus.ERROR, "I could not tap the ${chosen.name} card in Rapido. Nothing was prepared.")
            }
            driver.pause(700)
            nodes = driver.snapshot() ?: return RapidoResult(false, RapidoStatus.SCREEN_UNREADABLE, "The screen could not be read after choosing ${chosen.name}.")
        }
        val after = RapidoScreenReader.fareOptions(nodes)
        val now = after.firstOrNull { RideText.normalize(it.name) == RideText.normalize(chosen.name) }
            ?: return RapidoResult(false, RapidoStatus.NOT_VERIFIED, "${chosen.name} disappeared from Rapido's list after I chose it. Nothing was prepared.")
        if (!selectionConfirmed(nodes, now)) {
            return RapidoResult(false, RapidoStatus.NOT_VERIFIED,
                "I tapped ${now.name} but could not confirm from the screen that it is the selected ride type. Nothing was prepared; please check the selection in Rapido.")
        }

        // 4. Payment, only through Rapido's own selector.
        if (!payment.isNullOrBlank()) {
            val set = selectPayment(payment, nodes, deadline)
            if (set.error != null) return set.error
            nodes = set.nodes
        }

        // 5. Summary from the screen. Every field the user will hear came from Rapido.
        val pickupFallback = session.pickupLabel ?: if (req.usesCurrentLocation) "Current location" else null
        val summary = RapidoScreenReader.readSummary(nodes, now, pickupFallback, session.dropLabel ?: req.drop, session.stopLabels)
        if (!summary.readable) {
            return RapidoResult(false, RapidoStatus.NOT_VERIFIED, "The pickup, drop, ride type or fare could not all be read, so nothing was prepared.")
        }
        val pending = gate.prepare(summary, utterance)
        session.lastFingerprint = pending.fingerprint

        val msg = StringBuilder("Ride ready to book: ${summary.optionName}, from ${summary.pickup} to ${summary.drop}")
        if (summary.stops.isNotEmpty()) msg.append(" via ").append(summary.stops.joinToString(", "))
        msg.append(". Fare ${RapidoMoney.format(summary.fare, summary.fareMax)}")
        summary.etaMinutes?.let { msg.append(", captain about $it min away") }
        msg.append(". Payment: ").append(summary.payment ?: "not shown on this screen (Rapido's default will be used)").append('.')
        if (summary.extras.isNotEmpty()) msg.append(" Shown extras: ").append(summary.extras.joinToString("; ")).append('.')
        if (fareChanged) msg.append(" The fare changed since you last heard it (was ${RapidoMoney.format(earlier!!.fare, earlier.fareMax)}).")
        if (!req.pickupNote.isNullOrBlank()) msg.append(" Your pickup note is NOT sent: Jarvis cannot add notes in Rapido, so tell the captain by call or chat.")
        return RapidoResult(true, RapidoStatus.PREPARED, msg.toString(), verified = true,
            extra = mapOf("ride" to summary.optionName, "pickup" to summary.pickup, "drop" to summary.drop,
                "fare_min" to summary.fare, "fare_max" to summary.fareMax, "eta_min" to summary.etaMinutes,
                "payment" to summary.payment, "fare_changed" to fareChanged, "note_applied" to req.pickupNote.isNullOrBlank()))
    }

    private fun pickOption(options: List<FareOption>, name: String?, vehicle: VehicleCategory?, req: RideRequest): FareOption? {
        if (!name.isNullOrBlank()) {
            options.firstOrNull { RideText.normalize(it.name) == RideText.normalize(name) }?.let { return it }
            options.filter { RideText.containment(name, it.name) >= 0.99 }.minByOrNull { it.name.length }?.let { return it }
            name.trim().toIntOrNull()?.let { i -> options.getOrNull(i - 1)?.let { return it } }
            return null
        }
        val wanted = req.copy(vehicle = vehicle ?: req.vehicle)
        return RapidoRanker.recommend(options, wanted).option
    }

    private fun selectionConfirmed(nodes: List<RapidoNode>, option: FareOption): Boolean {
        if (option.selected) return true
        val button = RapidoScreenReader.bookButton(nodes) ?: return false
        val label = button.label.lowercase(Locale.ROOT)
        // The Book button names the ride type it will book ("Book Auto"); it must name this one and no other.
        val cat = option.category
        val words = when (cat) {
            VehicleCategory.BIKE -> listOf("bike")
            VehicleCategory.AUTO -> listOf("auto", "rickshaw")
            VehicleCategory.CAB -> listOf("cab", "taxi", "mini", "prime", "sedan", "suv", "xl", "economy", "premium")
            VehicleCategory.OTHER -> emptyList()
        }
        val others = VehicleCategory.values().filter { it != cat && it != VehicleCategory.OTHER }
            .flatMap { c -> when (c) { VehicleCategory.BIKE -> listOf("bike"); VehicleCategory.AUTO -> listOf("auto", "rickshaw"); else -> listOf("cab", "taxi") } }
        if (RideText.containment(option.name, label) >= 0.99 && option.name.isNotBlank()) return true
        return words.any { label.contains(it) } && others.none { label.contains(it) && !words.any { w -> label.contains(w) } }
    }

    private class PaymentSet(val nodes: List<RapidoNode>, val error: RapidoResult?)

    private suspend fun selectPayment(wanted: String, start: List<RapidoNode>, deadline: Deadline): PaymentSet {
        val key = RideText.normalize(wanted)
        fun current(nodes: List<RapidoNode>) = RapidoScreenReader.paymentText(nodes)?.let { RideText.normalize(it).contains(key) } == true
        if (current(start)) return PaymentSet(start, null)
        val opener = start.firstOrNull { it.clickable && Regex("""\b(?:cash|upi|wallet|payment|pay via|pay using|paytm|phonepe)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.haystack) }
            ?: return PaymentSet(start, RapidoResult(false, RapidoStatus.UNSUPPORTED, "I could not find a payment selector on Rapido's fare screen, so I left the payment method as it is."))
        driver.click(opener)
        driver.pause(700)
        var nodes = driver.snapshot() ?: return PaymentSet(start, RapidoResult(false, RapidoStatus.SCREEN_UNREADABLE, "The screen could not be read while choosing the payment method."))
        if (RapidoScreenReader.classify(nodes) == RapidoScreen.CHALLENGE) return PaymentSet(nodes, stuck(Waited(RapidoScreen.CHALLENGE, nodes), "choosing the payment method"))
        val option = nodes.firstOrNull { it.clickable && RideText.normalize(it.label).contains(key) }
        if (option != null) {
            driver.click(option)
            driver.pause(800)
            nodes = driver.snapshot() ?: nodes
        }
        val back = waitFor(deadline, 8_000, setOf(RapidoScreen.RIDE_OPTIONS))
        if (back.screen == RapidoScreen.CHALLENGE) return PaymentSet(back.nodes, stuck(back, "choosing the payment method"))
        if (back.screen != RapidoScreen.RIDE_OPTIONS) return PaymentSet(back.nodes, stuck(back, "returning from the payment selector"))
        if (!current(back.nodes)) {
            return PaymentSet(back.nodes, RapidoResult(false, RapidoStatus.NOT_VERIFIED,
                "I tried to choose \"$wanted\" but Rapido's fare screen does not show it as the payment method. The method may need setup or authorisation in Rapido itself; nothing was prepared."))
        }
        return PaymentSet(back.nodes, null)
    }

    // ---- Booking ------------------------------------------------------------------------------------

    /** Books the prepared ride. Runs only through [RideGate]; see its rules. */
    suspend fun book(utterance: String): RapidoResult {
        val w0 = driver.snapshot() ?: return RapidoResult(false, RapidoStatus.SCREEN_UNREADABLE, "The screen could not be read, so nothing was booked.")
        val screen = RapidoScreenReader.classify(w0)
        if (RapidoScreenReader.isActiveRide(screen)) {
            return RapidoResult(false, RapidoStatus.ACTIVE_RIDE, "A Rapido ride is already in progress, so I did not book another. Use rapido_status.",
                extra = mapOf("stage" to RapidoScreenReader.stageOf(screen).name))
        }
        if (screen != RapidoScreen.RIDE_OPTIONS) {
            return RapidoResult(false, RapidoStatus.WRONG_SCREEN, "Rapido's fare screen is not open, so nothing was booked. Prepare the booking again.")
        }
        val pendingSummary = gate.pending?.summary
            ?: return RapidoResult(false, RapidoStatus.REFUSED, "No ride has been prepared and read back to the user yet.", extra = mapOf("code" to "NOT_PREPARED"))
        val options = RapidoScreenReader.fareOptions(w0)
        val option = options.firstOrNull { RideText.normalize(it.name) == RideText.normalize(pendingSummary.optionName.orEmpty()) }
        val req = session.request
        val summary = RapidoScreenReader.readSummary(w0, option, session.pickupLabel ?: if (req?.usesCurrentLocation == true) "Current location" else null, session.dropLabel ?: req?.drop, session.stopLabels)
        val selectedOk = option != null && selectionConfirmed(w0, option)

        when (val decision = gate.authorize(if (selectedOk) summary else summary.copy(optionName = null), utterance)) {
            is RideDecision.Denied -> {
                if (decision.code == "CHANGED" && summary.readable) {
                    gate.prepare(summary, utterance)
                    return RapidoResult(false, RapidoStatus.REFUSED,
                        decision.reason + " New fare: ${RapidoMoney.format(summary.fare, summary.fareMax)} for ${summary.optionName}. Ask the user to confirm again.",
                        extra = mapOf("code" to decision.code))
                }
                return RapidoResult(false, RapidoStatus.REFUSED, decision.reason, extra = mapOf("code" to decision.code))
            }
            is RideDecision.Allowed -> {
                val ride = decision.ride
                val button = RapidoScreenReader.bookButton(w0)
                    ?: return RapidoResult(false, RapidoStatus.NOT_FOUND, "The Book button was not found, so nothing was booked.")
                // Recorded BEFORE the tap so that, whatever happens next, this ride can never be submitted twice.
                gate.markAttempted(ride)
                driver.progress("Booking the ride")
                var dispatched = driver.click(button)
                if (!dispatched) {
                    driver.pause(800)
                    val again = driver.snapshot()
                    val b2 = again?.let { if (RapidoScreenReader.classify(it) == RapidoScreen.RIDE_OPTIONS) RapidoScreenReader.bookButton(it) else null }
                    // A retry is only safe when the first tap was never dispatched at all.
                    if (b2 != null) dispatched = driver.click(b2)
                }
                if (!dispatched) {
                    gate.release(ride.fingerprint)
                    return RapidoResult(false, RapidoStatus.ERROR, "The tap on Book could not be sent. The ride was NOT booked.")
                }
                return verifyBooking(ride)
            }
        }
    }

    private suspend fun verifyBooking(ride: PendingRide): RapidoResult {
        val deadline = Deadline(driver, BOOK_SUBMIT_MS + DRIVER_WAIT_MS)
        var requestedAt: Long? = null
        var lastScreen = RapidoScreen.UNKNOWN
        while (!deadline.expired()) {
            if (driver.cancelled()) {
                return RapidoResult(false, RapidoStatus.NOT_VERIFIED, "Stopped, as you asked. The Book button WAS pressed; check Rapido before trying again. I will not press it a second time.")
            }
            driver.pause(1_000)
            val nodes = driver.snapshot() ?: continue
            val screen = RapidoScreenReader.classify(nodes)
            lastScreen = screen
            when (screen) {
                RapidoScreen.DRIVER_ASSIGNED, RapidoScreen.DRIVER_ARRIVED, RapidoScreen.ON_TRIP -> {
                    gate.markBooked(ride)
                    val d = RapidoScreenReader.readDriver(nodes)
                    return RapidoResult(true, RapidoStatus.BOOKED, "Booked: Rapido has assigned a captain. " + describeDriver(d), verified = true,
                        extra = driverExtra(d) + mapOf("stage" to RapidoScreenReader.stageOf(screen).name))
                }
                RapidoScreen.SEARCHING_DRIVER -> {
                    if (requestedAt == null) { requestedAt = driver.now(); driver.progress("Rapido is searching for a captain") }
                    if (driver.now() - requestedAt > DRIVER_WAIT_MS) {
                        gate.markBooked(ride)
                        return RapidoResult(true, RapidoStatus.REQUESTED,
                            "The ride request is in and Rapido is still searching for a captain. It is not confirmed until a captain is assigned. Ask me for the status in a moment.",
                            verified = true, extra = mapOf("stage" to RideStage.SEARCHING.name))
                    }
                }
                RapidoScreen.NO_DRIVERS -> {
                    gate.release(ride.fingerprint)
                    return RapidoResult(false, RapidoStatus.NO_DRIVERS,
                        "Rapido found no captain for this ride. Nothing is booked. I can check fares again or try another ride type, but only if you ask.")
                }
                RapidoScreen.CHALLENGE -> return RapidoResult(false, RapidoStatus.NEEDS_USER,
                    "Rapido needs ${RapidoScreenReader.challengeKind(nodes)} to continue. Please complete it yourself. The ride is NOT confirmed yet; ask me for the status afterwards.")
                RapidoScreen.SIGN_IN -> return RapidoResult(false, RapidoStatus.NEEDS_SIGN_IN,
                    "Rapido asked you to log in again. The ride is NOT confirmed. Log in yourself, then ask me for the status.")
                RapidoScreen.LOCATION_OFF -> return RapidoResult(false, RapidoStatus.NEEDS_LOCATION,
                    "Rapido needs location to book. Turn it on and ask me for the status; the ride is NOT confirmed.")
                else -> Unit
            }
        }
        return RapidoResult(false, RapidoStatus.NOT_VERIFIED,
            "I pressed Book but did not see Rapido searching for or assigning a captain (last screen: ${lastScreen.name.lowercase(Locale.ROOT).replace('_', ' ')}). " +
                "I cannot say it was booked. Check Rapido before trying again; I will not press Book a second time.")
    }

    // ---- Status ----------------------------------------------------------------------------------------

    suspend fun status(): RapidoResult {
        val nodes = driver.snapshot() ?: return RapidoResult(false, RapidoStatus.SCREEN_UNREADABLE, "The screen could not be read. Make sure the accessibility service is on and Rapido is open.")
        if (!RapidoApp.isRapidoPackage(driver.foregroundPackage())) {
            if (!openApp()) return launchFailure()
            driver.pause(1_200)
        }
        val fresh = driver.snapshot() ?: nodes
        val screen = RapidoScreenReader.classify(fresh)
        val stage = RapidoScreenReader.stageOf(screen)
        val d = RapidoScreenReader.readDriver(fresh)
        val extra = LinkedHashMap<String, Any?>(driverExtra(d))
        extra["stage"] = stage.name
        return when (screen) {
            RapidoScreen.SEARCHING_DRIVER -> RapidoResult(true, RapidoStatus.STATUS, "Rapido is searching for a captain. No captain is assigned yet.", true, extra)
            RapidoScreen.DRIVER_ASSIGNED -> RapidoResult(true, RapidoStatus.STATUS, "A captain is on the way. " + describeDriver(d), true, extra)
            RapidoScreen.DRIVER_ARRIVED -> RapidoResult(true, RapidoStatus.STATUS, "The captain has arrived at the pickup. " + describeDriver(d), true, extra)
            RapidoScreen.ON_TRIP -> RapidoResult(true, RapidoStatus.STATUS, "The trip is in progress. " + describeDriver(d), true, extra)
            RapidoScreen.TRIP_COMPLETED -> { gate.release(null); RapidoResult(true, RapidoStatus.STATUS, "The ride is completed.", true, extra) }
            RapidoScreen.NO_DRIVERS -> { gate.release(null); RapidoResult(true, RapidoStatus.NO_DRIVERS, "Rapido shows no captain was found. Nothing is booked.", true, extra) }
            RapidoScreen.RIDE_OPTIONS, RapidoScreen.HOME, RapidoScreen.LOCATION_SEARCH, RapidoScreen.MAP_PICKER ->
                RapidoResult(true, RapidoStatus.STATUS, "No ride is active on screen right now (Rapido is showing the booking screens).", true, extra)
            RapidoScreen.SIGN_IN -> stuck(Waited(screen, fresh), "checking the ride")
            RapidoScreen.CHALLENGE -> stuck(Waited(screen, fresh), "checking the ride")
            RapidoScreen.CANCEL_FLOW -> RapidoResult(true, RapidoStatus.STATUS, "Rapido's cancel dialog is open; the ride is not cancelled yet.", true, extra)
            else -> RapidoResult(false, RapidoStatus.NOT_VERIFIED, "I cannot tell the ride state from the current Rapido screen (${screen.name.lowercase(Locale.ROOT).replace('_', ' ')}).", false, extra)
        }
    }

    private fun describeDriver(d: DriverInfo): String {
        if (!d.any) return "Captain details are not visible on this screen."
        val parts = ArrayList<String>()
        d.name?.let { parts += "Captain $it" }
        d.rating?.let { parts += "rated $it" }
        val vehicle = listOfNotNull(d.vehicleModel, d.vehicleNumber).joinToString(" ")
        if (vehicle.isNotBlank()) parts += "vehicle $vehicle"
        d.etaText?.let { parts += it }
        return parts.joinToString(", ") + ". (Read from Rapido's screen; the ride-start OTP is not read aloud by Jarvis.)"
    }

    private fun driverExtra(d: DriverInfo): Map<String, Any?> = linkedMapOf(
        "captain_name" to d.name, "vehicle_number" to d.vehicleNumber, "vehicle_model" to d.vehicleModel,
        "captain_rating" to d.rating, "arrival" to d.etaText
    )

    // ---- Cancelling ----------------------------------------------------------------------------------

    /**
     * Phase one (no confirmation yet): reads the live ride and stores what cancelling means. Phase two: only after the
     * user, in a NEW message, clearly says to cancel, taps Cancel in Rapido and proves the ride ended.
     */
    suspend fun cancelRide(utterance: String, reason: String?): RapidoResult {
        val deadline = Deadline(driver, CANCEL_BUDGET_MS)
        val nodes = driver.snapshot() ?: return RapidoResult(false, RapidoStatus.SCREEN_UNREADABLE, "The screen could not be read, so nothing was cancelled.")
        val screen = RapidoScreenReader.classify(nodes)
        val stage = RapidoScreenReader.stageOf(screen)
        if (screen == RapidoScreen.CANCEL_FLOW) return continueCancel(utterance, reason, nodes, deadline)
        if (!RapidoScreenReader.isActiveRide(screen) || screen == RapidoScreen.ON_TRIP) {
            return RapidoResult(false, RapidoStatus.WRONG_SCREEN,
                if (screen == RapidoScreen.ON_TRIP) "The trip has already started; a trip in progress cannot be cancelled from Jarvis. Use Rapido's support or ask the captain."
                else "There is no cancellable Rapido ride on screen right now (${screen.name.lowercase(Locale.ROOT).replace('_', ' ')}). Nothing was cancelled.",
                extra = mapOf("stage" to stage.name))
        }
        val pending = gate.pendingCancel
        if (pending == null || pending.stage != stage || !RideConfirmation.isExplicit(RideAction.CANCEL, utterance)) {
            // Nothing is tapped. Explain what cancelling means and wait for a clear message.
            val fee = nodes.firstOrNull { Regex("""cancellation (?:fee|charge)""", RegexOption.IGNORE_CASE).containsMatchIn(it.haystack) }?.label?.trim()
            gate.prepareCancel(stage, utterance)
            val d = RapidoScreenReader.readDriver(nodes)
            val msg = "The ride is ${stage.name.lowercase(Locale.ROOT).replace('_', ' ')}. " + (if (d.any && stage != RideStage.SEARCHING) describeDriver(d) + " " else "") +
                (fee?.let { "Rapido shows: \"$it\". " } ?: "Rapido may charge a cancellation fee; it is shown in the app, not known to me. ") +
                "Ask the user whether to cancel, and wait for them to say so clearly in a new message."
            return RapidoResult(true, RapidoStatus.CANCEL_PREPARED, msg, verified = true, extra = mapOf("stage" to stage.name))
        }
        when (val decision = gate.authorizeCancel(stage, utterance)) {
            is CancelDecision.Denied -> return RapidoResult(false, RapidoStatus.REFUSED, decision.reason, extra = mapOf("code" to decision.code))
            is CancelDecision.Allowed -> Unit
        }
        val button = RapidoScreenReader.cancelRideButton(nodes)
            ?: return RapidoResult(false, RapidoStatus.NOT_FOUND, "I could not find Rapido's Cancel button on this screen, so nothing was cancelled. You can cancel in the Rapido app.")
        driver.progress("Cancelling the ride")
        if (!driver.click(button)) return RapidoResult(false, RapidoStatus.ERROR, "The tap on Cancel could not be sent. The ride was NOT cancelled.")
        driver.pause(900)
        val dialog = driver.snapshot() ?: return RapidoResult(false, RapidoStatus.SCREEN_UNREADABLE, "The screen could not be read after pressing Cancel; check Rapido.")
        return continueCancel(utterance, reason, dialog, deadline, authorized = true)
    }

    private suspend fun continueCancel(utterance: String, reason: String?, first: List<RapidoNode>, deadline: Deadline, authorized: Boolean = false): RapidoResult {
        if (!authorized) {
            // The dialog was left open by an earlier call; the same rules apply before it is confirmed.
            when (val decision = gate.authorizeCancel(stageForDialog(), utterance)) {
                is CancelDecision.Denied -> return RapidoResult(false, RapidoStatus.REFUSED, decision.reason, extra = mapOf("code" to decision.code))
                is CancelDecision.Allowed -> Unit
            }
        }
        var nodes = first
        var screen = RapidoScreenReader.classify(nodes)
        if (screen == RapidoScreen.CANCEL_FLOW) {
            val reasons = RapidoScreenReader.cancelReasonOptions(nodes)
            if (reasons.isNotEmpty()) {
                val wanted = reason?.takeIf { it.isNotBlank() }
                val pick = (if (wanted != null) reasons.firstOrNull { RideText.containment(wanted, it.label) >= 0.6 } else null)
                    ?: reasons.firstOrNull { Regex("""changed my mind|plan(?:s)? changed|no longer need|don't need|other""", RegexOption.IGNORE_CASE).containsMatchIn(it.label) }
                if (pick == null) {
                    return RapidoResult(false, RapidoStatus.NEEDS_USER,
                        "Rapido wants a cancellation reason. Ask the user to pick one, then call again with reason: " + reasons.take(6).joinToString("; ") { "\"${it.label}\"" },
                        extra = mapOf("reasons" to reasons.take(6).map { it.label }))
                }
                driver.click(pick)
                driver.pause(700)
                nodes = driver.snapshot() ?: nodes
            }
            val confirm = RapidoScreenReader.confirmCancelButton(nodes) ?: RapidoScreenReader.cancelRideButton(nodes)
            if (confirm != null) {
                driver.click(confirm)
                driver.pause(900)
            }
        }
        // Prove it: the live-ride screens must be gone, or Rapido must say it was cancelled.
        val limit = Deadline(driver, minOf(20_000L, deadline.left().coerceAtLeast(8_000L)))
        var last = RapidoScreen.UNKNOWN
        while (!limit.expired()) {
            driver.pause(900)
            nodes = driver.snapshot() ?: continue
            screen = RapidoScreenReader.classify(nodes)
            last = screen
            val said = nodes.any { Regex("""(?:ride|booking|trip) (?:has been |was |is )?cancel(?:l)?ed|cancelled successfully""", RegexOption.IGNORE_CASE).containsMatchIn(it.haystack) }
            if (said || screen in setOf(RapidoScreen.HOME, RapidoScreen.RIDE_OPTIONS, RapidoScreen.LOCATION_SEARCH)) {
                gate.release(gate.activeFingerprint())
                gate.cancelDone()
                return RapidoResult(true, RapidoStatus.RIDE_CANCELLED, "The ride is cancelled" + (if (said) " (Rapido shows the cancellation)." else " (Rapido is back on its booking screen)."), verified = true)
            }
            if (screen == RapidoScreen.CHALLENGE || screen == RapidoScreen.SIGN_IN) return stuck(Waited(screen, nodes), "cancelling the ride")
        }
        return RapidoResult(false, RapidoStatus.NOT_VERIFIED,
            "I pressed Cancel but Rapido still looks like a live ride (last screen: ${last.name.lowercase(Locale.ROOT).replace('_', ' ')}). I cannot say it was cancelled. Check the Rapido app.")
    }

    private fun stageForDialog(): RideStage = gate.pendingCancel?.stage ?: RideStage.UNKNOWN
}
