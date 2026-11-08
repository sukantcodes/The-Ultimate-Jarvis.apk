package com.jarvis.assistant.ui.onboarding.steps

import android.animation.LayoutTransition
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.onboarding.LiveStatus
import com.jarvis.assistant.onboarding.OnboardingStep
import com.jarvis.assistant.onboarding.PermissionCatalog
import com.jarvis.assistant.onboarding.PermissionKey
import com.jarvis.assistant.onboarding.PermissionRules
import com.jarvis.assistant.onboarding.PermissionSpec
import com.jarvis.assistant.onboarding.PermissionUiState
import com.jarvis.assistant.ui.onboarding.Motion
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
import com.jarvis.assistant.ui.onboarding.views.PermissionGlyphView
import kotlinx.coroutines.launch

/**
 * Stage 2: one real Android prompt per permission, never a bulk request. Only permissions the app already declares and
 * already uses are listed. State is read live from Android on every refresh; nothing is remembered as "granted".
 */
class PermissionsStepFragment : StepFragment() {

    override val step = OnboardingStep.PERMISSIONS

    private class Row(val spec: PermissionSpec, val status: TextView, val action: Button, val detail: LinearLayout)

    private val specs by lazy { PermissionCatalog.forSdk(Build.VERSION.SDK_INT) }
    private val rows = mutableListOf<Row>()
    private lateinit var primary: Button
    private lateinit var summary: TextView

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // The result map names exactly what was asked, so this also works if the process was recreated mid-dialog.
        for (spec in specs) if (spec.request.any { it in result.keys }) vm.store.markAsked(spec.key)
        vm.refresh()
    }

    override fun build(scaffold: StepScaffold) {
        val ctx = requireContext()
        rows.clear()
        scaffold.heading(
            "Permissions",
            "JARVIS asks only for what its features use. Each one is a separate Android prompt that you control, " +
                "and each can be changed later in Settings."
        )

        summary = text(ctx, "", 13f, Palette.TEXT_DIM)
        scaffold.addBody(summary, 12)

        for (spec in specs) scaffold.addBody(buildCard(spec), 12)

        primary = primaryButton(ctx, "Continue") { advance(coreGranted(vm.live.value)) }
        standardFooter(primary)

        viewLifecycleOwnerLiveRefresh()
    }

    private fun buildCard(spec: PermissionSpec): View {
        val ctx = requireContext()
        val card = verticalLayout(ctx).apply {
            background = glass(ctx, 20)
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            if (Motion.enabled(ctx)) layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(PermissionGlyphView(ctx, spec.key), LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(64)))

        val titles = verticalLayout(ctx).apply { setPadding(ctx.dp(14), 0, ctx.dp(8), 0) }
        titles.addView(text(ctx, spec.title, 17f, Palette.TEXT, bold = true), lp())
        titles.addView(text(ctx, spec.tagline, 13f, Palette.TEXT_DIM), lp(topDp = 3, context = ctx))
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val status = pill(ctx, "…", Palette.TEXT_DIM)
        header.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(header, lp())

        val detail = verticalLayout(ctx).apply { visibility = View.GONE }
        detail.addView(text(ctx, spec.why, 13.5f, Palette.TEXT_DIM), lp(topDp = 12, context = ctx))
        detail.addView(
            text(ctx, "Unlocks: " + spec.unlocks.joinToString(" · "), 13f, Palette.CYAN),
            lp(topDp = 8, context = ctx)
        )
        card.addView(detail, lp())

        val action = secondaryButton(ctx, "Allow") { onAction(spec) }
        card.addView(action, lp(topDp = 12, context = ctx))

        val toggle = View.OnClickListener {
            detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        header.setOnClickListener(toggle)

        rows += Row(spec, status, action, detail)
        return card
    }

    private fun stateOf(spec: PermissionSpec, live: LiveStatus): PermissionUiState {
        val granted = live.isGranted(spec.key)
        val rationale = spec.request.any { shouldShowRequestPermissionRationale(it) }
        return PermissionRules.derive(granted, vm.store.wasAsked(spec.key), rationale)
    }

    private fun onAction(spec: PermissionSpec) {
        when (stateOf(spec, vm.live.value)) {
            PermissionUiState.GRANTED -> Unit
            PermissionUiState.BLOCKED -> openAppSettings()
            PermissionUiState.NOT_ASKED, PermissionUiState.DENIED -> {
                // A real system prompt. The tutorial never marks anything granted itself.
                launcher.launch(spec.request.toTypedArray())
            }
        }
    }

    private fun coreGranted(live: LiveStatus): Boolean = specs.filter { it.core }.all { live.isGranted(it.key) }

    private fun render(live: LiveStatus) {
        for (row in rows) {
            when (stateOf(row.spec, live)) {
                PermissionUiState.GRANTED -> {
                    restyle(row.status, "ALLOWED", Palette.MINT)
                    row.action.visibility = View.GONE
                }
                PermissionUiState.NOT_ASKED -> {
                    restyle(row.status, if (row.spec.core) "NEEDED" else "OPTIONAL", Palette.TEXT_DIM)
                    row.action.visibility = View.VISIBLE
                    row.action.text = "Allow ${row.spec.title.lowercase()}"
                }
                PermissionUiState.DENIED -> {
                    restyle(row.status, "DENIED", Palette.AMBER)
                    row.action.visibility = View.VISIBLE
                    row.action.text = "Ask again"
                    row.detail.visibility = View.VISIBLE
                }
                PermissionUiState.BLOCKED -> {
                    restyle(row.status, "BLOCKED", Palette.ROSE)
                    row.action.visibility = View.VISIBLE
                    row.action.text = "Open Settings to allow"
                    row.detail.visibility = View.VISIBLE
                }
            }
        }
        val allowed = specs.count { live.isGranted(it.key) }
        summary.text = "$allowed of ${specs.size} allowed"
        primary.text = if (coreGranted(live)) "Continue" else "Continue without the essentials"
    }

    private fun viewLifecycleOwnerLiveRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.live.collect { render(it) }
            }
        }
    }

    @Suppress("unused")
    private fun keyOf(spec: PermissionSpec): PermissionKey = spec.key
}
