package com.jarvis.assistant.background

import com.jarvis.assistant.util.MultilingualCommandHelper
import com.jarvis.assistant.util.PromptGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end verification of the Project Build Orchestration Pipeline.
 *
 * Verifies:
 * 1. System prompt explicitly instructs Gemini Live to execute build_website_or_game.
 * 2. If no custom provider is active, ask: 'No custom model is active. Should I create the website myself, or will you add a custom provider?'
 * 3. If user says 'You create' / 'tum banao' / 'I will create', select Gemini directly.
 * 4. Project creation requests are recognized accurately.
 * 5. High detail and duration pacing are properly parsed and handled.
 * 6. In-depth code-analyzed explanation is generated in the started language without generic placeholder text.
 */
class ProjectBuildOrchestrationPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSystemPromptInstructsGeminiLiveToExecuteBuildWebsiteOrGame() {
        val prompt = PromptGenerator.generateSystemPrompt(
            personality = "jarvis_pro",
            userName = "Sir",
            language = "English"
        )

        // 1. Must explicitly mandate build_website_or_game tool execution
        assertTrue("Prompt must contain build_website_or_game tool call mandate",
            prompt.contains("build_website_or_game", ignoreCase = true))

        // 2. Must prohibit empty chatter and conversational delays
        assertTrue("Prompt must prohibit empty conversational chatter",
            prompt.contains("STRICT PROHIBITION ON CHATTER", ignoreCase = true) ||
            prompt.contains("DO NOT give empty conversational chatter", ignoreCase = true))

