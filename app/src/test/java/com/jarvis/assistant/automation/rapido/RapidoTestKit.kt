package com.jarvis.assistant.automation.rapido

/** Builders for fake Rapido screens. Layout is label based, like the real reader. */
object RScreens {
    private var y = 0
    private fun next(step: Int = 120): Int { y += step; return y }
    fun reset() { y = 0 }

    fun node(text: String, clickable: Boolean = false, editable: Boolean = false, top: Int = next(), desc: String = "",
             viewId: String = "", selected: Boolean = false) =
        RapidoNode(text = text, description = desc, viewId = viewId, clickable = clickable, editable = editable, selected = selected,
            left = 0, top = top, right = 1000, bottom = top + 100)

    fun home(): List<RapidoNode> { reset(); return listOf(node("Where to?", clickable = true), node("Saved places", clickable = true), node("Book a ride")) }

    fun search(suggestions: List<String> = emptyList(), typed: String = ""): List<RapidoNode> {
        reset()
        val out = ArrayList<RapidoNode>()
        out += node(typed.ifBlank { "Search drop location" }, clickable = true, editable = true)
        out += node("Set location on map", clickable = true)
        suggestions.forEach { out += node(it, clickable = true) }
        return out
    }

    fun mapPin(address: String): List<RapidoNode> { reset(); return listOf(node("Move the map to set your drop"), node(address), node("Confirm drop", clickable = true)) }

    /** Fare cards: (name, price text, eta text). The Book button names the selected card. */
    fun options(pickup: String, drop: String, cards: List<Triple<String, String, String>>, selectedName: String? = null, payment: String? = "Cash",
                bookLabel: String? = null, extraByCard: Map<String, String> = emptyMap(), unavailable: Set<String> = emptySet()): List<RapidoNode> {
        reset()
        val out = ArrayList<RapidoNode>()
        out += node("Pickup")
        out += node(pickup)
        out += node("Drop")
        out += node(drop)
        for ((name, price, eta) in cards) {
            val top = next(160)
            out += node(name, clickable = true, top = top, selected = name == selectedName)
            if (name in unavailable) out += node("Currently unavailable", top = top + 30)
            else {
                out += node(price, top = top + 20)
                out += node(eta, top = top + 40)
                extraByCard[name]?.let { out += node(it, top = top + 60) }
            }
        }
        payment?.let { out += node(it, clickable = true) }
        val label = bookLabel ?: (selectedName?.let { "Book $it" } ?: "Book Ride")
        out += node(label, clickable = true, top = next(200))
        return out
    }

    fun searching(): List<RapidoNode> { reset(); return listOf(node("Finding your captain"), node("Please wait while we connect you"), node("Cancel", clickable = true)) }
    fun assigned(): List<RapidoNode> { reset(); return listOf(node("Captain is on the way"), node("Captain: Ravi Kumar"), node("KA 01 AB 1234"), node("Honda Activa"), node("4.8"), node("Arriving in 4 mins"), node("Share OTP 4821 with captain"), node("Call captain", clickable = true), node("Cancel ride", clickable = true)) }
    fun arrived(): List<RapidoNode> { reset(); return listOf(node("Captain has arrived"), node("KA 01 AB 1234"), node("Share OTP 4821 with captain"), node("Cancel ride", clickable = true)) }
    fun onTrip(): List<RapidoNode> { reset(); return listOf(node("Trip in progress"), node("Share ride status", clickable = true)) }
    fun completed(): List<RapidoNode> { reset(); return listOf(node("Ride completed"), node("Rate your captain")) }
    fun noDrivers(): List<RapidoNode> { reset(); return listOf(node("No captains available nearby"), node("Try again", clickable = true)) }
    fun cancelDialog(): List<RapidoNode> { reset(); return listOf(node("Why do you want to cancel?"), node("Changed my mind", clickable = true), node("Captain is late", clickable = true), node("Cancel ride", clickable = true)) }
    fun loading(): List<RapidoNode> { reset(); return listOf(node("")) }
    fun offline(): List<RapidoNode> { reset(); return listOf(node("No internet connection"), node("Try again", clickable = true), node("Rapido")) }
    fun login(): List<RapidoNode> { reset(); return listOf(node("Log in to continue"), node("Enter mobile number", editable = true), node("Continue", clickable = true)) }
    fun otpEntry(): List<RapidoNode> { reset(); return listOf(node("Enter OTP"), node("OTP sent to your number"), node("OTP", editable = true), node("Verify", clickable = true)) }
    fun locationOff(): List<RapidoNode> { reset(); return listOf(node("Turn on location to find rides"), node("Enable location", clickable = true)) }
    fun upiPin(): List<RapidoNode> { reset(); return listOf(node("Enter UPI PIN"), node("PIN", editable = true)) }

    val defaultCards = listOf(
        Triple("Bike", "₹89", "3 min"),
        Triple("Auto", "₹140", "5 min"),
        Triple("Cab Economy", "₹210", "7 min"),
        Triple("Cab Premium", "₹320", "9 min")
    )
}

/**
 * A scripted fake Rapido app. It moves between screens when taps and typing happen, and keeps a virtual clock so
 * no test waits in real time. Behaviour switches (works/fails) let tests inject failures.
 */
