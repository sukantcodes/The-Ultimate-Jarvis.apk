package com.jarvis.assistant.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCatalogTest {

    @Test
    fun grantedWinsOverEverything() {
        assertEquals(PermissionUiState.GRANTED, PermissionRules.derive(true, askedBefore = false, shouldShowRationale = false))
        assertEquals(PermissionUiState.GRANTED, PermissionRules.derive(true, askedBefore = true, shouldShowRationale = true))
    }

    @Test
    fun neverAskedIsNotAskedEvenWithoutRationale() {
        assertEquals(PermissionUiState.NOT_ASKED, PermissionRules.derive(false, askedBefore = false, shouldShowRationale = false))
    }

    @Test
    fun deniedOnceCanStillBeAskedAgain() {
        assertEquals(PermissionUiState.DENIED, PermissionRules.derive(false, askedBefore = true, shouldShowRationale = true))
    }

    @Test
    fun askedBeforeWithoutRationaleIsPermanentlyBlocked() {
        assertEquals(PermissionUiState.BLOCKED, PermissionRules.derive(false, askedBefore = true, shouldShowRationale = false))
    }

    @Test
    fun notificationsOnlyExistFromAndroid13() {
        assertFalse(PermissionCatalog.forSdk(32).any { it.key == PermissionKey.NOTIFICATIONS })
        assertTrue(PermissionCatalog.forSdk(33).any { it.key == PermissionKey.NOTIFICATIONS })
    }

    @Test
    fun mediaPermissionDependsOnSdk() {
        fun media(sdk: Int) = PermissionCatalog.forSdk(sdk).first { it.key == PermissionKey.MEDIA }
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), media(30).request)
        assertTrue("android.permission.READ_MEDIA_IMAGES" in media(33).request)
        assertTrue("android.permission.READ_MEDIA_VISUAL_USER_SELECTED" in media(34).request)
        assertFalse("android.permission.READ_MEDIA_VISUAL_USER_SELECTED" in media(33).request)
    }

    @Test
    fun everySpecRequestsSomethingAndExplainsItself() {
        for (sdk in listOf(26, 30, 33, 34)) {
            for (spec in PermissionCatalog.forSdk(sdk)) {
                assertTrue(spec.title, spec.request.isNotEmpty())
                assertTrue(spec.title, spec.grantedIfAny.isNotEmpty())
                assertTrue(spec.title, spec.request.containsAll(spec.grantedIfAny.filter { it in spec.request }))
                assertTrue(spec.title, spec.why.isNotBlank() && spec.unlocks.isNotEmpty())
            }
        }
    }

    @Test
    fun onlyMicrophoneAndNotificationsAreEssential() {
        val core = PermissionCatalog.forSdk(34).filter { it.core }.map { it.key }.toSet()
        assertEquals(setOf(PermissionKey.MICROPHONE, PermissionKey.NOTIFICATIONS), core)
    }

    @Test
    fun keysAreUniquePerSdk() {
        val keys = PermissionCatalog.forSdk(34).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
