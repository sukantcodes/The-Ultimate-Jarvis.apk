package com.jarvis.assistant.automation.rapido

import java.util.Locale

/** Small text readers for the way Rapido words fares, times and statuses. */
object RapidoText {
    private val I = RegexOption.IGNORE_CASE
    private val PRICE = Regex("""(?:₹|(?<![a-z])rs\.?|(?<![a-z])inr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", I)
    private val RANGE = Regex(
        """(?:₹|(?<![a-z])rs\.?)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:-|–|to)\s*(?:₹|(?<![a-z])rs\.?)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", I
    )
    private val ETA = Regex("""(\d{1,3})\s*(?:mins?|minutes?)\b""", I)
    private val SEATS = Regex("""(\d)\s*(?:seats?|seater|persons?|people)\b""", I)
    private val EXTRA = Regex(
        """(?:toll|tax(?:es)?|gst|surge|high demand|busy|booking fee|convenience fee|platform fee|night (?:charge|fee)|parking|airport|waiting charge|cancellation fee)[^|;\n]{0,40}""", I
    )
    private val FEATURE = Regex(
        """\b(?:ac|non-ac|helmet|pink|lite|express|women only|luxury|top rated|cash only|no cash|safest|fastest|cheapest|recommended|faster)\b""", I
    )

    fun parseAmount(raw: String): Double? = raw.replace(",", "").trim().toDoubleOrNull()

    fun prices(text: String): List<Double> =
        PRICE.findAll(text).mapNotNull { parseAmount(it.groupValues[1]) }.toList()

    fun hasPrice(text: String): Boolean = PRICE.containsMatchIn(text)

    /** (low, high) when a range such as "₹80-₹95" is shown; (price, null) for a single price. */
    fun fare(text: String): Pair<Double?, Double?> {
        RANGE.find(text)?.let { m ->
            val a = parseAmount(m.groupValues[1])
            val b = parseAmount(m.groupValues[2])
            if (a != null && b != null && b > a) return a to b
        }
        // A struck-through original price comes first in some cards; the lowest price shown is what is charged.
        val all = prices(text)
        return if (all.isEmpty()) null to null else all.min() to null
    }

    fun etaMinutes(text: String): Int? = ETA.find(text)?.groupValues?.get(1)?.toIntOrNull()
    fun seats(text: String): Int? = SEATS.find(text)?.groupValues?.get(1)?.toIntOrNull()
    fun extras(text: String): List<String> = EXTRA.findAll(text).map { it.value.trim().trimEnd(',', '.') }.distinct().take(4).toList()
    fun features(text: String): List<String> = FEATURE.findAll(text).map { it.value.lowercase(Locale.ROOT) }.distinct().take(4).toList()

    /** Rating such as "4.8" or "4.8 ★". Only a number between 1 and 5 with one decimal counts. */
    fun rating(text: String): Double? =
        Regex("""(?<![\d.])([1-5]\.\d)(?![\d.])\s*(?:★|stars?|rating)?""", I).find(text)?.groupValues?.get(1)?.toDoubleOrNull()

    /** Indian vehicle registration such as "KA 01 AB 1234". */
    fun vehicleNumber(text: String): String? =
        Regex("""\b([A-Z]{2})[\s-]?(\d{1,2})[\s-]?([A-Z]{1,3})[\s-]?(\d{4})\b""").find(text.uppercase(Locale.ROOT))
            ?.let { it.groupValues[1] + " " + it.groupValues[2].padStart(2, '0') + " " + it.groupValues[3] + " " + it.groupValues[4] }
}

/**
 * Reads Rapido screens from a list of accessibility nodes: which screen it is, the fare cards, the ride summary, the
 * captain's details and where the buttons are.
 *
 * Every matching rule is label based, never pixel based, so different screen sizes and layouts keep working. The labels
 * are English; the app in another language is reported as an unknown screen instead of being guessed at. Rapido changes
 * its screens often: any label that cannot be found makes the step fail honestly instead of being assumed.
 */
object RapidoScreenReader {
    private val I = RegexOption.IGNORE_CASE

