package com.jarvis.assistant.automation.blinkit

import com.jarvis.assistant.automation.amazon.ConfirmationPhrase
import com.jarvis.assistant.automation.amazon.LedgerEntry
import com.jarvis.assistant.automation.amazon.OrderLedger
import com.jarvis.assistant.automation.amazon.OrderState
import java.util.Locale

enum class BlinkitAction { ORDER, CANCEL }

/** Recognises an explicit, positive instruction in what the user just said. English and Hinglish. */
object BlinkitConfirmation {
    private val NEGATIVE = Regex(
        """\b(don't|dont|do not|not yet|not now|no|nahi|nahin|nhi|mat|stop|wait|ruko|ruk|rukna|rehne do|rehne de|abhi nahi|hold on|never)\b"""
    )
    private val EXTRA_ORDER = listOf(
        Regex("""\bpay\b.*\b(kar do|kardo|karo|kar de|now)\b"""),
        Regex("""\b(order|orders)\b.*\b(laga do|laga de|laga dijiye|bhej do|kar do|kardo|karo)\b"""),
        Regex("""\b(go ahead|haan|han|yes|yeah|yep|ok|okay|sure)\b.*\b(pay|order|place|checkout)\b"""),
        Regex("""\bplace (it|the order|my order)\b""")
    )
    private val CANCEL_NEGATIVE = Regex(
        """\b(don't|dont|do not|not yet|not now|nahi|nahin|nhi|mat|wait|ruko|rukna|rehne do|rehne de|abhi nahi|hold on|never|keep)\b"""
    )
    private val CANCEL_POSITIVE = listOf(
        Regex("""\bcancel\b.*\b(it|order|karo|kar do|kardo|kar de)\b"""),
        Regex("""\b(yes|haan|han|yeah|yep|ok|okay|sure|confirm)\b.*\bcancel\b"""),
        Regex("""\bcancel kar\b"""),
        Regex("""\border cancel\b""")
    )

    fun normalize(text: String): String = ConfirmationPhrase.normalize(text)

    fun isExplicit(action: BlinkitAction, text: String): Boolean {
        val t = normalize(text)
        if (t.length < 5) return false
        return when (action) {
            BlinkitAction.ORDER -> ConfirmationPhrase.isExplicit(text) || (!NEGATIVE.containsMatchIn(t) && EXTRA_ORDER.any { it.containsMatchIn(t) })
            BlinkitAction.CANCEL -> !CANCEL_NEGATIVE.containsMatchIn(t) && CANCEL_POSITIVE.any { it.containsMatchIn(t) }
        }
    }
}

data class PendingBlinkitOrder(
    val fingerprint: String,
    val summary: CheckoutSummary,
    val preparedAt: Long,
    /** What the user said in the turn that prepared the order. */
    val prepareUtterance: String
)

data class PendingCancel(val preparedAt: Long, val prepareUtterance: String, val stage: OrderStage)

sealed class OrderDecision {
    data class Allowed(val order: PendingBlinkitOrder) : OrderDecision()
    data class Denied(val code: String, val reason: String) : OrderDecision()
}

sealed class CancelDecision {
    data class Allowed(val pending: PendingCancel) : CancelDecision()
    data class Denied(val code: String, val reason: String) : CancelDecision()
}

/**
 * The only path to placing or cancelling a Blinkit order. An order is allowed only when ALL of these hold:
 *  - the checkout was prepared and read back first (summary read, fingerprint stored);
 *  - the preparation is recent (prices and fees move);
 *  - the user's latest words are an explicit confirmation AND are a different, later utterance than the one that
 *    prepared the order (so one sentence can never both prepare and confirm);
 *  - the payment screen right now has the same fingerprint (items, quantities, total, address, payment);
 *  - the same checkout was not already submitted (the ledger is written BEFORE the tap, so a timeout or crash
 *    can never lead to a second tap).
 * Cancelling follows the same shape: prepare (state is read back), then a new explicit message.
 */