        // 3. Must cover both websites and browser games
        assertTrue("Prompt must cover website and browser game creation",
            prompt.contains("website", ignoreCase = true) && prompt.contains("game", ignoreCase = true))
    }

    @Test
    fun testNoCustomModelActiveQuestionPrompt() {
        val askProviderEn = MultilingualCommandHelper.getLocalizedAskProviderChoice("English")
        assertEquals(
            "No custom model is active. Should I create the website myself, or will you add a custom provider?",
            askProviderEn
        )

        val askProviderHi = MultilingualCommandHelper.getLocalizedAskProviderChoice("Hindi")
        assertTrue(askProviderHi.contains("custom provider", ignoreCase = true))
        assertTrue(askProviderHi.contains("kya main khud", ignoreCase = true))

        val askProviderBn = MultilingualCommandHelper.getLocalizedAskProviderChoice("Bengali")
        assertTrue(askProviderBn.contains("কাস্টম", ignoreCase = true))
        assertTrue(askProviderBn.contains("ওয়েবসাইট", ignoreCase = true))
    }

    @Test
    fun testUserSelectsGeminiDirectlyWithVoicePhrases() {
        // Requirement 3: "You create" / "tum banao" / "I will create"
        assertTrue(MultilingualCommandHelper.isUserChoosesJarvisDirectly("You create"))
        assertTrue(MultilingualCommandHelper.isUserChoosesJarvisDirectly("you create"))
        assertTrue(MultilingualCommandHelper.isUserChoosesJarvisDirectly("tum banao"))
        assertTrue(MultilingualCommandHelper.isUserChoosesJarvisDirectly("tum banao website"))
        assertTrue(MultilingualCommandHelper.isUserChoosesJarvisDirectly("I will create"))
        assertTrue(MultilingualCommandHelper.isUserChoosesJarvisDirectly("i will create"))
        assertTrue(MultilingualCommandHelper.isUserChoosesJarvisDirectly("aap banao"))
        assertTrue(MultilingualCommandHelper.isUserChoosesJarvisDirectly("khud bana do"))
        assertTrue(MultilingualCommandHelper.isUserChoosesJarvisDirectly("tu bana"))
        assertTrue(MultilingualCommandHelper.isUserChoosesJarvisDirectly("tu banao"))
        assertTrue(MultilingualCommandHelper.isUserChoosesJarvisDirectly("gemini"))
    }

    @Test
    fun testWebBuildRequestDetection() {
        // English
        assertTrue(WebBuildOrchestrator.isWebBuildRequest("create a website for my portfolio"))
        assertTrue(WebBuildOrchestrator.isWebBuildRequest("build a snake game in 5 minutes"))
        assertTrue(WebBuildOrchestrator.isWebBuildRequest("make a landing page for my bakery"))
        assertTrue(WebBuildOrchestrator.isWebBuildRequest("make a browser game"))

        // Hindi / Hinglish
        assertTrue(WebBuildOrchestrator.isWebBuildRequest("ek website bana do"))
        assertTrue(WebBuildOrchestrator.isWebBuildRequest("mere liye ek game banao"))
        assertTrue(WebBuildOrchestrator.isWebBuildRequest("snake game bana do"))

        // Unrelated speech must not be detected as web build
        assertFalse(WebBuildOrchestrator.isWebBuildRequest("what is the weather today"))
        assertFalse(WebBuildOrchestrator.isWebBuildRequest("open whatsapp and call dad"))
    }

    @Test
    fun testQualityAndTimeParsingAndHighDetailEnforcement() {
        val (q1, m1) = MultilingualCommandHelper.parseQualityAndTime("High detail in 5 minutes")
        assertEquals("High Detail", q1)
        assertEquals(5, m1)

        val (q2, m2) = MultilingualCommandHelper.parseQualityAndTime("standard in 2 minutes")
        assertEquals("Standard", q2)
        assertEquals(2, m2)

        val (q3, m3) = MultilingualCommandHelper.parseQualityAndTime("5 minute mein high quality")
        assertEquals("High Detail", q3)
        assertEquals(5, m3)
    }

    @Test
    fun testProjectCodeAnalyzerExplanationInStartedLanguages() {
        val projectDir = tempFolder.newFolder("test_portfolio")

        // Create sample HTML, CSS, JS files
        File(projectDir, "index.html").writeText("""
            <!DOCTYPE html>
            <html>
            <head><title>Test Portfolio</title><link rel="stylesheet" href="style.css"></head>
            <body>
                <header class="hero"><h1>Developer Showcase</h1></header>
                <nav class="nav-bar"><a href="#projects">Projects</a></nav>
                <canvas id="gameCanvas"></canvas>
            </body>
            </html>
        """.trimIndent())

        File(projectDir, "style.css").writeText("""
            @keyframes pulseGlow { 0% { opacity: 0.8; } 100% { opacity: 1; } }
            .hero { display: flex; backdrop-filter: blur(10px); }
        """.trimIndent())

        File(projectDir, "script.js").writeText("""
            function initGame() {
                const ctx = new (window.AudioContext || window.webkitAudioContext)();
                requestAnimationFrame(loop);
            }
            function loop() {}
        """.trimIndent())

        // 1. English explanation
        val analysis = ProjectCodeAnalyzer.analyze(projectDir, "Portfolio Showcase", "website")
        assertTrue(analysis.htmlComponents.isNotEmpty())
        assertTrue(analysis.cssAnimations.isNotEmpty())
        assertTrue(analysis.jsMechanics.isNotEmpty())

        val synthEn = ProjectCodeAnalyzer::class.java.getDeclaredMethod(
            "synthesizeBespokeExplanation",
            ProjectCodeAnalyzer.ProjectCodeAnalysis::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val expEn = synthEn.invoke(ProjectCodeAnalyzer, analysis, "Portfolio Showcase", "http://127.0.0.1:8080", "English") as String
        assertTrue(expEn.contains("Portfolio Showcase", ignoreCase = true))
        assertTrue(expEn.contains("pulseGlow", ignoreCase = true))
        assertTrue(expEn.contains("Audio", ignoreCase = true) || expEn.contains("physics", ignoreCase = true))

        // 2. Hindi explanation
        val expHi = synthEn.invoke(ProjectCodeAnalyzer, analysis, "Portfolio Showcase", "http://127.0.0.1:8080", "Hindi") as String
        assertTrue(expHi.contains("Portfolio Showcase", ignoreCase = true))
        assertTrue(expHi.contains("local server par live", ignoreCase = true))

        // 3. Bengali explanation
        val expBn = synthEn.invoke(ProjectCodeAnalyzer, analysis, "Portfolio Showcase", "http://127.0.0.1:8080", "Bengali") as String
        assertTrue(expBn.contains("Portfolio Showcase", ignoreCase = true))
        assertTrue(expBn.contains("সার্ভারে", ignoreCase = true))
    }
}