    // ---- Screen classification ----------------------------------------------------------------

    private val CHALLENGE_PHRASES = listOf(
        "captcha", "type the characters", "not a robot", "solve this puzzle", "verify it's you", "verify it’s you",
        "verify your identity", "enter upi pin", "upi pin", "enter your pin", "enter cvv", "security code",
        "authentication required", "approve the payment", "approve payment", "complete the payment", "enter mpin",
        "payment pending", "complete payment on", "enter card"
    )
    private val OTP_ENTRY_PHRASES = listOf("enter otp", "enter the otp", "enter the code", "verification code", "otp sent", "otp has been sent", "one time password", "one-time password")
    private val SIGN_IN_PHRASES = listOf("log in", "login", "sign in", "enter mobile number", "enter your mobile", "enter phone number", "continue with phone", "verify your number", "welcome to rapido")
    private val OFFLINE_PHRASES = listOf("no internet", "no network", "check your connection", "check your internet", "you're offline", "you are offline", "connection lost", "network error")
    private val ERROR_PHRASES = listOf("something went wrong", "oops", "try again later", "unable to load", "could not load", "couldn't load", "server error", "technical issue")
    private val LOCATION_OFF_PHRASES = listOf(
        "turn on location", "enable location", "location is off", "location services", "gps is off", "gps is disabled",
        "allow location", "location permission", "enable gps", "turn on gps", "location access"
    )
    private val CANCEL_FLOW_PHRASES = listOf("reason for cancel", "why do you want to cancel", "select a reason", "reason for cancelling", "cancel reason", "are you sure you want to cancel", "cancel your ride?", "cancel ride?", "cancel booking?")
    private val COMPLETED_PHRASES = listOf("ride completed", "trip completed", "ride ended", "trip ended", "rate your ride", "rate your captain", "payment summary", "you have reached", "you've reached", "you have arrived at your destination", "how was your ride", "paid to captain", "collect cash")
    private val ON_TRIP_PHRASES = listOf("trip in progress", "ride in progress", "on trip", "ride started", "heading to destination", "on the way to destination", "on your way to", "share ride status", "share live trip", "trip started")
    private val ARRIVED_PHRASES = listOf("captain has arrived", "captain arrived", "captain is here", "your captain has reached", "captain has reached", "arrived at pickup", "arrived at your pickup", "driver has arrived", "captain is waiting", "has arrived")
    private val ASSIGNED_PHRASES = listOf("captain is on the way", "captain on the way", "captain assigned", "is arriving", "arriving in", "on the way to pickup", "on his way", "on the way to you", "share otp", "ride otp", "start otp", "call captain", "contact captain", "captain details", "captain will arrive", "your captain")
    private val SEARCHING_PHRASES = listOf("finding captain", "finding your captain", "looking for captain", "searching for captain", "finding a captain", "connecting you", "finding nearby", "searching for nearby", "assigning captain", "looking for a captain", "finding ride", "booking your ride", "confirming your ride", "finding the best")
    private val NO_DRIVER_PHRASES = listOf("no captains", "no captain", "no drivers", "no rider", "captains are not available", "captains not available", "couldn't find a captain", "could not find a captain", "no captain found", "no ride available", "not able to find", "all captains are busy", "no vehicles", "unable to find a captain", "unable to find captain")
    // Entry points such as "Set location on map" live on the SEARCH screen, so they are not evidence of the pin screen.
    private val MAP_PICKER_PHRASES = listOf("confirm pickup", "confirm drop", "confirm destination", "confirm location", "confirm and proceed", "move the map", "drag the map", "pin location")
    private val LOADING_PHRASES = listOf("loading", "please wait", "fetching", "getting fares", "checking fares", "fetching fares", "getting prices", "finding rides")

    private val VEHICLE_WORDS = Regex("""\b(?:bike|bikes|auto|cab|taxi|mini|prime|sedan|suv|xl|economy|premium|lite|rickshaw)\b""", I)
    private val BOOK_BUTTON = Regex("""^\s*(?:book|confirm|request)\b.*\b(?:bike|auto|cab|taxi|mini|prime|sedan|suv|xl|economy|premium|lite|rapido|ride|now|rickshaw)\b""", I)

