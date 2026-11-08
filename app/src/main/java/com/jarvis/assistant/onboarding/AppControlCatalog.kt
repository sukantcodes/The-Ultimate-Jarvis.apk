package com.jarvis.assistant.onboarding

/**
 * How JARVIS reaches an app. Every label below reflects code that exists in this app today:
 * deep links and intents, the Accessibility service, or an explicit confirmation gate.
 */
enum class Mechanism(val label: String) {
    DIRECT_INTENT("Direct intent"),
    ACCESSIBILITY("Accessibility automation"),
    CONFIRMATION("Needs your confirmation"),
    UNAVAILABLE("Not available")
}

data class Capability(val text: String, val mechanisms: List<Mechanism>)

/** The eight stages every supported task passes through, shown as the workflow animation. */
object WorkflowStages {
    val NAMES = listOf(
        "Voice command",
        "Understanding",
        "App selection",
        "Navigation",
        "Element identification",
        "Authorized action",
        "Result verification",
        "Voice response"
    )
}

/** [notes] has one line per entry in [WorkflowStages.NAMES]. */
data class AppProfile(
    val id: String,
    val name: String,
    val packages: List<String>,
    val example: String,
    val capabilities: List<Capability>,
    val notes: List<String>
) {
    /** The device-control profile has no package: it is always "installed". */
    val alwaysAvailable: Boolean get() = packages.isEmpty()
}

object AppControlCatalog {
    const val DEVICE_ID = "device"

    private val needsAccessibilityNote =
        Capability("Anything while Accessibility is switched off, or in apps that block it", listOf(Mechanism.UNAVAILABLE))

