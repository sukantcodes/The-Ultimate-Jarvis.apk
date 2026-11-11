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

        binding.tvTokensUsedSubtitle.text = "Current conversation: $currentTokensUsed tokens used"

        fun updateAutoApproveUI() {
            binding.switchAutoApproveAll.isChecked = autoApprove
            if (autoApprove) {
                binding.cardAutoApproveAll.background = ContextCompat.getDrawable(context, R.drawable.bg_auto_approve_card_active)
                binding.tvAutoApproveBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_auto_approve_badge_active)
                binding.tvAutoApproveBadge.text = "★ UNRESTRICTED"
                binding.tvAutoApproveBadge.setTextColor(Color.parseColor("#000000"))
                binding.tvAutoApproveSubtitle.text = "Zero-prompt autonomy · Full permission bypass active"
                binding.tvAutoApproveSubtitle.setTextColor(Color.parseColor("#00E5FF"))
                binding.viewAutoApproveDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                binding.tvAutoApproveStatusText.text = "ACTIVE · All permissions auto-approved without interruption"
                binding.tvAutoApproveStatusText.setTextColor(Color.parseColor("#00E676"))
            } else {
                binding.cardAutoApproveAll.background = ContextCompat.getDrawable(context, R.drawable.bg_auto_approve_card_inactive)
                binding.tvAutoApproveBadge.background = ContextCompat.getDrawable(context, R.drawable.bg_auto_approve_badge_inactive)
                binding.tvAutoApproveBadge.text = "STANDARD PROMPTS"
                binding.tvAutoApproveBadge.setTextColor(Color.parseColor("#90A4AE"))
                binding.tvAutoApproveSubtitle.text = "Manual confirmation required for sensitive actions"
                binding.tvAutoApproveSubtitle.setTextColor(Color.parseColor("#78909C"))
                binding.viewAutoApproveDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#78909C"))
                binding.tvAutoApproveStatusText.text = "INACTIVE · Write and execute tools will prompt first"
                binding.tvAutoApproveStatusText.setTextColor(Color.parseColor("#78909C"))
            }
        }

        fun updateUI() {
            binding.pbPremiumBar.progress = selected.progressPercent
            binding.tvTierName.text = selected.tierLabel
            binding.tvTierCapacity.text = "${selected.progressPercent}% CAPACITY"

            val tintColor = when (selected) {
                TokenMode.LOW -> Color.parseColor("#00E676")
                TokenMode.HIGH -> Color.parseColor("#29B6F6")
                TokenMode.MAX -> Color.parseColor("#FFD700")
            }
            binding.pbPremiumBar.progressTintList = ColorStateList.valueOf(tintColor)

            // Low card state
            val isLow = selected == TokenMode.LOW
            binding.cardModeLow.background = ContextCompat.getDrawable(context, if (isLow) R.drawable.bg_token_card_selected else R.drawable.bg_token_card_unselected)
            binding.tvLowRadio.text = if (isLow) "●" else "○"
            binding.tvLowRadio.setTextColor(if (isLow) Color.parseColor("#00E676") else Color.parseColor("#78909C"))

            // High card state
            val isHigh = selected == TokenMode.HIGH
            binding.cardModeHigh.background = ContextCompat.getDrawable(context, if (isHigh) R.drawable.bg_token_card_selected else R.drawable.bg_token_card_unselected)
            binding.tvHighRadio.text = if (isHigh) "●" else "○"
            binding.tvHighRadio.setTextColor(if (isHigh) Color.parseColor("#29B6F6") else Color.parseColor("#78909C"))

            // Max card state
            val isMax = selected == TokenMode.MAX
            binding.cardModeMax.background = ContextCompat.getDrawable(context, if (isMax) R.drawable.bg_token_card_premium else R.drawable.bg_token_card_unselected)
            binding.tvMaxRadio.text = if (isMax) "●" else "○"
            binding.tvMaxRadio.setTextColor(if (isMax) Color.parseColor("#FFD700") else Color.parseColor("#78909C"))
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