    private fun contains(h: String, phrases: List<String>): Boolean = phrases.any { h.contains(it) }

    /** All the readable text of the screen, lower-cased. */
    private fun allText(nodes: List<RapidoNode>): String = nodes.joinToString(" | ") { it.haystack }

    fun classify(nodes: List<RapidoNode>): RapidoScreen {
        if (nodes.isEmpty()) return RapidoScreen.LOADING
        val h = allText(nodes)
        val hasEditable = nodes.any { it.editable }
        val options = fareOptions(nodes)

        // 1. Things only the user can do.
        if (contains(h, CHALLENGE_PHRASES)) return RapidoScreen.CHALLENGE
        // The ride-start OTP is SHOWN to the passenger ("Share OTP 1234 with captain"); only an OTP ENTRY field is a challenge.
        if (hasEditable && contains(h, OTP_ENTRY_PHRASES)) return RapidoScreen.CHALLENGE
        if (hasEditable && contains(h, SIGN_IN_PHRASES) && options.isEmpty() && !contains(h, ASSIGNED_PHRASES)) return RapidoScreen.SIGN_IN

        // 2. Ride in progress, from most to least advanced. These beat the fare list.
        if (contains(h, CANCEL_FLOW_PHRASES)) return RapidoScreen.CANCEL_FLOW
        if (contains(h, COMPLETED_PHRASES)) return RapidoScreen.TRIP_COMPLETED
        if (contains(h, ON_TRIP_PHRASES) && !contains(h, ASSIGNED_PHRASES.filter { it != "your captain" })) return RapidoScreen.ON_TRIP
        if (contains(h, ARRIVED_PHRASES)) return RapidoScreen.DRIVER_ARRIVED
        if (contains(h, ASSIGNED_PHRASES) && (RapidoText.vehicleNumber(nodes.joinToString(" ") { it.label }) != null || contains(h, listOf("share otp", "call captain", "contact captain", "captain is on the way", "captain on the way", "arriving in")))) {
            return RapidoScreen.DRIVER_ASSIGNED
        }
        if (contains(h, NO_DRIVER_PHRASES)) return RapidoScreen.NO_DRIVERS
        if (contains(h, SEARCHING_PHRASES) && options.size < 2) return RapidoScreen.SEARCHING_DRIVER

        // 3. Problems with the page or the phone.
        if (contains(h, OFFLINE_PHRASES)) return RapidoScreen.OFFLINE
        if (contains(h, LOCATION_OFF_PHRASES) && options.isEmpty()) return RapidoScreen.LOCATION_OFF
        if (contains(h, ERROR_PHRASES) && options.isEmpty()) return RapidoScreen.ERROR

        // 4. Booking screens.
        if (options.size >= 2 || (options.size == 1 && bookButton(nodes) != null)) return RapidoScreen.RIDE_OPTIONS
        if (contains(h, MAP_PICKER_PHRASES) && confirmPlaceButton(nodes) != null) return RapidoScreen.MAP_PICKER
        if (hasEditable && nodes.count { it.clickable && it.label.length >= 4 } >= 2) return RapidoScreen.LOCATION_SEARCH
        if (contains(h, listOf("where to", "where are you going", "search destination", "enter destination", "enter drop", "drop location", "book a ride", "your rides", "saved places"))) {
            // A home screen whose search box is itself an input still counts as home; an input with no home text is a search screen.
            return RapidoScreen.HOME
        }
        if (hasEditable) return RapidoScreen.LOCATION_SEARCH
        if (contains(h, LOADING_PHRASES) || nodes.all { it.label.isBlank() }) return RapidoScreen.LOADING
        return RapidoScreen.UNKNOWN
    }

