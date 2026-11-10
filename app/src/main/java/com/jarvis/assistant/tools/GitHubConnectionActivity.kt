package com.jarvis.assistant.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

/** GitHub Device Flow sign-in with an encrypted local credential and optional PAT fallback. */
class GitHubConnectionActivity : AppCompatActivity() {
    private var signInJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply {
            text = "GitHub connection"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(R.attr.themeTextPrimary))
            setPadding(0, 0, 0, pad)
        }
        val intro = TextView(this).apply {
            text = "Sign in securely using GitHub Device Flow. JARVIS shows a one-time code; approve it on GitHub and the app completes sign-in automatically. No client secret is embedded. The OAuth sign-in requests read:user and public_repo only, so it can access public repositories but not private repositories. GitHub writes still require JARVIS's separate in-app approval."
            textSize = 14f
            setTextColor(themeColor(R.attr.themeTextSecondary))
            setPadding(0, 0, 0, dp(12))
        }
        val status = TextView(this).apply {
            text = if (GitHubCredentialStore.isConfigured(this@GitHubConnectionActivity)) {
                "An encrypted GitHub credential is saved on this device. Test the connection to verify it."
            } else "Not connected. Public repositories can still be read without signing in."
            setTextColor(themeColor(R.attr.themeTextSecondary))
            setPadding(0, dp(12), 0, dp(12))
        }
        val signIn = Button(this).apply {
            text = "Sign in with GitHub"
            setOnClickListener { beginDeviceSignIn(status, this) }
        }
        val test = Button(this).apply {
            text = "Test GitHub connection"
            setOnClickListener {
                if (!GitHubCredentialStore.isConfigured(this@GitHubConnectionActivity)) {
                    Toast.makeText(this@GitHubConnectionActivity, "Sign in with GitHub first.", Toast.LENGTH_SHORT).show()
                } else {
                    isEnabled = false
                    status.text = "Checking GitHub authentication…"
                    lifecycleScope.launch {
                        try {
                            val account = GitHubWriteTools.validateConnection(this@GitHubConnectionActivity)
                            status.text = "Connected and authenticated as @$account. Credential is encrypted on this device."
                            Toast.makeText(this@GitHubConnectionActivity, "GitHub connection verified.", Toast.LENGTH_SHORT).show()
                        } catch (error: Exception) {
                            status.text = "Connection check failed: ${error.message ?: "Check network or credential permissions."}"
                        } finally {
                            isEnabled = true
                        }
                    }
                }
            }
        }

        // Optional compatibility path for users who already manage a fine-grained PAT.
        val fallbackTitle = TextView(this).apply {
            text = "Advanced fallback · Personal access token"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(R.attr.themeTextPrimary))
            setPadding(0, dp(22), 0, dp(6))
        }
        val fallbackInfo = TextView(this).apply {
            text = "Only use a fine-grained token restricted to the repositories and permissions you need. It is stored using Android encrypted preferences."
            textSize = 12f
            setTextColor(themeColor(R.attr.themeTextMuted))
            setPadding(0, 0, 0, dp(8))
        }
        val token = EditText(this).apply {
            hint = "Fine-grained GitHub token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val save = Button(this).apply {
            text = "Save encrypted token"
            setOnClickListener {
                val value = token.text?.toString()?.trim().orEmpty()
                if (value.length < 20 || value.any { it.isWhitespace() }) {
                    Toast.makeText(this@GitHubConnectionActivity, "Enter a valid token value.", Toast.LENGTH_LONG).show()
                } else {
                    runCatching { GitHubCredentialStore.save(this@GitHubConnectionActivity, value) }
                        .onSuccess {
                            token.setText("")
                            status.text = "Encrypted credential saved. Test the connection to verify it."
                            Toast.makeText(this@GitHubConnectionActivity, "GitHub credential saved securely.", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { Toast.makeText(this@GitHubConnectionActivity, "Secure storage unavailable: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
        val remove = Button(this).apply {
            text = "Disconnect / remove saved credential"
            setOnClickListener {
                runCatching { GitHubCredentialStore.clear(this@GitHubConnectionActivity) }
                    .onSuccess {
                        token.setText("")
                        status.text = "GitHub credential removed from this device. You can still read public repositories."
                        Toast.makeText(this@GitHubConnectionActivity, "Disconnected.", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { Toast.makeText(this@GitHubConnectionActivity, "Could not remove credential: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }

        root.addView(title)
        root.addView(intro)
        root.addView(signIn)
        root.addView(test)
        root.addView(status)
        root.addView(fallbackTitle)
        root.addView(fallbackInfo)
        root.addView(token, LinearLayout.LayoutParams(-1, -2))
        root.addView(save)
        root.addView(remove)
        val scroll = ScrollView(this).apply { addView(root) }
        ThemeManager.applyDynamicBackground(scroll)
        setContentView(scroll)
    }

    private fun beginDeviceSignIn(status: TextView, button: Button) {
        if (signInJob?.isActive == true) return
        button.isEnabled = false
        status.text = "Requesting a secure GitHub sign-in code…"
        signInJob = lifecycleScope.launch {
            var codeDialog: AlertDialog? = null
            try {
                val authorization = GitHubDeviceFlow.requestAuthorization()
                val codeView = LinearLayout(this@GitHubConnectionActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(24), dp(12), dp(24), dp(8))
                }
                codeView.addView(TextView(this@GitHubConnectionActivity).apply {
                    text = "Enter this one-time code on GitHub"
                    textSize = 14f
                })
                codeView.addView(TextView(this@GitHubConnectionActivity).apply {
                    text = authorization.userCode
                    textSize = 30f
                    letterSpacing = 0.12f
                    typeface = Typeface.MONOSPACE
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(12), 0, dp(12))
                })
                codeView.addView(TextView(this@GitHubConnectionActivity).apply {
                    text = "The code expires in about ${authorization.expiresInSeconds / 60} minutes. JARVIS will wait for approval automatically."
                    textSize = 12f
                })
                codeDialog = AlertDialog.Builder(this@GitHubConnectionActivity)
                    .setTitle("Authorize JARVIS")
                    .setView(codeView)
                    .setPositiveButton("Open GitHub") { _, _ -> openVerificationPage(authorization.verificationUri) }
                    .setNeutralButton("Copy code") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("GitHub device code", authorization.userCode))
                        Toast.makeText(this@GitHubConnectionActivity, "One-time code copied.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel") { _, _ -> signInJob?.cancel() }
                    .create()
                codeDialog.setOnCancelListener { signInJob?.cancel() }
                codeDialog.show()
                status.text = "Waiting for GitHub approval. Open the verification page and enter the displayed code…"

                val accessToken = GitHubDeviceFlow.awaitAccessToken(authorization) { interval ->
                    status.text = "Waiting for GitHub approval… next secure check in ${interval}s."
                }
                // Never show or log the access token. Persist it only in encrypted preferences.
                GitHubCredentialStore.save(this@GitHubConnectionActivity, accessToken)
                val account = GitHubWriteTools.validateConnection(this@GitHubConnectionActivity)
                codeDialog.dismiss()
                status.text = "Connected and authenticated as @$account. Credential is encrypted on this device."
                Toast.makeText(this@GitHubConnectionActivity, "GitHub sign-in successful.", Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                status.text = "GitHub sign-in cancelled."
                throw cancelled
            } catch (error: Exception) {
                status.text = "GitHub sign-in failed: ${error.message ?: "Check your connection and try again."}"
            } finally {
                codeDialog?.dismiss()
                button.isEnabled = true
                signInJob = null
            }
        }
    }

    private fun openVerificationPage(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "Could not open GitHub. Visit https://github.com/login/device manually.", Toast.LENGTH_LONG).show() }
    }

    private fun themeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return if (value.resourceId != 0) getColor(value.resourceId) else value.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        signInJob?.cancel()
        signInJob = null
        super.onDestroy()
    }
}
