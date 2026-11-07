package com.jarvis.assistant.ui.onboarding

import android.Manifest
import android.content.Intent
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
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
            text = "J A R V I S   •   PREMIUM SETUP"
            textSize = 12f
            letterSpacing = .22f
            setTextColor(Color.rgb(103, 216, 255))
            gravity = Gravity.CENTER
        }
        column.addView(brand, LinearLayout.LayoutParams(-1, dp(34)))
        progress = TextView(this).apply {
            textSize = 12f
            letterSpacing = .16f
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
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        primary = MaterialButton(this).apply {
            text = "CONTINUE"
            cornerRadius = dp(20)
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(0, 190, 255), Color.rgb(45, 105, 255), Color.rgb(132, 75, 255))
            ).apply { cornerRadius = dp(20).toFloat() }
            elevation = dp(8).toFloat()
            textSize = 16f
            letterSpacing = .04f
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
        progress.text = "SETUP  •  ${(step + 1).coerceAtMost(8)} / 8"
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
                secondary.text = "CONTINUE WITHOUT"
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
                secondary.text = "BACK"
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
                secondary.text = "SKIP AI SETUP"
            }
            4 -> {
                hero("ALLOW RESTRICTED SETTINGS", "Some Android versions block accessibility activation for sideloaded apps until the user explicitly allows restricted settings.", "⚙")
                addWorkflowDemo(listOf("APP INFO", "⋮ MENU", "ALLOW"))
                infoCard("STEP 1", "Open JARVIS App Info → tap the three-dot menu (if available) → Allow restricted settings. Device manufacturers may use different wording, and some devices do not show this option.")
                content.addView(actionButton("OPEN JARVIS APP INFO") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }, fullWidth())
                statusCard("Restricted settings", "Android does not expose a reliable public API to verify this toggle on every device. Confirm it manually if your device displays it.", false)
                primary.text = "I’VE CHECKED — CONTINUE"
                secondary.text = "SKIP THIS STEP"
            }
            5 -> {
                hero("ENABLE ACCESSIBILITY", "Accessibility lets JARVIS identify supported screen controls and perform actions you explicitly request.", "⌗")
                addWorkflowDemo(listOf("SETTINGS", "JARVIS", "ENABLE"))
                val enabled = PermissionHelper.isAccessibilityServiceEnabled(this)
                statusCard("JARVIS Accessibility Service", if (enabled) "Enabled and detected by Android." else "Not enabled. Open Android Accessibility settings, find JARVIS and turn its service on.", enabled)
                content.addView(actionButton("OPEN ACCESSIBILITY SETTINGS") {

                    PermissionHelper.openAccessibilitySettings(this)
                }, fullWidth())
                infoCard("PRIVACY & CONTROL", "Android requires you to enable this service manually. JARVIS does not activate it silently. You can turn it off at any time in system settings.")
                primary.text = if (enabled) "CONTINUE" else "CHECK AGAIN"
                secondary.text = "CONTINUE WITHOUT ACCESSIBILITY"
            }
            6 -> {
                hero("APP CONTROL & VOICE", "See how a supported request moves through JARVIS. This is a safe animated preview; it will not open apps or perform actions.", "✧")
                addWorkflowDemo()
                listOf(
                    "APP SKILLS  →  WhatsApp, YouTube, Blinkit, Rapido and Amazon where supported",
                    "ACCESSIBILITY  →  Identifies supported controls and assists with navigation and typing",
                    "VOICE  →  Wake-word and voice features where configured",
                    "OFFLINE SPEECH  →  Vosk can recognize speech offline; AI responses still depend on the configured AI engine"
                ).forEach { infoCard("CAPABILITY", it) }
                primary.text = "CONTINUE TO SYSTEM CHECK"
                secondary.text = "BACK"
            }
            7 -> {
                hero("FINAL SYSTEM CHECK", "Review your actual setup status. Optional items can be completed later in Settings.", "✓")
                val mic = hasPermission(Manifest.permission.RECORD_AUDIO)
                val notifications = Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                val access = PermissionHelper.isAccessibilityServiceEnabled(this)
                val api = prefs.apiKey.isNotBlank() && prefs.canStoreApiKeySecurely
                statusCard("MICROPHONE", if (mic) "Granted" else "Not granted — voice features may be unavailable", mic)
                statusCard("NOTIFICATIONS", if (notifications) "Available" else "Not granted — background notices may be limited", notifications)
                statusCard("GEMINI API", if (api) "Key saved in encrypted storage; connection should be tested in AI settings" else "Optional — not configured", api)
                statusCard("ACCESSIBILITY", if (access) "Enabled and detected" else "Not enabled — app automation is limited", access)
                statusCard("USER CARD", "Name: ${prefs.userName}\nPhoto: ${if (prefs.profilePhotoUri.isBlank()) "Default avatar" else "Selected"}", true)
                primary.text = "FINISH SETUP"
                secondary.text = "BACK"
            }
        }
        // Rebind primary action for ordinary pages after any custom profile action.
        if (step != 2) primary.setOnClickListener { advance() }
        secondary.visibility = if (step == 0) View.VISIBLE else View.VISIBLE
        content.alpha = 1f
        content.translationY = 0f
        // Premium page entrance: stagger each block with a soft spring-like settle.
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = dp(24).toFloat()
            child.scaleX = .975f
            child.scaleY = .975f
            child.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(index * 75L).setDuration(520L)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.85f)).start()
        }
    }

    private fun addWorkflowDemo(labels: List<String> = listOf("VOICE", "JARVIS", "APP", "DONE")) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(16))
        }
        labels.forEachIndexed { index, label ->
            val node = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                letterSpacing = .08f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.argb(220, 24, 65 + index * 12, 125 + index * 18))
                    setStroke(dp(1), Color.argb(150, 92, 188, 255))
                }
                alpha = 0f
                scaleX = .88f
                scaleY = .88f
            }
            row.addView(node, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                if (index > 0) leftMargin = dp(5)
                if (index < labels.lastIndex) rightMargin = dp(5)
            })
            node.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(index * 330L).setDuration(620L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.15f)).start()
        }
        content.addView(row, fullWidth())
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
            6 -> { step++ }
            7 -> {
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
        when (step) {
            0 -> {
                prefs.hasCompletedSetupTutorial = true
                prefs.setupTutorialStep = 0
                finish()
            }
            1, 3, 4, 5 -> {
                // Optional configuration can be completed later from Settings.
                step++
                render()
            }
            else -> {
                step = (step - 1).coerceAtLeast(0)
                render()
            }
        }
    }

    private fun corePermissionsGranted(): Boolean =
        hasPermission(Manifest.permission.RECORD_AUDIO) &&
            (Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS))

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hero(title: String, description: String, symbol: String) {
        val heroFrame = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.argb(105, 0, 195, 255), Color.argb(95, 92, 75, 255), Color.argb(45, 20, 35, 75))
            ).apply { cornerRadius = dp(30).toFloat(); setStroke(dp(1), Color.argb(110, 100, 205, 255)) }
            elevation = dp(10).toFloat()
        }
        val glyph = TextView(this).apply {
            text = symbol
            textSize = 54f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(dp(24).toFloat(), 0f, 0f, Color.rgb(0, 190, 255))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(0, 180, 255), Color.rgb(104, 73, 255))
            ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
            elevation = dp(12).toFloat()
        }
        heroFrame.addView(glyph, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
        content.addView(heroFrame, LinearLayout.LayoutParams(-1, dp(154)).apply {
            bottomMargin = dp(22); leftMargin = dp(4); rightMargin = dp(4)
        })
        glyph.scaleX = .72f
        glyph.scaleY = .72f
        glyph.animate().scaleX(1f).scaleY(1f).rotation(360f).setDuration(900L)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        val heading = TextView(this).apply {
            text = title
            textSize = 30f
            gravity = Gravity.CENTER
            letterSpacing = .015f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(2).toFloat(), 1f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setShadowLayer(dp(16).toFloat(), 0f, dp(3).toFloat(), Color.argb(150, 0, 115, 255))
        }
        content.addView(heading, fullWidth())
        val body = TextView(this).apply {
            text = description
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(207, 222, 255))
            setLineSpacing(dp(6).toFloat(), 1.04f)
            setPadding(dp(8), dp(12), dp(8), dp(22))
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        content.addView(body, fullWidth())
    }

    private fun infoCard(title: String, body: String) {
        val card = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = dp(7).toFloat()
            setCardBackgroundColor(Color.argb(235, 13, 25, 51))
            strokeWidth = dp(1)
            strokeColor = Color.argb(155, 55, 155, 255)
            setContentPadding(dp(19), dp(17), dp(19), dp(18))
        }
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(TextView(this).apply {
            text = title
            textSize = 13f
            letterSpacing = .14f
            setTextColor(Color.rgb(72, 218, 255))
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        })
        stack.addView(TextView(this).apply {
            text = body
            textSize = 16f
            setTextColor(Color.rgb(238, 244, 255))
            setPadding(0, dp(9), 0, 0)
            setLineSpacing(dp(5).toFloat(), 1.02f)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
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
            cornerRadius = dp(18)
            setTextColor(Color.WHITE)
            textSize = 15f
            letterSpacing = .025f
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(0, 153, 235), Color.rgb(58, 93, 238), Color.rgb(123, 70, 224))
            ).apply { cornerRadius = dp(18).toFloat() }
            elevation = dp(6).toFloat()
            setOnClickListener { action() }
        }

    private fun fullWidth() = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(10)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class OrbBackdrop(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pulse = 0f
        private var animator: ValueAnimator? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 4200L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = w * (.72f + pulse * .10f)
            paint.shader = RadialGradient(
                w * (.22f + pulse * .08f), h * (.20f + pulse * .035f), radius,
                intArrayOf(Color.argb(125, 0, 177, 255), Color.argb(54, 44, 71, 210), Color.TRANSPARENT),
                floatArrayOf(0f, .43f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(
                w * (.88f - pulse * .06f), h * .57f, radius * .72f,
                intArrayOf(Color.argb(85, 139, 58, 255), Color.argb(28, 28, 91, 190), Color.TRANSPARENT),
                floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = Color.argb(24, 91, 201, 255)
            canvas.drawCircle(w * .5f, h * .30f, w * (.34f + pulse * .025f), paint)
            canvas.drawCircle(w * .5f, h * .30f, w * (.43f - pulse * .02f), paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(145, 69, 215, 255)
            canvas.drawCircle(w * (.5f + .34f * kotlin.math.cos(pulse * 6.283f)), h * .30f, dp(2).toFloat(), paint)
            paint.color = Color.argb(120, 166, 104, 255)
            canvas.drawCircle(w * (.5f + .43f * kotlin.math.cos(pulse * 6.283f + 2f)), h * .30f, dp(2).toFloat(), paint)
        }
    }

    companion object {
        private const val STATE_STEP = "setup_tutorial_step"
    }
}
