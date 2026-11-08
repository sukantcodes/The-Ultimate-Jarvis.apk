package com.jarvis.assistant.ui.onboarding

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.R
import com.jarvis.assistant.onboarding.OnboardingProgress
import com.jarvis.assistant.onboarding.OnboardingStep
import com.jarvis.assistant.onboarding.OnboardingStore
import com.jarvis.assistant.ui.onboarding.steps.AccessibilityStepFragment
import com.jarvis.assistant.ui.onboarding.steps.AiStepFragment
import com.jarvis.assistant.ui.onboarding.steps.AppControlStepFragment
import com.jarvis.assistant.ui.onboarding.steps.FinalStepFragment
import com.jarvis.assistant.ui.onboarding.steps.IntroStepFragment
import com.jarvis.assistant.ui.onboarding.steps.PermissionsStepFragment
import com.jarvis.assistant.ui.onboarding.steps.ProfileStepFragment
import com.jarvis.assistant.ui.onboarding.steps.VoiceStepFragment
import com.jarvis.assistant.ui.onboarding.views.AmbientBackgroundView
import com.jarvis.assistant.ui.onboarding.views.StepRailView
import kotlinx.coroutines.launch

/**
 * Hosts the first-launch tutorial. It owns only the frame (background, progress rail, skip, back handling);
 * every stage is a [StepFragment]. The shown stage always follows [OnboardingViewModel.progress], which is
 * persisted, so the tutorial resumes after rotation, process death and trips to Android Settings.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var vm: OnboardingViewModel
    private lateinit var rail: StepRailView
    private lateinit var stepLabel: android.widget.TextView
    private lateinit var skipAll: android.widget.Button
    private var shownStep: OnboardingStep? = null
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A replay starts from the top. This must happen before the view model reads the stored progress,
        // and only on a fresh launch: rotation or process recreation must keep the walk-through where it was.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_REPLAY, false)) {
            OnboardingStore(this).beginReplay()
        }
        vm = ViewModelProvider(this)[OnboardingViewModel::class.java]

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(buildFrame())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (vm.progress.value.current != OnboardingStep.INTRO) vm.back() else finish()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.progress.collect { render(it) }
            }
        }
    }

    private fun buildFrame(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        root.addView(AmbientBackgroundView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(12), dp(4))
        }
        rail = StepRailView(this)
        header.addView(rail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stepLabel = text(this, "", 12f, Palette.TEXT_DIM, bold = true).apply { letterSpacing = 0.12f }
        labelRow.addView(stepLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        skipAll = textButton(this, "Skip tutorial") { confirmSkipAll() }
        labelRow.addView(skipAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(labelRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val container = FragmentContainerView(this).apply { id = R.id.onboarding_container }
        column.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    private fun render(progress: OnboardingProgress) {
        val steps = OnboardingStep.values()
        rail.update(steps.map { progress.stateOf(it) }, progress.current.ordinal)
        stepLabel.text = "STEP ${progress.current.ordinal + 1} OF ${steps.size}  ·  ${progress.current.title.uppercase()}"
        // The last stage has its own Finish action; skipping everything from there makes no sense.
        skipAll.visibility = if (progress.current == OnboardingStep.FINAL) View.INVISIBLE else View.VISIBLE
        showStep(progress.current)
    }

    private fun showStep(step: OnboardingStep) {
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(step.id)
        if (shownStep == step && existing != null && existing.isAdded) return
        val forward = (shownStep?.ordinal ?: -1) < step.ordinal
        val tx = fm.beginTransaction()
        if (shownStep != null && Motion.enabled(this)) {
            if (forward) tx.setCustomAnimations(R.anim.onb_enter, R.anim.onb_exit)
            else tx.setCustomAnimations(R.anim.onb_pop_enter, R.anim.onb_pop_exit)
        }
        tx.replace(R.id.onboarding_container, existing ?: fragmentFor(step), step.id)
        tx.commit()
        shownStep = step
    }

    private fun fragmentFor(step: OnboardingStep): Fragment = when (step) {
        OnboardingStep.INTRO -> IntroStepFragment()
        OnboardingStep.PERMISSIONS -> PermissionsStepFragment()
        OnboardingStep.PROFILE -> ProfileStepFragment()
        OnboardingStep.AI -> AiStepFragment()
        OnboardingStep.ACCESSIBILITY -> AccessibilityStepFragment()
        OnboardingStep.APP_CONTROL -> AppControlStepFragment()
        OnboardingStep.VOICE -> VoiceStepFragment()
        OnboardingStep.FINAL -> FinalStepFragment()
    }

    private fun confirmSkipAll() {
        AlertDialog.Builder(this)
            .setTitle("Skip the tutorial?")
            .setMessage("Nothing is turned on for you. You can replay the tutorial any time from Settings.")
            .setPositiveButton("Skip") { _, _ -> finishTutorialAndClose() }
            .setNegativeButton("Keep going", null)
            .show()
    }

    /** Ends the tutorial for good (finished or skipped) and returns to the existing home screen. */
    fun finishTutorialAndClose() {
        if (closing) return
        closing = true
        vm.finishTutorial()
        // Keep the flag from the previous setup tutorial in sync so nothing else treats this person as un-onboarded.
        com.jarvis.assistant.JarvisApp.instance.preferences.hasCompletedSetupTutorial = true
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_REPLAY = "com.jarvis.assistant.onboarding.REPLAY"

        fun intent(context: Context, replay: Boolean = false): Intent =
            Intent(context, OnboardingActivity::class.java).putExtra(EXTRA_REPLAY, replay)
    }
}
