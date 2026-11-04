package com.jarvis.assistant.automation.amazon

import java.util.Locale

/**
 * Hard rules for the generic tools (click_ui_element, type_text). They stop the AI from reaching around
 * the Amazon tools: it cannot press a purchase button directly, and it cannot type into sign-in, OTP,
 * CAPTCHA, PIN or card-security screens.
 */
object AmazonGuards {
    private val PURCHASE_LABELS = listOf(
        "place your order", "place order", "buy now", "pay now", "confirm and pay", "confirm & pay",
        "complete purchase", "confirm order", "pay with", "place and pay"
    )

    const val PURCHASE_MESSAGE =
        "Blocked: buying on Amazon only happens through amazon_prepare_checkout followed by amazon_place_order, " +
            "after the user has heard the order summary and clearly said to place it. Do not tap purchase buttons directly."

    const val TYPING_MESSAGE =
        "Blocked: Amazon is showing a sign-in, OTP, CAPTCHA, PIN or card-security screen. Jarvis never types passwords, codes or " +
            "card details and never bypasses these checks. Tell the user to complete it themselves, then continue."

    /** Returns a refusal message, or null when a generic click is allowed. */
    fun genericClickBlock(
        amazonContext: Boolean,
        screen: AmazonScreen?,
        target: String,
        description: String,
        byPositionOrId: Boolean
    ): String? {
        if (!amazonContext) return null
        val text = (target + " " + description).lowercase(Locale.ROOT)
        if (PURCHASE_LABELS.any { text.contains(it) }) return PURCHASE_MESSAGE
        // On the final review screen a tap by coordinates or id could land on the order button without naming it.
        if (screen == AmazonScreen.ORDER_REVIEW && byPositionOrId) return PURCHASE_MESSAGE
        return null
    }

    fun genericTypeBlock(amazonContext: Boolean, screen: AmazonScreen?): String? =
        if (amazonContext && (screen == AmazonScreen.CHALLENGE || screen == AmazonScreen.SIGN_IN)) TYPING_MESSAGE else null
}