    val apps: List<AppProfile> = listOf(
        AppProfile(
            id = "whatsapp",
            name = "WhatsApp",
            packages = listOf("com.whatsapp", "com.whatsapp.w4b"),
            example = "Message Riya on WhatsApp: I'll be ten minutes late",
            capabilities = listOf(
                Capability("Message a contact with the text you dictate", listOf(Mechanism.DIRECT_INTENT, Mechanism.ACCESSIBILITY)),
                Capability("Voice or video call a contact", listOf(Mechanism.ACCESSIBILITY)),
                needsAccessibilityNote
            ),
            notes = listOf(
                "You speak the request, by wake word or by tapping the orb.",
                "JARVIS pulls out who to message and exactly what to say.",
                "WhatsApp, or WhatsApp Business, is picked from what is installed.",
                "An Android intent opens the chat with your text already filled in.",
                "Accessibility locates the Send button on the screen.",
                "Your request is the authorization: only the text you dictated goes to the contact you named.",
                "The outcome is judged from what the screen shows afterwards, where the app allows it.",
                "JARVIS tells you whether it went through."
            )
        ),
        AppProfile(
            id = "youtube",
            name = "YouTube",
            packages = listOf("com.google.android.youtube"),
            example = "Play Kesariya on YouTube",
            capabilities = listOf(
                Capability("Search and open a video directly", listOf(Mechanism.DIRECT_INTENT)),
                Capability("Pause, resume, next and previous", listOf(Mechanism.DIRECT_INTENT)),
                Capability("Subscribe, fullscreen and tapping a result", listOf(Mechanism.ACCESSIBILITY)),
                needsAccessibilityNote
            ),
            notes = listOf(
                "You say what to play.",
                "JARVIS strips the filler words and keeps the song or video name.",
                "YouTube is the target app.",
                "A YouTube deep link jumps straight to the top result, with no menus.",
                "For taps such as Subscribe, Accessibility finds the button on screen.",
                "Playing what you named is the action you asked for.",
                "Playback and the on-screen result are checked where possible.",
                "JARVIS confirms what is playing."
            )
        ),
        AppProfile(
            id = "blinkit",
            name = "Blinkit",
            packages = listOf("com.grofers.customerapp"),
            example = "Order milk and bread on Blinkit",
            capabilities = listOf(
                Capability("Search, compare items and plan a budget", listOf(Mechanism.ACCESSIBILITY)),
                Capability("Build the cart, pick the address and payment method, check order status", listOf(Mechanism.ACCESSIBILITY)),
                Capability("Place or cancel an order", listOf(Mechanism.ACCESSIBILITY, Mechanism.CONFIRMATION)),
                Capability("Type OTPs, UPI PINs, passwords or card details", listOf(Mechanism.UNAVAILABLE))
            ),
            notes = listOf(
                "You tell JARVIS what you need.",
                "JARVIS turns it into a shopping task: items, quantities and any budget.",
                "Blinkit is opened.",
                "JARVIS searches and moves through the app screens.",
                "Accessibility reads the products, cart and buttons on screen.",
                "Placing or cancelling an order only happens after you explicitly say so. Screens asking for OTP, PIN or card details are never typed into.",
                "Cart and order state are read back from the screen.",
                "JARVIS reads out the cart and asks before anything is ordered."
            )
        ),
        AppProfile(
            id = "rapido",
            name = "Rapido",
            packages = listOf("com.rapido.passenger"),
            example = "Compare bike and auto fares to the airport",
            capabilities = listOf(
                Capability("Check fares and compare bike, auto and cab", listOf(Mechanism.ACCESSIBILITY)),
                Capability("Ride status", listOf(Mechanism.ACCESSIBILITY)),
                Capability("Book or cancel a ride", listOf(Mechanism.ACCESSIBILITY, Mechanism.CONFIRMATION)),
                Capability("Type OTPs, PINs or card details", listOf(Mechanism.UNAVAILABLE))
            ),
            notes = listOf(
                "You say where you are going.",
                "JARVIS extracts the drop point and ride type.",
                "Rapido is opened.",
                "JARVIS moves through the booking screens.",
                "Accessibility reads fares and options from the screen.",
                "Booking or cancelling needs your explicit confirmation. JARVIS never presses the final button on its own.",
                "The ride state is read back from the screen.",
                "JARVIS reads out the options and waits for your decision."
            )
        ),
        AppProfile(
            id = "amazon",
            name = "Amazon",
            packages = listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
            example = "Find a 10000 mAh power bank under 1500 rupees on Amazon",
            capabilities = listOf(
                Capability("Search, compare and open products", listOf(Mechanism.ACCESSIBILITY)),
                Capability("Add to cart and read the cart", listOf(Mechanism.ACCESSIBILITY)),
                Capability("Prepare checkout and place an order", listOf(Mechanism.ACCESSIBILITY, Mechanism.CONFIRMATION)),
                Capability("Type sign-in, OTP, PIN or card details", listOf(Mechanism.UNAVAILABLE))
            ),
            notes = listOf(
                "You describe the product.",
                "JARVIS builds a search with your price and spec limits.",
                "The Amazon shopping app is opened.",
                "JARVIS searches and scrolls the results.",
                "Accessibility reads titles, prices and ratings on screen.",
                "Nothing is purchased until you explicitly confirm. Sign-in, OTP and card screens are never typed into.",
                "Product and cart details are read back from the screen.",
                "JARVIS summarises the best matches and asks what to do next."
            )
        ),
        AppProfile(
            id = DEVICE_ID,
            name = "Device control",
            packages = emptyList(),
            example = "Turn on the flashlight and set brightness to 40 percent",
            capabilities = listOf(
                Capability("Brightness, volume and flashlight", listOf(Mechanism.DIRECT_INTENT)),
                Capability("Media keys, opening apps and phone calls", listOf(Mechanism.DIRECT_INTENT)),
                Capability("Alarms and timers in JARVIS's own clock", listOf(Mechanism.DIRECT_INTENT)),
                Capability("Typing into any text field", listOf(Mechanism.ACCESSIBILITY)),
                Capability("Anything Android itself doesn't let apps do, such as settings reserved for the system", listOf(Mechanism.UNAVAILABLE))
            ),
            notes = listOf(
                "You say what to change.",
                "JARVIS maps it to a device setting or action.",
                "A system action is chosen: no app needs to be opened for most of them.",
                "Android settings and intents are used directly where they exist.",
                "For typing, Accessibility finds the focused input field.",
                "Your spoken request is the authorization.",
                "JARVIS reads the new state back where Android exposes it.",
                "JARVIS says what changed."
            )
        )
    )

    fun byId(id: String): AppProfile? = apps.firstOrNull { it.id == id }
}
