package com.jarvis.assistant.tools

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Explicit local setup for optional GitHub repository access. */
class GitHubConnectionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 20).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val title = TextView(this).apply { text = "GitHub connection"; textSize = 24f; setPadding(0, 0, 0, pad) }
        val intro = TextView(this).apply {
            text = "Connect an optional GitHub fine-grained personal access token and restrict it to only the repositories JARVIS needs. For browsing and importing, grant Metadata (read) and Contents (read). To create feature branches or commit approved workspace files, also grant Contents (read and write). To open pull requests, grant Pull requests (read and write). JARVIS stores the token encrypted on this device and sends it only to api.github.com; it is never added to chat prompts or tool results. Repository writes require a separate in-app approval. JARVIS blocks direct commits to the repository default branch and does not merge pull requests."
            textSize = 14f
            setPadding(0, 0, 0, pad)
        }
        val token = EditText(this).apply {
            hint = "GitHub personal access token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val status = TextView(this).apply {
            text = if (GitHubCredentialStore.isConfigured(this@GitHubConnectionActivity)) "An encrypted token is saved on this device." else "No GitHub token saved. Public repositories can still be read."
            setPadding(0, pad, 0, pad)
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
                            status.text = "Encrypted token saved. Repository access will verify it on the next GitHub request."
                            Toast.makeText(this@GitHubConnectionActivity, "GitHub token saved securely.", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { Toast.makeText(this@GitHubConnectionActivity, "Secure storage unavailable: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
        val test = Button(this).apply {
            text = "Test GitHub connection"
            setOnClickListener {
                if (!GitHubCredentialStore.isConfigured(this@GitHubConnectionActivity)) {
                    Toast.makeText(this@GitHubConnectionActivity, "Save a token first.", Toast.LENGTH_SHORT).show()
                } else {
                    isEnabled = false
                    status.text = "Checking GitHub authentication…"
                    lifecycleScope.launch {
                        try {
                            val account = GitHubWriteTools.validateConnection(this@GitHubConnectionActivity)
                            status.text = "Connected and authenticated as @$account. Token remains encrypted on this device."
                            Toast.makeText(this@GitHubConnectionActivity, "GitHub connection verified.", Toast.LENGTH_SHORT).show()
                        } catch (error: Exception) {
                            status.text = "Connection check failed: ${error.message ?: "Check token permissions and network."}"
                        } finally {
                            isEnabled = true
                        }
                    }
                }
            }
        }
        val remove = Button(this).apply {
            text = "Remove saved token"
            setOnClickListener {
                runCatching { GitHubCredentialStore.clear(this@GitHubConnectionActivity) }
                    .onSuccess { token.setText(""); status.text = "Saved GitHub token removed."; Toast.makeText(this@GitHubConnectionActivity, "Token removed.", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(this@GitHubConnectionActivity, "Could not remove token: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
        root.addView(title)
        root.addView(intro)
        root.addView(token, LinearLayout.LayoutParams(-1, -2))
        root.addView(save)
        root.addView(test)
        root.addView(remove)
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
