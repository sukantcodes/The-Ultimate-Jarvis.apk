package com.jarvis.assistant.automation.rapido

import java.util.Locale

/** What kind of consequential action a confirmation is for. */
enum class RideAction { BOOK, CANCEL }

/** Recognises an explicit, positive instruction in what the user just said. English and Hinglish. */
object RideConfirmation {
    private val NEGATIVE = Regex(
        """\b(don't|dont|do not|not yet|not now|no|nahi|nahin|nhi|mat|stop|wait|ruko|ruk|rukna|rehne do|abhi nahi|hold on|never|rehne de)\b"""
    )
    private val BOOK_POSITIVE = listOf(
        Regex("""\bconfirm(ed)?\b.*\b(ride|booking|book|trip|rapido)\b"""),
        Regex("""\b(ride|booking|book|trip)\b.*\bconfirm(ed)?\b"""),
        Regex("""\bbook\b.*\b(it|this|the ride|ride|now|kar do|kardo|karo|kar de)\b"""),
        Regex("""\b(ride|booking|cab|auto|bike)\b.*\b(book kar do|book kardo|book karo|book kar de|kar do|kardo|laga do|laga de)\b"""),
        Regex("""\b(go ahead|haan|han|yes|yeah|yep|ok|okay|sure)\b.*\b(book|ride|booking|confirm)\b"""),
        Regex("""\bconfirm kar\b"""),
        Regex("""\bbook it\b""")
    )
    private val CANCEL_POSITIVE = listOf(
        Regex("""\bcancel\b.*\b(it|ride|trip|booking|karo|kar do|kardo|kar de)\b"""),
        Regex("""\b(yes|haan|han|yeah|yep|ok|okay|sure|confirm)\b.*\bcancel\b"""),
        Regex("""\bcancel kar\b"""),
        Regex("""\bride cancel\b|\bbooking cancel\b""")
    )
    // "cancel" is a normal word in the request itself; a refusal of the cancellation is what must be rejected.
    private val CANCEL_NEGATIVE = Regex(
        """\b(don't|dont|do not|not yet|not now|nahi|nahin|nhi|mat|wait|ruko|rukna|rehne do|rehne de|abhi nahi|hold on|never|keep|don't cancel|dont cancel)\b"""
    )

    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").replace(Regex("\\s+"), " ").trim()

    fun isExplicit(action: RideAction, text: String): Boolean {
        val t = normalize(text)
        if (t.length < 5) return false
        return when (action) {
            RideAction.BOOK -> !NEGATIVE.containsMatchIn(t) && BOOK_POSITIVE.any { it.containsMatchIn(t) }
            RideAction.CANCEL -> !CANCEL_NEGATIVE.containsMatchIn(t) && CANCEL_POSITIVE.any { it.containsMatchIn(t) }
        }
    }
}

enum class BookingState { PREPARED, ATTEMPTED, BOOKED }

data class RideLedgerEntry(val fingerprint: String, val state: BookingState, val at: Long)

/** Remembers which rides were already submitted, so a ride is never booked twice. */
interface RideLedger {
    fun get(fingerprint: String): RideLedgerEntry?
    fun put(entry: RideLedgerEntry)
    fun remove(fingerprint: String)
    fun anyActive(since: Long): RideLedgerEntry?
    fun clear()
}

class InMemoryRideLedger : RideLedger {
    private val map = HashMap<String, RideLedgerEntry>()
    @Synchronized override fun get(fingerprint: String): RideLedgerEntry? = map[fingerprint]
    @Synchronized override fun put(entry: RideLedgerEntry) { map[entry.fingerprint] = entry }
    @Synchronized override fun remove(fingerprint: String) { map.remove(fingerprint) }
    @Synchronized override fun anyActive(since: Long): RideLedgerEntry? =
        map.values.filter { (it.state == BookingState.ATTEMPTED || it.state == BookingState.BOOKED) && it.at >= since }.maxByOrNull { it.at }
    @Synchronized override fun clear() { map.clear() }
}

data class PendingRide(
    val fingerprint: String,
    val summary: RideSummary,
    val preparedAt: Long,
    /** What the user said in the turn that prepared the booking. */
    val prepareUtterance: String
)

data class PendingCancel(val preparedAt: Long, val prepareUtterance: String, val stage: RideStage)

sealed class RideDecision {
    data class Allowed(val ride: PendingRide) : RideDecision()
    data class Denied(val code: String, val reason: String) : RideDecision()
}

sealed class CancelDecision {
    data class Allowed(val pending: PendingCancel) : CancelDecision()
    data class Denied(val code: String, val reason: String) : CancelDecision()
}

/**
 * The only path to booking or cancelling a ride. A booking is allowed only when ALL of these hold:
 *  - the ride was prepared and read back first (summary read, fingerprint stored);
 *  - the preparation is recent (fares go stale);
 *  - the user's latest words are an explicit confirmation AND are a different, later utterance than the one that
 *    prepared the ride (so one sentence can never both prepare and confirm);
 *  - the ride screen right now has the same fingerprint (pickup, drop, stops, ride type, fare, payment);
 *  - no ride was already submitted for it, and no other live booking attempt is recent.
 * Cancelling follows the same shape: prepare (state is read back), then a new explicit message.
 */
