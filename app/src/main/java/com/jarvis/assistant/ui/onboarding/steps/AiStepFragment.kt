package com.jarvis.assistant.ui.onboarding.steps

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.assistant.onboarding.LiveStatus
import com.jarvis.assistant.onboarding.OnboardingStep
import com.jarvis.assistant.ui.onboarding.ApiUiState
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
import com.jarvis.assistant.ui.onboarding.textButton
import com.jarvis.assistant.ui.onboarding.verticalLayout
import com.jarvis.assistant.ui.onboarding.withAlpha
import kotlinx.coroutines.launch

/**
 * Stage 4: connect the Gemini API key. Validation uses the same [com.jarvis.assistant.network.ApiKeyValidator] as the
 * existing API Connection screen, and saving goes through the existing Keystore-backed preferences. The key is never
 * logged, never put in saved instance state and never shown unless the person taps "Show".
 */
class AiStepFragment : StepFragment() {

    override val step = OnboardingStep.AI

    private lateinit var input: EditText
    private lateinit var toggle: Button
    private lateinit var testBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var result: TextView
    private lateinit var saved: TextView
    private lateinit var storage: TextView
    private lateinit var primary: Button
    private var shown = false

    override fun build(scaffold: StepScaffold) {
        val ctx = requireContext()
        scaffold.heading(
            "Connect your AI",
            "JARVIS thinks with Google's Gemini. Gemini needs an API key that belongs to you. JARVIS can't talk or answer without one."
        )

        val about = verticalLayout(ctx).apply {
            background = glass(ctx, 20)
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
        }
        about.addView(text(ctx, "What this does", 15f, Palette.TEXT, bold = true), lp())
        about.addView(
            text(
                ctx,
                "Your key is used to send your conversation to Gemini and bring back answers. It is kept encrypted with the " +
                    "Android Keystore on this phone, is never written to logs and is sent only to Google's Gemini service. " +
                    "Free keys come from Google AI Studio.",
                13.5f, Palette.TEXT_DIM
            ),
            lp(topDp = 6, context = ctx)
        )
        about.addView(secondaryButton(ctx, "Get a free key (opens browser)") { openStudio() }, lp(topDp = 10, context = ctx))
        scaffold.addBody(about, 16)

        saved = pill(ctx, "…", Palette.TEXT_DIM)
        scaffold.addBody(saved, 16)
        storage = text(ctx, "", 13f, Palette.AMBER)
        scaffold.addBody(storage, 8)

        input = EditText(ctx).apply {
            hint = "Paste your Gemini API key"
            setHintTextColor(withAlpha(Palette.TEXT_DIM, 140))
            setTextColor(Palette.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            maxLines = 1
            isSingleLine = true
            background = glass(ctx, 16)
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            // The key must not be restored from saved instance state; the in-memory draft survives rotation instead.
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(vm.draftKey)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    vm.draftKey = s?.toString() ?: ""
                }
            })
        }
        scaffold.addBody(input, 8)

        toggle = textButton(ctx, "Show key") {
            shown = !shown
            applyVisibility()
        }
        scaffold.addBody(toggle, 0)

        result = text(ctx, "", 14f, Palette.TEXT_DIM)
        scaffold.addBody(result, 4)

        testBtn = secondaryButton(ctx, "Test key") { vm.testKey(input.text.toString()) }
        scaffold.addBody(testBtn, 12)
        saveBtn = secondaryButton(ctx, "Save key securely", Palette.MINT) { save() }
        scaffold.addBody(saveBtn, 8)

        primary = primaryButton(ctx, "Continue") { advance(vm.live.value.aiConfigured) }
        standardFooter(primary, "Skip, I'll add it later")

        applyVisibility()
        onLive { renderLive(it) }
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.api.collect { renderApi(it) } }
        }
    }

    private fun applyVisibility() {
        val selection = input.selectionStart
        input.transformationMethod = if (shown) null else PasswordTransformationMethod.getInstance()
        toggle.text = if (shown) "Hide key" else "Show key"
        if (selection >= 0) input.setSelection(minOf(selection, input.text.length))
    }

    private fun renderLive(live: LiveStatus) {
        when {
            !live.aiConfigured -> restyle(saved, "NO KEY SAVED", Palette.AMBER)
            live.lastApiCheck?.ok == true -> restyle(saved, "KEY SAVED · VERIFIED", Palette.MINT)
            live.lastApiCheck != null -> restyle(saved, "KEY SAVED · LAST TEST FAILED", Palette.ROSE)
            else -> restyle(saved, "KEY SAVED · NOT TESTED", Palette.CYAN)
        }
        if (live.groqSelected) {
            storage.visibility = View.VISIBLE
            storage.text = "Another AI engine (Groq) is currently selected in your settings; this step configures Gemini only."
        } else if (!live.secureStorageAvailable) {
            storage.visibility = View.VISIBLE
            storage.text = "Secure key storage isn't available on this device, so JARVIS can't save a key here."
        } else {
            storage.visibility = View.GONE
        }
        saveBtn.isEnabled = live.secureStorageAvailable
        saveBtn.alpha = if (live.secureStorageAvailable) 1f else 0.4f
        primary.text = if (live.aiConfigured) "Continue" else "Continue without a key"
    }

    private fun renderApi(state: ApiUiState) {
        testBtn.isEnabled = !state.testing
        testBtn.text = if (state.testing) "Testing…" else "Test key"
        result.text = state.message ?: ""
        result.setTextColor(
            when (state.ok) {
                true -> Palette.MINT
                false -> Palette.ROSE
                null -> Palette.TEXT_DIM
            }
        )
    }

    private fun save() {
        val error = vm.saveKey(input.text.toString())
        if (error != null) {
            result.text = error
            result.setTextColor(Palette.ROSE)
            return
        }
        input.setText("")
        shown = false
        applyVisibility()
        result.text = "Key saved securely."
        result.setTextColor(Palette.MINT)
    }

    private fun openStudio() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        } catch (_: Exception) {
            result.text = "No browser found. Visit aistudio.google.com/apikey on any device."
            result.setTextColor(Palette.AMBER)
        }
    }
}
