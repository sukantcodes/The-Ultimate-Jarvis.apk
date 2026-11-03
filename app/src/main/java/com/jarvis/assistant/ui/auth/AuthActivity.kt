package com.jarvis.assistant.ui.auth

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.ui.home.MainActivity
import kotlinx.coroutines.launch

/**
 * Email authentication entry point. Guest access is deliberately available without an account.
 * Pro entitlement is not granted by authentication; entitlement checks must be performed separately.
 */
class AuthActivity : AppCompatActivity() {
    private enum class Page { LOGIN, REGISTER }
    private var page = Page.LOGIN
    private lateinit var root: LinearLayout
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var action: Button
    private lateinit var switch: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        renderPage()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(36), dp(28), dp(28))
            setBackgroundColor(Color.rgb(8, 10, 18))
        }
        val title = TextView(this).apply {
            text = "JARVIS"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Your assistant. Your choice."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        email = field("Email address", false)
        password = field("Password (min. 6 characters)", true)
        action = Button(this)
        switch = TextView(this).apply {
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener {
                page = if (page == Page.LOGIN) Page.REGISTER else Page.LOGIN
                renderPage()
            }
        }
        status = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        val guest = Button(this).apply {
            text = "Continue as Guest"
            setOnClickListener { openJarvis() }
        }
        root.addView(title, params(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(subtitle, params(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
        root.addView(email, params(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        root.addView(password, params(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        root.addView(action, params(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(switch, params(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(status, params(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        root.addView(guest, params(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
        setContentView(root)
    }

    private fun field(hintText: String, secret: Boolean) = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.GRAY)
        setTextColor(Color.WHITE)
        textSize = 16f
        setSingleLine(true)
        inputType = if (secret) 129 else 33
        setPadding(dp(14), 0, dp(14), 0)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.CYAN)
    }

    private fun renderPage() {
        val registering = page == Page.REGISTER
        action.text = if (registering) "Create account" else "Login"
        switch.text = if (registering) "Already registered? Login" else "New here? Create account"
        status.text = ""
        action.setOnClickListener { submit() }
    }

    private fun submit() {
        val e = email.text.toString().trim()
        val p = password.text.toString()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
            email.error = "Enter a valid email"
            return
        }
        if (p.length < 6) {
            password.error = "Password must be at least 6 characters"
            return
        }
        action.isEnabled = false
        status.text = "Connecting securely…"
        lifecycleScope.launch {
            val result = SupabaseEmailAuth.authenticate(e, p, page == Page.REGISTER)
            action.isEnabled = true
            status.text = result.message
            if (result.success) {
                if (page == Page.REGISTER && result.needsEmailConfirmation) {
                    Toast.makeText(this@AuthActivity, "Check your inbox to confirm your email, then login.", Toast.LENGTH_LONG).show()
                    page = Page.LOGIN
                    renderPage()
                } else {
                    openJarvis()
                }
            }
        }
    }

    private fun openJarvis() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun params(w: Int, h: Int) = LinearLayout.LayoutParams(w, h).apply {
        bottomMargin = dp(8)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
