package com.jarvis.assistant.ui.settings

import android.app.KeyguardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.databinding.ActivityScreenUnlockBinding
import com.jarvis.assistant.security.UnlockCredentialStore
import com.jarvis.assistant.security.UnlockCredentialStore.LockType
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lets the owner save the phone's screen-lock credential (PIN, password or pattern) so Jarvis can
 * unlock the phone by voice, and run a lock-then-unlock self-test.
 */
class ScreenUnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScreenUnlockBinding
    private lateinit var store: UnlockCredentialStore

    private val patternDots = StringBuilder()
    private val dotButtons = ArrayList<MaterialButton>()
    private var ignoreSwitchCallback = false
    private var testRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        // Keep the PIN / password out of screenshots and the recent-apps thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityScreenUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        store = UnlockCredentialStore(applicationContext)

        buildPatternGrid()
        preselectSavedType()
        applyLockTypeUi()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------------------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------------------

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnEnableAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
        }

        binding.rgLockType.setOnCheckedChangeListener { _, _ ->
            binding.etSecret.text?.clear()
            binding.etConfirm.text?.clear()
            clearPattern()
            applyLockTypeUi()
        }

        binding.btnClearPattern.setOnClickListener { clearPattern() }
        binding.btnSave.setOnClickListener { saveCredential() }
        binding.btnClear.setOnClickListener { confirmDelete() }
        binding.btnTest.setOnClickListener { confirmTest() }

        ignoreSwitchCallback = true
        binding.switchAllowWithoutVoice.isChecked = store.allowWithoutVoiceMatch
        ignoreSwitchCallback = false
        binding.switchAllowWithoutVoice.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitchCallback) return@setOnCheckedChangeListener
            if (checked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Allow unlock without voice match?")
                    .setMessage("Anyone who says the unlock command near your phone will be able to unlock it. Only turn this on if you understand the risk.")
                    .setNegativeButton("Cancel") { _, _ -> setSwitchSilently(false) }
                    .setPositiveButton("Turn on") { _, _ -> store.allowWithoutVoiceMatch = true }
                    .setOnCancelListener { setSwitchSilently(false) }
                    .show()
            } else {
                store.allowWithoutVoiceMatch = false
            }
        }
    }

    private fun setSwitchSilently(value: Boolean) {
        ignoreSwitchCallback = true
        binding.switchAllowWithoutVoice.isChecked = value
        ignoreSwitchCallback = false
    }

    private fun preselectSavedType() {
        when (store.type()) {
            LockType.PASSWORD -> binding.rbPassword.isChecked = true
            LockType.PATTERN -> binding.rbPattern.isChecked = true
            else -> binding.rbPin.isChecked = true
        }
    }

    private fun selectedType(): LockType = when (binding.rgLockType.checkedRadioButtonId) {
        R.id.rbPassword -> LockType.PASSWORD
        R.id.rbPattern -> LockType.PATTERN
        else -> LockType.PIN
    }

    private fun applyLockTypeUi() {
        when (selectedType()) {
            LockType.PIN -> {
                binding.layoutSecretInputs.visibility = android.view.View.VISIBLE
                binding.layoutPattern.visibility = android.view.View.GONE
                binding.tilSecret.hint = "PIN"
                binding.tilConfirm.hint = "Confirm PIN"
                binding.etSecret.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                binding.etConfirm.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            LockType.PASSWORD -> {
                binding.layoutSecretInputs.visibility = android.view.View.VISIBLE
                binding.layoutPattern.visibility = android.view.View.GONE
                binding.tilSecret.hint = "Password"
                binding.tilConfirm.hint = "Confirm password"
                binding.etSecret.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                binding.etConfirm.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            LockType.PATTERN -> {
                binding.layoutSecretInputs.visibility = android.view.View.GONE
                binding.layoutPattern.visibility = android.view.View.VISIBLE
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Pattern grid
    // ---------------------------------------------------------------------------------------

    private fun buildPatternGrid() {
        val density = resources.displayMetrics.density
        val size = (64 * density).toInt()
        val margin = (6 * density).toInt()
        val accent = TypedValue().let { tv ->
            theme.resolveAttribute(R.attr.themeAccent, tv, true)
            tv.data
        }
        for (dot in 1..9) {
            val button = MaterialButton(this).apply {
                text = dot.toString()
                textSize = 16f
                insetTop = 0
                insetBottom = 0
                setPadding(0, 0, 0, 0)
                cornerRadius = size / 2
                backgroundTintList = ColorStateList.valueOf(accent)
                setTextColor(0xFFFFFFFF.toInt())
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(margin, margin, margin, margin)
                }
                setOnClickListener { onDotTapped(dot, this) }
            }
            dotButtons.add(button)
            binding.gridPattern.addView(button)
        }
        refreshPatternText()
    }

    private fun onDotTapped(dot: Int, button: MaterialButton) {
        val ch = ('0' + dot)
        if (patternDots.indexOf(ch.toString()) >= 0) return
        patternDots.append(ch)
        button.alpha = 0.35f
        refreshPatternText()
    }

    private fun clearPattern() {
        patternDots.setLength(0)
        dotButtons.forEach { it.alpha = 1f }
        refreshPatternText()
    }

    private fun refreshPatternText() {
        binding.tvPatternSequence.text = if (patternDots.isEmpty()) {
            "No dots chosen yet"
        } else {
            patternDots.toString().toList().joinToString("  →  ")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------

    private fun saveCredential() {
        val type = selectedType()
        val secret: String
        if (type == LockType.PATTERN) {
            secret = patternDots.toString()
        } else {
            val first = binding.etSecret.text?.toString().orEmpty()
            val second = binding.etConfirm.text?.toString().orEmpty()
            if (first != second) {
                toast("The two entries do not match.")
                return
            }
            secret = first
        }
        val error = store.save(type, secret)
        if (error != null) {
            toast(error)
            return
        }
        binding.etSecret.text?.clear()
        binding.etConfirm.text?.clear()
        clearPattern()
        toast("Screen lock saved on this device.")
        refreshStatus()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete saved screen lock?")
            .setMessage("Jarvis will no longer be able to unlock your phone until you save it again.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                store.clear()
                refreshStatus()
                toast("Saved screen lock deleted.")
            }
            .show()
    }

    private fun confirmTest() {
        if (testRunning) return
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            toast("Turn on the Jarvis accessibility service first.")
            return
        }
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isDeviceSecure && !store.hasCredential()) {
            toast("Save your screen lock first.")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Lock and test")
            .setMessage("Jarvis will lock your phone, wait a few seconds, then try to wake and unlock it with the saved screen lock. Please do not touch the phone during the test.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ -> runTest(service) }
            .show()
    }

    private fun runTest(service: JarvisAccessibilityService) {
        testRunning = true
        binding.tvTestResult.text = "Locking the phone and testing…"
        val appContext = applicationContext
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val main = Handler(Looper.getMainLooper())

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            fun report(message: String) {
                main.post {
                    testRunning = false
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                    if (!isFinishing && !isDestroyed) binding.tvTestResult.text = message
                }
            }

            val locked = service.lockScreenNow()
            if (!locked) {
                report("This phone cannot be locked from Jarvis (needs Android 9 or newer), so the test could not run. Lock the phone yourself and say \"unlock my phone\" instead.")
                return@launch
            }
            delay(3_000L)
            if (!keyguard.isKeyguardLocked) {
                report("The phone did not lock, so the test could not run.")
                return@launch
            }
            val result = service.unlockDevice()
            report(if (result.success) "Test passed: ${result.message}" else "Test failed: ${result.message}")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Status
    // ---------------------------------------------------------------------------------------

    private fun refreshStatus() {
        val type = store.type()
        binding.tvCredentialStatus.text = when {
            !store.isStorageAvailable -> "Secure storage is not available on this device."
            type == null || !store.hasCredential() -> "No screen lock saved yet"
            type == LockType.PIN -> "Saved: PIN (${store.secretLength()} digits)"
            type == LockType.PASSWORD -> "Saved: password (${store.secretLength()} characters)"
            else -> "Saved: pattern (${store.secretLength()} dots)"
        }

        val a11yOn = PermissionHelper.isAccessibilityServiceEnabled(this)
        binding.tvServiceStatus.text = if (a11yOn) {
            "Accessibility service: on"
        } else {
            "Accessibility service: off. Jarvis needs it to press the lock-screen keys."
        }
        binding.btnEnableAccessibility.visibility = if (a11yOn) android.view.View.GONE else android.view.View.VISIBLE

        val prefs = (application as JarvisApp).preferences
        val voiceReady = prefs.isOwnerVoiceEnabled && prefs.hasOwnerVoiceProfile()
        binding.tvVoiceStatus.text = if (voiceReady) {
            "Voice match: on. Only your enrolled voice can unlock."
        } else {
            "Voice match: not set up. Enroll your voice in Settings, or unlocking stays disabled unless you allow it below."
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
