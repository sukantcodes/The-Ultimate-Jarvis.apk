package com.jarvis.assistant.ui.onboarding

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.jarvis.assistant.onboarding.OnboardingStep

/** A LinearLayout that never grows wider than [maxWidthPx], so tablets and landscape keep a readable column. */
class MaxWidthLinearLayout(context: Context, private val maxWidthPx: Int) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val capped = if (mode != MeasureSpec.UNSPECIFIED && size > maxWidthPx) {
            MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY)
        } else widthMeasureSpec
        super.onMeasure(capped, heightMeasureSpec)
    }
}

/**
 * Common page layout for every stage: a scrolling body with a heading, and a footer pinned to the bottom
 * that holds the stage's actions. Everything is capped to a readable column width.
 */
class StepScaffold(context: Context) : LinearLayout(context) {

    val body: LinearLayout
    val footer: LinearLayout
    private val titleView: TextView
    private val subtitleView: TextView

    init {
        orientation = VERTICAL
        val maxWidth = context.dp(560)
        val gutter = context.dp(22)

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val bodyHolder = FrameLayout(context)
        body = MaxWidthLinearLayout(context, maxWidth).apply {
            orientation = VERTICAL
            setPadding(gutter, context.dp(8), gutter, context.dp(16))
        }
        bodyHolder.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        scroll.addView(bodyHolder, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        titleView = text(context, "", 26f, Palette.TEXT, bold = true)
        subtitleView = text(context, "", 15f, Palette.TEXT_DIM)
        body.addView(titleView, lp())
        body.addView(subtitleView, lp(topDp = 8, context = context))

        val footerHolder = FrameLayout(context)
        footer = MaxWidthLinearLayout(context, maxWidth).apply {
            orientation = VERTICAL
            setPadding(gutter, context.dp(10), gutter, context.dp(12))
        }
        footerHolder.addView(footer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        addView(footerHolder, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun heading(title: CharSequence, subtitle: CharSequence?) {
        titleView.text = title
        subtitleView.text = subtitle ?: ""
        subtitleView.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun addBody(view: View, topDp: Int = 16) {
        body.addView(view, lp(topDp = topDp, context = context))
    }

    fun addFooter(view: View, topDp: Int = 8) {
        footer.addView(view, lp(topDp = topDp, context = context))
    }
}

/**
 * Base class of the eight stages. A stage builds its views once; live information comes from the shared
 * [OnboardingViewModel] flows, so rotation and process recreation need no special handling inside a stage.
 */
abstract class StepFragment : Fragment() {

    abstract val step: OnboardingStep

    protected val vm: OnboardingViewModel by lazy {
        ViewModelProvider(requireActivity())[OnboardingViewModel::class.java]
    }

    protected lateinit var scaffold: StepScaffold
        private set

    /** Fill [scaffold]: heading, body and footer. Called once per view creation. */
    protected abstract fun build(scaffold: StepScaffold)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        scaffold = StepScaffold(requireContext())
        build(scaffold)
        return scaffold
    }

    override fun onResume() {
        super.onResume()
        // The person may have just come back from Android Settings or a permission dialog.
        vm.refresh()
    }

    /** Records [step] and moves to the next stage. [satisfied] is the real, freshly read condition, never a guess. */
    protected fun advance(satisfied: Boolean) = vm.settle(step, satisfied)

    protected fun skipStep() = vm.skip(step)

    /** Runs [block] with the live status whenever it changes, while this stage is on screen. */
    protected fun onLive(block: (com.jarvis.assistant.onboarding.LiveStatus) -> Unit) {
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.live.collect { block(it) } }
        }
    }

    /** Standard footer: the stage's main action, then an optional skip, then Back (except on the first stage). */
    protected fun standardFooter(primary: android.widget.Button, skipLabel: String? = null) {
        val ctx = requireContext()
        scaffold.addFooter(primary, 0)
        if (skipLabel != null && step.skippable) scaffold.addFooter(textButton(ctx, skipLabel) { skipStep() }, 4)
        if (step != OnboardingStep.INTRO) scaffold.addFooter(textButton(ctx, "Back") { vm.back() }, 0)
    }

    /** Opens this app's page in Android Settings (where blocked permissions and Restricted settings live). */
    protected fun openAppSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", requireContext().packageName, null)
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            android.widget.Toast.makeText(requireContext(), "Couldn't open Settings on this device.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
