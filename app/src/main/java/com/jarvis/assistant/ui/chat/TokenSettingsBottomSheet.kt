package com.jarvis.assistant.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.SheetTokenSettingsBinding
import com.jarvis.assistant.provider.TokenMode
import com.jarvis.assistant.provider.TokenModeStore
import com.jarvis.assistant.tools.PermissionPreferences

object TokenSettingsBottomSheet {

    fun show(
        context: Context,
        currentTokensUsed: Int = 0,
        onModeChanged: ((TokenMode) -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(context)
        val binding = SheetTokenSettingsBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        var selected = TokenModeStore.get(context)
        var autoApprove = PermissionPreferences.isAutoApproveAll(context)

        val themeAccent = try { com.jarvis.assistant.util.ThemeManager.getThemeAccentColor() } catch (_: Exception) { Color.parseColor("#00E5FF") }
        val themeSecondary = try { com.jarvis.assistant.util.ThemeManager.getThemeSecondaryColor() } catch (_: Exception) { Color.parseColor("#7C4DFF") }
        val themeSuccess = try { com.jarvis.assistant.util.ThemeManager.getThemeSuccessColor() } catch (_: Exception) { Color.parseColor("#00E676") }
        val themeWarning = try { com.jarvis.assistant.util.ThemeManager.getThemeWarningColor() } catch (_: Exception) { Color.parseColor("#FFD700") }

        binding.tvTokensUsedSubtitle.text = "Current conversation: $currentTokensUsed tokens used"

        fun updateAutoApproveUI() {
            binding.switchAutoApproveAll.isChecked = autoApprove
            if (autoApprove) {
                binding.cardAutoApproveAll.background = ContextCompat.getDrawable(context, R.drawable.bg_auto_approve_card_active)
                binding.tvAutoApproveBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_auto_approve_badge_active)
                binding.tvAutoApproveBadge.text = "★ UNRESTRICTED"
                binding.tvAutoApproveBadge.setTextColor(com.jarvis.assistant.util.ThemeManager.getThemeOnAccentColor())
                binding.tvAutoApproveSubtitle.text = "Zero-prompt autonomy · Full permission bypass active"
                binding.tvAutoApproveSubtitle.setTextColor(themeAccent)
                binding.viewAutoApproveDot.backgroundTintList = ColorStateList.valueOf(themeSuccess)
                binding.tvAutoApproveStatusText.text = "ACTIVE · All permissions auto-approved without interruption"
                binding.tvAutoApproveStatusText.setTextColor(themeSuccess)
            } else {
                binding.cardAutoApproveAll.background = ContextCompat.getDrawable(context, R.drawable.bg_auto_approve_card_inactive)
                binding.tvAutoApproveBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_auto_approve_badge_inactive)
                binding.tvAutoApproveBadge.text = "STANDARD PROMPTS"
                binding.tvAutoApproveBadge.setTextColor(com.jarvis.assistant.util.ThemeManager.getThemeTextSecondaryColor())
                binding.tvAutoApproveSubtitle.text = "Manual confirmation required for sensitive actions"
                binding.tvAutoApproveSubtitle.setTextColor(com.jarvis.assistant.util.ThemeManager.getThemeTextMutedColor())
                binding.viewAutoApproveDot.backgroundTintList = ColorStateList.valueOf(com.jarvis.assistant.util.ThemeManager.getThemeTextMutedColor())
                binding.tvAutoApproveStatusText.text = "INACTIVE · Write and execute tools will prompt first"
                binding.tvAutoApproveStatusText.setTextColor(com.jarvis.assistant.util.ThemeManager.getThemeTextMutedColor())
            }
        }

        fun updateUI() {
            binding.pbPremiumBar.progress = selected.progressPercent
            binding.tvTierName.text = selected.tierLabel
            binding.tvTierCapacity.text = "${selected.progressPercent}% CAPACITY"

            val tintColor = when (selected) {
                TokenMode.LOW -> themeSuccess
                TokenMode.HIGH -> themeAccent
                TokenMode.MAX -> themeWarning
            }
            binding.pbPremiumBar.progressTintList = ColorStateList.valueOf(tintColor)

            // Low card state
            val isLow = selected == TokenMode.LOW
            binding.cardModeLow.background = ContextCompat.getDrawable(context, if (isLow) R.drawable.bg_token_card_selected else R.drawable.bg_token_card_unselected)
            binding.tvLowRadio.text = if (isLow) "●" else "○"
            binding.tvLowRadio.setTextColor(if (isLow) themeSuccess else com.jarvis.assistant.util.ThemeManager.getThemeTextMutedColor())

            // High card state
            val isHigh = selected == TokenMode.HIGH
            binding.cardModeHigh.background = ContextCompat.getDrawable(context, if (isHigh) R.drawable.bg_token_card_selected else R.drawable.bg_token_card_unselected)
            binding.tvHighRadio.text = if (isHigh) "●" else "○"
            binding.tvHighRadio.setTextColor(if (isHigh) themeAccent else com.jarvis.assistant.util.ThemeManager.getThemeTextMutedColor())

            // Max card state
            val isMax = selected == TokenMode.MAX
            binding.cardModeMax.background = ContextCompat.getDrawable(context, if (isMax) R.drawable.bg_token_card_premium else R.drawable.bg_token_card_unselected)
            binding.tvMaxRadio.text = if (isMax) "●" else "○"
            binding.tvMaxRadio.setTextColor(if (isMax) themeWarning else com.jarvis.assistant.util.ThemeManager.getThemeTextMutedColor())
        }

        updateUI()
        updateAutoApproveUI()

        binding.cardAutoApproveAll.setOnClickListener {
            autoApprove = !autoApprove
            PermissionPreferences.setAutoApproveAll(context, autoApprove)
            updateAutoApproveUI()
            val msg = if (autoApprove) "⚡ Auto-Approve Enabled: All permissions will be approved automatically" else "Auto-Approve Disabled: Safety prompts enabled"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchAutoApproveAll.setOnCheckedChangeListener { _, isChecked ->
            if (autoApprove != isChecked) {
                autoApprove = isChecked
                PermissionPreferences.setAutoApproveAll(context, autoApprove)
                updateAutoApproveUI()
                val msg = if (autoApprove) "⚡ Auto-Approve Enabled: All permissions will be approved automatically" else "Auto-Approve Disabled: Safety prompts enabled"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.cardModeLow.setOnClickListener {
            selected = TokenMode.LOW
            updateUI()
        }

        binding.cardModeHigh.setOnClickListener {
            selected = TokenMode.HIGH
            updateUI()
        }

        binding.cardModeMax.setOnClickListener {
            selected = TokenMode.MAX
            updateUI()
        }

        binding.btnApplyTokenSettings.setOnClickListener {
            TokenModeStore.set(context, selected)
            PermissionPreferences.setAutoApproveAll(context, autoApprove)
            Toast.makeText(context, "Settings applied: ${selected.label} tier active", Toast.LENGTH_SHORT).show()
            onModeChanged?.invoke(selected)
            dialog.dismiss()
        }

        dialog.show()
    }
}
