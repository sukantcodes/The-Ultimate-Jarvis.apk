package com.jarvis.assistant.study

import android.text.TextPaint
import com.jarvis.assistant.study.model.BoardPlaybackStatus
import com.jarvis.assistant.study.model.StudyDoubtEntry
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.study.model.StudyStreamEvent
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.model.TeacherProfile
import com.jarvis.assistant.study.model.VerificationStatus
import com.jarvis.assistant.study.render.StudyAnimationEngine
import com.jarvis.assistant.study.render.StudyMathRenderer
import com.jarvis.assistant.study.voice.StudySpeechSynchronizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyRenderAndAnimationTest {

    @get:Rule
    val testTimeout: Timeout = Timeout.seconds(30)

    @Test
    fun mathRendererParsesFractionsRadicalsScriptsAndNeverOverflowsNarrowScreen() {
        val ast = StudyMathRenderer.parseAst("\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}")
        assertTrue(ast.any { it is StudyMathRenderer.MathNode.Fraction })

        val paint = TextPaint().apply { textSize = 36f }
        // Exceed StudyMathRenderer's 120f minimum wrap threshold cleanly even with 1px/char Robolectric stub Paint
        val charW = paint.measureText("M").coerceAtLeast(1f)
        val narrowWidth = maxOf(140f, charW * 16f)
        val longExpr = (1..18).joinToString(" + ") { "\\frac{x_$it^2 + $it}{y_$it + 1}" } + " + \\sqrt{16x^2 + 9} = 42"
        val measured = StudyMathRenderer.measure(longExpr, paint, maxWidthPx = narrowWidth)
        assertTrue("Expected wrapped lines >= 2, got ${measured.lines.size} for totalWidth=${measured.totalWidth}", measured.lines.size >= 2)
        assertTrue(measured.totalWidth <= narrowWidth + 0.5f)
        assertTrue(measured.totalHeight > paint.textSize)
    }

    @Test
    fun animationEngineStreamsOrderedStepsAndSupportsPausePrevNextReplayUndoRedo() {
        val engine = StudyAnimationEngine()
        engine.beginStreaming(TeacherProfile(), "2x + 5 = 15")
        engine.onStreamMetadata(
            StudyStreamEvent.Metadata(
                subject = StudySubject.MATHEMATICS,
                topic = "Linear Equation",
                chapter = "",
                difficulty = "Easy",
                inferredLevel = com.jarvis.assistant.study.model.EducationLevel.CLASS_9_10,
                question = "2x + 5 = 15",
                givenValues = emptyList(),
                requiredQuantity = "x",
                formulas = emptyList(),
                definitions = emptyList()
            )
        )

        val step0 = StudyStep(0, "Step 1", "Subtract 5: 2x = 10", explanation = "Isolate variable.")
        val step1 = StudyStep(1, "Step 2", "Divide by 2: x = 5", explanation = "Solve for x.")

        engine.appendValidatedStep(step0)
        // Step 0 begins revealing mid-stream before step 1 arrives
        repeat(10) { engine.tick(60L) }
        val midProg = engine.getStepProgress(0)
        assertTrue(midProg > 0f && midProg < 1f)

        // Appending step 1 preserves ordering & does not overwrite step 0 progress
        engine.appendValidatedStep(step1)
        assertEquals(2, engine.getSolution()?.steps?.size)
        assertEquals(midProg, engine.getStepProgress(0), 0.001f)

        // Pause freezes progress accurately
        engine.pause()
        assertEquals(BoardPlaybackStatus.PAUSED, engine.getStatus())
        engine.tick(100L)
        assertEquals(midProg, engine.getStepProgress(0), 0.001f)

        // Complete stream & test manual Next / Prev / Replay Step / Replay All
        val fullSol = StudySolution(
            subject = StudySubject.MATHEMATICS,
            topic = "Linear Equation",
            question = "2x + 5 = 15",
            steps = listOf(step0, step1),
            finalAnswer = "x = 5",
            verificationStatus = VerificationStatus.VERIFIED
        )
        engine.onStreamCompleted(fullSol)
        engine.nextStep() // Completes step 0 immediately
        assertEquals(1f, engine.getStepProgress(0), 0.001f)
        engine.nextStep() // Advances to step 1
        assertEquals(1, engine.getActiveStepIndex())

        engine.previousStep()
        assertEquals(0, engine.getActiveStepIndex())

        // Replay All resets and animates from Step 0 at 2x speed
        engine.setSpeed(2.0f)
        engine.replayAll()
        assertEquals(0f, engine.getStepProgress(0), 0.001f)
        repeat(60) { engine.tick(50L) }
        assertEquals(BoardPlaybackStatus.COMPLETED, engine.getStatus())
        assertEquals(1f, engine.getFinalAnswerProgress(), 0.001f)

        // Doubt attachment preserves original steps
        engine.attachDoubt(
            StudyDoubtEntry(
                targetStepIndex = 0,
                targetFocus = "Step 1",
                question = "Why subtract 5?",
                boardSummary = "Additive inverse cancels +5",
                explanation = "Subtracting 5 leaves 2x alone."
            )
        )
        assertEquals(2, engine.getSolution()?.steps?.size)
        assertEquals(1, engine.getSolution()?.doubts?.size)

        // Erase + Undo + Redo recovery
        engine.clearBoardAnimated()
        repeat(10) { engine.tick(60L) }
        assertEquals(BoardPlaybackStatus.IDLE, engine.getStatus())
        assertTrue(engine.undo())
        assertEquals("x = 5", engine.getSolution()?.finalAnswer)
        assertTrue(engine.redo())
        assertEquals(BoardPlaybackStatus.IDLE, engine.getStatus())
    }

    @Test
    fun speechSynchronizerPreparesNaturalTeacherSpeechFromMathAndLatex() {
        val raw = "Substitute \\frac{3}{4} into x^2 + \\sqrt{16} → y"
        val spoken = StudySpeechSynchronizer.prepareSpeechText(raw)
        assertFalse(spoken.contains("\\"))
        assertTrue(spoken.contains("3 over 4"))
        assertTrue(spoken.contains("squared"))
        assertTrue(spoken.contains("square root of 16"))
        assertTrue(spoken.contains("gives"))
    }
}
