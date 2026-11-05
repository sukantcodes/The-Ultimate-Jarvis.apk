package com.jarvis.assistant.ui.owner

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.auth.AuthManager
import com.jarvis.assistant.databinding.ActivityOwnerPasswordBinding
import com.jarvis.assistant.owner.OwnerDashboardClient
import com.jarvis.assistant.owner.OwnerResult
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch

/**
 * Settings → Owner Section → Dashboard → this screen. The typed password goes straight to the backend
 * for verification; it is not stored, logged or kept after the request. The dashboard opens only after
 * the server accepts it.
 */
class OwnerPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOwnerPasswordBinding
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        // Keeps the password field out of screenshots and the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityOwnerPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnUnlock.setOnClickListener { submit() }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        if (!AuthManager.isSignedIn) showError(getString(R.string.owner_pw_need_account))
    }

    private fun submit() {
        if (busy) return
        val text = binding.etPassword.text
        if (text.isNullOrEmpty()) return
        if (!AuthManager.isSignedIn) { showError(getString(R.string.owner_pw_need_account)); return }

        val password = CharArray(text.length).also { text.getChars(0, text.length, it, 0) }
        binding.etPassword.text?.clear()
        setBusy(true)
        lifecycleScope.launch {
            val result = OwnerDashboardClient.verify(password)
            setBusy(false)
            when (result) {
                is OwnerResult.Ok -> {
                    startActivity(Intent(this@OwnerPasswordActivity, OwnerDashboardActivity::class.java))
                    finish()
                }
                is OwnerResult.Locked -> showError(getString(R.string.owner_pw_locked, formatWait(result.retryAfterSec)))
                OwnerResult.Denied -> showError(getString(R.string.owner_pw_denied))
                OwnerResult.Unavailable -> showError(getString(R.string.owner_pw_unavailable))
                OwnerResult.Network -> showError(getString(R.string.owner_pw_network))
                OwnerResult.NotSignedIn -> showError(getString(R.string.owner_pw_need_account))
                else -> showError(getString(R.string.owner_pw_error))
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.btnUnlock.text = getString(if (value) R.string.owner_pw_verifying else R.string.owner_pw_unlock)
        binding.btnUnlock.alpha = if (value) 0.6f else 1f
        binding.etPassword.isEnabled = !value
        if (value) binding.tvPasswordError.visibility = android.view.View.GONE
    }

    private fun showError(message: String) {
        binding.tvPasswordError.text = message
        binding.tvPasswordError.visibility = android.view.View.VISIBLE
    }

    private fun formatWait(seconds: Int): String =
        if (seconds >= 60) "${(seconds + 59) / 60} min" else "$seconds s"
}
