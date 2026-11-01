package com.jarvis.assistant.automation.blinkit

import java.net.URLEncoder
import java.util.Locale

/**
 * Hard rules for the generic tools (click_ui_element, type_text). They stop the AI from reaching around the Blinkit
 * tools: it cannot press a pay / place-order / cancel button directly, and it cannot type into login, OTP, CAPTCHA,
 * UPI PIN or card-security screens.
 */
object BlinkitGuards {
    private val PURCHASE_LABELS = listOf(
        "place order", "pay now", "pay securely", "confirm and pay", "confirm & pay", "make payment", "pay and place",
        "pay & place", "pay with", "pay ₹", "pay rs"
    )
    private val CANCEL_LABELS = listOf("cancel order", "cancel this order", "yes, cancel", "confirm cancellation")

    const val PURCHASE_MESSAGE =
        "Blocked: ordering on Blinkit only happens through blinkit_prepare_checkout followed by blinkit_place_order, " +
            "after the user has heard the order summary and clearly said to place it. Do not tap pay or place-order buttons directly."

    const val CANCEL_MESSAGE =
        "Blocked: cancelling a Blinkit order only happens through blinkit_order_status with action=cancel, which first explains the " +
            "order state and then needs the user to clearly say to cancel in a new message. Do not tap cancel buttons directly."

    const val TYPING_MESSAGE =
        "Blocked: Blinkit is showing a login, OTP, CAPTCHA, UPI PIN or card-security screen. Jarvis never types passwords, codes or " +
            "card details and never bypasses these checks. Tell the user to complete it themselves, then continue."

    /** Returns a refusal message, or null when a generic click is allowed. */
    fun genericClickBlock(blinkitContext: Boolean, screen: BlinkitScreen?, target: String, description: String, byPositionOrId: Boolean): String? {
        if (!blinkitContext) return null
        val text = (target + " " + description).lowercase(Locale.ROOT)
        if (PURCHASE_LABELS.any { text.contains(it) }) return PURCHASE_MESSAGE
        if (CANCEL_LABELS.any { text.contains(it) }) return CANCEL_MESSAGE
        // On the payment screen a tap by coordinates or id could land on the pay button without naming it.
        if (screen == BlinkitScreen.PAYMENT && byPositionOrId) return PURCHASE_MESSAGE
        return null
    }

    fun genericTypeBlock(blinkitContext: Boolean, screen: BlinkitScreen?): String? =
        if (blinkitContext && (screen == BlinkitScreen.CHALLENGE || screen == BlinkitScreen.SIGN_IN)) TYPING_MESSAGE else null
}

/**
 * Links into the Blinkit app. Blinkit documents no public API and no deep-link scheme, so none is claimed here. The
 * https link below is what the website uses; if the installed app does not take it, the agent opens the app and types
 * the search itself. Whatever the app shows is read from the screen and re-checked, so a link the app ignores can never
 * make a wrong result look right.
 */
object BlinkitLinks {
    /** Blinkit's Android package (formerly Grofers). */
    val packages: List<String> = listOf("com.grofers.customerapp")

    fun isBlinkitPackage(pkg: String?): Boolean = pkg != null && pkg in packages

    fun searchUrl(query: String): String = "https://blinkit.com/s/?q=" + URLEncoder.encode(query.trim(), "UTF-8")
}
