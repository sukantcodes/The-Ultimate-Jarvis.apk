package com.jarvis.assistant.tools.appcontrol

import com.jarvis.assistant.tools.Mode
import com.jarvis.assistant.tools.MapSettings
import com.jarvis.assistant.tools.PermissionPolicy
import com.jarvis.assistant.tools.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppNavigatorTest {
    private val manifest: String by lazy {
        val candidates = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        candidates.first { it.isFile }.readText()
    }

    @Test fun everyScreenIdIsUniqueAndFindable() {
        val ids = AppNavigator.destinations.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotNull(AppNavigator.find(it)) }
        assertNotNull(AppNavigator.find("API-Connection"))
        assertNull(AppNavigator.find("not_a_screen"))
    }

    @Test fun everyScreenIsDeclaredInTheManifest() {
        AppNavigator.destinations.forEach { d ->
            val short = "." + d.className.removePrefix("com.jarvis.assistant.")
            assertTrue("${d.id} -> $short is missing from AndroidManifest.xml", manifest.contains("android:name=\"$short\""))
        }
    }

    @Test fun protectedScreensAreNotListed() {
        val names = AppNavigator.destinations.map { it.className }
        assertTrue(names.none { it.contains("Owner") })
        assertTrue(names.none { it.contains("AlarmRing") })
    }

    @Test fun onlyTheProviderEditorTakesAnArgument() {
        val withArg = AppNavigator.destinations.filter { it.argumentExtra != null }.map { it.id }
        assertEquals(listOf("provider_editor"), withArg)
    }

    @Test fun appControlWritesDoNotAskButDestructiveOnesStillDo() {
        val settings = MapSettings()
        val policy = PermissionPolicy(settings)
        assertEquals(Mode.ALLOW, policy.modeFor(Risk.WRITE, PermissionPolicy.APP_CONTROL_GROUP))
        assertEquals(Mode.ASK, policy.modeFor(Risk.DESTRUCTIVE, PermissionPolicy.APP_CONTROL_GROUP))
        assertEquals(Mode.ALLOW, policy.modeFor(Risk.WRITE, PermissionPolicy.MAPS_GROUP))
        assertEquals(Mode.ASK, policy.modeFor(Risk.DESTRUCTIVE, PermissionPolicy.MAPS_GROUP))
        // Other groups keep their normal rule.
        assertEquals(Mode.ASK, policy.modeFor(Risk.WRITE, "files"))
    }

    @Test fun theUserCanStillTurnAppControlWritesBackToAsk() {
        val settings = MapSettings()
        val policy = PermissionPolicy(settings)
        policy.setMode(Risk.WRITE, Mode.ASK, PermissionPolicy.APP_CONTROL_GROUP)
        assertEquals(Mode.ASK, policy.modeFor(Risk.WRITE, PermissionPolicy.APP_CONTROL_GROUP))
        policy.setMode(Risk.WRITE, Mode.DENY, PermissionPolicy.APP_CONTROL_GROUP)
        assertEquals(Mode.DENY, policy.modeFor(Risk.WRITE, PermissionPolicy.APP_CONTROL_GROUP))
    }
}
