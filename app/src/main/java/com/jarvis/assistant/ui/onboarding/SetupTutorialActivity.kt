package com.jarvis.assistant.ui.onboarding

import android.Manifest
import android.content.Intent
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
    private val premiumAnimators = mutableListOf<Animator>()

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

    override fun onDestroy() {
        premiumAnimators.forEach { it.cancel() }
        premiumAnimators.clear()
        super.onDestroy()
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
                    else setImageResource(R.drawable.jarvis_default_user)
                    if (drawable == null) setImageResource(R.drawable.jarvis_default_user)
                    clipToOutline = true
                }
                avatar.cameraDistance = dp(1400).toFloat()
                avatar.rotationY = -24f
                avatar.alpha = 0f
                avatar.scaleX = .72f
                avatar.scaleY = .72f
                content.addView(avatar)
                avatar.animate().alpha(1f).scaleX(1f).scaleY(1f).rotationY(0f)
                    .setStartDelay(180L).setDuration(1050L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.05f)).start()
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
        runPremiumEntrance()
    }

    /**
     * A coordinated, lifecycle-bound entrance system. Every page element receives a staggered
     * spring settle; hero surfaces, cards and action controls get their own restrained motion.
     * Animations are cancelled before a page is rebuilt and when the Activity is destroyed.
     */
    private fun runPremiumEntrance() {
        premiumAnimators.forEach { it.cancel() }
        premiumAnimators.clear()
        content.alpha = 1f
        content.translationY = 0f

        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = dp(30).toFloat()
            child.scaleX = .94f
            child.scaleY = .94f
            child.cameraDistance = dp(1800).toFloat()
            child.rotationX = 16f
            child.rotationY = if (index % 2 == 0) -12f else 12f
            child.animate()
                .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f)
                .setStartDelay(index * 115L)
                .setDuration(900L)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.78f))
                .start()
        }

        // Give each premium surface a subtle, non-distracting floating lift.
        content.post {
            if (isFinishing || isDestroyed) return@post
            for (index in 0 until content.childCount) {
                val view = content.getChildAt(index)
                if (view is MaterialCardView) {
                    val lift = ObjectAnimator.ofFloat(view, View.TRANSLATION_Z, dp(0).toFloat(), dp(5).toFloat(), dp(0).toFloat()).apply {
                        duration = 2600L
                        startDelay = index * 110L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.RESTART
                    }
                    premiumAnimators += lift
                    lift.start()
                }
            }
            var buttonIndex = 0
            for (childIndex in 0 until content.childCount) {
                val button = content.getChildAt(childIndex) as? MaterialButton ?: continue
                val pulse = ObjectAnimator.ofPropertyValuesHolder(
                    button,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.018f, 1f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.018f, 1f)
                ).apply {
                    duration = 1900L
                    startDelay = 500L + buttonIndex * 180L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                }
                premiumAnimators += pulse
                pulse.start()
                buttonIndex++
            }
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
                scaleX = .78f
                scaleY = .78f
                rotationY = -18f
            }
            row.addView(node, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                if (index > 0) leftMargin = dp(5)
                if (index < labels.lastIndex) rightMargin = dp(5)
            })
            node.animate().alpha(1f).scaleX(1f).scaleY(1f).rotationY(0f)
                .setStartDelay(index * 380L).setDuration(760L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.05f)).start()
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
        glyph.animate().scaleX(1f).scaleY(1f).rotation(360f).rotationX(12f).setDuration(1050L)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.05f)).withEndAction {
                glyph.animate().rotation(0f).rotationX(0f).setDuration(420L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }.start()
        heroFrame.alpha = 0f
        heroFrame.scaleX = .96f
        heroFrame.scaleY = .96f
        heroFrame.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(720L).setInterpolator(android.view.animation.OvershootInterpolator(.8f)).start()
        val heading = TextView(this).apply {
            text = title
            textSize = 38f
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
            textSize = 20f
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
            textSize = 16f
            letterSpacing = .16f
            setTextColor(Color.rgb(72, 218, 255))
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        })
        stack.addView(TextView(this).apply {
            text = body
            textSize = 18f
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
            textSize = 17f
            letterSpacing = .035f
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
        private val glowPalette = intArrayOf(Color.rgb(0, 214, 255), Color.rgb(133, 55, 255), Color.rgb(255, 54, 163), Color.rgb(255, 151, 42), Color.rgb(45, 255, 177))

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 9000L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
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

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val phase = pulse * (Math.PI * 2.0)
            val radius = maxOf(w, h) * (.66f + .10f * kotlin.math.sin(phase).toFloat())
            val colors = intArrayOf(Color.rgb(0, 214, 255), Color.rgb(133, 55, 255), Color.rgb(255, 54, 163), Color.rgb(255, 151, 42))
            for (i in 0..3) {
                val x = w * (0.15f + i * 0.23f + 0.06f * kotlin.math.sin(phase + i).toFloat())
                val y = h * (0.18f + (i % 2) * 0.55f + 0.04f * kotlin.math.cos(phase + i).toFloat())
                val color = colors[i]
                paint.shader = RadialGradient(x, y, radius * .8f,
                    intArrayOf(Color.argb(130, Color.red(color), Color.green(color), Color.blue(color)), Color.argb(42, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
                    floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = Color.argb(44, 126, 196, 255)
            val cx = w * .5f
            val cy = h * .46f
            for (ring in 0..4) {
                canvas.save()
                canvas.rotate((pulse * 360f) * (if (ring % 2 == 0) 1f else -1f) + ring * 27f, cx, cy)
                canvas.drawOval(android.graphics.RectF(cx - w * (.24f + ring * .045f), cy - h * (.10f + ring * .018f), cx + w * (.24f + ring * .045f), cy + h * (.10f + ring * .018f)), paint)
                canvas.restore()
            }
            paint.style = Paint.Style.FILL
            for (orb in 0 until 10) {
                val angle = phase * (if (orb % 2 == 0) 1.0 else -1.15) + orb * Math.PI / 5.0
                val x = cx + kotlin.math.cos(angle).toFloat() * w * (.25f + (orb % 3) * .05f)
                val y = cy + kotlin.math.sin(angle).toFloat() * h * (.10f + (orb % 3) * .02f)
                val color = glowPalette[orb % glowPalette.size]
                paint.color = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
                paint.setShadowLayer(dp(14).toFloat(), 0f, 0f, color)
                canvas.drawCircle(x, y, dp(3 + orb % 3).toFloat(), paint)
                paint.clearShadowLayer()
            }
        }
    }

    companion object {
        private const val STATE_STEP = "setup_tutorial_step"
    }
}
