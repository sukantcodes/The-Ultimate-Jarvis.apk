package com.jarvis.assistant.automation.rapido

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RapidoAgentFlowTest {
    private val phone = FakeRapido().apply { places["Koramangala"] = listOf("Koramangala 5th Block, Bengaluru") }
    private val gate = RideGate({ phone.now() }, InMemoryRideLedger())
    private val session = RapidoSession()
    private val agent = RapidoAgent(phone, gate, session)

    private fun req(text: String) = RapidoQueryParser.parse(text)
    private fun find(text: String = "bike to Koramangala", choice: Int? = null, refresh: Boolean = false) =
        runBlocking { agent.findRides(req(text), choice, refresh) }
    private fun prepare(option: String? = null, payment: String? = null, said: String = "bike to koramangala") =
        runBlocking { agent.prepareBooking(option, null, payment, said) }
    private fun book(said: String) = runBlocking { agent.book(said) }
    private fun status() = runBlocking { agent.status() }
    private fun cancel(said: String, reason: String? = null) = runBlocking { agent.cancelRide(said, reason) }
    private fun clicked(label: String) = phone.log.any { it == "click:$label" }
    private fun typed() = phone.log.filter { it.startsWith("type:") }

    // ---- location -------------------------------------------------------------------------------

    @Test fun locationStatesAreReportedHonestlyAndNeverExposeCoordinates() {
        phone.location = LocationState(LocationAccess.PRECISE, 20, "Indiranagar")
        val ok = runBlocking { agent.locationStatus() }
        assertTrue(ok.success)
        assertEquals("PRECISE", ok.extra["access"])
        assertFalse(ok.extra.keys.any { it.contains("lat") || it.contains("lon") })
        for (a in listOf(LocationAccess.PERMISSION_DENIED, LocationAccess.SERVICES_OFF, LocationAccess.UNAVAILABLE)) {
            phone.location = LocationState(a)
            val r = runBlocking { agent.locationStatus() }
            assertFalse(a.name, r.success)
            assertEquals(RapidoStatus.NEEDS_LOCATION, r.status)
        }
        phone.location = LocationState(LocationAccess.APPROXIMATE, 900)
        assertTrue(runBlocking { agent.locationStatus() }.success)
    }

    // ---- finding and comparing rides ------------------------------------------------------------------------

    @Test fun findsRidesComparesThemAndRecommends() {
        val r = find("cheapest bike to Koramangala")
        assertTrue(r.message, r.success)
        assertEquals(RapidoStatus.OK, r.status)
        assertTrue(r.verified)
        assertTrue(r.message.contains("Bike: ₹89"))
        assertTrue(r.message.contains("Cab Premium: ₹320"))
        assertTrue(r.message.contains("Recommended: Bike"))
        assertEquals("Koramangala 5th Block, Bengaluru", session.dropLabel)
        assertEquals(0, phone.bookClicks)
    }

    @Test fun budgetAndVehicleChangeTheRecommendationButNeverInventAnything() {
        val r = find("cab to Koramangala under 250")
        assertEquals("Cab Economy", r.extra["recommended"])
        val over = find("cab to Koramangala under 100")
        assertTrue(over.message.contains("Nothing is within"))
        assertEquals(true, over.extra["recommended_over_budget"])
    }

    @Test fun unavailableOptionIsShownButNotRecommended() {
        phone.unavailable = setOf("Bike")
        val r = find("cheapest ride to Koramangala")
        assertTrue(r.message.contains("NOT available"))
        assertEquals("Auto", r.extra["recommended"])
    }

    @Test fun visibleExtrasAreReportedExactlyAsShown() {
        phone.extras = mapOf("Cab Economy" to "Toll charges extra")
        val r = find("cab to Koramangala")
        assertTrue(r.message.contains("Toll charges extra"))
    }

    @Test fun missingDestinationAsksInsteadOfGuessing() {
        val r = find("book a ride")
        assertFalse(r.success)
        assertTrue(r.message.contains("Where do you want to go"))
        assertTrue(phone.log.none { it.startsWith("click") || it.startsWith("type") })
    }

    @Test fun ambiguousPlaceAsksThenResumesFromTheChoice() {
        phone.places["Koramangala"] = listOf("Koramangala 5th Block, Bengaluru", "Koramangala Water Tank, Bengaluru")
        val a = find("bike to Koramangala")
        assertEquals(RapidoStatus.AMBIGUOUS, a.status)
        assertTrue(a.message.contains("1. Koramangala 5th Block"))
        assertTrue(a.message.contains("2. Koramangala Water Tank"))
        assertEquals(2, (a.extra["choices"] as List<*>).size)
        val typedBefore = typed().size
        val b = find("bike to Koramangala", choice = 2)
        assertTrue(b.message, b.success)
        assertEquals("Koramangala Water Tank, Bengaluru", session.dropLabel)
        assertEquals("the list was still on screen, so nothing is typed twice", typedBefore, typed().size)
    }

    @Test fun badChoiceNumberIsRefusedWithoutTapping() {
        phone.places["Koramangala"] = listOf("Koramangala 5th Block, Bengaluru", "Koramangala Water Tank, Bengaluru")
        find("bike to Koramangala")
        val r = find("bike to Koramangala", choice = 9)
        assertEquals(RapidoStatus.AMBIGUOUS, r.status)
        assertNull(session.dropLabel)
    }

    @Test fun noMatchingSuggestionIsReportedNotGuessed() {
        phone.places["Koramangala"] = listOf("Whitefield Main Road, Bengaluru", "Hebbal Flyover, Bengaluru")
        val r = find("bike to Koramangala")
        assertEquals(RapidoStatus.AMBIGUOUS, r.status)
        assertTrue(r.message.contains("None of Rapido's suggestions"))
        assertNull(session.dropLabel)
    }

    @Test fun mapPinIsConfirmedOnlyWhenItSitsOnTheChosenPlace() {
        phone.needsPinConfirm = true
        val ok = find("bike to Koramangala")
        assertTrue(ok.message, ok.success)
        assertTrue(clicked("Confirm drop"))

        val phone2 = FakeRapido().apply { places["Koramangala"] = listOf("Koramangala 5th Block, Bengaluru"); needsPinConfirm = true; pinAddress = "Some Other Lane" }
        val agent2 = RapidoAgent(phone2, RideGate({ phone2.now() }, InMemoryRideLedger()), RapidoSession())
        val r = runBlocking { agent2.findRides(req("bike to Koramangala"), null, false) }
        assertEquals(RapidoStatus.NEEDS_USER, r.status)
        assertTrue(r.message.contains("Move the pin"))
        assertFalse(phone2.log.contains("click:Confirm drop"))
    }

    @Test fun refreshReportsWhatChanged() {
        find("bike to Koramangala")
        phone.farePlus = 10
        phone.refreshOptions()
        val r = find("bike to Koramangala", refresh = true)
        assertTrue(r.message, r.message.contains("Bike changed from ₹89 to ₹99"))
        assertEquals("the same trip is only re-read, not re-entered", 1, typed().size)
        val again = find("bike to Koramangala", refresh = true)
        assertTrue(again.message.contains("No fare changes"))
    }

    @Test fun stopsAreNeverSilentlyDropped() {
        val r = find("bike to Koramangala via Lalbagh")
        assertEquals(RapidoStatus.UNSUPPORTED, r.status)
        assertTrue(r.message.contains("Add stop"))
    }

    @Test fun scheduledRequestsAreExplainedAndNeverBookedAsImmediate() {
        val f = find("kal subah 7 baje auto to Koramangala")
        assertTrue(f.message.contains("Scheduling a later ride is not automated"))
        val p = prepare()
        assertEquals(RapidoStatus.UNSUPPORTED, p.status)
        assertNull(gate.pending)
        assertEquals(0, phone.bookClicks)
    }

    // ---- failures and recovery ---------------------------------------------------------------------------------

    @Test fun typingFailureIsReportedAndNothingIsChanged() {
        phone.typingWorks = false
        val r = find()
        assertFalse(r.success)
        assertTrue(r.message.contains("could not type"))
        assertEquals(0, phone.bookClicks)
    }

    @Test fun offlinePageIsRetriedAndWorkContinues() {
        phone.show(RScreens.offline(), "offline")
        val r = find()
        assertTrue(r.message, r.success)
        assertTrue(clicked("Try again"))
    }

    @Test fun loginAndSecurityScreensStopTheAgentWithoutTyping() {
        phone.show(RScreens.login(), "login")
        val a = find()
        assertEquals(RapidoStatus.NEEDS_SIGN_IN, a.status)
        phone.show(RScreens.upiPin(), "pin")
        val b = find()
        assertEquals(RapidoStatus.NEEDS_USER, b.status)
        assertTrue(typed().isEmpty())
    }

    @Test fun locationOffScreenGivesAnActionableMessage() {
        phone.show(RScreens.locationOff(), "loc")
        val r = find()
        assertEquals(RapidoStatus.NEEDS_LOCATION, r.status)
        assertTrue(r.message.contains("pickup address"))
    }

    @Test fun notInstalledAndUnreadableAreHonest() {
        phone.installed = false
        phone.inFront = false
        assertTrue(find().message.contains("not installed"))
        val p2 = FakeRapido().apply { unreadable = true }
        val a2 = RapidoAgent(p2, RideGate({ p2.now() }, InMemoryRideLedger()), RapidoSession())
        val r = runBlocking { a2.findRides(req("bike to Koramangala"), null, false) }
        assertEquals(RapidoStatus.SCREEN_UNREADABLE, r.status)
    }

    @Test fun userCancellationStopsTheWork() {
        phone.cancelFlag = true
        val r = find()
        assertEquals(RapidoStatus.CANCELLED, r.status)
        assertEquals(0, phone.bookClicks)
    }

    @Test fun anAlreadyLiveRideIsNeverDisturbed() {
        phone.showAssigned()
        val r = find()
        assertEquals(RapidoStatus.ACTIVE_RIDE, r.status)
        assertTrue(phone.log.none { it.startsWith("click") || it.startsWith("type") || it == "back" })
    }

    // ---- preparing and confirming a booking -------------------------------------------------------------------------

    @Test fun preparingReadsBackEverythingAndNeverBooks() {
        find("cheapest bike to Koramangala")
        val p = prepare(said = "cheapest bike to koramangala")
        assertEquals(p.message, RapidoStatus.PREPARED, p.status)
        assertTrue(p.verified)
        assertTrue(p.message.contains("Bike"))
        assertTrue(p.message.contains("₹89"))
        assertTrue(p.message.contains("Koramangala 5th Block"))
        assertTrue(p.message.contains("Cash"))
        assertNotNull(gate.pending)
        assertEquals(0, phone.bookClicks)
        assertFalse(clicked("Book Bike"))
    }

    @Test fun cannotPrepareBeforeAnyTripIsSetUp() {
        val r = prepare()
        assertEquals(RapidoStatus.NOT_FOUND, r.status)
        assertNull(gate.pending)
    }

    @Test fun selectionThatCannotBeProvenBlocksPreparation() {
        find()
        phone.selectionBroken = true
        val r = prepare()
        assertEquals(RapidoStatus.NOT_VERIFIED, r.status)
        assertNull(gate.pending)
    }

    @Test fun routeThatDoesNotMatchTheScreenBlocksPreparation() {
        find()
        phone.dropText = "Whitefield"
        phone.refreshOptions()
        val r = prepare()
        assertEquals(RapidoStatus.NOT_VERIFIED, r.status)
        assertTrue(r.message.contains("destination"))
        assertNull(gate.pending)
    }

    @Test fun unavailableOrUnknownRideTypesAreRefused() {
        phone.unavailable = setOf("Auto")
        find("auto to Koramangala")
        assertEquals(RapidoStatus.UNAVAILABLE, prepare("Auto").status)
        assertEquals(RapidoStatus.NOT_FOUND, prepare("Helicopter").status)
    }

    @Test fun paymentIsOnlyChangedThroughRapidoAndProvenOnScreen() {
        find()
        val r = prepare(payment = "UPI")
        assertEquals(RapidoStatus.NOT_VERIFIED, r.status)
        assertNull(gate.pending)
        val same = prepare(payment = "Cash")
        assertEquals(RapidoStatus.PREPARED, same.status)
    }

    @Test fun pickupNoteIsNeverClaimedAsSent() {
        find("auto to Koramangala note: wait at the blue gate")
        val p = prepare("Auto")
        assertTrue(p.message.contains("NOT sent"))
        assertEquals(false, p.extra["note_applied"])
    }

    @Test fun bookingWithoutPreparationIsRefused() {
        find()
        phone.letTimePass(10_000)
        val r = book("yes book it")
        assertEquals(RapidoStatus.REFUSED, r.status)
        assertEquals(0, phone.bookClicks)
    }

    @Test fun oneSentenceCanNeverPrepareAndConfirm() {
        find()
        prepare(said = "book a bike to koramangala and confirm the ride")
        phone.letTimePass(10_000)
        val r = book("book a bike to koramangala and confirm the ride")
        assertEquals("SAME_TURN", r.extra["code"])
        assertEquals(0, phone.bookClicks)
    }

    @Test fun vagueOrNegativeAnswersNeverBook() {
        find()
        prepare()
        phone.letTimePass(10_000)
        for (say in listOf("hmm", "ok", "what's the fare", "don't book it", "ruko")) {
            assertEquals(say, "NOT_CONFIRMED", book(say).extra["code"])
        }
        assertEquals(0, phone.bookClicks)
    }

    @Test fun confirmedBookingIsVerifiedBeforeItIsClaimed() {
        find()
        prepare()
        phone.letTimePass(10_000)
        val r = book("yes book it")
        assertEquals(r.message, RapidoStatus.BOOKED, r.status)
        assertTrue(r.verified)
        assertEquals(1, phone.bookClicks)
        assertEquals("Ravi Kumar", r.extra["captain_name"])
        assertEquals("KA 01 AB 1234", r.extra["vehicle_number"])
        assertFalse(r.message.contains("4821"))
        assertFalse(r.extra.values.any { it.toString().contains("4821") })
    }

    @Test fun stillSearchingIsReportedAsRequestedNotBooked() {
        phone.afterBook = listOf(RScreens.searching())
        find()
        prepare()
        phone.letTimePass(10_000)
        val r = book("yes book it")
        assertEquals(RapidoStatus.REQUESTED, r.status)
        assertTrue(r.message.contains("not confirmed until a captain is assigned"))
        assertEquals(1, phone.bookClicks)
    }

    @Test fun unprovenResultIsNeverClaimedAndNeverRepeated() {
        phone.afterBook = listOf(RScreens.loading())
        find()
        prepare()
        phone.letTimePass(10_000)
        val r = book("yes book it")
        assertEquals(RapidoStatus.NOT_VERIFIED, r.status)
        assertFalse(r.success)
        assertTrue(r.message.contains("will not press Book a second time"))
        // The user hears that, asks again, the screen is back on the fares: the ledger still stops a second booking.
        phone.refreshOptions()
        prepare(said = "try the bike again")
        phone.letTimePass(10_000)
        val again = book("yes book it")
        assertEquals("DUPLICATE", again.extra["code"])
        assertEquals(1, phone.bookClicks)
    }

    @Test fun bookingIsRefusedWhileAnotherRideIsLive() {
        find()
        prepare()
        phone.letTimePass(10_000)
        assertEquals(RapidoStatus.BOOKED, book("yes book it").status)
        val r = book("yes book it")
        assertEquals(RapidoStatus.ACTIVE_RIDE, r.status)
        assertEquals(1, phone.bookClicks)
    }

    @Test fun aFareChangeAfterThePreparationNeedsANewConfirmation() {
        find()
        prepare()
        phone.farePlus = 15
        phone.refreshOptions()
        phone.letTimePass(10_000)
        val r = book("yes book it")
        assertEquals("CHANGED", r.extra["code"])
        assertTrue(r.message.contains("₹104"))
        assertEquals(0, phone.bookClicks)
        phone.letTimePass(10_000)
        val ok = book("confirm the ride")
        assertEquals(RapidoStatus.BOOKED, ok.status)
        assertEquals(1, phone.bookClicks)
    }

    @Test fun aStaleFareIsRefused() {
        find()
        prepare()
        phone.letTimePass(5 * 60_000)
        val r = book("yes book it")
        assertEquals("EXPIRED", r.extra["code"])
        assertEquals(0, phone.bookClicks)
    }

    @Test fun noCaptainMeansNothingIsBookedAndTheRideMayBeRetriedLater() {
        phone.afterBook = listOf(RScreens.searching(), RScreens.noDrivers())
        find()
        prepare()
        phone.letTimePass(10_000)
        val r = book("yes book it")
        assertEquals(RapidoStatus.NO_DRIVERS, r.status)
        assertFalse(r.success)
        assertNull(gate.activeFingerprint())
    }

    @Test fun challengeDuringBookingIsLeftToTheUser() {
        phone.afterBook = listOf(RScreens.upiPin())
        find()
        prepare()
        phone.letTimePass(10_000)
        val r = book("yes book it")
        assertEquals(RapidoStatus.NEEDS_USER, r.status)
        assertTrue(r.message.contains("NOT confirmed"))
        assertTrue(typed().size == 1)
    }

    @Test fun bookingFromTheWrongScreenIsRefused() {
        find()
        prepare()
        phone.show(RScreens.home(), "home")
        phone.letTimePass(10_000)
        assertEquals(RapidoStatus.WRONG_SCREEN, book("yes book it").status)
        assertEquals(0, phone.bookClicks)
    }

    @Test fun undispatchedTapIsReleasedAndRetriedNever() {
        phone.bookDispatches = false
        find()
        prepare()
        phone.letTimePass(10_000)
        val r = book("yes book it")
        assertFalse(r.success)
        assertEquals(RapidoStatus.NOT_VERIFIED, r.status)
    }

    // ---- status and cancelling --------------------------------------------------------------------------------------

    @Test fun statusReportsEachStageFromTheScreen() {
        phone.showAssigned()
        val a = status()
        assertEquals("DRIVER_ASSIGNED", a.extra["stage"])
        assertTrue(a.message.contains("Ravi Kumar"))
        phone.show(RScreens.arrived(), "assigned")
        assertEquals("DRIVER_ARRIVED", status().extra["stage"])
        phone.show(RScreens.onTrip(), "assigned")
        assertEquals("ON_TRIP", status().extra["stage"])
        phone.show(RScreens.searching(), "assigned")
        assertEquals("SEARCHING", status().extra["stage"])
        phone.show(RScreens.home(), "home")
        assertEquals("NONE", status().extra["stage"])
    }

    @Test fun aFinishedRideFreesTheLedgerForTheNextBooking() {
        find()
        prepare()
        phone.letTimePass(10_000)
        book("yes book it")
        assertNotNull(gate.activeFingerprint())
        phone.show(RScreens.completed(), "done")
        assertEquals("COMPLETED", status().extra["stage"])
        assertNull(gate.activeFingerprint())
    }

    @Test fun cancellingNeedsAnExplainedStateThenANewExplicitMessage() {
        phone.showAssigned()
        val first = cancel("cancel my ride")
        assertEquals(RapidoStatus.CANCEL_PREPARED, first.status)
        assertEquals(0, phone.cancelClicks)
        phone.letTimePass(500)
        val sameTurn = cancel("cancel my ride")
        assertEquals("SAME_TURN", sameTurn.extra["code"])
        assertEquals(0, phone.cancelClicks)
        phone.letTimePass(10_000)
        val vague = cancel("hmm what is the fee")
        assertEquals(RapidoStatus.CANCEL_PREPARED, vague.status)
        assertEquals(0, phone.cancelClicks)
        phone.letTimePass(10_000)
        val done = cancel("yes cancel it")
        assertEquals(done.message, RapidoStatus.RIDE_CANCELLED, done.status)
        assertTrue(done.verified)
        assertEquals(1, phone.cancelClicks)
        assertTrue(clicked("Changed my mind"))
    }

    @Test fun cancellationRefusalNeverCancels() {
        phone.showAssigned()
        cancel("cancel my ride")
        phone.letTimePass(10_000)
        val r = cancel("don't cancel it")
        assertEquals(RapidoStatus.CANCEL_PREPARED, r.status)
        assertEquals(0, phone.cancelClicks)
    }

    @Test fun cancelAfterTheTripStartedOrWithNoRideIsRefused() {
        phone.show(RScreens.onTrip(), "assigned")
        assertEquals(RapidoStatus.WRONG_SCREEN, cancel("cancel my ride").status)
        phone.show(RScreens.home(), "home")
        assertEquals(RapidoStatus.WRONG_SCREEN, cancel("cancel my ride").status)
        assertEquals(0, phone.cancelClicks)
    }

    @Test fun cancelReasonIsAskedWhenNoneIsNeutral() {
        phone.showAssigned()
        cancel("cancel my ride")
        phone.letTimePass(10_000)
        phone.show(RScreens.assigned(), "assigned")
        val nodes = listOf(RScreens.node("Why do you want to cancel?"), RScreens.node("Captain is late", clickable = true), RScreens.node("Wrong address entered", clickable = true), RScreens.node("Cancel ride", clickable = true))
        phone.onCancelConfirm = RScreens.home()
        // dialog with no neutral reason: the user must choose
        val dialogPhone = FakeRapido().apply { show(RScreens.assigned(), "assigned") }
        val g = RideGate({ dialogPhone.now() }, InMemoryRideLedger())
        val a = RapidoAgent(dialogPhone, g, RapidoSession())
        runBlocking { a.cancelRide("cancel my ride", null) }
        dialogPhone.letTimePass(10_000)
        dialogPhone.cancelDialogOverride(nodes)
        val r = runBlocking { a.cancelRide("yes cancel it", null) }
        assertEquals(RapidoStatus.NEEDS_USER, r.status)
        assertTrue(r.message.contains("Wrong address entered"))
        val picked = runBlocking { a.cancelRide("yes cancel it", "Wrong address entered") }
        assertTrue(picked.message, picked.status == RapidoStatus.RIDE_CANCELLED || picked.status == RapidoStatus.NOT_VERIFIED)
    }
}
