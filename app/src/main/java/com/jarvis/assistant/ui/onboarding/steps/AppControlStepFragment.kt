package com.jarvis.assistant.ui.onboarding.steps

import android.content.Intent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.onboarding.AppControlCatalog
import com.jarvis.assistant.onboarding.AppProfile
import com.jarvis.assistant.onboarding.LiveStatus
import com.jarvis.assistant.onboarding.Mechanism
import com.jarvis.assistant.onboarding.OnboardingStep
import com.jarvis.assistant.onboarding.WorkflowStages
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.StepFragment
import com.jarvis.assistant.ui.onboarding.StepScaffold
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.glass
import com.jarvis.assistant.ui.onboarding.lp
import com.jarvis.assistant.ui.onboarding.pill
import com.jarvis.assistant.ui.onboarding.primaryButton
import com.jarvis.assistant.ui.onboarding.restyle
import com.jarvis.assistant.ui.onboarding.secondaryButton
import com.jarvis.assistant.ui.onboarding.text
import com.jarvis.assistant.ui.onboarding.verticalLayout
import com.jarvis.assistant.ui.onboarding.views.PipelineView

/**
 * Stage 6: what JARVIS can do inside each app, and how. Every line comes from [AppControlCatalog], which mirrors code that
 * exists in this app. The workflow animation is an explanation; nothing is sent, bought or booked from here. The only real
 * action is the optional "Open app" button, which just launches the app because you tapped it.
 */
class AppControlStepFragment : StepFragment() {

    override val step = OnboardingStep.APP_CONTROL

