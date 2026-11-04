package com.jarvis.assistant.automation.rapido

import java.util.Locale

/** Package names of the Rapido passenger app. */
object RapidoApp {
    val packages: List<String> = listOf("com.rapido.passenger")
    fun isRapidoPackage(pkg: String?): Boolean = pkg != null && pkg in packages
}

/**
 * Hard rules for the generic tools (click_ui_element, type_text). They stop the AI from reaching around the Rapido
 * tools: it cannot press Book, Cancel or a payment button directly, and it cannot type into sign-in, OTP, CAPTCHA,
 * PIN or card-security screens.
 */
object RapidoGuards {
    private val CONSEQUENTIAL = Regex(
        """\b(?:book(?:\s+(?:now|ride|auto|bike|cab|taxi|rapido|mini|prime|sedan|suv|xl|economy|premium|lite))?|confirm\s+(?:booking|ride|cancel\w*)|request\s+ride|""" +
            """cancel\s+(?:ride|booking|trip)|yes,?\s+cancel|pay\s+now|pay\s+with|add\s+money|recharge|make\s+payment|proceed\s+to\s+pay)\b""",
        RegexOption.IGNORE_CASE
    )

    const val BOOKING_MESSAGE =
        "Blocked: booking, cancelling or paying in Rapido only happens through rapido_prepare_booking followed by rapido_book " +
            "(or rapido_cancel_ride), after the user has heard the summary and clearly said to go ahead. Do not tap those buttons directly."

    const val TYPING_MESSAGE =
        "Blocked: Rapido is showing a sign-in, OTP, CAPTCHA, PIN or card-security screen. Jarvis never types passwords, codes or " +
            "card details and never bypasses these checks. Tell the user to complete it themselves, then continue."

    /** Returns a refusal message, or null when a generic click is allowed. */
    fun genericClickBlock(
        rapidoContext: Boolean,
        screen: RapidoScreen?,
        target: String,
        description: String,
        byPositionOrId: Boolean
    ): String? {
        if (!rapidoContext) return null
        val text = "$target $description".lowercase(Locale.ROOT)
        if (CONSEQUENTIAL.containsMatchIn(text)) return BOOKING_MESSAGE
        // On the fare screen or in the cancel dialog, a tap by coordinates or id could land on Book/Cancel without naming it.
        if ((screen == RapidoScreen.RIDE_OPTIONS || screen == RapidoScreen.CANCEL_FLOW) && byPositionOrId) return BOOKING_MESSAGE
        return null
    }

    fun genericTypeBlock(rapidoContext: Boolean, screen: RapidoScreen?): String? =
        if (rapidoContext && (screen == RapidoScreen.CHALLENGE || screen == RapidoScreen.SIGN_IN)) TYPING_MESSAGE else null
}