    fun challengeKind(nodes: List<RapidoNode>): String {
        val h = allText(nodes)
        return when {
            h.contains("captcha") || h.contains("not a robot") || h.contains("puzzle") -> "a CAPTCHA"
            h.contains("upi pin") || h.contains("mpin") || h.contains("enter your pin") -> "your UPI PIN"
            h.contains("cvv") || h.contains("security code") || h.contains("enter card") -> "your card security code"
            contains(h, OTP_ENTRY_PHRASES) -> "an OTP"
            h.contains("payment") -> "payment approval"
            else -> "a security check"
        }
    }

    // ---- Fare cards ---------------------------------------------------------------------------

    fun categoryOf(name: String): VehicleCategory {
        val n = name.lowercase(Locale.ROOT)
        return when {
            Regex("""\bbike|motorcycle|two wheeler\b""").containsMatchIn(n) -> VehicleCategory.BIKE
            Regex("""\bauto|rickshaw\b""").containsMatchIn(n) -> VehicleCategory.AUTO
            Regex("""\bcab|taxi|mini|prime|sedan|suv|xl|economy|premium|hatchback|car\b""").containsMatchIn(n) -> VehicleCategory.CAB
            else -> VehicleCategory.OTHER
        }
    }

    private fun looksLikeCardName(n: RapidoNode): Boolean {
        if (n.editable) return false
        val label = n.label
        if (label.isBlank() || label.length > 120) return false
        val lower = label.lowercase(Locale.ROOT)
        if (lower.startsWith("book ") || lower.startsWith("confirm ") || lower.startsWith("request ")) return false
        if (!VEHICLE_WORDS.containsMatchIn(lower)) return false
        val withoutPrice = lower.replace(Regex("""(?:₹|rs\.?)\s*[0-9][0-9,.]*"""), " ")
        val words = RideText.normalize(withoutPrice).split(' ').filter { it.isNotEmpty() }
        // Either a short name ("Cab Economy"), or one merged card node that also carries a price.
        return words.size <= 4 || RapidoText.hasPrice(label)
    }

    /** The fare cards on a ride-options screen, top to bottom, read only from what is shown. */
    fun fareOptions(nodes: List<RapidoNode>): List<FareOption> {
        val names = nodes.filter { looksLikeCardName(it) }.sortedBy { it.top }
        // Drop name nodes stacked on the same line as a better one (a subtitle repeating "Bike").
        val heads = ArrayList<RapidoNode>()
        for (n in names) {
            val prev = heads.lastOrNull()
            if (prev != null && n.top - prev.top < 40 && !RapidoText.hasPrice(n.label)) continue
            heads.add(n)
        }
        val out = ArrayList<FareOption>()
        for ((i, head) in heads.withIndex()) {
            val bandTop = head.top - 24
            val bandBottom = heads.getOrNull(i + 1)?.let { it.top - 24 } ?: (head.bottom + 420)
            val band = nodes.filter { it.centerY in bandTop until bandBottom && !it.editable }
            val text = band.joinToString(" | ") { it.label }
            val (fare, fareMax) = RapidoText.fare(text)
            val lower = text.lowercase(Locale.ROOT)
            val unavailable = Regex("""\b(?:not available|unavailable|no captains?|sold out|currently unavailable|no .{0,12} nearby)\b""").containsMatchIn(lower)
            // A card with neither a fare nor an "unavailable" note is not a fare card (a stray label); an unavailable one is still listed.
            if (fare == null && !unavailable) continue
            val nameOnly = head.label.replace(Regex("""(?:₹|rs\.?)\s*[0-9][0-9,.]*"""), "").split('|').first().trim().take(40)
            out += FareOption(
                name = nameOnly.ifBlank { head.label.take(40) },
                category = categoryOf(head.label),
                fare = fare,
                fareMax = fareMax,
                etaMinutes = RapidoText.etaMinutes(text),
                seats = RapidoText.seats(text),
                features = RapidoText.features(text),
                extras = RapidoText.extras(text),
                available = !unavailable,
                selected = band.any { (it.selected || it.checked) && (it === head || it.centerY in head.top..head.bottom) } || head.selected || head.checked ||
                    band.any { it.description.contains("selected", ignoreCase = true) && !it.description.contains("not selected", ignoreCase = true) },
                position = out.size
            )
        }
        return out
    }

