package com.jarvis.assistant.onboarding

import com.jarvis.assistant.network.ApiKeyCheck
import com.jarvis.assistant.network.ApiKeyValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppControlCatalogTest {

    @Test
    fun everyRequestedAppIsPresent() {
        val ids = AppControlCatalog.apps.map { it.id }
        assertEquals(listOf("whatsapp", "youtube", "blinkit", "rapido", "amazon", AppControlCatalog.DEVICE_ID), ids)
    }

    @Test
    fun everyAppHasOneNotePerWorkflowStage() {
        AppControlCatalog.apps.forEach {
            assertEquals(it.name, WorkflowStages.NAMES.size, it.notes.size)
            assertTrue(it.name, it.notes.all { n -> n.isNotBlank() })
        }
    }

    @Test
    fun workflowHasTheEightRequiredStagesInOrder() {
        assertEquals(
            listOf(
                "Voice command", "Understanding", "App selection", "Navigation",
                "Element identification", "Authorized action", "Result verification", "Voice response"
            ),
            WorkflowStages.NAMES
        )
    }

    @Test
    fun onlyTheDeviceProfileIsPackageless() {
        AppControlCatalog.apps.forEach {
            assertEquals(it.id, it.id == AppControlCatalog.DEVICE_ID, it.alwaysAvailable)
        }
    }

    @Test
    fun everyAppStatesWhatIsUnavailable() {
        AppControlCatalog.apps.forEach { app ->
            assertTrue(app.name, app.capabilities.any { Mechanism.UNAVAILABLE in it.mechanisms })
            assertTrue(app.name, app.capabilities.all { it.mechanisms.isNotEmpty() && it.text.isNotBlank() })
        }
    }

    @Test
    fun appExamplesAreNotEmpty() {
        assertFalse(AppControlCatalog.apps.any { it.example.isBlank() })
    }
}

class ApiKeyDescribeTest {
    @Test
    fun messagesNeverEchoTheKeyAndNameTheOutcome() {
        assertTrue(ApiKeyValidator.describe(ApiKeyCheck.Valid(120)).contains("accepted"))
        assertTrue(ApiKeyValidator.describe(ApiKeyCheck.Rejected(403)).contains("rejected"))
        assertTrue(ApiKeyValidator.describe(ApiKeyCheck.Rejected(429)).contains("rate-limiting"))
        assertTrue(ApiKeyValidator.describe(ApiKeyCheck.Rejected(503)).contains("problem"))
        assertTrue(ApiKeyValidator.describe(ApiKeyCheck.Rejected(418)).contains("418"))
        assertTrue(ApiKeyValidator.describe(ApiKeyCheck.Unreachable(timedOut = true)).contains("timed out"))
        assertTrue(ApiKeyValidator.describe(ApiKeyCheck.Unreachable(timedOut = false)).contains("reach"))
        assertEquals("Paste an API key first.", ApiKeyValidator.describe(ApiKeyCheck.Empty))
    }
}
