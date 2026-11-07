package com.jarvis.assistant.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.ui.settings.ApiConnectionActivity
import com.jarvis.assistant.util.PermissionHelper

/**
 * First-run setup guide. Every status shown here is derived from Android or the app's
 * existing persisted configuration; this Activity never grants special access itself.
 */
class SetupTutorialActivity : AppCompatActivity() {
    private val prefs by lazy { JarvisApp.instance.preferences }
    private lateinit var content: LinearLayout
    private lateinit var progress: TextView
    private lateinit var primary: MaterialButton
    private lateinit var secondary: MaterialButton
    private var step = 0
    private var returnedFromSettings = false
    private var draftName: String? = null

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { render() }

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { /* Photo Picker grants may be temporary on some providers. */ }
            prefs.profilePhotoUri = uri.toString()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getInt(STATE_STEP) ?: prefs.setupTutorialStep
        buildShell()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_STEP, step)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun buildShell() {
        window.statusBarColor = Color.rgb(5, 9, 22)
        window.navigationBarColor = Color.rgb(5, 9, 22)
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(5, 9, 22))
            addView(OrbBackdrop(this@SetupTutorialActivity), FrameLayout.LayoutParams(-1, -1))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        val brand = TextView(this).apply {
            text = "J A R V I S   •   SYSTEM SETUP"
            textSize = 11f
            letterSpacing = .18f
            setTextColor(Color.rgb(103, 216, 255))
            gravity = Gravity.CENTER
        }
        column.addView(brand, LinearLayout.LayoutParams(-1, dp(34)))
        progress = TextView(this).apply {
            textSize = 11f
            letterSpacing = .12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(145, 164, 195))
            setPadding(0, dp(8), 0, dp(12))
        }
        column.addView(progress)
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, dp(18))
        }
        scroll.addView(content, ScrollView.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        primary = MaterialButton(this).apply {
            text = "CONTINUE"
            cornerRadius = dp(18)
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(35, 128, 255))
            isAllCaps = false
        }
        secondary = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "SKIP FOR NOW"
            isAllCaps = false
            setTextColor(Color.rgb(165, 183, 211))
            strokeColor = android.content.res.ColorStateList.valueOf(Color.rgb(65, 88, 125))
        }
        actions.addView(primary, LinearLayout.LayoutParams(-1, dp(54)))
        actions.addView(secondary, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) })
        column.addView(actions)
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        primary.setOnClickListener { advance() }
        secondary.setOnClickListener { skipOrBack() }
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        prefs.setupTutorialStep = step
        content.removeAllViews()
        progress.text = "SETUP  •  ${(step + 1).coerceAtMost(7)} / 7"
        when (step) {
            0 -> {
                hero("WELCOME TO THE ALL NEW JARVIS", "Your personal AI assistant is almost ready. Let’s configure the essentials together.", "✦")
                infoCard("CREATED AND DESIGNED BY SUKANTA BHATTACHARYA", "A guided setup for your profile, AI connection, permissions and supported app controls.")
                primary.text = "BEGIN SETUP"
                secondary.text = "SKIP TUTORIAL"
            }
            1 -> {
                hero("PERMISSIONS", "JARVIS asks only when a feature needs access. You remain in control.", "◈")
                permissionRow("Microphone", "Required for voice conversations", hasPermission(Manifest.permission.RECORD_AUDIO))
                if (Build.VERSION.SDK_INT >= 33) permissionRow("Notifications", "Used for visible service and task updates", hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                else permissionRow("Notifications", "Controlled by Android notification settings", true)
                infoCard("OTHER PERMISSIONS", "Camera, contacts, location and media access are requested contextually when you use the related feature—not all at once during setup.")
                primary.text = if (corePermissionsGranted()) "CONTINUE" else "ALLOW REQUIRED PERMISSIONS"
                secondary.text = "BACK"
            }
            2 -> {
                hero("YOUR USER CARD", "Personalize the card JARVIS uses to address you.", "◎")
                val avatar = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(104), dp(104)).apply { bottomMargin = dp(12) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.rgb(23, 39, 68))
                    val uri = prefs.profilePhotoUri
                    if (uri.isNotBlank()) runCatching { setImageURI(Uri.parse(uri)) }
                    if (drawable == null) setImageResource(R.drawable.ic_user_profile)
                    clipToOutline = true
                }
                content.addView(avatar)
                val photo = actionButton(if (prefs.profilePhotoUri.isBlank()) "CHOOSE PROFILE PHOTO" else "CHANGE PROFILE PHOTO") {
                    photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                content.addView(photo, fullWidth())
                val nameLayout = TextInputLayout(this).apply {
                    hint = "Your display name"
                    boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                    setBoxStrokeColor(Color.rgb(72, 140, 255))
                }
                val nameInput = TextInputEditText(nameLayout.context).apply {
                    setText(draftName ?: prefs.userName)
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draftName = s?.toString() }
                        override fun afterTextChanged(s: android.text.Editable?) = Unit
                    })
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                    textSize = 16f
                }
                nameLayout.addView(nameInput)
                content.addView(nameLayout, fullWidth().apply { topMargin = dp(14) })
                infoCard("PROFILE PREVIEW", "Name: ${prefs.userName}\nPhoto: ${if (prefs.profilePhotoUri.isBlank()) "Default avatar" else "Selected photo"}")
                primary.text = "SAVE PROFILE & CONTINUE"
                primary.setOnClickListener {
                    val newName = nameInput.text?.toString()?.trim().orEmpty()
                    if (newName.isNotBlank()) prefs.userName = newName
                    draftName = prefs.userName
                    step++
                    render()
                }
                secondary.text = "BACK"
            }
            3 -> {
                hero("CONNECT YOUR AI", "Configure the Gemini API using JARVIS’s existing secure configuration screen.", "⌘")
                val configured = prefs.apiKey.isNotBlank() && prefs.canStoreApiKeySecurely
                statusCard("Gemini API", if (configured) "A key is saved in encrypted storage. Re-test it in configuration if needed." else "Not configured yet. You can add and validate your key now or do it later.", configured)
                content.addView(actionButton("OPEN AI CONFIGURATION") {
                    startActivity(Intent(this, ApiConnectionActivity::class.java))
                }, fullWidth())
                infoCard("SECURITY", "JARVIS uses its existing encrypted API-key storage. Never share your key or place it in screenshots, logs or source code.")
                primary.text = "CONTINUE"
                secondary.text = "BACK"
            }
            4 -> {
                hero("ALLOW RESTRICTED SETTINGS", "Some Android versions block accessibility activation for sideloaded apps until the user explicitly allows restricted settings.", "⚙")
                infoCard("STEP 1", "Open JARVIS App Info → tap the three-dot menu (if available) → Allow restricted settings. Device manufacturers may use different wording, and some devices do not show this option.")
                content.addView(actionButton("OPEN JARVIS APP INFO") {
                    returnedFromSettings = true
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }, fullWidth())
                statusCard("Restricted settings", "Android does not expose a reliable public API to verify this toggle on every device. Confirm it manually if your device displays it.", false)
                primary.text = "I’VE CHECKED — CONTINUE"
                secondary.text = "BACK"
            }
            5 -> {
                hero("ENABLE ACCESSIBILITY", "Accessibility lets JARVIS identify supported screen controls and perform actions you explicitly request.", "⌗")
                val enabled = PermissionHelper.isAccessibilityServiceEnabled(this)
                statusCard("JARVIS Accessibility Service", if (enabled) "Enabled and detected by Android." else "Not enabled. Open Android Accessibility settings, find JARVIS and turn its service on.", enabled)
                content.addView(actionButton("OPEN ACCESSIBILITY SETTINGS") {
                    returnedFromSettings = true
                    PermissionHelper.openAccessibilitySettings(this)
                }, fullWidth())
                infoCard("PRIVACY & CONTROL", "Android requires you to enable this service manually. JARVIS does not activate it silently. You can turn it off at any time in system settings.")
                primary.text = if (enabled) "CONTINUE" else "CHECK AGAIN"
                secondary.text = "BACK"
            }
            6 -> {
                hero("JARVIS IS READY TO LEARN", "Explore how your assistant works. These are examples of supported integration paths, not automatic actions.", "✧")
                listOf(
                    "VOICE COMMAND  →  JARVIS understands your request",
                    "APP SKILLS  →  WhatsApp, YouTube, Blinkit, Rapido and Amazon where supported",
                    "ACCESSIBILITY  →  Finds supported controls and assists with navigation and typing",
                    "VOICE & OFFLINE  →  Vosk speech recognition and configured voice responses"
                ).forEach { infoCard("CAPABILITY", it) }
                val mic = hasPermission(Manifest.permission.RECORD_AUDIO)
                val access = PermissionHelper.isAccessibilityServiceEnabled(this)
                val api = prefs.apiKey.isNotBlank() && prefs.canStoreApiKeySecurely
                statusCard("SYSTEM CHECK", "Microphone: ${if (mic) "Granted" else "Not granted"}\nAI key: ${if (api) "Configured" else "Optional / not configured"}\nAccessibility: ${if (access) "Enabled" else "Not enabled"}\nProfile: ${if (prefs.userName.isNotBlank()) "Saved" else "Default"}", mic && access)
                primary.text = "FINISH SETUP"
                secondary.text = "BACK"
            }
        }
        // Rebind primary action for ordinary pages after any custom profile action.
        if (step != 2) primary.setOnClickListener { advance() }
        secondary.visibility = if (step == 0) View.VISIBLE else View.VISIBLE
        content.alpha = 0f
        content.translationY = dp(12).toFloat()
        content.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun advance() {
        when (step) {
            1 -> {
                if (!corePermissionsGranted()) {
                    val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                    permissionRequest.launch(permissions.toTypedArray())
                    return
                }
                step++
            }
            3 -> { step++ }
            4 -> { step++ }
            5 -> {
                if (!PermissionHelper.isAccessibilityServiceEnabled(this)) {
                    render()
                    return
                }
                step++
            }
            6 -> {
                prefs.hasCompletedSetupTutorial = true
                prefs.setupTutorialStep = 0
                finish()
                return
            }
            else -> step++
        }
        render()
    }

    private fun skipOrBack() {
        if (step == 0) {
            prefs.hasCompletedSetupTutorial = true
            prefs.setupTutorialStep = 0
            finish()
        } else {
            step = (step - 1).coerceAtLeast(0)
            render()
        }
    }

    private fun corePermissionsGranted(): Boolean =
        hasPermission(Manifest.permission.RECORD_AUDIO) &&
            (Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS))

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hero(title: String, description: String, symbol: String) {
        val glyph = TextView(this).apply {
            text = symbol
            textSize = 42f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(91, 203, 255))
            setShadowLayer(dp(20).toFloat(), 0f, 0f, Color.rgb(31, 122, 255))
        }
        content.addView(glyph, LinearLayout.LayoutParams(-1, dp(88)))
        val heading = TextView(this).apply {
            text = title
            textSize = 23f
            gravity = Gravity.CENTER
            letterSpacing = .035f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        content.addView(heading, fullWidth())
        val body = TextView(this).apply {
            text = description
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(173, 190, 218))
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(8), dp(10), dp(8), dp(14))
        }
        content.addView(body, fullWidth())
    }

    private fun infoCard(title: String, body: String) {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.argb(218, 16, 27, 50))
            strokeWidth = dp(1)
            strokeColor = Color.argb(100, 67, 124, 204)
            setContentPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(TextView(this).apply {
            text = title
            textSize = 10f
            letterSpacing = .12f
            setTextColor(Color.rgb(101, 203, 255))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        stack.addView(TextView(this).apply {
            text = body
            textSize = 13f
            setTextColor(Color.rgb(221, 231, 247))
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        card.addView(stack)
        content.addView(card, fullWidth().apply { bottomMargin = dp(10) })
    }

    private fun permissionRow(name: String, explanation: String, granted: Boolean) {
        statusCard(name, explanation, granted)
    }

    private fun statusCard(title: String, description: String, ready: Boolean) {
        infoCard("${if (ready) "✓" else "○"}  $title", description)
    }

    private fun actionButton(label: String, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            cornerRadius = dp(15)
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(26, 91, 180))
            setOnClickListener { action() }
        }

    private fun fullWidth() = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(10)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class OrbBackdrop(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.shader = RadialGradient(w * .5f, h * .22f, w * .72f,
                intArrayOf(Color.argb(85, 22, 91, 190), Color.argb(24, 25, 48, 112), Color.TRANSPARENT),
                floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }
    }

    companion object {
        private const val STATE_STEP = "setup_tutorial_step"
    }
}