    /** The clickable node of a fare card (the name node itself, or the card it sits in). */
    fun cardNode(nodes: List<RapidoNode>, option: FareOption): RapidoNode? =
        nodes.filter { looksLikeCardName(it) }
            .filter { RideText.normalize(it.label).startsWith(RideText.normalize(option.name).take(12)) || RideText.containment(it.label, option.name) >= 0.8 }
            .minByOrNull { Math.abs(it.top - (nodes.filter { n -> looksLikeCardName(n) }.sortedBy { n -> n.top }.getOrNull(option.position)?.top ?: it.top)) }

    // ---- Buttons ------------------------------------------------------------------------------

    fun bookButton(nodes: List<RapidoNode>): RapidoNode? =
        nodes.filter { it.clickable && it.label.length in 4..60 && BOOK_BUTTON.containsMatchIn(it.label) }
            .maxByOrNull { it.top }

    fun confirmPlaceButton(nodes: List<RapidoNode>): RapidoNode? =
        nodes.filter { it.clickable && !it.label.contains("on map", ignoreCase = true) && Regex("""^\s*(?:confirm|done|proceed|set)\b.*\b(?:pickup|drop|destination|location|and proceed)\b|^\s*(?:confirm|done)\s*$""", I).containsMatchIn(it.label) }
            .maxByOrNull { it.top }

    fun retryButton(nodes: List<RapidoNode>): RapidoNode? =
        nodes.firstOrNull { it.clickable && Regex("""^\s*(?:try again|retry|refresh|reload)\b""", I).containsMatchIn(it.label) }

    fun dismissButton(nodes: List<RapidoNode>): RapidoNode? =
        nodes.firstOrNull { it.clickable && Regex("""^\s*(?:no thanks|not now|skip|maybe later|later|close|got it|dismiss|remind me later)\s*$""", I).containsMatchIn(it.label) }

    fun cancelRideButton(nodes: List<RapidoNode>): RapidoNode? =
        nodes.filter { it.clickable && Regex("""^\s*cancel\s*(?:ride|booking|trip)?\s*$""", I).containsMatchIn(it.label) }.minByOrNull { it.top }

    /** The final "yes, cancel" button of the cancel dialog. */
    fun confirmCancelButton(nodes: List<RapidoNode>): RapidoNode? =
        nodes.filter { it.clickable && Regex("""^\s*(?:yes,?\s*)?cancel\s*(?:ride|booking|trip)?\s*$|^\s*confirm cancel""", I).containsMatchIn(it.label) }.maxByOrNull { it.top }

    fun cancelReasonOptions(nodes: List<RapidoNode>): List<RapidoNode> =
        nodes.filter { it.clickable && it.label.length in 6..80 && !Regex("""^\s*(?:yes|no|cancel|back|close|dismiss|submit)\b""", I).containsMatchIn(it.label) }

    fun whereToBox(nodes: List<RapidoNode>): RapidoNode? =
        nodes.firstOrNull { Regex("""where (?:to|are you going)|search destination|enter destination|enter drop|drop location|drop point|search drop""", I).containsMatchIn(it.haystack) && (it.clickable || it.editable) }
            ?: nodes.firstOrNull { it.editable }

    fun pickupBox(nodes: List<RapidoNode>): RapidoNode? =
        nodes.firstOrNull { (it.clickable || it.editable) && (it.viewId.contains("pickup", true) || it.viewId.contains("source", true) || it.viewId.contains("origin", true) || Regex("""\b(?:pickup|pick up|current location|from)\b""", I).containsMatchIn(it.haystack)) && it.label.length in 2..120 }

