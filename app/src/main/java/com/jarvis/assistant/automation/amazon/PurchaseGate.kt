package com.jarvis.assistant.automation.amazon

import java.util.Locale

/** Recognises an explicit, positive "place the order" instruction in what the user just said. */
object ConfirmationPhrase {
    private val NEGATIVE = Regex(
        """\b(don't|dont|do not|not yet|not now|no|nahi|nahin|nhi|mat|cancel|stop|wait|ruko|ruk|rukna|rehne do|abhi nahi|hold on|never)\b"""
    )
    private val POSITIVE = listOf(
        Regex("""\bconfirm(ed)?\b.*\b(order|purchase|buy|payment)\b"""),
        Regex("""\b(order|purchase|buy|payment)\b.*\bconfirm(ed)?\b"""),
        Regex("""\bplace\b.*\b(order|it)\b"""),
        Regex("""\border\b.*\b(kar do|kardo|kar de|karo|kar dijiye|place kar|laga do|laga de)\b"""),
        Regex("""\b(go ahead|haan|han|yes|yeah|yep|ok|okay)\b.*\b(order|place|buy|purchase|kharid)\b"""),
        Regex("""\bconfirm kar\b"""),
        Regex("""\b(buy it|order it|purchase it)\b""")
    )

    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").replace(Regex("\\s+"), " ").trim()

    fun isExplicit(text: String): Boolean {
        val t = normalize(text)
        if (t.length < 5) return false
        if (NEGATIVE.containsMatchIn(t)) return false
        return POSITIVE.any { it.containsMatchIn(t) }
    }
}

enum class OrderState { PREPARED, ATTEMPTED, PLACED }

data class LedgerEntry(val fingerprint: String, val state: OrderState, val at: Long)

/** Remembers which checkouts were already submitted, so an order is never placed twice. */
interface OrderLedger {
    fun get(fingerprint: String): LedgerEntry?
    fun put(entry: LedgerEntry)
    fun clear()
}

class InMemoryOrderLedger : OrderLedger {
    private val map = HashMap<String, LedgerEntry>()
    @Synchronized override fun get(fingerprint: String): LedgerEntry? = map[fingerprint]
    @Synchronized override fun put(entry: LedgerEntry) { map[entry.fingerprint] = entry }
    @Synchronized override fun clear() { map.clear() }
}

data class PendingOrder(
    val fingerprint: String,
    val summary: CheckoutSummary,
    val preparedAt: Long,
    /** What the user said in the turn that prepared the order. */
    val prepareUtterance: String
)

sealed class GateDecision {
    data class Allowed(val order: PendingOrder) : GateDecision()
    data class Denied(val code: String, val reason: String) : GateDecision()
}

/**
 * The only path to placing an order. An order is allowed only when ALL of these hold:
 *  - the checkout was prepared and read back first (summary read, fingerprint stored);
 *  - the preparation is recent;
 *  - the user's latest words are an explicit confirmation AND are a different, later utterance than the one that
 *    prepared the order (so one sentence can never both prepare and confirm);
 *  - the checkout on screen right now has the same fingerprint (items, quantities, total, address, payment);
 *  - the same checkout was not already submitted.
 */
class PurchaseGate(
    private val clock: () -> Long,
    private val ledger: OrderLedger,
    private val ttlMs: Long = 10 * 60_000L,
    private val minGapMs: Long = 2_500L,
    private val dedupeWindowMs: Long = 30 * 60_000L
) {
    @Volatile var pending: PendingOrder? = null
        private set

    fun prepare(summary: CheckoutSummary, utterance: String): PendingOrder {
        val order = PendingOrder(summary.fingerprint(), summary, clock(), utterance)
        pending = order
        // Never downgrade a checkout that was already submitted: preparing it again must not erase that fact.
        val existing = ledger.get(order.fingerprint)
        if (existing == null || existing.state == OrderState.PREPARED) {
            ledger.put(LedgerEntry(order.fingerprint, OrderState.PREPARED, order.preparedAt))
        }
        return order
    }

    fun authorize(current: CheckoutSummary, utterance: String): GateDecision {
        val order = pending ?: return GateDecision.Denied("NOT_PREPARED", "No checkout has been prepared and read back to the user yet.")
        val now = clock()
        if (now - order.preparedAt > ttlMs) {
            pending = null
            return GateDecision.Denied("EXPIRED", "The prepared checkout is too old. Prepare it again and read the total back to the user.")
        }
        if (!ConfirmationPhrase.isExplicit(utterance)) {
            return GateDecision.Denied("NOT_CONFIRMED", "The user has not explicitly told you to place this order. Read the summary and ask them to say so.")
        }
        val said = ConfirmationPhrase.normalize(utterance)
        val prepared = ConfirmationPhrase.normalize(order.prepareUtterance)
        // The words that prepared the order (or any sentence containing them) can never also confirm it.
        val sameTurn = prepared.isNotEmpty() && (said == prepared || said.contains(prepared) || prepared.contains(said))
        if (sameTurn || now - order.preparedAt < minGapMs) {
            return GateDecision.Denied("SAME_TURN", "The confirmation must be a new message after the user heard the order summary.")
        }
        if (!current.readable) {
            return GateDecision.Denied("UNREADABLE", "The order total or items cannot be read on the screen right now, so the order cannot be verified.")
        }
        if (current.fingerprint() != order.fingerprint) {
            return GateDecision.Denied("CHANGED", "The checkout changed since it was read back (items, total, address or payment). Prepare it again and confirm the new total.")
        }
        val seen = ledger.get(order.fingerprint)
        if (seen != null && (seen.state == OrderState.ATTEMPTED || seen.state == OrderState.PLACED) && now - seen.at < dedupeWindowMs) {
            return GateDecision.Denied("DUPLICATE", "This exact checkout was already submitted. Check Your Orders in Amazon instead of ordering again.")
        }
        return GateDecision.Allowed(order)
    }

    fun markAttempted(order: PendingOrder) = ledger.put(LedgerEntry(order.fingerprint, OrderState.ATTEMPTED, clock()))
    fun markPlaced(order: PendingOrder) { ledger.put(LedgerEntry(order.fingerprint, OrderState.PLACED, clock())); pending = null }
    fun clear() { pending = null }
}
