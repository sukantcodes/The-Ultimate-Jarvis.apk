package com.jarvis.assistant.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStatusTest {

    private val sdk34 = 34

    private fun allGranted() = PermissionCatalog.forSdk(sdk34).associate { it.key to true }

    @Test
    fun satisfiedAlwaysMeansCompleted() {
        StepState.values().forEach {
            assertEquals(SetupStatus.COMPLETED, SetupStatusRules.derive(true, it, core = true))
            assertEquals(SetupStatus.COMPLETED, SetupStatusRules.derive(true, it, core = false))
        }
    }

    @Test
    fun skippedStaysSkippedWhenNotSatisfied() {
        assertEquals(SetupStatus.SKIPPED, SetupStatusRules.derive(false, StepState.SKIPPED, core = true))
        assertEquals(SetupStatus.SKIPPED, SetupStatusRules.derive(false, StepState.SKIPPED, core = false))
    }

    @Test
    fun finishedButNoLongerTrueNeedsAttention() {
        assertEquals(SetupStatus.NEEDS_ATTENTION, SetupStatusRules.derive(false, StepState.COMPLETED, core = false))
    }

    @Test
    fun untouchedCoreNeedsAttentionAndUntouchedOptionalIsOptional() {
        assertEquals(SetupStatus.NEEDS_ATTENTION, SetupStatusRules.derive(false, StepState.PENDING, core = true))
        assertEquals(SetupStatus.OPTIONAL, SetupStatusRules.derive(false, StepState.PENDING, core = false))
    }

    @Test
    fun emptyDeviceReportsNothingAsCompleted() {
        val items = SetupSummary.build(LiveStatus(), OnboardingProgress(), sdk34)
        assertTrue(items.none { it.status == SetupStatus.COMPLETED })
    }

    @Test
    fun summaryCoversEveryRequiredArea() {
        val items = SetupSummary.build(LiveStatus(), OnboardingProgress(), sdk34)
        val steps = items.map { it.step }.toSet()
        assertEquals(
            setOf(
                OnboardingStep.PERMISSIONS, OnboardingStep.PROFILE, OnboardingStep.AI,
                OnboardingStep.ACCESSIBILITY, OnboardingStep.VOICE, OnboardingStep.APP_CONTROL
            ),
            steps
        )
    }

    @Test
    fun accessibilityRowFollowsLiveStateNotTutorialProgress() {
        val finished = OnboardingProgress().complete(OnboardingStep.ACCESSIBILITY)
        val off = SetupSummary.build(LiveStatus(), finished, sdk34).first { it.step == OnboardingStep.ACCESSIBILITY }
        assertEquals(SetupStatus.NEEDS_ATTENTION, off.status)
        val on = SetupSummary.build(LiveStatus(accessibilityEnabled = true, accessibilityConnected = true), finished, sdk34)
            .first { it.step == OnboardingStep.ACCESSIBILITY }
        assertEquals(SetupStatus.COMPLETED, on.status)
    }

    @Test
    fun aiRowDistinguishesSavedFromVerified() {
        fun detail(live: LiveStatus) = SetupSummary.build(live, OnboardingProgress(), sdk34).first { it.step == OnboardingStep.AI }.detail
        assertEquals("No API key saved yet", detail(LiveStatus()))
        assertEquals("Key saved, not tested yet", detail(LiveStatus(apiKeyPresent = true)))
        assertEquals("Key saved and verified", detail(LiveStatus(apiKeyPresent = true, lastApiCheck = ApiCheckRecord(true, 1L))))
        assertEquals("Key saved, last test failed", detail(LiveStatus(apiKeyPresent = true, lastApiCheck = ApiCheckRecord(false, 1L))))
    }

    @Test
    fun appControlNeedsAccessibilityAndAnInstalledApp() {
        fun status(live: LiveStatus) = SetupSummary.build(live, OnboardingProgress(), sdk34).first { it.step == OnboardingStep.APP_CONTROL }.status
        val apps = mapOf("whatsapp" to true, AppControlCatalog.DEVICE_ID to true)
        assertEquals(SetupStatus.OPTIONAL, status(LiveStatus(accessibilityEnabled = false, installedApps = apps)))
        assertEquals(SetupStatus.COMPLETED, status(LiveStatus(accessibilityEnabled = true, installedApps = apps)))
        assertEquals(
            SetupStatus.OPTIONAL,
            status(LiveStatus(accessibilityEnabled = true, installedApps = mapOf(AppControlCatalog.DEVICE_ID to true)))
        )
    }

    @Test
    fun permissionsRowIsCompletedOnlyWhenEssentialsAreGranted() {
        val none = SetupSummary.build(LiveStatus(), OnboardingProgress(), sdk34).first { it.step == OnboardingStep.PERMISSIONS }
        assertEquals(SetupStatus.NEEDS_ATTENTION, none.status)
        val all = SetupSummary.build(LiveStatus(permissionGranted = allGranted()), OnboardingProgress(), sdk34)
            .first { it.step == OnboardingStep.PERMISSIONS }
        assertEquals(SetupStatus.COMPLETED, all.status)
    }
}