class BlinkitGate(
    private val clock: () -> Long,
    private val ledger: OrderLedger,
    private val ttlMs: Long = 8 * 60_000L,
    private val minGapMs: Long = 2_500L,
    private val dedupeWindowMs: Long = 30 * 60_000L,
    private val cancelTtlMs: Long = 3 * 60_000L
) {
    @Volatile var pending: PendingBlinkitOrder? = null
        private set
    @Volatile var pendingCancel: PendingCancel? = null
        private set

    fun prepare(summary: CheckoutSummary, utterance: String): PendingBlinkitOrder {
        val order = PendingBlinkitOrder(summary.fingerprint(), summary, clock(), utterance)
        pending = order
        // Never downgrade a checkout that was already submitted: preparing it again must not erase that fact.
        val existing = ledger.get(order.fingerprint)
        if (existing == null || existing.state == OrderState.PREPARED) {
            ledger.put(LedgerEntry(order.fingerprint, OrderState.PREPARED, order.preparedAt))
        }
        return order
    }

    private fun sameTurn(said: String, prepared: String, preparedAt: Long, now: Long): Boolean {
        val a = normalizeForTurn(said)
        val b = normalizeForTurn(prepared)
        val overlap = b.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
        return overlap || now - preparedAt < minGapMs
    }

    private fun normalizeForTurn(s: String) = BlinkitConfirmation.normalize(s)

    fun authorize(current: CheckoutSummary, utterance: String): OrderDecision {
        val order = pending ?: return OrderDecision.Denied("NOT_PREPARED", "No checkout has been prepared and read back to the user yet.")
        val now = clock()
        if (now - order.preparedAt > ttlMs) {
            pending = null
            return OrderDecision.Denied("EXPIRED", "The prepared checkout is too old. Prepare it again and read the total back to the user.")
        }
        if (!BlinkitConfirmation.isExplicit(BlinkitAction.ORDER, utterance)) {
            return OrderDecision.Denied("NOT_CONFIRMED", "The user has not explicitly told you to place this order. Read the summary and ask them to say so.")
        }
        if (sameTurn(utterance, order.prepareUtterance, order.preparedAt, now)) {
            return OrderDecision.Denied("SAME_TURN", "The confirmation must be a new message after the user heard the order summary.")
        }
        if (!current.readable) {
            return OrderDecision.Denied("UNREADABLE", "The order total or items cannot be read on the screen right now, so the order cannot be verified.")
        }
        if (current.fingerprint() != order.fingerprint) {
            return OrderDecision.Denied("CHANGED", "The checkout changed since it was read back (items, total, address or payment). Prepare it again and confirm the new total.")
        }
        val seen = ledger.get(order.fingerprint)
        if (seen != null && (seen.state == OrderState.ATTEMPTED || seen.state == OrderState.PLACED) && now - seen.at < dedupeWindowMs) {
            return OrderDecision.Denied("DUPLICATE", "This exact checkout was already submitted. Check the order status (blinkit_order_status) instead of ordering again.")
        }
        return OrderDecision.Allowed(order)
    }

    fun markAttempted(order: PendingBlinkitOrder) = ledger.put(LedgerEntry(order.fingerprint, OrderState.ATTEMPTED, clock()))
    fun markPlaced(order: PendingBlinkitOrder) { ledger.put(LedgerEntry(order.fingerprint, OrderState.PLACED, clock())); pending = null }
    fun clear() { pending = null; pendingCancel = null }

    /** The checkout whose submission is still unresolved, if any, so a status check can settle it. */
    fun lastAttempted(): String? = pending?.fingerprint

    // ---- cancelling ---------------------------------------------------------------------------

    fun prepareCancel(stage: OrderStage, utterance: String): PendingCancel {
        val p = PendingCancel(clock(), utterance, stage)
        pendingCancel = p
        return p
    }

    fun authorizeCancel(stage: OrderStage, utterance: String): CancelDecision {
        val p = pendingCancel ?: return CancelDecision.Denied("NOT_PREPARED", "Cancelling has not been explained to the user yet. Describe the order state and ask them to confirm.")
        val now = clock()
        if (now - p.preparedAt > cancelTtlMs) {
            pendingCancel = null
            return CancelDecision.Denied("EXPIRED", "The cancellation request is too old. Ask the user again.")
        }
        if (!BlinkitConfirmation.isExplicit(BlinkitAction.CANCEL, utterance)) {
            return CancelDecision.Denied("NOT_CONFIRMED", "The user has not explicitly told you to cancel the order.")
        }
        if (sameTurn(utterance, p.prepareUtterance, p.preparedAt, now)) {
            return CancelDecision.Denied("SAME_TURN", "The cancellation must be confirmed in a new message after the user heard what cancelling means.")
        }
        if (stage != p.stage) {
            pendingCancel = null
            return CancelDecision.Denied("CHANGED", "The order changed (it is now ${stage.name.lowercase(Locale.ROOT).replace('_', ' ')}). Describe the new state and ask the user again.")
        }
        return CancelDecision.Allowed(p)
    }

    fun cancelDone() { pendingCancel = null }
}
