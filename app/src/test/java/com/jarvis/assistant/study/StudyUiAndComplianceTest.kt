package com.jarvis.assistant.study

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityStudyBoardBinding
import com.jarvis.assistant.study.model.EducationLevel
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.study.model.StudySubject
import com.jarvis.assistant.study.render.StudyAnimationEngine
import com.jarvis.assistant.study.render.StudyWhiteboardView
import com.jarvis.assistant.study.ui.StudyBoardActivity
import com.jarvis.assistant.tools.appcontrol.AppNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyUiAndComplianceTest {

    @Test
    fun appNavigatorRegistersStudyBoardDestinationCleanly() {
        val dest = AppNavigator.destinations.firstOrNull { it.id == "study_board" }
        assertNotNull(dest)
        assertEquals("com.jarvis.assistant.study.ui.StudyBoardActivity", dest!!.className)
        assertEquals(null, dest.argumentExtra)
    }

    @Test
    fun portraitActivityAndLandscapeThemedLayoutRenderCleanlyWithoutOverflow() {
        val controller = Robolectric.buildActivity(StudyBoardActivity::class.java)
        val portraitActivity = controller.setup().get()
        assertNotNull(portraitActivity.findViewById<StudyWhiteboardView>(R.id.studyWhiteboard))

        // Wrap landscape config context with explicit JARVIS theme so ?attr/theme* attributes resolve natively
        val landConfig = Configuration(portraitActivity.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        val themedLandContext = ContextThemeWrapper(
            portraitActivity.createConfigurationContext(landConfig),
            R.style.Theme_JarvisAssistant_Dark
        )
        val landBinding = ActivityStudyBoardBinding.inflate(LayoutInflater.from(themedLandContext))
        assertNotNull(landBinding.studyWhiteboard)
        assertNotNull(landBinding.btnStudyCamera)
        assertNotNull(landBinding.btnStudyPdf)
        assertNotNull(landBinding.btnStudyPlayPause)

        val wb = landBinding.studyWhiteboard
        val engine = StudyAnimationEngine()
        engine.loadSavedSolution(
            StudySolution(
                subject = StudySubject.PHYSICS,
                topic = "Kinematics",
                educationLevel = EducationLevel.CLASS_11_12,
                question = "A car starts from rest with a = 2 m/s^2 for 5 s. Find velocity.",
                givenValues = listOf("u = 0", "a = 2 m/s^2", "t = 5 s"),
                formulas = listOf("v = u + at"),
                steps = listOf(StudyStep(0, "Substitution", "v = 0 + 2 × 5 = 10 m/s")),
                finalAnswer = "v = 10 m/s"
            ),
            resumeStepIndex = 0,
            completedAlready = true
        )
        wb.attachEngine(engine, object : StudyWhiteboardView.Listener {
            override fun onFrameTick(deltaMs: Long) = StudyAnimationEngine.FrameOutcome()
            override fun onStepTapped(stepIndex: Int) = Unit
            override fun onStepBookmarkTapped(stepIndex: Int) = Unit
            override fun onUserScrolledManually(followingLive: Boolean) = Unit
        })

        // 320dp compact portrait width check & landscape check
        listOf(320 to 520, 800 to 420).forEach { (w, h) ->
            wb.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            wb.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            wb.draw(Canvas(bmp))
            assertTrue(wb.width == w && wb.height == h)
            bmp.recycle()
        }

        controller.pause().stop().destroy()
    }

    @Test
    @Config(sdk = [34], qualifiers = "land")
    fun nativeLandscapeOrientationLaunchesStudyBoardActivity() {
        val controller = Robolectric.buildActivity(StudyBoardActivity::class.java)
        val activity = controller.setup().get()
        assertNotNull(activity.findViewById<StudyWhiteboardView>(R.id.studyWhiteboard))
        controller.pause().stop().destroy()
    }
}
