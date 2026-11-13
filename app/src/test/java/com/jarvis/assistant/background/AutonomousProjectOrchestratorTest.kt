package com.jarvis.assistant.background

import com.jarvis.assistant.tools.server.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutonomousProjectOrchestratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testInitialStageIsIdle() {
        assertEquals(AutonomousProjectOrchestrator.Stage.IDLE, AutonomousProjectOrchestrator.getStage())
        assertFalse(AutonomousProjectOrchestrator.isBuilding())
        assertFalse(AutonomousProjectOrchestrator.isProjectWorkflowActive())
    }

    @Test
    fun testGeneratedProjectPassesBuilderValidation() {
        val testProjectDir = tempFolder.newFolder("test_website")

        // Reflectively invoke or test generateHighDetailHtml, Css, Js
        val orchestratorClass = AutonomousProjectOrchestrator::class.java
        val genHtml = orchestratorClass.getDeclaredMethod("generateHighDetailHtml", String::class.java, String::class.java)
        genHtml.isAccessible = true
        val html = genHtml.invoke(AutonomousProjectOrchestrator, "JARVIS Cyber Dashboard", "website") as String

        val genCss = orchestratorClass.getDeclaredMethod("generateHighDetailCss", String::class.java)
        genCss.isAccessible = true
        val css = genCss.invoke(AutonomousProjectOrchestrator, "website") as String

        val genJs = orchestratorClass.getDeclaredMethod("generateHighDetailJs", String::class.java, String::class.java)
        genJs.isAccessible = true
        val js = genJs.invoke(AutonomousProjectOrchestrator, "JARVIS Cyber Dashboard", "website") as String

        // Write files to project directory
        File(testProjectDir, "index.html").writeText(html)
        File(testProjectDir, "style.css").writeText(css)
        File(testProjectDir, "script.js").writeText(js)

        // Validate with ProjectBuilder
        val report = ProjectBuilder.check(testProjectDir, nodeAvailable = false)
        assertTrue("Project check problems: ${report.problems}", report.ok)
        assertEquals(0, report.problems.size)
    }

    @Test
    fun testGeneratedGamePassesBuilderValidation() {
        val testGameDir = tempFolder.newFolder("test_game")

        val orchestratorClass = AutonomousProjectOrchestrator::class.java
        val genHtml = orchestratorClass.getDeclaredMethod("generateHighDetailHtml", String::class.java, String::class.java)
        genHtml.isAccessible = true
        val html = genHtml.invoke(AutonomousProjectOrchestrator, "Cyberpunk Snake", "game") as String

        val genCss = orchestratorClass.getDeclaredMethod("generateHighDetailCss", String::class.java)
        genCss.isAccessible = true
        val css = genCss.invoke(AutonomousProjectOrchestrator, "game") as String

        val genJs = orchestratorClass.getDeclaredMethod("generateHighDetailJs", String::class.java, String::class.java)
        genJs.isAccessible = true
        val js = genJs.invoke(AutonomousProjectOrchestrator, "Cyberpunk Snake", "game") as String

        File(testGameDir, "index.html").writeText(html)
        File(testGameDir, "style.css").writeText(css)
        File(testGameDir, "script.js").writeText(js)

        val report = ProjectBuilder.check(testGameDir, nodeAvailable = false)
        assertTrue("Game check problems: ${report.problems}", report.ok)
        assertEquals(0, report.problems.size)
    }
}
