package com.jarvis.assistant.background

import com.jarvis.assistant.util.MultilingualCommandHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectCodeAnalyzerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testAnalyzeExtractsHtmlComponentsAndCssAnimations() {
        val dir = tempFolder.newFolder("cyber_game")

        val html = """
            <!doctype html>
            <html>
            <head><link rel="stylesheet" href="style.css"/></head>
            <body>
              <div class="hud"><span id="score">0</span></div>
              <canvas id="gameCanvas" width="400" height="400"></canvas>
              <div class="touch-controls"><button id="btnUp" class="d-pad">▲</button></div>
              <button id="btnSound">Sound</button>
            </body>
            </html>
        """.trimIndent()

        val css = """
            @keyframes pulseGlow { 0% { opacity: 0.8; } 100% { opacity: 1; } }
            @keyframes shockwave { from { transform: scale(0.9); } to { transform: scale(1.1); } }
            .hud { display: flex; backdrop-filter: blur(8px); box-shadow: 0 0 15px #00E5FF; }
            .touch-controls { display: grid; }
            @media (max-width: 600px) { canvas { width: 100%; } }
        """.trimIndent()

        val js = """
            const audioCtx = new (window.AudioContext || window.webkitAudioContext)();
            function init() { requestAnimationFrame(loop); }
            function loop() {
                updatePhysics();
                checkCollision();
                requestAnimationFrame(loop);
            }
            function checkCollision() {}
            function updatePhysics() {}
            window.addEventListener('keydown', (e) => {});
            window.addEventListener('touchstart', (e) => {});
            localStorage.setItem('highScore', 100);
        """.trimIndent()

        File(dir, "index.html").writeText(html)
        File(dir, "style.css").writeText(css)
        File(dir, "script.js").writeText(js)

        val analysis = ProjectCodeAnalyzer.analyze(dir, fallbackTitle = "Cyber Game", fallbackType = "game")

        assertTrue(analysis.isGame)
        assertTrue(analysis.hasTouchControls)
        assertTrue(analysis.hasAudio)
        assertTrue(analysis.hasPersistence)
        assertTrue(analysis.cssAnimations.contains("pulseGlow"))
        assertTrue(analysis.cssAnimations.contains("shockwave"))
        assertTrue(analysis.htmlComponents.contains("Interactive 2D Game Canvas"))
        assertTrue(analysis.htmlComponents.contains("On-Screen Touch D-Pad Controls"))
        assertTrue(analysis.jsMechanics.contains("60 FPS Smooth Game Loop"))
        assertTrue(analysis.jsMechanics.contains("Real-Time Bounding Box Collision Engine"))
        assertTrue(analysis.jsMechanics.contains("Web Audio Synthesizer (Dynamic Sound FX)"))
    }

    @Test
    fun testGenerateFilePurposeProvidesSmartFileRoles() {
        val htmlContent = "<canvas id='gameCanvas'></canvas><div class='touch-controls'></div>"
        val htmlPurposeEn = ProjectCodeAnalyzer.generateFilePurpose("index.html", htmlContent, isNew = true, language = "English")
        assertTrue(htmlPurposeEn.contains("index.html", ignoreCase = true))
        assertTrue(htmlPurposeEn.contains("interactive game canvas", ignoreCase = true))

        val htmlPurposeHi = ProjectCodeAnalyzer.generateFilePurpose("index.html", htmlContent, isNew = true, language = "Hindi")
        assertTrue(htmlPurposeHi.contains("index.html", ignoreCase = true))
        assertTrue(htmlPurposeHi.contains("game canvas", ignoreCase = true))

        val cssContent = "@keyframes neonPulse { 0% {} 100% {} } .hero { display: flex; }"
        val cssPurposeEn = ProjectCodeAnalyzer.generateFilePurpose("style.css", cssContent, isNew = true, language = "English")
        assertTrue(cssPurposeEn.contains("style.css", ignoreCase = true))
        assertTrue(cssPurposeEn.contains("neonPulse", ignoreCase = true))

        val jsContent = "requestAnimationFrame(tick); const ctx = new AudioContext(); checkCollision();"
        val jsPurposeEn = ProjectCodeAnalyzer.generateFilePurpose("script.js", jsContent, isNew = true, language = "English")
        assertTrue(jsPurposeEn.contains("script.js", ignoreCase = true))
        assertTrue(jsPurposeEn.contains("60fps physics loop", ignoreCase = true))
        assertTrue(jsPurposeEn.contains("Web Audio sound FX", ignoreCase = true))
    }

    @Test
    fun testGenerateExplanationProducesBespokeNonStaticSummary() = runBlocking {
        val dir = tempFolder.newFolder("neon_portal")

        val html = """
            <!doctype html>
            <html><body>
            <header class="hero"><h1>Portal</h1></header>
            <nav class="nav"><a href="#">Home</a></nav>
            <div class="card">Feature</div>
            </body></html>
        """.trimIndent()

        val css = """
            @keyframes floatingHero { from { transform: translateY(0); } to { transform: translateY(-10px); } }
            body { display: flex; }
        """.trimIndent()

        val js = """
            function initializeApp() { console.log("Initialized"); }
            window.addEventListener('DOMContentLoaded', initializeApp);
        """.trimIndent()

        File(dir, "index.html").writeText(html)
        File(dir, "style.css").writeText(css)
        File(dir, "script.js").writeText(js)

        val explanation = ProjectCodeAnalyzer.generateExplanation(
            dir = dir,
            topic = "Neon Portal",
            type = "website",
            url = "http://127.0.0.1:8080/",
            language = "English"
        )

        assertTrue(explanation.contains("Neon Portal", ignoreCase = true))
        assertTrue(explanation.contains("website", ignoreCase = true))
        assertTrue(explanation.contains("floatingHero", ignoreCase = true))
        assertTrue(explanation.contains("Hero Showcase Section", ignoreCase = true) || explanation.contains("Responsive Navigation Header", ignoreCase = true))
        assertTrue(explanation.contains("live", ignoreCase = true))
    }

    @Test
    fun testFilesCheckingAndServerCreatedLocalization() {
        val checkingEn = MultilingualCommandHelper.getLocalizedFilesChecking("English")
        assertTrue(checkingEn.contains("checked", ignoreCase = true))
        assertTrue(checkingEn.contains("previewed soon", ignoreCase = true))

        val checkingHi = MultilingualCommandHelper.getLocalizedFilesChecking("Hindi")
        assertTrue(checkingHi.contains("checking", ignoreCase = true))
        assertTrue(checkingHi.contains("preview", ignoreCase = true))

        val serverEn = MultilingualCommandHelper.getLocalizedServerCreatedAndPreview("http://127.0.0.1:8080/", "English")
        assertTrue(serverEn.contains("Server successfully created", ignoreCase = true))
        assertTrue(serverEn.contains("preview", ignoreCase = true))

        val serverHi = MultilingualCommandHelper.getLocalizedServerCreatedAndPreview("http://127.0.0.1:8080/", "Hindi")
        assertTrue(serverHi.contains("Server successfully create", ignoreCase = true))
        assertTrue(serverHi.contains("preview", ignoreCase = true))
    }

    @Test
    fun testBuildStartingPhramingIncludesWorkStartedAndCompletionMinutes() {
        val startEn = MultilingualCommandHelper.getLocalizedBuildStarting("Retro Arcade", "High Detail", 5, "English")
        assertTrue(startEn.contains("Your work has started", ignoreCase = true))
        assertTrue(startEn.contains("5 minutes", ignoreCase = true))
        assertTrue(startEn.contains("Retro Arcade", ignoreCase = true))

        val startHi = MultilingualCommandHelper.getLocalizedBuildStarting("Snake Game", "High Detail", 5, "Hindi")
        assertTrue(startHi.contains("kaam shuru ho gaya", ignoreCase = true))
        assertTrue(startHi.contains("5 minute", ignoreCase = true))
        assertTrue(startHi.contains("Snake Game", ignoreCase = true))
    }
}
