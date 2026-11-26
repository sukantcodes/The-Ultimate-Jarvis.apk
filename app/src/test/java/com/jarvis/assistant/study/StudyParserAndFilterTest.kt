package com.jarvis.assistant.study

import com.jarvis.assistant.study.ai.StudyResponseParser
import com.jarvis.assistant.study.ai.StudyResponseValidator
import com.jarvis.assistant.study.ai.StudyStrictFilter
import com.jarvis.assistant.study.model.DiagramElement
import com.jarvis.assistant.study.model.EducationLevel
import com.jarvis.assistant.study.model.StudyDiagramSpec
import com.jarvis.assistant.study.model.StudyDoubtEntry
import com.jarvis.assistant.study.model.StudyJsonCodec
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.model.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class StudyParserAndFilterTest {

    @get:Rule
    val testTimeout: Timeout = Timeout.seconds(30)

    @Test
    fun strictFilterStripsChatbotFillerAndKeepsEducationalContent() {
        val noisy = """
            Sure, I'd be happy to help! Let's solve this step by step:
            2x + 5 = 15
            Wait, let me verify that carefully.
            Subtract 5 from both sides to isolate the variable term.
            Hope this helps! Good luck!
        """.trimIndent()

        val cleaned = StudyStrictFilter.cleanBoardText(noisy)
        assertFalse(cleaned.contains("Sure"))
        assertFalse(cleaned.contains("step by step"))
        assertFalse(cleaned.contains("Wait, let me"))
        assertFalse(cleaned.contains("Hope this helps"))
        assertFalse(cleaned.contains("Good luck"))
        assertTrue(cleaned.contains("2x + 5 = 15"))
        assertTrue(cleaned.contains("Subtract 5 from both sides"))
    }

    @Test
    fun ndjsonParserHandlesSplitNetworkChunksAndMalformedLinesSafely() {
        val parser = StudyResponseParser()
        val line1 = """{"event":"meta","subject":"Mathematics","topic":"Linear Equations","question":"2x + 5 = 15"}"""
        val garbage = """{incomplete malformed garbage"""
        val line2 = """{"event":"step","label":"Step 1","boardText":"Subtract 5: 2x = 10","mathExpressions":["2x = 15 - 5 = 10"],"explanation":"Subtract 5 from both sides."}"""
        val line3 = """{"event":"step","label":"Step 2","boardText":"Divide by 2: x = 5","mathExpressions":["x = 10 / 2 = 5"],"explanation":"Divide both sides by 2."}"""
        val line4 = """{"event":"final","final_answer":"x = 5","verification":"verified"}"""

        val payload = "$line1\n$garbage\n$line2\n$line3\n$line4\n"
        val emitted = ArrayList<StudyResponseParser.ParsedEvent>()
        // Feed arbitrarily sliced chunks (simulating flaky SSE frames)
        var i = 0
        while (i < payload.length) {
            val end = (i + 17).coerceAtMost(payload.length)
            emitted += parser.feed(payload.substring(i, end))
            i = end
        }
        emitted += parser.finish()

        val metaCount = emitted.count { it is StudyResponseParser.ParsedEvent.Meta }
        val steps = emitted.filterIsInstance<StudyResponseParser.ParsedEvent.Step>()
        val finals = emitted.filterIsInstance<StudyResponseParser.ParsedEvent.Final>()

        assertEquals(1, metaCount)
        assertEquals(2, steps.size)
        assertEquals(1, finals.size)
        assertEquals("x = 5", finals.first().finalAnswer)
        assertEquals(VerificationStatus.VERIFIED, finals.first().verificationStatus)
    }

    @Test
    fun monolithicFallbackExtractsStepsIncrementallyWhileStreaming() {
        val parser = StudyResponseParser()
        val part1 = """
            Here is your solution:
            {"subject":"Physics","topic":"Kinematics","steps":[
              {"label":"Given","content":"u = 0 m/s, a = 2 m/s^2, t = 4 s","explanation":"List known quantities."}
        """.trimIndent()
        val part2 = """
              ,{"label":"Formula","content":"v = u + at = 0 + 2(4) = 8 m/s","explanation":"First equation of motion."}
            ],"final_answer":"v = 8 m/s"}
        """.trimIndent()

        val firstEvents = parser.feed(part1)
        assertTrue(firstEvents.any { it is StudyResponseParser.ParsedEvent.Step })

        val secondEvents = parser.feed(part2) + parser.finish()
        val allSteps = (firstEvents + secondEvents).filterIsInstance<StudyResponseParser.ParsedEvent.Step>()
        val finals = (firstEvents + secondEvents).filterIsInstance<StudyResponseParser.ParsedEvent.Final>()

        assertEquals(2, allSteps.size)
        assertEquals(1, finals.size)
        assertEquals("v = 8 m/s", finals.first().finalAnswer)
    }

    @Test
    fun validatorClampsDiagramBoundsAndRejectsIncompleteSolutions() {
        val rawDiagram = StudyDiagramSpec(
            diagramType = "geometry",
            title = "Triangle",
            elements = listOf(
                DiagramElement.Line(-25f, 150f, 120f, 50f, label = "AB"),
                DiagramElement.CircleArc(50f, 50f, 95f)
            )
        )
        val clamped = StudyResponseValidator.validateDiagram(rawDiagram)!!
        val line = clamped.elements[0] as DiagramElement.Line
        val circle = clamped.elements[1] as DiagramElement.CircleArc
        assertTrue(line.x1 >= 2f && line.y1 <= 98f && line.x2 <= 98f)
        assertTrue(circle.radius <= 44f)

        val incomplete = StudySolution(
            question = "Integrate x^2 dx",
            steps = emptyList(),
            finalAnswer = ""
        )
        val check = StudyResponseValidator.finalizeAndVerify(incomplete)
        assertTrue(check is StudyResponseValidator.ValidationResult.Rejected)
    }

    @Test
    fun jsonCodecRoundTripsSolutionWithDiagramsAndDoubts() {
        val original = StudySolution(
            id = "sol-test-1",
            subject = StudySubject.CHEMISTRY,
            topic = "Benzene Ring",
            educationLevel = EducationLevel.CLASS_11_12,
            question = "Draw benzene structure",
            givenValues = listOf("Formula: C6H6"),
            formulas = listOf("Hückel rule: (4n + 2)π electrons"),
            steps = listOf(
                StudyStep(
                    stepIndex = 0,
                    label = "Structure",
                    boardText = "Regular hexagonal conjugated ring",
                    mathExpressions = listOf("C_6H_6"),
                    explanation = "6 sp2 carbons form a planar aromatic hexagon.",
                    diagram = StudyDiagramSpec(
                        diagramType = "chem_ring",
                        title = "Benzene",
                        elements = listOf(DiagramElement.ChemRing(50f, 50f, 24f, sides = 6, aromatic = true))
                    ),
                    isBookmarked = true
                )
            ),
            finalAnswer = "Planar aromatic hexagon C6H6",
            verificationStatus = VerificationStatus.VERIFIED,
            doubts = listOf(
                StudyDoubtEntry(
                    targetStepIndex = 0,
                    targetFocus = "Step 1",
                    question = "Why is there a circle inside?",
                    boardSummary = "Delocalized 6 pi electrons",
                    explanation = "Pi electrons resonate equally across all six C–C bonds."
                )
            )
        )

        val json = StudyJsonCodec.encodeSolution(original)
        val decoded = StudyJsonCodec.decodeSolution(json)
        assertNotNull(decoded)
        assertEquals(original.id, decoded!!.id)
        assertEquals(StudySubject.CHEMISTRY, decoded.subject)
        assertEquals(1, decoded.steps.size)
        assertTrue(decoded.steps[0].isBookmarked)
        assertTrue(decoded.steps[0].diagram?.elements?.firstOrNull() is DiagramElement.ChemRing)
        assertEquals(1, decoded.doubts.size)
        assertEquals(0, decoded.doubts[0].targetStepIndex)
    }
}
