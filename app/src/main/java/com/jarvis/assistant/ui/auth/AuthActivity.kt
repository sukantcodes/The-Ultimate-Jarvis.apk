package com.jarvis.assistant.ui.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.auth.AuthError
import com.jarvis.assistant.auth.AuthFlow
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.auth.AuthResult
import com.jarvis.assistant.auth.AuthValidators
import com.jarvis.assistant.auth.CallbackOutcome
import com.jarvis.assistant.auth.FieldIssue
import com.jarvis.assistant.auth.SignUpOutcome
import com.jarvis.assistant.databinding.ActivityAuthBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch

/**
 * Login, Register, Forgot-password and Set-new-password flows, plus Guest Mode.
 *
 * Launched two ways:
 *  - Root mode (app start with no session and no guest choice): success or "Continue as guest" opens MainActivity.
 *  - Return mode ([EXTRA_RETURN_RESULT]; from the drawer or a feature that needs an account): finishes with
 *    RESULT_OK on success so the caller can continue what the user originally asked for.
 *
 * Signing in only proves identity; it never grants a Pro entitlement.
 */
class AuthActivity : AppCompatActivity() {

    private enum class Mode { LOGIN, REGISTER, FORGOT, NEW_PASSWORD }

    private lateinit var binding: ActivityAuthBinding
    private var mode = Mode.LOGIN
    private var busy = false
    private var lastGoogleLaunch = 0L
    // Captured once: the OAuth callback re-delivers a different intent that has no extras.
    private var returnMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        returnMode = intent.getBooleanExtra(EXTRA_RETURN_RESULT, false) ||
            savedInstanceState?.getBoolean(EXTRA_RETURN_RESULT, false) == true
        AuthManager.init(applicationContext)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.authRoot)

        mode = savedInstanceState?.getString(STATE_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
            ?: Mode.LOGIN
        wireClicks()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    busy -> Unit
                    mode != Mode.LOGIN && mode != Mode.NEW_PASSWORD -> switchTo(Mode.LOGIN)
                    returnMode -> { setResult(RESULT_CANCELED); finish() }
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })

        render()
        if (savedInstanceState == null) playEntrance()
        AuthManager.consumeNotice()?.let { showStatus(it, error = false) }
        handleCallbackIntent(intent)
    }

    /** Gentle staggered fade-and-rise for the logo, brand and card on first open. */
    private fun playEntrance() {
        val rise = resources.displayMetrics.density * 22f
        binding.ivAuthLogo.apply { alpha = 0f; scaleX = 0.8f; scaleY = 0.8f }
        binding.tvAuthBrand.apply { alpha = 0f; translationY = rise }
        binding.cardAuth.apply { alpha = 0f; translationY = rise * 1.6f }
        binding.ivAuthLogo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520L)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f)).start()
        binding.tvAuthBrand.animate().alpha(1f).translationY(0f).setStartDelay(140L).setDuration(420L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        binding.cardAuth.animate().alpha(1f).translationY(0f).setStartDelay(260L).setDuration(520L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCallbackIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, mode.name)
        outState.putBoolean(EXTRA_RETURN_RESULT, returnMode)
    }

    // ── Wiring ──────────────────────────────────────────────────────────────────────────────────

    private fun wireClicks() = with(binding) {
        btnPrimary.setOnClickListener { submit() }
        btnGoogle.setOnClickListener { startGoogle() }
        btnGuest.setOnClickListener { continueAsGuest() }
        tvForgot.setOnClickListener { if (!busy) switchTo(Mode.FORGOT) }
        tvSwitch.setOnClickListener {
            if (busy) return@setOnClickListener
            switchTo(if (mode == Mode.LOGIN) Mode.REGISTER else Mode.LOGIN)
        }
        etConfirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (mode == Mode.LOGIN && actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
    }

    private fun switchTo(newMode: Mode) {
        mode = newMode
        clearErrors()
        hideStatus()
        render()
    }

    private fun render() = with(binding) {
        val login = mode == Mode.LOGIN
        val register = mode == Mode.REGISTER
        val forgot = mode == Mode.FORGOT
        val newPass = mode == Mode.NEW_PASSWORD

        tvAuthTitle.setText(
            when (mode) {
                Mode.LOGIN -> R.string.auth_login_title
                Mode.REGISTER -> R.string.auth_register_title
                Mode.FORGOT -> R.string.auth_forgot_title
                Mode.NEW_PASSWORD -> R.string.auth_newpass_title
            }
        )
        tvAuthSubtitle.setText(
            when (mode) {
                Mode.LOGIN -> R.string.auth_login_subtitle
                Mode.REGISTER -> R.string.auth_register_subtitle
                Mode.FORGOT -> R.string.auth_forgot_subtitle
                Mode.NEW_PASSWORD -> R.string.auth_newpass_subtitle
            }
        )
        btnPrimary.setText(
            when (mode) {
                Mode.LOGIN -> R.string.auth_action_login
                Mode.REGISTER -> R.string.auth_action_register
                Mode.FORGOT -> R.string.auth_action_send_reset
                Mode.NEW_PASSWORD -> R.string.auth_action_save_password
            }
        )
        tilEmail.visibility = if (newPass) View.GONE else View.VISIBLE
        tilPassword.visibility = if (forgot) View.GONE else View.VISIBLE
        tilPassword.hint = getString(if (newPass) R.string.auth_new_password_hint else R.string.auth_password_hint)
        tilConfirm.visibility = if (register || newPass) View.VISIBLE else View.GONE
        etPassword.imeOptions = if (login) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        tvForgot.visibility = if (login) View.VISIBLE else View.GONE
        // Social sign-in only where it makes sense.
        val showSocial = login || register
        rowOr.visibility = if (showSocial) View.VISIBLE else View.GONE
        btnGoogle.visibility = if (showSocial) View.VISIBLE else View.GONE
        tvSwitch.setText(
            when (mode) {
                Mode.LOGIN -> R.string.auth_switch_to_register
                Mode.REGISTER -> R.string.auth_switch_to_login
                Mode.FORGOT -> R.string.auth_back_to_login
                Mode.NEW_PASSWORD -> R.string.auth_back_to_login
            }
        )
        tvSwitch.visibility = if (newPass) View.GONE else View.VISIBLE
        btnGuest.setText(if (returnMode) R.string.auth_action_not_now else R.string.auth_action_guest)
        applyBusy()
    }

    // ── Actions ─────────────────────────────────────────────────────────────────────────────────

    private fun submit() {
        if (busy) return
        val email = binding.etEmail.text?.toString().orEmpty().trim()
        val password = binding.etPassword.text?.toString().orEmpty()
        val confirm = binding.etConfirm.text?.toString().orEmpty()
        clearErrors()
        hideStatus()

        when (mode) {
            Mode.LOGIN -> {
                val issues = AuthValidators.validate(email, password, "", register = false)
                if (!applyIssues(issues.email, issues.password, null)) return
                run(R.string.auth_status_signing_in) {
                    when (val r = AuthManager.signIn(email, password)) {
                        is AuthResult.Success -> onAuthenticated()
                        is AuthResult.Failure -> showStatus(r.message, error = true)
                    }
                }
            }
            Mode.REGISTER -> {
                val issues = AuthValidators.validate(email, password, confirm, register = true)
                if (!applyIssues(issues.email, issues.password, issues.confirm)) return
                run(R.string.auth_status_creating) {
                    when (val r = AuthManager.signUp(email, password)) {
                        is AuthResult.Failure -> showStatus(r.message, error = true)
                        is AuthResult.Success -> when (r.value) {
                            is SignUpOutcome.SignedIn -> onAuthenticated()
                            SignUpOutcome.ConfirmationRequired -> {
                                switchTo(Mode.LOGIN)
                                binding.etEmail.setText(email)
                                showStatus(getString(R.string.auth_info_confirm_email), error = false)
                            }
                            SignUpOutcome.AlreadyRegistered ->
                                showStatus(
                                    com.jarvis.assistant.auth.SupabaseResponseParser
                                        .messageFor(AuthError.ALREADY_REGISTERED, ""),
                                    error = true
                                )
                        }
                    }
                }
            }
            Mode.FORGOT -> {
                val issues = AuthValidators.validate(email, "x", "", register = false)
                if (!applyIssues(issues.email, null, null)) return
                run(R.string.auth_status_sending) {
                    when (val r = AuthManager.sendPasswordReset(email)) {
                        // Same message whether or not the account exists, so addresses can't be probed.
                        is AuthResult.Success -> {
                            switchTo(Mode.LOGIN)
                            binding.etEmail.setText(email)
                            showStatus(getString(R.string.auth_info_reset_sent), error = false)
                        }
                        is AuthResult.Failure -> showStatus(r.message, error = true)
                    }
                }
            }
            Mode.NEW_PASSWORD -> {
                val issues = AuthValidators.validateNewPassword(password, confirm)
                if (!applyIssues(null, issues.password, issues.confirm)) return
                run(R.string.auth_status_saving) {
                    when (val r = AuthManager.updatePassword(password)) {
                        is AuthResult.Success -> onAuthenticated()
                        is AuthResult.Failure -> showStatus(r.message, error = true)
                    }
                }
            }
        }
    }

    private fun startGoogle() {
        if (busy) return
        // Ignore rapid repeat taps: each tap would start a new PKCE flow and invalidate the previous one.
        val now = SystemClock.elapsedRealtime()
        if (now - lastGoogleLaunch < 3000L) return
        hideStatus()
        when (val r = AuthManager.beginGoogleSignIn()) {
            is AuthResult.Failure -> showStatus(r.message, error = true)
            is AuthResult.Success -> {
                lastGoogleLaunch = now
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.value)))
                    showStatus(getString(R.string.auth_status_google_browser), error = false)
                } catch (_: ActivityNotFoundException) {
                    lastGoogleLaunch = 0L
                    showStatus(getString(R.string.auth_err_no_browser), error = true)
                }
            }
        }
    }

    private fun continueAsGuest() {
        if (busy) return
        if (returnMode) {
            setResult(RESULT_CANCELED)
            finish()
        } else {
            AuthManager.continueAsGuest()
            openJarvis()
        }
    }

    private fun handleCallbackIntent(callback: Intent?) {
        val uri = callback?.data ?: return
        callback.data = null // consume once so a recreate or re-delivery can't replay the link
        lifecycleScope.launch {
            setBusy(true, getString(R.string.auth_status_finishing))
            val outcome = AuthManager.handleCallback(uri)
            setBusy(false, null)
            when (outcome) {
                null -> hideStatus()
                is CallbackOutcome.Completed ->
                    if (outcome.flow == AuthFlow.RECOVERY) {
                        switchTo(Mode.NEW_PASSWORD)
                    } else {
                        onAuthenticated()
                    }
                is CallbackOutcome.Info -> { switchTo(Mode.LOGIN); showStatus(outcome.message, error = false) }
                is CallbackOutcome.Failed ->
                    showStatus(outcome.message, error = outcome.error != AuthError.CANCELLED)
            }
        }
    }

    private fun onAuthenticated() {
        hideKeyboard()
        if (returnMode) {
            setResult(RESULT_OK)
            finish()
        } else {
            openJarvis()
        }
    }

    private fun openJarvis() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_POST_LOGIN_WELCOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Runs [block] with the busy state on, blocking repeat submissions. */
    private fun run(statusRes: Int, block: suspend () -> Unit) {
        setBusy(true, getString(statusRes))
        lifecycleScope.launch {
            try {
                block()
            } finally {
                setBusy(false, null)
            }
        }
    }

    private fun setBusy(value: Boolean, message: String?) {
        busy = value
        if (value && message != null) showStatus(message, error = false)
        if (!value && message == null) { /* leave the final status text in place */ }
        applyBusy()
    }

    private fun applyBusy() = with(binding) {
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        val enabled = !busy
        listOf<View>(btnPrimary, btnGoogle, btnGuest, tvForgot, tvSwitch, etEmail, etPassword, etConfirm)
            .forEach { it.isEnabled = enabled }
    }

    private fun applyIssues(email: FieldIssue?, password: FieldIssue?, confirm: FieldIssue?): Boolean {
        binding.tilEmail.error = email?.let {
            getString(if (it == FieldIssue.REQUIRED) R.string.auth_err_email_required else R.string.auth_err_email_invalid)
        }
        binding.tilPassword.error = password?.let {
            when (it) {
                FieldIssue.TOO_SHORT -> getString(R.string.auth_err_password_short, AuthValidators.MIN_PASSWORD_LENGTH)
                else -> getString(R.string.auth_err_password_required)
            }
        }
        binding.tilConfirm.error = confirm?.let {
            getString(if (it == FieldIssue.MISMATCH) R.string.auth_err_confirm_mismatch else R.string.auth_err_confirm_required)
        }
        val firstBad: TextInputLayout? = listOf(binding.tilEmail, binding.tilPassword, binding.tilConfirm)
            .firstOrNull { it.error != null }
        firstBad?.editText?.requestFocus()
        return firstBad == null
    }

    private fun clearErrors() {
        binding.tilEmail.error = null
        binding.tilPassword.error = null
        binding.tilConfirm.error = null
    }

    private fun showStatus(message: String, error: Boolean) = with(binding.tvStatus) {
        text = message
        setTextColor(
            if (error) ContextCompat.getColor(context, R.color.status_red)
            else ContextCompat.getColor(context, R.color.status_green)
        )
        visibility = View.VISIBLE
    }

    private fun hideStatus() { binding.tvStatus.visibility = View.GONE }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    /** Lets a feature that needs an account launch sign-in and continue when it succeeds. */
    class RequireAccount : ActivityResultContract<Unit, Boolean>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(context, AuthActivity::class.java).putExtra(EXTRA_RETURN_RESULT, true)

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            resultCode == android.app.Activity.RESULT_OK
    }

    companion object {
        const val EXTRA_RETURN_RESULT = "com.jarvis.assistant.auth.RETURN_RESULT"
        private const val STATE_MODE = "auth_mode"

        /** Builds the auth intent; [returnToCaller] finishes with a result instead of opening MainActivity. */
        fun intent(context: Context, returnToCaller: Boolean = false): Intent =
            Intent(context, AuthActivity::class.java).apply {
                if (returnToCaller) putExtra(EXTRA_RETURN_RESULT, true)
            }

        /** Intent for the drawer's "Sign in" entry: returns to the running app instead of restarting it. */
        fun returnIntent(context: Context): Intent =
            Intent(context, AuthActivity::class.java).putExtra(EXTRA_RETURN_RESULT, true)
    }
}
