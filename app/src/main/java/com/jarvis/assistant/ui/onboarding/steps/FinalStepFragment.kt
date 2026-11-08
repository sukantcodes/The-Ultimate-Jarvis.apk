package com.jarvis.assistant.ui.onboarding.steps

import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.onboarding.OnboardingProgress
import com.jarvis.assistant.onboarding.OnboardingStep
import com.jarvis.assistant.onboarding.SetupItem
import com.jarvis.assistant.onboarding.SetupStatus
import com.jarvis.assistant.onboarding.SetupSummary
import com.jarvis.assistant.ui.onboarding.OnboardingActivity
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.StepFragment
import com.jarvis.assistant.ui.onboarding.StepScaffold
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.glass
import com.jarvis.assistant.ui.onboarding.lp
import com.jarvis.assistant.ui.onboarding.pill
import com.jarvis.assistant.ui.onboarding.primaryButton
import com.jarvis.assistant.ui.onboarding.withAlpha
import com.jarvis.assistant.ui.onboarding.secondaryButton
import com.jarvis.assistant.ui.onboarding.text
import com.jarvis.assistant.ui.onboarding.verticalLayout
import com.jarvis.assistant.ui.onboarding.views.FinishBurstView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Stage 8: the system check. Every row is derived from live state by [SetupSummary]; nothing is hard-coded as done.
 * "Set up" takes the person to the relevant stage and brings them back here afterwards. Finish is always available:
 * missing items are reported as Needs attention or Skipped, never hidden.
 */
class FinalStepFragment : StepFragment() {

    override val step = OnboardingStep.FINAL

    private lateinit var list: LinearLayout
    private lateinit var headline: TextView
    private lateinit var finishBtn: Button
    private var finishing = false

    override fun build(scaffold: StepScaffold) {
        val ctx = requireContext()
        scaffold.heading("System check", "This is what is actually ready on your phone right now. It updates live.")
        headline = text(ctx, "", 15f, Palette.CYAN, bold = true)
        scaffold.addBody(headline, 12)
        list = verticalLayout(ctx)
        scaffold.addBody(list, 12)

        finishBtn = primaryButton(ctx, "Finish setup") { finish() }
        standardFooter(finishBtn)

        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.live.combine(vm.progress) { live, progress -> SetupSummary.build(live, progress, Build.VERSION.SDK_INT) to progress }
                    .collect { (items, progress) -> render(items, progress) }
            }
        }
    }

    private fun render(items: List<SetupItem>, @Suppress("UNUSED_PARAMETER") progress: OnboardingProgress) {
        val ctx = requireContext()
        list.removeAllViews()
        for (item in items) {
            val row = verticalLayout(ctx).apply {
                background = glass(ctx, 18)
                setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            }
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(text(ctx, item.title, 16f, Palette.TEXT, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(pill(ctx, item.status.label.uppercase(), colorOf(item.status)))
            row.addView(head, lp())
            row.addView(text(ctx, item.detail, 13.5f, Palette.TEXT_DIM), lp(topDp = 6, context = ctx))
            if (item.status != SetupStatus.COMPLETED) {
                val label = if (item.status == SetupStatus.NEEDS_ATTENTION) "Fix this" else "Set up"
                row.addView(secondaryButton(ctx, label, colorOf(item.status)) { vm.jumpFromFinal(item.step) }, lp(topDp = 10, context = ctx))
            }
            list.addView(row, lp(topDp = 10, context = ctx))
        }
        val attention = items.count { it.status == SetupStatus.NEEDS_ATTENTION }
        val done = items.count { it.status == SetupStatus.COMPLETED }
        headline.text = when {
            attention > 0 -> "$done of ${items.size} ready · $attention need attention"
            done == items.size -> "Everything is ready"
            else -> "$done of ${items.size} ready · the rest is optional or skipped"
        }
        headline.setTextColor(if (attention > 0) Palette.AMBER else Palette.MINT)
    }

    private fun colorOf(status: SetupStatus): Int = when (status) {
        SetupStatus.COMPLETED -> Palette.MINT
        SetupStatus.NEEDS_ATTENTION -> Palette.AMBER
        SetupStatus.OPTIONAL -> Palette.CYAN
        SetupStatus.SKIPPED -> Palette.TEXT_DIM
    }

    private fun finish() {
        if (finishing) return
        finishing = true
        finishBtn.isEnabled = false
        val activity = requireActivity() as OnboardingActivity
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val burst = FinishBurstView(activity).apply {
            isClickable = true
            setBackgroundColor(withAlpha(Palette.BG, 235))
        }
        root.addView(burst, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        burst.play { activity.finishTutorialAndClose() }
    }
}