    /** The text field for a place: the pickup field, or the destination field (the last input when the app shows two). */
    fun placeField(nodes: List<RapidoNode>, pickup: Boolean): RapidoNode? {
        val inputs = nodes.filter { it.editable }
        if (inputs.isEmpty()) return null
        val pickupHint = Regex("""pickup|pick up|\bfrom\b|current location|source|origin""", I)
        return if (pickup) inputs.firstOrNull { pickupHint.containsMatchIn(it.haystack) || pickupHint.containsMatchIn(it.viewId) } ?: inputs.first()
        else inputs.firstOrNull { !pickupHint.containsMatchIn(it.haystack) && !pickupHint.containsMatchIn(it.viewId) } ?: inputs.last()
    }

    fun addStopButton(nodes: List<RapidoNode>): RapidoNode? =
        nodes.firstOrNull { it.clickable && Regex("""\badd (?:a )?(?:stop|another stop|destination)\b""", I).containsMatchIn(it.haystack) }

    fun currentLocationChoice(nodes: List<RapidoNode>): RapidoNode? =
        nodes.firstOrNull { it.clickable && Regex("""\b(?:current location|use my location|my location|use current)\b""", I).containsMatchIn(it.haystack) }

    /** Place suggestions under the search field: clickable, text-bearing nodes that are not controls. */
    fun suggestions(nodes: List<RapidoNode>): List<RapidoNode> {
        val field = nodes.firstOrNull { it.editable }
        val skip = Regex("""^\s*(?:back|close|clear|cancel|search|confirm|done|set location on map|choose on map|select on map|add stop|saved places|recent|recents|home|work|current location|use my location)\b""", I)
        return nodes.filter {
            it.clickable && !it.editable && it.label.length in 4..140 && !skip.containsMatchIn(it.label) &&
                !RapidoText.hasPrice(it.label) && (field == null || it.top > field.bottom - 4)
        }.sortedBy { it.top }
    }

    // ---- Ride summary / captain / payment ----------------------------------------------------

    private fun placeNear(nodes: List<RapidoNode>, idHints: List<String>, labelPrefix: Regex): String? {
        nodes.firstOrNull { n -> idHints.any { n.viewId.contains(it, true) } && n.label.isNotBlank() && !labelOnly(n.label) }?.let { return clean(it.label, labelPrefix) }
        nodes.firstOrNull { labelPrefix.containsMatchIn(it.description) && it.description.length > 8 }?.let { return clean(it.description, labelPrefix) }
        val sorted = nodes.sortedBy { it.top }
        for ((i, n) in sorted.withIndex()) {
            if (labelPrefix.matches(n.label.trim())) {
                sorted.getOrNull(i + 1)?.let { next -> if (next.label.isNotBlank() && !labelOnly(next.label) && !RapidoText.hasPrice(next.label)) return next.label.trim() }
            }
        }
        return null
    }

    private fun labelOnly(s: String) = Regex("""^\s*(?:pickup|pick up|drop|destination|from|to)\s*(?:location)?\s*$""", I).matches(s)
    private fun clean(s: String, prefix: Regex) = s.replace(prefix, "").trim(' ', ':', '-', ',').ifBlank { s.trim() }

    private val PICKUP_PREFIX = Regex("""^\s*(?:pickup|pick up|from)\s*(?:location)?\s*[:\-]?\s*""", I)
    private val DROP_PREFIX = Regex("""^\s*(?:drop|destination|to)\s*(?:location)?\s*[:\-]?\s*""", I)

    fun pickupText(nodes: List<RapidoNode>): String? = placeNear(nodes, listOf("pickup", "pick_up", "source", "origin"), PICKUP_PREFIX)
    fun dropText(nodes: List<RapidoNode>): String? = placeNear(nodes, listOf("drop", "dest"), DROP_PREFIX)

    fun paymentText(nodes: List<RapidoNode>): String? =
        nodes.firstOrNull { Regex("""\b(?:cash|upi|rapido wallet|wallet|paytm|phonepe|gpay|google pay|pay via|payment method|pay using|credit card|debit card|pay online)\b""", I).containsMatchIn(it.haystack) && it.label.length in 3..60 && !it.haystack.contains("add money") }
            ?.label?.trim()