class RideGate(
    private val clock: () -> Long,
    private val ledger: RideLedger,
    private val ttlMs: Long = 4 * 60_000L,
    private val minGapMs: Long = 2_500L,
    private val dedupeWindowMs: Long = 20 * 60_000L,
    private val cancelTtlMs: Long = 3 * 60_000L
) {
    @Volatile var pending: PendingRide? = null
        private set
    @Volatile var pendingCancel: PendingCancel? = null
        private set

    fun prepare(summary: RideSummary, utterance: String): PendingRide {
        val ride = PendingRide(summary.fingerprint(), summary, clock(), utterance)
        pending = ride
        // Never downgrade a ride that was already submitted: preparing it again must not erase that fact.
        val existing = ledger.get(ride.fingerprint)
        if (existing == null || existing.state == BookingState.PREPARED) {
            ledger.put(RideLedgerEntry(ride.fingerprint, BookingState.PREPARED, ride.preparedAt))
        }
        return ride
    }

    private fun sameTurn(said: String, prepared: String, preparedAt: Long, now: Long): Boolean {
        val a = RideConfirmation.normalize(said)
        val b = RideConfirmation.normalize(prepared)
        val overlap = b.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
        return overlap || now - preparedAt < minGapMs
    }

    fun authorize(current: RideSummary, utterance: String): RideDecision {
        val ride = pending ?: return RideDecision.Denied("NOT_PREPARED", "No ride has been prepared and read back to the user yet.")
        val now = clock()
        if (now - ride.preparedAt > ttlMs) {
            pending = null
            return RideDecision.Denied("EXPIRED", "The prepared fare is too old. Prepare the ride again so the current fare is read back.")
        }
        if (!RideConfirmation.isExplicit(RideAction.BOOK, utterance)) {
            return RideDecision.Denied("NOT_CONFIRMED", "The user has not explicitly told you to book this ride. Read the summary and ask them to say so.")
        }
        if (sameTurn(utterance, ride.prepareUtterance, ride.preparedAt, now)) {
            return RideDecision.Denied("SAME_TURN", "The confirmation must be a new message after the user heard the ride summary.")
        }
        if (!current.readable) {
            return RideDecision.Denied("UNREADABLE", "The pickup, drop, ride type or fare cannot be read on the screen right now, so the ride cannot be verified.")
        }
        if (current.fingerprint() != ride.fingerprint) {
            return RideDecision.Denied("CHANGED", "The ride changed since it was read back (pickup, drop, ride type, fare or payment). Prepare it again and confirm the new details.")
        }
        val seen = ledger.get(ride.fingerprint)
        if (seen != null && (seen.state == BookingState.ATTEMPTED || seen.state == BookingState.BOOKED) && now - seen.at < dedupeWindowMs) {
            return RideDecision.Denied("DUPLICATE", "This exact ride was already submitted. Check the Rapido app (rapido_status) instead of booking again.")
        }
        // A booking attempt for ANY trip is still unresolved: booking another could create two live rides.
        val other = ledger.anyActive(now - dedupeWindowMs)
        if (other != null && other.fingerprint != ride.fingerprint) {
            return RideDecision.Denied("OTHER_ACTIVE", "Another ride booking was submitted a few minutes ago and is not finished or cancelled. Check it with rapido_status first.")
        }
        return RideDecision.Allowed(ride)
    }

    fun markAttempted(ride: PendingRide) = ledger.put(RideLedgerEntry(ride.fingerprint, BookingState.ATTEMPTED, clock()))
    fun markBooked(ride: PendingRide) { ledger.put(RideLedgerEntry(ride.fingerprint, BookingState.BOOKED, clock())); pending = null }

    /** The ride ended (cancelled, completed or never started): the same trip may be booked again later. */
    fun release(fingerprint: String?) {
        if (fingerprint != null) ledger.remove(fingerprint)
        else ledger.clear()
        pending = null
    }

    fun activeFingerprint(): String? = ledger.anyActive(clock() - dedupeWindowMs)?.fingerprint
    fun clear() { pending = null; pendingCancel = null }

    // ---- cancelling ---------------------------------------------------------------------------

    fun prepareCancel(stage: RideStage, utterance: String): PendingCancel {
        val p = PendingCancel(clock(), utterance, stage)
        pendingCancel = p
        return p
    }

    fun authorizeCancel(stage: RideStage, utterance: String): CancelDecision {
        val p = pendingCancel ?: return CancelDecision.Denied("NOT_PREPARED", "Cancelling has not been explained to the user yet. Describe the ride state and ask them to confirm.")
        val now = clock()
        if (now - p.preparedAt > cancelTtlMs) {
            pendingCancel = null
            return CancelDecision.Denied("EXPIRED", "The cancellation request is too old. Ask the user again.")
        }
        if (!RideConfirmation.isExplicit(RideAction.CANCEL, utterance)) {
            return CancelDecision.Denied("NOT_CONFIRMED", "The user has not explicitly told you to cancel the ride.")
        }
        if (sameTurn(utterance, p.prepareUtterance, p.preparedAt, now)) {
            return CancelDecision.Denied("SAME_TURN", "The cancellation must be confirmed in a new message after the user heard what cancelling means.")
        }
        if (stage != p.stage) {
            pendingCancel = null
            return CancelDecision.Denied("CHANGED", "The ride changed (it is now ${stage.name.lowercase(Locale.ROOT).replace('_', ' ')}). Describe the new state and ask the user again.")
        }
        return CancelDecision.Allowed(p)
    }

    fun cancelDone() { pendingCancel = null }
}