class FakeRapido : RapidoDriver {
    var screen: List<RapidoNode> = RScreens.home()
    var clock = 0L
    val log = ArrayList<String>()
    var installed = true
    var inFront = true
    var cancelFlag = false
    var typingWorks = true
    var unreadable = false
    var location = LocationState(LocationAccess.PRECISE, 20, "Indiranagar")

    /** Suggestions Rapido returns for typed text. */
    val places = HashMap<String, List<String>>()
    var pickupText = "Current location"
    var dropText = ""
    var cards = RScreens.defaultCards
    var selected: String? = null
    var payment = "Cash"
    var unavailable = emptySet<String>()
    var extras = emptyMap<String, String>()
    /** What a press on Book leads to. */
    var afterBook: List<List<RapidoNode>> = listOf(RScreens.searching(), RScreens.assigned())
    var bookDispatches = true
    var bookClicks = 0
    var cancelClicks = 0
    var needsPinConfirm = false
    /** Address shown under the map pin; null means the pin sits on the chosen place. */
    var pinAddress: String? = null
    /** When true, tapping a fare card does not select it. */
    var selectionBroken = false
    var farePlus = 0
    var onCancelConfirm: List<RapidoNode> = RScreens.home()
    var snapshotsAfterBook = 0
    private var bookedAt = -1
    private var state = "home"
    private var typedText = ""
    private var pending: Triple<String, String, String>? = null

    /** Test helper: lets virtual time pass, as when the user takes a moment to answer. */
    fun letTimePass(ms: Long) { clock += ms }

    private fun optionsScreen() = RScreens.options(pickupText, dropText,
        cards.map { Triple(it.first, bump(it.second), it.third) }, selected, payment, extraByCard = extras, unavailable = unavailable)

    private fun bump(price: String): String {
        if (farePlus == 0) return price
        val n = price.filter { it.isDigit() }.toInt() + farePlus
        return "₹$n"
    }

    override fun isRapidoInstalled() = installed
    override fun foregroundPackage(): String? = if (inFront) "com.rapido.passenger" else "com.other"
    override suspend fun locationState() = location

    override suspend fun snapshot(): List<RapidoNode>? {
        if (unreadable) return null
        if (state == "booking") {
            snapshotsAfterBook++
            val idx = minOf(afterBook.size - 1, (snapshotsAfterBook - 1) / 2)
            screen = afterBook[idx]
        }
        return screen
    }

    override suspend fun launchApp(): Boolean { log += "launch"; if (!installed) return false; inFront = true; return true }

    override suspend fun click(node: RapidoNode): Boolean {
        log += "click:${node.label}"
        val label = node.label
        when {
            state == "home" && label.startsWith("Where to") -> { screen = RScreens.search(); state = "search" }
            state == "options" && cards.any { it.first == label } -> { if (!selectionBroken) { selected = label; screen = optionsScreen() } }
            state == "options" && label.startsWith("Book") -> {
                bookClicks++
                if (bookDispatches) { state = "booking"; snapshotsAfterBook = 0 }
            }
            state == "options" && label == payment -> Unit
            (state == "assigned" || state == "booking") && label == "Cancel ride" -> { cancelClicks++; screen = dialogNodes ?: RScreens.cancelDialog(); state = "cancel" }
            state == "cancel" && label == "Changed my mind" -> Unit
            state == "cancel" && label == "Captain is late" -> Unit
            state == "cancel" && label == "Cancel ride" -> { screen = onCancelConfirm; state = "home" }
            state == "search" && label == "Confirm drop" -> Unit
            label == "Confirm drop" -> { screen = optionsScreen(); state = "options" }
            label == "Try again" -> { screen = RScreens.home(); state = "home" }
            state == "search" -> {
                val first = label.lineSequence().first()
                if (typedText.isNotBlank() && places[typedText]?.any { it == label } == true) {
                    dropText = first
                    if (needsPinConfirm) { screen = RScreens.mapPin(pinAddress ?: first); state = "pin" }
                    else { screen = optionsScreen(); state = "options" }
                }
            }
        }
        return true
    }

    override suspend fun typeInto(node: RapidoNode, text: String): Boolean {
        log += "type:$text"
        if (!typingWorks) return false
        typedText = text
        screen = RScreens.search(places[text].orEmpty(), text)
        return true
    }

    override suspend fun scroll(down: Boolean): Boolean { log += if (down) "scroll:down" else "scroll:up"; return true }
    override suspend fun back(): Boolean { log += "back"; screen = RScreens.home(); state = "home"; return true }
    override suspend fun pause(ms: Long) { clock += ms }
    override fun now(): Long = clock
    override fun cancelled(): Boolean = cancelFlag
    override fun progress(message: String) { log += "progress:$message" }

    /** Jump straight to the live-ride screens a test needs. */
    /** Replaces the cancel dialog the next press of Cancel will show. */
    var dialogNodes: List<RapidoNode>? = null
    fun cancelDialogOverride(nodes: List<RapidoNode>) { dialogNodes = nodes }
    fun refreshOptions() { screen = optionsScreen(); state = "options" }
    fun showAssigned() { screen = RScreens.assigned(); state = "assigned" }
    fun showSearching() { screen = RScreens.searching(); state = "assigned" }
    fun showOptions(drop: String = "Koramangala 5th Block") { dropText = drop; screen = optionsScreen(); state = "options" }
    fun show(nodes: List<RapidoNode>, st: String) { screen = nodes; state = st }
}