    /** Available payment methods listed anywhere on the screen. */
    fun paymentMethods(nodes: List<RapidoNode>): List<String> =
        nodes.filter { Regex("""\b(?:cash|upi|rapido wallet|wallet|paytm|phonepe|gpay|google pay|card)\b""", I).containsMatchIn(it.haystack) && it.label.length in 3..40 }
            .map { it.label.trim() }.distinct().take(6)

    fun readSummary(nodes: List<RapidoNode>, option: FareOption?, fallbackPickup: String?, fallbackDrop: String?, stops: List<String>): RideSummary {
        return RideSummary(
            pickup = pickupText(nodes) ?: fallbackPickup,
            drop = dropText(nodes) ?: fallbackDrop,
            stops = stops,
            optionName = option?.name,
            category = option?.category,
            fare = option?.fare,
            fareMax = option?.fareMax,
            etaMinutes = option?.etaMinutes,
            payment = paymentText(nodes),
            extras = option?.extras.orEmpty()
        )
    }

    /** True when a place label is visible on the screen (loosely matched, since Rapido shortens addresses). */
    fun placeVisible(nodes: List<RapidoNode>, place: String): Boolean =
        place.isNotBlank() && nodes.any { it.label.isNotBlank() && RideText.containment(place, it.label) >= 0.6 }

    /** Captain details as shown. The ride-start OTP is deliberately never read. */
    fun readDriver(nodes: List<RapidoNode>): DriverInfo {
        val texts = nodes.map { it.label }.filter { it.isNotBlank() }
        val joined = texts.joinToString(" | ")
        val number = RapidoText.vehicleNumber(joined)
        val nameNode = nodes.firstOrNull { n ->
            n.viewId.contains("driver_name", true) || n.viewId.contains("captain_name", true) ||
                Regex("""^\s*(?:captain|driver)\s*[:\-]\s*\S+""", I).containsMatchIn(n.label)
        }
        val name = nameNode?.label?.replace(Regex("""^\s*(?:captain|driver)\s*[:\-]\s*""", I), "")?.trim()?.takeIf { it.isNotBlank() }
        val model = texts.firstOrNull { Regex("""\b(?:honda|hero|bajaj|tvs|suzuki|yamaha|royal enfield|activa|splendor|pulsar|shine|jupiter|access|maruti|swift|dzire|wagon|tata|hyundai|toyota|innova|ertiga|etios|baleno|kia|mahindra|piaggio|ape)\b""", I).containsMatchIn(it) && it.length < 50 }
        val eta = texts.firstOrNull { Regex("""\b(?:arriving in|arrives in|reaching in|\d+\s*mins? away)\b""", I).containsMatchIn(it) }
        val rated = texts.firstOrNull { RapidoText.rating(it) != null && it.length < 20 }?.let { RapidoText.rating(it) }
        return DriverInfo(name, number, model?.trim(), rated, eta?.trim())
    }

    fun stageOf(screen: RapidoScreen): RideStage = when (screen) {
        RapidoScreen.SEARCHING_DRIVER -> RideStage.SEARCHING
        RapidoScreen.DRIVER_ASSIGNED -> RideStage.DRIVER_ASSIGNED
        RapidoScreen.DRIVER_ARRIVED -> RideStage.DRIVER_ARRIVED
        RapidoScreen.ON_TRIP -> RideStage.ON_TRIP
        RapidoScreen.TRIP_COMPLETED -> RideStage.COMPLETED
        RapidoScreen.NO_DRIVERS -> RideStage.NO_DRIVERS
        RapidoScreen.RIDE_OPTIONS, RapidoScreen.HOME, RapidoScreen.LOCATION_SEARCH, RapidoScreen.MAP_PICKER -> RideStage.NONE
        else -> RideStage.UNKNOWN
    }

    /** Screens that mean a ride is already live, so a second booking must never start. */
    fun isActiveRide(screen: RapidoScreen): Boolean =
        screen in setOf(RapidoScreen.SEARCHING_DRIVER, RapidoScreen.DRIVER_ASSIGNED, RapidoScreen.DRIVER_ARRIVED, RapidoScreen.ON_TRIP)
}
