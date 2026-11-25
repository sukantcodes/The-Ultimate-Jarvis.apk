package com.jarvis.assistant.study

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import com.jarvis.assistant.provider.ChatConfigSource
import com.jarvis.assistant.provider.ChatResolution
import com.jarvis.assistant.provider.FakeTransport
import com.jarvis.assistant.provider.Fixtures
import com.jarvis.assistant.provider.ProviderProtocol
import com.jarvis.assistant.provider.TransportException
import com.jarvis.assistant.provider.TransportFailure
import com.jarvis.assistant.provider.WorkCategory
import com.jarvis.assistant.study.ai.StudyAIEngine
import com.jarvis.assistant.study.data.StudyBoardDatabase
import com.jarvis.assistant.study.data.StudyHistoryManager
import com.jarvis.assistant.study.export.StudyExportManager
import com.jarvis.assistant.study.input.StudyInputManager
import com.jarvis.assistant.study.model.DiagramElement
import com.jarvis.assistant.study.model.EducationLevel
import com.jarvis.assistant.study.model.ExplanationDepth
import com.jarvis.assistant.study.model.StudyDiagramSpec
import com.jarvis.assistant.study.model.StudyDoubtEntry
import com.jarvis.assistant.study.model.StudyLanguage
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.study.model.StudyStreamEvent
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.model.TeacherProfile
import com.jarvis.assistant.study.model.VerificationStatus
import com.jarvis.assistant.study.ui.StudyBoardViewModel
import com.jarvis.assistant.study.voice.StudySpeechSynchronizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyEndToEndIntegrationTest {

    private lateinit var app: Application
    private lateinit var db: StudyBoardDatabase
    private lateinit var historyManager: StudyHistoryManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, StudyBoardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = app.getSharedPreferences("study_test_prefs_${System.nanoTime()}", Context.MODE_PRIVATE)
        historyManager = StudyHistoryManager(db.studySessionDao(), prefs)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun roomHistoryOrganizesBySubjectSearchesBookmarksAndResumesInterruptedSessions(): Unit = runBlocking {
        val bioSol = StudySolution(
            id = "bio-1",
            subject = StudySubject.BIOLOGY,
            topic = "Plant Cell",
            educationLevel = EducationLevel.CLASS_9_10,
            question = "Describe chloroplasts in plant cells",
            steps = listOf(
                StudyStep(0, "Concept", "Plastids with chlorophyll", isBookmarked = true)
            ),
            finalAnswer = "Site of photosynthesis"
        )
        val chemSol = StudySolution(
            id = "chem-1",
            subject = StudySubject.CHEMISTRY,
            topic = "Combustion",
            question = "Balance CH4 + O2 -> CO2 + H2O",
            steps = listOf(
                StudyStep(0, "Balanced", "CH4 + 2O2 -> CO2 + 2H2O", isBookmarked = false),
                StudyStep(1, "Check", "C:1, H:4, O:4 on both sides", isBookmarked = false)
            ),
            finalAnswer = "CH4 + 2O2 -> CO2 + 2H2O"
        )

        historyManager.saveSession(bioSol, lastStepIndex = 0, isCompleted = true, markSaved = true)
        historyManager.saveSession(chemSol, lastStepIndex = 1, isCompleted = false, markSaved = true)

        val chemResults = historyManager.searchSessions("", StudySubject.CHEMISTRY, bookmarkedOnly = false)
        assertEquals(1, chemResults.size)
        assertEquals("chem-1", chemResults.first().id)

        val bookmarked = historyManager.searchSessions("", subject = null, bookmarkedOnly = true)
        assertEquals(1, bookmarked.size)
        assertEquals("bio-1", bookmarked.first().id)

        val searchHit = historyManager.searchSessions("photosynthesis", subject = null, bookmarkedOnly = false)
        assertEquals(1, searchHit.size)
        assertEquals("bio-1", searchHit.first().id)

        val recoverable = historyManager.loadLastRecoverableSession()
        assertNotNull(recoverable)
        assertEquals("chem-1", recoverable!!.first.id)
        assertEquals(1, recoverable.second.lastStepIndex)

        val enriched = historyManager.loadEnrichedProfile()
        assertEquals(1, enriched.completedSessionsCount)
        assertTrue(enriched.recentTopics.any { it.contains("Plant Cell") || it.contains("Combustion") })
        Unit
    }

    @Test
    fun aiEngineStreamsMultiSubjectSolutionHandlesDoubtAndNetworkInterruption(): Unit = runBlocking {
        val transport = FakeTransport()
        val provider = Fixtures.resolved(protocol = ProviderProtocol.OPENAI_COMPATIBLE)
        val configSource = object : ChatConfigSource {
            override fun resolveChat(): ChatResolution = ChatResolution.Ready(provider)
            override fun resolveChat(category: WorkCategory): ChatResolution = ChatResolution.Ready(provider)
        }

        val ndjsonLines = listOf(
            """{"event":"meta","subject":"Physics","topic":"Ohm Law","question":"V = 12 V, R = 4 ohm. Find I.","given":["V = 12 V","R = 4 ohm"],"required":"Current I","formulas":["I = V / R"]}""",
            """{"event":"step","label":"Substitution","boardText":"I = 12 V / 4 ohm = 3 A","mathExpressions":["I = \\frac{12}{4} = 3\\text{ A}"],"explanation":"Divide potential difference by resistance.","diagram":{"diagramType":"circuit","title":"Simple Circuit","elements":[{"type":"rect","x":30,"y":30,"w":40,"h":20,"label":"R = 4 ohm"}]}}""",
            """{"event":"final","final_answer":"I = 3 A","verification":"verified"}"""
        ).joinToString("\n") + "\n"

        val chunk1 = ndjsonLines.substring(0, ndjsonLines.length / 2)
        val chunk2 = ndjsonLines.substring(ndjsonLines.length / 2)
        transport.stream(
            FakeTransport.StreamRule(
                matches = { true },
                lines = Fixtures.sse(chunk1, chunk2)
            )
        )

        val engine = StudyAIEngine(
            configSource = configSource,
            transport = transport,
            keyRotation = null,
            builtInGeminiKeyProvider = { "" },
            isOnlineProvider = { true }
        )

        val events = engine.streamSolution(
            question = "Find current for V = 12 V and R = 4 ohm",
            profile = TeacherProfile(EducationLevel.CLASS_10_OR_11_FIX(), StudyLanguage.ENGLISH, ExplanationDepth.DETAILED)
        ).toList()

        assertTrue(events.any { it is StudyStreamEvent.Metadata })
        assertTrue(events.any { it is StudyStreamEvent.StepValidated })
        val completed = events.filterIsInstance<StudyStreamEvent.Completed>().firstOrNull()?.solution
        assertNotNull(completed)
        assertEquals(StudySubject.PHYSICS, completed!!.subject)
        assertEquals("I = 3 A", completed.finalAnswer)

        // Follow-up Doubt Mode
        transport.onUrl("chat/completions", 200, Fixtures.completion(
            """{"boardSummary":"By Ohm's Law, I is directly proportional to V","explanation":"Keeping resistance constant, I equals voltage over resistance.","mathExpressions":["V = I \\times R"]}"""
        ))
        val doubtRes = engine.askDoubt(completed, completed.steps.first(), "Why divide by R?", TeacherProfile())
        assertTrue(doubtRes is StudyAIEngine.DoubtResult.Answered)

        // Simulated Network Drop -> emits clean Error instead of crashing
        transport.clearStreams()
        transport.stream(
            FakeTransport.StreamRule(
                matches = { true },
                lines = emptyList(),
                failWith = TransportException(TransportFailure.UNREACHABLE, "Connection reset by peer")
            )
        )
        val dropEvents = engine.streamSolution("Balance Fe + H2O", TeacherProfile()).toList()
        assertTrue(dropEvents.any { it is StudyStreamEvent.Error })
        Unit
    }

    @Test
    fun exportManagerGeneratesValidMultiPagePdfWithDiagramsAndDoubts() {
        runBlocking {
            val solution = StudySolution(
                subject = StudySubject.MATHEMATICS,
                topic = "Quadratic Equation",
                educationLevel = EducationLevel.CLASS_9_10,
                question = "Solve x^2 - 5x + 6 = 0",
                givenValues = listOf("a = 1, b = -5, c = 6"),
                formulas = listOf("x = (-b ± √(b² - 4ac)) / 2a"),
                steps = (0..5).map { idx ->
                    StudyStep(
                        stepIndex = idx,
                        label = "Step ${idx + 1}",
                        boardText = "Work row ${idx + 1}: factorize expression step ${idx + 1}.",
                        mathExpressions = listOf("\\frac{5 \\pm \\sqrt{25 - 24}}{2}"),
                        explanation = "Detailed algebraic reasoning for step ${idx + 1}.",
                        diagram = if (idx == 5) {
                            StudyDiagramSpec(
                                "graph",
                                "Parabola",
                                listOf(
                                    DiagramElement.Axis(),
                                    DiagramElement.Curve(listOf(20f to 30f, 50f to 80f, 80f to 30f), "y = x^2 - 5x + 6")
                                )
                            )
                        } else null
                    )
                },
                finalAnswer = "x = 3 or x = 2",
                verificationStatus = VerificationStatus.VERIFIED,
                doubts = listOf(
                    StudyDoubtEntry(
                        targetStepIndex = 0,
                        targetFocus = "Step 1",
                        question = "Why is a = 1?",
                        boardSummary = "Coefficient of x^2 is implicitly 1.",
                        explanation = "When no number precedes x^2, its coefficient is 1."
                    )
                )
            )

            val result = StudyExportManager.exportSolutionPdf(app, solution)
            assertTrue("PDF export failed: ${result.exceptionOrNull()}", result.isSuccess)
            val artifact = result.getOrThrow()
            assertTrue("Expected multi-page pagination (>=2 pages), got ${artifact.pageCount}", artifact.pageCount >= 2)
            val pdfFile = artifact.cacheFile
            assertTrue(pdfFile.exists())
            val bytes = pdfFile.readBytes()
            // In Robolectric JVM, ShadowPdfDocument.writeTo is a no-op stub (0 bytes) unless native Skia PDF is active
            if (bytes.isNotEmpty()) {
                assertTrue(bytes.size > 800)
                assertEquals("%PDF-", String(bytes, 0, 5))
            }
            pdfFile.delete()
        }
    }

    @Test
    fun viewModelGuardsDuplicateRequestsAndRecoversAcrossRecreation(): Unit = runBlocking {
        val transport = FakeTransport()
        val provider = Fixtures.resolved(protocol = ProviderProtocol.OPENAI_COMPATIBLE)
        val configSource = object : ChatConfigSource {
            override fun resolveChat(): ChatResolution = ChatResolution.Ready(provider)
            override fun resolveChat(category: WorkCategory): ChatResolution = ChatResolution.Ready(provider)
        }
        val streamBody = listOf(
            """{"event":"meta","subject":"English","topic":"Active & Passive","question":"Change to passive: She wrote a letter."}""",
            """{"event":"step","label":"Rule","boardText":"Object becomes subject: A letter","explanation":"Move object to front."}""",
            """{"event":"step","label":"Corrected Sentence","boardText":"A letter was written by her.","explanation":"Past simple passive uses was + past participle."}""",
            """{"event":"final","final_answer":"A letter was written by her.","verification":"verified"}"""
        ).joinToString("\n") + "\n"

        transport.stream(
            FakeTransport.StreamRule(
                matches = { true },
                lines = Fixtures.sse(streamBody),
                delayEachMs = 40L
            )
        )

        suspend fun pumpUntil(timeoutMs: Long = 4000L, predicate: () -> Boolean) {
            val start = System.currentTimeMillis()
            while (!predicate() && System.currentTimeMillis() - start < timeoutMs) {
                org.robolectric.shadows.ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(25))
                delay(25L)
            }
        }

        val engine = StudyAIEngine(configSource, transport, null, { "" }, isOnlineProvider = { true })
        val vm1 = StudyBoardViewModel.createForTest(
            application = app,
            savedStateHandle = SavedStateHandle(),
            aiEngine = engine,
            historyManager = historyManager,
            inputManager = StudyInputManager(app, engine),
            speechSync = StudySpeechSynchronizer(app)
        )

        // Duplicate identical submission while streaming must only trigger ONE transport request!
        vm1.submitQuestion("Change to passive: She wrote a letter.")
        vm1.submitQuestion("  change to passive: she wrote a letter.  ")
        pumpUntil { vm1.animationEngine.getSolution()?.finalAnswer?.isNotBlank() == true && !vm1.uiState.value.isGenerating }
        assertEquals(1, transport.requests.size)

        // Save progress and simulate Activity/ViewModel recreation restoring from Room DB
        vm1.saveProgressOnBackground()
        pumpUntil(400L) { false }

        val vm2 = StudyBoardViewModel.createForTest(
            application = app,
            savedStateHandle = SavedStateHandle(),
            aiEngine = engine,
            historyManager = historyManager,
            inputManager = StudyInputManager(app, engine),
            speechSync = StudySpeechSynchronizer(app)
        )
        pumpUntil { vm2.animationEngine.getSolution() != null }
        val restored = vm2.animationEngine.getSolution()
        assertNotNull(restored)
        assertEquals(StudySubject.ENGLISH, restored!!.subject)
        assertEquals("A letter was written by her.", restored.finalAnswer)
    }

    private fun EducationLevel.Companion.CLASS_10_OR_11_FIX() = EducationLevel.CLASS_11_12
}
