package com.jarvis.assistant.onboarding

enum class PermissionKey { MICROPHONE, NOTIFICATIONS, CONTACTS, CAMERA, PHONE, LOCATION, MEDIA }

/** How a permission looks to the user right now. [BLOCKED] means Android will no longer show its prompt. */
enum class PermissionUiState { GRANTED, NOT_ASKED, DENIED, BLOCKED }

object PermissionRules {
    /**
     * Android never reports "permanently denied" directly. A permission that was asked for before, is not granted and
     * no longer allows a rationale is one the system will not prompt for again: the only way forward is App Settings.
     */
    fun derive(granted: Boolean, askedBefore: Boolean, shouldShowRationale: Boolean): PermissionUiState = when {
        granted -> PermissionUiState.GRANTED
        !askedBefore -> PermissionUiState.NOT_ASKED
        shouldShowRationale -> PermissionUiState.DENIED
        else -> PermissionUiState.BLOCKED
    }
}

/**
 * One runtime permission the app genuinely uses today.
 * @param request manifest permissions passed to the system prompt together
 * @param grantedIfAny the permission counts as granted when any one of these is granted
 */
data class PermissionSpec(
    val key: PermissionKey,
    val title: String,
    val tagline: String,
    val why: String,
    val unlocks: List<String>,
    val core: Boolean,
    val request: List<String>,
    val grantedIfAny: List<String>
)

/** Every entry is a permission already declared in the manifest and already used by an existing feature. */
object PermissionCatalog {
    private const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    private const val CAMERA = "android.permission.CAMERA"
    private const val CALL_PHONE = "android.permission.CALL_PHONE"
    private const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    private const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    private const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    private const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    private const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    private const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"

    private const val TIRAMISU = 33
    private const val UPSIDE_DOWN_CAKE = 34

    fun forSdk(sdk: Int): List<PermissionSpec> {
        val list = mutableListOf<PermissionSpec>()

        list += PermissionSpec(
            key = PermissionKey.MICROPHONE,
            title = "Microphone",
            tagline = "So JARVIS can hear you.",
            why = "Voice is how you talk to JARVIS: the wake word, live conversation and typing by voice all need the " +
                "microphone. During a live conversation your speech goes to the AI service you configure. You can mute " +
                "the microphone from the home screen at any time.",
            unlocks = listOf("Hey JARVIS wake word", "Live voice conversation", "Dictation and typing by voice"),
            core = true,
            request = listOf(RECORD_AUDIO),
            grantedIfAny = listOf(RECORD_AUDIO)
        )

        if (sdk >= TIRAMISU) {
            list += PermissionSpec(
                key = PermissionKey.NOTIFICATIONS,
                title = "Notifications",
                tagline = "So JARVIS can run visibly in the background.",
                why = "Android requires a visible notification while JARVIS listens or works in the background. " +
                    "Alarms and update notices use notifications too.",
                unlocks = listOf("Background voice service indicator", "Alarm and timer alerts", "Update notices"),
                core = true,
                request = listOf(POST_NOTIFICATIONS),
                grantedIfAny = listOf(POST_NOTIFICATIONS)
            )
        }

        list += PermissionSpec(
            key = PermissionKey.CONTACTS,
            title = "Contacts",
            tagline = "So \"message Riya\" knows who Riya is.",
            why = "JARVIS matches the name you say against your saved contacts for calls and WhatsApp. " +
                "Without it, you can still dictate a phone number.",
            unlocks = listOf("Calling and WhatsApp by contact name"),
            core = false,
            request = listOf(READ_CONTACTS),
            grantedIfAny = listOf(READ_CONTACTS)
        )

        list += PermissionSpec(
            key = PermissionKey.CAMERA,
            title = "Camera",
            tagline = "For the flashlight, face lock and camera scan.",
            why = "The camera permission is used for the flashlight command, the optional face lock and the camera scan " +
                "feature. JARVIS never opens the camera on its own.",
            unlocks = listOf("Flashlight on and off", "Face lock", "Camera scan"),
            core = false,
            request = listOf(CAMERA),
            grantedIfAny = listOf(CAMERA)
        )

        list += PermissionSpec(
            key = PermissionKey.PHONE,
            title = "Phone calls",
            tagline = "Only to place a normal call when you ask.",
            why = "Needed so JARVIS can start a regular phone call after you ask for one. It does not call anyone by itself.",
            unlocks = listOf("\"Call Mom\" style voice calls"),
            core = false,
            request = listOf(CALL_PHONE),
            grantedIfAny = listOf(CALL_PHONE)
        )

        list += PermissionSpec(
            key = PermissionKey.LOCATION,
            title = "Location",
            tagline = "For local weather and place-aware answers.",
            why = "Used to answer questions such as the weather where you are. It is read when you ask, not tracked in the background.",
            unlocks = listOf("Local weather"),
            core = false,
            request = listOf(FINE_LOCATION, COARSE_LOCATION),
            grantedIfAny = listOf(FINE_LOCATION, COARSE_LOCATION)
        )

        val mediaRequest: List<String>
        val mediaGranted: List<String>
        when {
            sdk >= UPSIDE_DOWN_CAKE -> {
                mediaRequest = listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_VISUAL_USER_SELECTED)
                mediaGranted = listOf(READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED)
            }
            sdk >= TIRAMISU -> {
                mediaRequest = listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
                mediaGranted = listOf(READ_MEDIA_IMAGES)
            }
            else -> {
                mediaRequest = listOf(READ_EXTERNAL_STORAGE)
                mediaGranted = listOf(READ_EXTERNAL_STORAGE)
            }
        }
        list += PermissionSpec(
            key = PermissionKey.MEDIA,
            title = "Photos and videos",
            tagline = "Only for JARVIS's built-in Gallery.",
            why = "Needed only by the optional Gallery viewer. Choosing a profile photo uses Android's Photo Picker " +
                "and needs no permission at all.",
            unlocks = listOf("Gallery browsing"),
            core = false,
            request = mediaRequest,
            grantedIfAny = mediaGranted
        )

        return list
    }
}
