package com.jarvis.assistant.onboarding

enum class SetupStatus(val label: String) {
    COMPLETED("Completed"),
    NEEDS_ATTENTION("Needs attention"),
    OPTIONAL("Optional"),
    SKIPPED("Skipped")
}

object SetupStatusRules {
    /**
     * @param satisfied the real, live condition (never a remembered tap)
     * @param stepState what the user did with the tutorial stage
     * @param core whether the feature is central to JARVIS working as intended
     */
    fun derive(satisfied: Boolean, stepState: StepState, core: Boolean): SetupStatus = when {
        satisfied -> SetupStatus.COMPLETED
        stepState == StepState.SKIPPED -> SetupStatus.SKIPPED
        stepState == StepState.COMPLETED -> SetupStatus.NEEDS_ATTENTION // finished once, no longer true
        core -> SetupStatus.NEEDS_ATTENTION
        else -> SetupStatus.OPTIONAL
    }
}

/** Result of the last connection test. The key itself is never stored here. */
data class ApiCheckRecord(val ok: Boolean, val at: Long)

/** A snapshot of the real state of the app, read from the existing components. */
data class LiveStatus(
    val permissionGranted: Map<PermissionKey, Boolean> = emptyMap(),
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val apiKeyPresent: Boolean = false,
    val groqSelected: Boolean = false,
    val groqKeyPresent: Boolean = false,
    val secureStorageAvailable: Boolean = true,
    val lastApiCheck: ApiCheckRecord? = null,
    val profilePhotoSet: Boolean = false,
    val profileSaved: Boolean = false,
    val speechModelReady: Boolean = false,
    val ownerVoiceEnrolled: Boolean = false,
    val offlineModelInstalled: Boolean = false,
    val installedApps: Map<String, Boolean> = emptyMap()
) {
    fun isGranted(key: PermissionKey): Boolean = permissionGranted[key] == true

    val aiConfigured: Boolean get() = if (groqSelected) groqKeyPresent else apiKeyPresent

    val supportedAppsInstalled: Int
        get() = installedApps.filterKeys { it != AppControlCatalog.DEVICE_ID }.count { it.value }
}

data class SetupItem(
    val step: OnboardingStep,
    val title: String,
    val detail: String,
    val status: SetupStatus
)

/** Builds the final system-check rows. Pure, so it can be unit tested without a device. */
object SetupSummary {

    fun build(live: LiveStatus, progress: OnboardingProgress, sdk: Int): List<SetupItem> {
        val specs = PermissionCatalog.forSdk(sdk)
        val core = specs.filter { it.core }
        val allowed = specs.count { live.isGranted(it.key) }
        val coreOk = core.all { live.isGranted(it.key) }

        val profileOk = live.profileSaved || live.profilePhotoSet

        val aiDetail = when {
            !live.aiConfigured -> "No API key saved yet"
            live.lastApiCheck?.ok == true -> "Key saved and verified"
            live.lastApiCheck != null -> "Key saved, last test failed"
            else -> "Key saved, not tested yet"
        }

        val a11yDetail = when {
            live.accessibilityConnected -> "Service is active"
            live.accessibilityEnabled -> "Enabled, waiting for the service to connect"
            else -> "Switched off in Android settings"
        }

        val micOk = live.isGranted(PermissionKey.MICROPHONE)
        val voiceOk = micOk && live.speechModelReady
        val voiceDetail = buildString {
            append(if (micOk) "Microphone allowed" else "Microphone not allowed")
            append(" · ")
            append(if (live.speechModelReady) "on-device speech model ready" else "on-device speech model not ready")
            append(" · ")
            append(if (live.ownerVoiceEnrolled) "your voice is enrolled" else "voice lock not set up")
        }

        val appsOk = live.accessibilityEnabled && live.supportedAppsInstalled > 0
        val appsDetail = when {
            !live.accessibilityEnabled -> "Needs Accessibility"
            live.supportedAppsInstalled == 0 -> "No supported apps found on this phone"
            else -> "${live.supportedAppsInstalled} supported app(s) installed"
        }

        return listOf(
            SetupItem(
                OnboardingStep.PERMISSIONS, "Permissions",
                "$allowed of ${specs.size} allowed" + if (coreOk) "" else " · essentials missing",
                SetupStatusRules.derive(coreOk, progress.stateOf(OnboardingStep.PERMISSIONS), core = true)
            ),
            SetupItem(
                OnboardingStep.PROFILE, "User card",
                if (profileOk) "Your profile is saved" else "Using the default profile",
                SetupStatusRules.derive(profileOk, progress.stateOf(OnboardingStep.PROFILE), core = false)
            ),
            SetupItem(
                OnboardingStep.AI, "AI configuration", aiDetail,
                SetupStatusRules.derive(live.aiConfigured, progress.stateOf(OnboardingStep.AI), core = true)
            ),
            SetupItem(
                OnboardingStep.ACCESSIBILITY, "Accessibility", a11yDetail,
                SetupStatusRules.derive(live.accessibilityEnabled, progress.stateOf(OnboardingStep.ACCESSIBILITY), core = true)
            ),
            SetupItem(
                OnboardingStep.VOICE, "Voice", voiceDetail,
                SetupStatusRules.derive(voiceOk, progress.stateOf(OnboardingStep.VOICE), core = false)
            ),
            SetupItem(
                OnboardingStep.APP_CONTROL, "App-control readiness", appsDetail,
                SetupStatusRules.derive(appsOk, progress.stateOf(OnboardingStep.APP_CONTROL), core = false)
            )
        )
    }
}
