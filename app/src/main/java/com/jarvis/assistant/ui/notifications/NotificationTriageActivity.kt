package com.jarvis.assistant.ui.notifications

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.chip.Chip
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.ActivityNotificationTriageBinding
import com.jarvis.assistant.notifications.MessageAlertSettings
import com.jarvis.assistant.util.SpeechAnnouncer
import com.jarvis.assistant.util.ThemeManager

/**
 * Autonomous Notification Triage & VIP Voice Dispatcher Configuration Activity.
 * Allows users to choose their triage filtering mode (All, Smart Urgent, VIP Only),
 * manage their VIP whitelist of contacts and groups, configure voice reply gating,
 * and test cybernetic announcement alerts.
 */
class NotificationTriageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationTriageBinding
    private val settings by lazy { MessageAlertSettings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationTriageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        applyCyberneticTheme()
        setupListeners()
        setupToggles()
        setupVipManager()
        updateTriageModeUI(settings.triageMode)
    }

    override fun onResume() {
        super.onResume()
        updateListenerStatus()
    }

    private fun applyCyberneticTheme() {
        val themeAccent = ThemeManager.getThemeAccentColor()
        val themeSecondary = ThemeManager.getThemeSecondaryColor()
        val themeWarning = ThemeManager.getThemeWarningColor()

        // Header cybernetic badge
        binding.tvHeaderBadge.background = ThemeManager.createDynamicBadgeDrawable(
            themeAccent, themeSecondary, 8f, this
        )
        binding.tvHeaderBadge.setTextColor(Color.WHITE)

        // Recommended badge on Smart Urgent card
        binding.badgeSmartUrgent.background = ThemeManager.createDynamicBadgeDrawable(
            themeAccent, themeSecondary, 6f, this
        )
        binding.badgeSmartUrgent.setTextColor(Color.WHITE)

        // Input field dynamic card styling
        binding.etVipName.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = ColorUtils.setAlphaComponent(themeSecondary, 80),
            fillColor = ColorUtils.setAlphaComponent(themeSecondary, 18),
            cornerRadiusDp = 10f,
            strokeWidthDp = 1f,
            context = this
        )

        // Buttons dynamic gradient badges
        binding.btnAddVip.background = ThemeManager.createDynamicBadgeDrawable(
            themeAccent, themeSecondary, 12f, this
        )
        binding.btnAddVip.setTextColor(Color.WHITE)

        binding.btnTestAnnouncement.background = ThemeManager.createDynamicBadgeDrawable(
            themeAccent, themeSecondary, 14f, this
        )
        binding.btnTestAnnouncement.setTextColor(Color.WHITE)

        binding.btnGrantPermission.background = ThemeManager.createDynamicBadgeDrawable(
            themeWarning, themeAccent, 12f, this
        )
        binding.btnGrantPermission.setTextColor(Color.WHITE)

        // Tint switches with theme accent
        ThemeManager.tintSwitch(binding.switchReadBody, themeAccent)
        ThemeManager.tintSwitch(binding.switchVoiceReply, themeAccent)
        ThemeManager.tintSwitch(binding.switchSpamFilter, themeAccent)
        ThemeManager.tintSwitch(binding.switchOwnerGated, themeAccent)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.cardModeSmart.setOnClickListener {
            animateCardPress(it)
            setTriageMode(MessageAlertSettings.TRIAGE_SMART_URGENT)
        }

        binding.cardModeVip.setOnClickListener {
            animateCardPress(it)
            setTriageMode(MessageAlertSettings.TRIAGE_VIP_ONLY)
        }

        binding.cardModeAll.setOnClickListener {
            animateCardPress(it)
            setTriageMode(MessageAlertSettings.TRIAGE_ALL_MESSAGES)
        }

        binding.btnGrantPermission.setOnClickListener {
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Notification Settings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestAnnouncement.setOnClickListener {
            animateCardPress(it)
            val testMessage = "Sir, Sarah sent an urgent message on WhatsApp: \"Please call me ASAP\". Say reply to respond."
            Toast.makeText(this, "Simulating triage voice announcement...", Toast.LENGTH_SHORT).show()
            if (!JarvisConversationService.announceMessage(testMessage)) {
                SpeechAnnouncer(this).say(testMessage)
            }
        }
    }

    private fun setTriageMode(mode: Int) {
        settings.triageMode = mode
        updateTriageModeUI(mode)
        val modeName = when (mode) {
            MessageAlertSettings.TRIAGE_ALL_MESSAGES -> "All Messages"
            MessageAlertSettings.TRIAGE_VIP_ONLY -> "VIP Whitelist Only"
            else -> "Smart Urgent"
        }
        Toast.makeText(this, "Triage Mode: $modeName", Toast.LENGTH_SHORT).show()
    }

    private fun updateTriageModeUI(mode: Int) {
        val themeAccent = ThemeManager.getThemeAccentColor()
        val themeSecondary = ThemeManager.getThemeSecondaryColor()
        val textPrimaryColor = resolveThemeColor(R.attr.themeTextPrimary)

        // Helper to style selected vs unselected cards
        fun applyCardStyle(
            card: com.google.android.material.card.MaterialCardView,
            checkIcon: View,
            titleView: android.widget.TextView,
            isSelected: Boolean
        ) {
            if (isSelected) {
                card.background = ThemeManager.createDynamicCardDrawable(
                    strokeColor = themeAccent,
                    fillColor = ColorUtils.setAlphaComponent(themeAccent, 28),
                    cornerRadiusDp = 14f,
                    strokeWidthDp = 2f,
                    context = this
                )
                checkIcon.visibility = View.VISIBLE
                titleView.setTextColor(themeAccent)
            } else {
                card.background = ThemeManager.createDynamicCardDrawable(
                    strokeColor = ColorUtils.setAlphaComponent(themeSecondary, 40),
                    fillColor = ColorUtils.setAlphaComponent(themeSecondary, 12),
                    cornerRadiusDp = 14f,
                    strokeWidthDp = 1f,
                    context = this
                )
                checkIcon.visibility = View.INVISIBLE
                titleView.setTextColor(textPrimaryColor)
            }
        }

        applyCardStyle(
            binding.cardModeSmart,
            binding.ivModeSmartCheck,
            binding.tvModeSmartTitle,
            mode == MessageAlertSettings.TRIAGE_SMART_URGENT
        )
        applyCardStyle(
            binding.cardModeVip,
            binding.ivModeVipCheck,
            binding.tvModeVipTitle,
            mode == MessageAlertSettings.TRIAGE_VIP_ONLY
        )
        applyCardStyle(
            binding.cardModeAll,
            binding.ivModeAllCheck,
            binding.tvModeAllTitle,
            mode == MessageAlertSettings.TRIAGE_ALL_MESSAGES
        )
    }

    private fun setupToggles() {
        binding.switchReadBody.isChecked = settings.readMessageBody
        binding.switchReadBody.setOnCheckedChangeListener { _, isChecked ->
            settings.readMessageBody = isChecked
        }

        binding.switchVoiceReply.isChecked = settings.enableVoiceReply
        binding.switchVoiceReply.setOnCheckedChangeListener { _, isChecked ->
            settings.enableVoiceReply = isChecked
        }

        binding.switchSpamFilter.isChecked = settings.spamFilterEnabled
        binding.switchSpamFilter.setOnCheckedChangeListener { _, isChecked ->
            settings.spamFilterEnabled = isChecked
        }

        binding.switchOwnerGated.isChecked = settings.ownerVoiceReplyGated
        binding.switchOwnerGated.setOnCheckedChangeListener { _, isChecked ->
            settings.ownerVoiceReplyGated = isChecked
        }
    }

    private fun setupVipManager() {
        binding.btnAddVip.setOnClickListener {
            animateCardPress(it)
            addVipFromInput()
        }

        binding.etVipName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addVipFromInput()
                true
            } else {
                false
            }
        }

        refreshVipChips()
    }

    private fun addVipFromInput() {
        val input = binding.etVipName.text.toString().trim()
        if (input.isEmpty()) {
            binding.etVipName.error = "Enter a contact or group name"
            return
        }
        val added = settings.addVip(input)
        if (added) {
            binding.etVipName.text?.clear()
            binding.etVipName.error = null
            refreshVipChips()
            Toast.makeText(this, "Added \"$input\" to VIP contacts", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "\"$input\" is already on your VIP list", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshVipChips() {
        val themeAccent = ThemeManager.getThemeAccentColor()
        val textPrimaryColor = resolveThemeColor(R.attr.themeTextPrimary)

        binding.chipGroupVips.removeAllViews()
        val vips = settings.getVipList()

        if (vips.isEmpty()) {
            binding.tvNoVips.visibility = View.VISIBLE
        } else {
            binding.tvNoVips.visibility = View.GONE
            for (vipName in vips) {
                val chip = Chip(this).apply {
                    text = vipName
                    isCloseIconVisible = true
                    setCloseIconResource(R.drawable.ic_close)
                    closeIconTint = ColorStateList.valueOf(themeAccent)
                    chipBackgroundColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(themeAccent, 32))
                    chipStrokeColor = ColorStateList.valueOf(themeAccent)
                    chipStrokeWidth = resources.displayMetrics.density * 1.2f
                    chipCornerRadius = resources.displayMetrics.density * 14f
                    setTextColor(ColorStateList.valueOf(textPrimaryColor))
                    minHeight = (44 * resources.displayMetrics.density).toInt()
                    setOnCloseIconClickListener {
                        settings.removeVip(vipName)
                        refreshVipChips()
                        Toast.makeText(
                            this@NotificationTriageActivity,
                            "Removed \"$vipName\" from VIP list",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                binding.chipGroupVips.addView(chip)
            }
        }
    }

    private fun updateListenerStatus() {
        val themeSuccess = ThemeManager.getThemeSuccessColor()
        val themeWarning = ThemeManager.getThemeWarningColor()
        val isGranted = isNotificationServiceEnabled()

        if (isGranted) {
            binding.viewStatusDot.background = ThemeManager.createDynamicBadgeDrawable(
                themeSuccess, themeSuccess, 5f, this
            )
            binding.tvListenerStatusTitle.text = "NOTIFICATION LISTENER: ACTIVE"
            binding.tvListenerStatusTitle.setTextColor(themeSuccess)
            val last = settings.lastStatus.ifBlank {
                "Listening for incoming chat messages across supported apps."
            }
            binding.tvListenerLastStatus.text = last
            binding.btnGrantPermission.visibility = View.GONE
        } else {
            binding.viewStatusDot.background = ThemeManager.createDynamicBadgeDrawable(
                themeWarning, themeWarning, 5f, this
            )
            binding.tvListenerStatusTitle.text = "NOTIFICATION ACCESS REQUIRED"
            binding.tvListenerStatusTitle.setTextColor(themeWarning)
            binding.tvListenerLastStatus.text =
                "Jarvis requires Notification Access to triage incoming messages and dispatch voice replies."
            binding.btnGrantPermission.visibility = View.VISIBLE
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return !flat.isNullOrEmpty() && flat.contains(pkgName)
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun animateCardPress(view: View) {
        view.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(70)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(70).start()
            }
            .start()
    }
}
