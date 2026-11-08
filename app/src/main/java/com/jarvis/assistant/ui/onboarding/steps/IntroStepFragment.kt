package com.jarvis.assistant.ui.onboarding.steps

import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.jarvis.assistant.onboarding.OnboardingStep
import com.jarvis.assistant.ui.onboarding.Motion
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.StepFragment
import com.jarvis.assistant.ui.onboarding.StepScaffold
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.lp
import com.jarvis.assistant.ui.onboarding.primaryButton
import com.jarvis.assistant.ui.onboarding.text
import com.jarvis.assistant.ui.onboarding.views.OrbView

/** Stage 1: the cinematic welcome. The two required lines are rendered exactly, auto-fitting instead of overflowing. */
class IntroStepFragment : StepFragment() {

    override val step = OnboardingStep.INTRO

    override fun build(scaffold: StepScaffold) {
        val ctx = requireContext()
        scaffold.heading("", null)
        scaffold.body.gravity = Gravity.CENTER_HORIZONTAL

        val orb = OrbView(ctx)
        scaffold.body.addView(orb, lp(heightPx = ctx.dp(240), topDp = 12, context = ctx))

        val welcome = fitted(WELCOME, 30f, 18f, Palette.TEXT, bold = true, lines = 2).apply { letterSpacing = 0.08f }
        scaffold.body.addView(welcome, lp(topDp = 20, context = ctx))

        val creator = fitted(CREATOR, 13f, 9f, Palette.CYAN, bold = true, lines = 2).apply { letterSpacing = 0.18f }
        scaffold.body.addView(creator, lp(topDp = 14, context = ctx))

        val blurb = text(
            ctx,
            "A short guided setup: permissions, your profile, your AI connection, Accessibility and a live look at what " +
                "JARVIS can do. Everything is optional except pressing the buttons yourself.",
            14f, Palette.TEXT_DIM, gravity = Gravity.CENTER
        )
        scaffold.body.addView(blurb, lp(topDp = 22, context = ctx))

        standardFooter(primaryButton(ctx, "Begin setup") { advance(true) })

        if (Motion.enabled(ctx)) {
            listOf(orb to 0L, welcome to 350L, creator to 650L, blurb to 950L).forEach { (view, delay) ->
                view.alpha = 0f
                view.translationY = ctx.dp(14).toFloat()
                view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(700).start()
            }
            orb.scaleX = 0.6f
            orb.scaleY = 0.6f
            orb.animate().scaleX(1f).scaleY(1f).setDuration(1100).start()
        }
    }

    private fun fitted(value: String, maxSp: Float, minSp: Float, color: Int, bold: Boolean, lines: Int): TextView =
        text(requireContext(), value, maxSp, color, bold, Gravity.CENTER).apply {
            maxLines = lines
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, minSp.toInt(), maxSp.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP
            )
        }

    override fun onDestroyView() {
        view?.let { clear(it) }
        super.onDestroyView()
    }

    private fun clear(v: View) {
        v.animate().cancel()
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) clear(v.getChildAt(i))
    }

    private companion object {
        const val WELCOME = "WELCOME TO THE ALL NEW JARVIS"
        const val CREATOR = "CREATED AND DESIGNED BY SUKANTA BHATTACHARYA"
    }
}
