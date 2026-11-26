package com.jarvis.assistant.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsSceneTest {

    private fun repoFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.first { it.isFile }
    }

    @Test
    fun allSceneKeysAreUniqueAndResolvable() {
        val scenes = SettingsScene.values()
        val keys = scenes.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        for (scene in scenes) {
            assertTrue(scene.key.isNotBlank())
            assertTrue(scene.label.isNotBlank())
            assertTrue(scene.blurb.isNotBlank())
            assertEquals(scene, SettingsScene.fromKey(scene.key))
        }
        assertNull(SettingsScene.fromKey("auto"))
        assertNull(SettingsScene.fromKey("nonexistent_scene"))
    }

    @Test
    fun jarvisPremiumAnimatedScenesAreRegistered() {
        val expected = listOf(
            "arc_reactor",
            "neural_pulse",
            "quantum_orbit",
            "cyber_matrix",
            "solar_corona",
            "holo_helix"
        )
        for (key in expected) {
            assertNotNull("Missing JARVIS animated scene: $key", SettingsScene.fromKey(key))
        }
    }

    @Test
    fun studyBoardDrawerOptionIsWiredAcrossLayoutMenuDeckAndMainActivity() {
        val layout = repoFile("src/main/res/layout/drawer_item_study_board.xml").readText()
        assertTrue(layout.contains("android:id=\"@+id/drawerStudyBoard\""))
        assertTrue(layout.contains("@drawable/ic_study_board"))

        val menu = repoFile("src/main/java/com/jarvis/assistant/ui/home/DrawerMenu.kt").readText()
        assertTrue(menu.contains("R.id.drawerStudyBoard"))
        assertTrue(menu.contains("R.layout.drawer_item_study_board"))

        val deck = repoFile("src/main/java/com/jarvis/assistant/ui/home/DrawerDeck.kt").readText()
        assertTrue(deck.contains("R.id.drawerStudyBoard"))

        val main = repoFile("src/main/java/com/jarvis/assistant/ui/home/MainActivity.kt").readText()
        assertTrue(main.contains("R.id.drawerStudyBoard"))
        assertTrue(main.contains("StudyBoardActivity"))
    }
}