    private val chips = mutableListOf<TextView>()
    private lateinit var installed: TextView
    private lateinit var example: TextView
    private lateinit var stageTitle: TextView
    private lateinit var stageNote: TextView
    private lateinit var capabilities: LinearLayout
    private lateinit var a11yNote: TextView
    private lateinit var openApp: android.widget.Button
    private lateinit var pipeline: PipelineView
    private var selected = 0

    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED, selected)
    }

    override fun build(scaffold: StepScaffold) {
        val ctx = requireContext()
        chips.clear()
        selected = savedInstanceStateSelected()
        scaffold.heading(
            "App control",
            "Pick an app to see what JARVIS can really do in it, and how. Nothing here sends or buys anything."
        )

        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        AppControlCatalog.apps.forEachIndexed { i, app ->
            val chip = pill(ctx, app.name, Palette.TEXT_DIM).apply {
                setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
                setOnClickListener { select(i) }
            }
            chips += chip
            row.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(8) })
        }
        scroll.addView(row)
        scaffold.addBody(scroll, 12)

        val card = verticalLayout(ctx).apply {
            background = glass(ctx, 22)
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        }
        installed = pill(ctx, "…", Palette.TEXT_DIM)
        card.addView(installed, lp())
        card.addView(text(ctx, "TRY SAYING", 11f, Palette.TEXT_DIM, bold = true).apply { letterSpacing = 0.14f }, lp(topDp = 12, context = ctx))
        example = text(ctx, "", 17f, Palette.TEXT, bold = true)
        card.addView(example, lp(topDp = 4, context = ctx))

        card.addView(text(ctx, "HOW IT WORKS (tap a step)", 11f, Palette.TEXT_DIM, bold = true).apply { letterSpacing = 0.14f }, lp(topDp = 16, context = ctx))
        pipeline = PipelineView(ctx, WorkflowStages.NAMES.size)
        card.addView(pipeline, lp(heightPx = ctx.dp(76), topDp = 6, context = ctx))
        stageTitle = text(ctx, "", 15f, Palette.CYAN, bold = true)
        stageNote = text(ctx, "", 13.5f, Palette.TEXT_DIM)
        card.addView(stageTitle, lp(topDp = 8, context = ctx))
        card.addView(stageNote, lp(topDp = 4, context = ctx))
        pipeline.onStageChanged = { showStage(it) }

        card.addView(text(ctx, "WHAT IT CAN DO", 11f, Palette.TEXT_DIM, bold = true).apply { letterSpacing = 0.14f }, lp(topDp = 16, context = ctx))
        capabilities = verticalLayout(ctx)
        card.addView(capabilities, lp(topDp = 6, context = ctx))

        a11yNote = text(ctx, "", 13.5f, Palette.TEXT_DIM)
        card.addView(a11yNote, lp(topDp = 14, context = ctx))
        openApp = secondaryButton(ctx, "Open app") { launchSelected() }
        card.addView(openApp, lp(topDp = 12, context = ctx))
        scaffold.addBody(card, 14)

        standardFooter(primaryButton(ctx, "Continue") { advance(true) }, "Skip for now")

        select(selected)
        onLive { renderLive(it) }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getInt(KEY_SELECTED, 0) ?: 0
    }

    private fun savedInstanceStateSelected(): Int = selected.coerceIn(0, AppControlCatalog.apps.lastIndex)

    private fun select(index: Int) {
        val ctx = requireContext()
        selected = index.coerceIn(0, AppControlCatalog.apps.lastIndex)
        val app = AppControlCatalog.apps[selected]
        chips.forEachIndexed { k, chip -> restyle(chip, AppControlCatalog.apps[k].name, if (k == selected) Palette.CYAN else Palette.TEXT_DIM) }
        example.text = "“${app.example}”"
        pipeline.restart()
        showStage(0)

        capabilities.removeAllViews()
        for (cap in app.capabilities) {
            val line = verticalLayout(ctx).apply {
                setPadding(0, ctx.dp(6), 0, ctx.dp(6))
            }
            line.addView(text(ctx, cap.text, 14f, Palette.TEXT), lp())
            val tags = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in cap.mechanisms) {
                tags.addView(
                    pill(ctx, m.label.uppercase(), colorOf(m)),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(6) }
                )
            }
            line.addView(tags, lp(topDp = 5, context = ctx))
            capabilities.addView(line, lp())
        }
        renderLive(vm.live.value)
    }

    private fun showStage(stage: Int) {
        val app = AppControlCatalog.apps[selected]
        val i = stage.coerceIn(0, WorkflowStages.NAMES.lastIndex)
        stageTitle.text = "${i + 1}. ${WorkflowStages.NAMES[i]}"
        stageNote.text = app.notes.getOrElse(i) { "" }
    }

    private fun renderLive(live: LiveStatus) {
        if (!::installed.isInitialized) return
        val app: AppProfile = AppControlCatalog.apps[selected]
        val isInstalled = live.installedApps[app.id] == true
        if (app.alwaysAvailable) {
            restyle(installed, "BUILT IN", Palette.MINT)
        } else if (isInstalled) {
            restyle(installed, "INSTALLED ON THIS PHONE", Palette.MINT)
        } else {
            restyle(installed, "NOT INSTALLED", Palette.AMBER)
        }
        a11yNote.text = if (live.accessibilityEnabled) {
            "Accessibility is on, so the on-screen steps above can run when you ask."
        } else {
            "Accessibility is off, so only the direct-intent steps work right now. You can enable it in the previous stage."
        }
        a11yNote.setTextColor(if (live.accessibilityEnabled) Palette.MINT else Palette.AMBER)
        openApp.visibility = if (!app.alwaysAvailable && isInstalled) View.VISIBLE else View.GONE
        openApp.text = "Open ${app.name}"
    }

    private fun launchSelected() {
        val app = AppControlCatalog.apps[selected]
        val pm = requireContext().packageManager
        val intent = app.packages.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) } ?: return
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            android.widget.Toast.makeText(requireContext(), "Couldn't open ${app.name}.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun colorOf(m: Mechanism): Int = when (m) {
        Mechanism.DIRECT_INTENT -> Palette.CYAN
        Mechanism.ACCESSIBILITY -> Palette.VIOLET
        Mechanism.CONFIRMATION -> Palette.AMBER
        Mechanism.UNAVAILABLE -> Palette.ROSE
    }

    private companion object {
        const val KEY_SELECTED = "selected_app"
    }
}
