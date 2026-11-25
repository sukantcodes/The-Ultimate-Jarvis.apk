package com.jarvis.assistant.study

import com.jarvis.assistant.study.ai.StudyOfflineSolver
import com.jarvis.assistant.study.ai.StudyPromptBuilder
import com.jarvis.assistant.study.model.DiagramElement
import com.jarvis.assistant.study.model.EducationLevel
import com.jarvis.assistant.study.model.ExplanationDepth
import com.jarvis.assistant.study.model.StudyLanguage
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.model.TeacherProfile
import com.jarvis.assistant.study.model.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyOfflineAndPromptTest {

    private val defaultProfile = TeacherProfile(
        educationLevel = EducationLevel.CLASS_11_12,
        preferredLanguage = StudyLanguage.ENGLISH,
        preferredDepth = ExplanationDepth.DETAILED
    )

    @Test
    fun offlineSolverSolvesLinearEquationDeterministically() {
        val result = StudyOfflineSolver.trySolve("Solve 2x + 5 = 15", defaultProfile)
        assertTrue(result is StudyOfflineSolver.OfflineResult.Solved)
        val sol = (result as StudyOfflineSolver.OfflineResult.Solved).solution
        assertEquals(StudySubject.MATHEMATICS, sol.subject)
        assertEquals("x = 5", sol.finalAnswer)
        assertEquals(VerificationStatus.VERIFIED, sol.verificationStatus)
        assertTrue(sol.steps.size >= 3)
        assertTrue(sol.isOffline)
    }

    @Test
    fun offlineSolverSolvesQuadraticAndBuildsParabolaDiagram() {
        val result = StudyOfflineSolver.trySolve("Solve x^2 - 5x + 6 = 0", defaultProfile)
        assertTrue(result is StudyOfflineSolver.OfflineResult.Solved)
        val sol = (result as StudyOfflineSolver.OfflineResult.Solved).solution
        assertEquals(StudySubject.MATHEMATICS, sol.subject)
        assertTrue(sol.finalAnswer.contains("3") && sol.finalAnswer.contains("2"))
        val diagram = sol.steps.lastOrNull()?.diagram
        assertTrue(diagram != null && diagram.elements.any { it is DiagramElement.Curve })
    }

    @Test
    fun offlineSolverSolvesPhysicsForceWithUnitsAndFreeBodyDiagram() {
        val result = StudyOfflineSolver.trySolve(
            "A block of m = 4 kg accelerates at a = 3 m/s^2. Find net force.",
            defaultProfile
        )
        assertTrue(result is StudyOfflineSolver.OfflineResult.Solved)
        val sol = (result as StudyOfflineSolver.OfflineResult.Solved).solution
        assertEquals(StudySubject.PHYSICS, sol.subject)
        assertEquals("F = 12 N", sol.finalAnswer)
        assertTrue(sol.givenValues.any { it.contains("4 kg") })
        assertTrue(sol.formulas.any { it.contains("F = m") })
        assertTrue(sol.steps.any { s -> s.diagram?.elements?.any { it is DiagramElement.VectorArrow } == true })
    }

    @Test
    fun offlineSolverRefusesComplexUnsupportedProblemsHonestlyWithoutFabricating() {
        val chemRes = StudyOfflineSolver.trySolve("Balance KMnO4 + HCl -> KCl + MnCl2 + H2O + Cl2", defaultProfile)
        assertTrue(chemRes is StudyOfflineSolver.OfflineResult.Unsupported)

        val bioRes = StudyOfflineSolver.trySolve("Explain human nephron ultrafiltration with a labelled diagram", defaultProfile)
        assertTrue(bioRes is StudyOfflineSolver.OfflineResult.Unsupported)
    }

    @Test
    fun promptBuilderAdaptsLanguageLevelAndSubjectFormatting() {
        val hinglishClass11Physics = TeacherProfile(
            educationLevel = EducationLevel.CLASS_11_12,
            preferredLanguage = StudyLanguage.HINGLISH,
            preferredDepth = ExplanationDepth.SIMPLE,
            recentTopics = listOf("PHYSICS: Projectile Motion")
        )
        val prompt = StudyPromptBuilder.buildTeachSystemPrompt(
            "A projectile is launched at 20 m/s at 30 degree. Find range.",
            hinglishClass11Physics,
            memoryContext = ""
        )
        assertTrue(prompt.contains("Classes 11–12"))
        assertTrue(prompt.contains("Hinglish"))
        assertTrue(prompt.contains("PHYSICS FORMAT"))
        assertTrue(prompt.contains("NEVER print greetings"))
        assertTrue(prompt.contains("Projectile Motion"))

        val chemPrompt = StudyPromptBuilder.buildTeachSystemPrompt(
            "Balance Fe + O2 -> Fe2O3",
            defaultProfile
        )
        assertTrue(chemPrompt.contains("CHEMISTRY FORMAT"))

        val doubtPrompt = StudyPromptBuilder.buildDoubtPrompt(
            solution = StudySolution(question = "2x + 5 = 15", steps = listOf(StudyStep(0, "Step 1", "2x = 10"))),
            targetStep = StudyStep(0, "Step 1", "2x = 10"),
            doubtQuestion = "Why subtract 5?",
            profile = hinglishClass11Physics
        )
        assertTrue(doubtPrompt.contains("Target Step 1"))
        assertTrue(doubtPrompt.contains("Why subtract 5?"))
        assertFalse(doubtPrompt.isBlank())
    }
}
