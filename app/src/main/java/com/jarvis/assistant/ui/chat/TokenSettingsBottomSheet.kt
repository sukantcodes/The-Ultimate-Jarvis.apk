package com.jarvis.assistant.ui.chat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jarvis.assistant.databinding.SheetTokenSettingsBinding
import com.jarvis.assistant.provider.TokenMode
import com.jarvis.assistant.provider.TokenModeStore
import com.jarvis.assistant.tools.PermissionPreferences
import com.jarvis.assistant.util.ThemeManager

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

        val themeAccent = try { ThemeManager.getThemeAccentColor() } catch (_: Exception) { Color.parseColor("#2F6BFF") }
        val themeSecondary = try { ThemeManager.getThemeSecondaryColor() } catch (_: Exception) { Color.parseColor("#8B5CF6") }
        val themeSuccess = try { ThemeManager.getThemeSuccessColor() } catch (_: Exception) { Color.parseColor("#00E676") }
        val themeWarning = try { ThemeManager.getThemeWarningColor() } catch (_: Exception) { Color.parseColor("#FFD700") }

        binding.tvTokensUsedSubtitle.text = "Current conversation: $currentTokensUsed tokens used"

        // Dynamic theme bindings
        ThemeManager.tintSwitch(binding.switchAutoApproveAll, themeAccent)
        binding.btnApplyTokenSettings.background = ThemeManager.createDynamicBadgeDrawable(themeAccent, themeSecondary, 12f, context)

        fun updateAutoApproveUI() {
            binding.switchAutoApproveAll.isChecked = autoApprove
            if (autoApprove) {
                binding.cardAutoApproveAll.background = ThemeManager.createDynamicCardDrawable(
                    strokeColor = themeAccent,
                    fillColor = ColorUtils.setAlphaComponent(themeAccent, 24),
                    cornerRadiusDp = 14f,
                    strokeWidthDp = 1.8f,
                    context = context
                )
                binding.tvAutoApproveBadge.background = ThemeManager.createDynamicBadgeDrawable(
                    startColor = themeAccent,
                    endColor = themeSecondary,
                    cornerRadiusDp = 12f,
                    context = context
                )
                binding.tvAutoApproveBadge.text = "★ UNRESTRICTED"
                binding.tvAutoApproveBadge.setTextColor(Color.parseColor("#000000"))
                binding.tvAutoApproveSubtitle.text = "Zero-prompt autonomy · Full permission bypass active"
                binding.tvAutoApproveSubtitle.setTextColor(themeAccent)
                binding.viewAutoApproveDot.backgroundTintList = ColorStateList.valueOf(themeSuccess)
                binding.tvAutoApproveStatusText.text = "ACTIVE · All permissions auto-approved without interruption"
                binding.tvAutoApproveStatusText.setTextColor(themeSuccess)
            } else {
                binding.cardAutoApproveAll.background = ThemeManager.createDynamicCardDrawable(
                    strokeColor = Color.parseColor("#232C3D"),
                    fillColor = Color.parseColor("#121722"),
                    cornerRadiusDp = 14f,
                    strokeWidthDp = 1f,
                    context = context
                )
                binding.tvAutoApproveBadge.background = ThemeManager.createDynamicCardDrawable(
                    strokeColor = Color.parseColor("#2E3A4E"),
                    fillColor = Color.parseColor("#20293D"),
                    cornerRadiusDp = 12f,
                    strokeWidthDp = 1f,
                    context = context
                )
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
                TokenMode.LOW -> themeSuccess
                TokenMode.HIGH -> themeAccent
                TokenMode.MAX -> themeWarning
            }
            binding.pbPremiumBar.progressTintList = ColorStateList.valueOf(tintColor)

            // Low card state
            val isLow = selected == TokenMode.LOW
            binding.cardModeLow.background = if (isLow) {
                ThemeManager.createDynamicCardDrawable(themeSuccess, ColorUtils.setAlphaComponent(themeSuccess, 22), 12f, 2f, context)
            } else {
                ThemeManager.createDynamicCardDrawable(Color.parseColor("#232C3D"), Color.parseColor("#121722"), 12f, 1f, context)
            }
            binding.tvLowRadio.text = if (isLow) "●" else "○"
            binding.tvLowRadio.setTextColor(if (isLow) themeSuccess else Color.parseColor("#78909C"))

            // High card state
            val isHigh = selected == TokenMode.HIGH
            binding.cardModeHigh.background = if (isHigh) {
                ThemeManager.createDynamicCardDrawable(themeAccent, ColorUtils.setAlphaComponent(themeAccent, 22), 12f, 2f, context)
            } else {
                ThemeManager.createDynamicCardDrawable(Color.parseColor("#232C3D"), Color.parseColor("#121722"), 12f, 1f, context)
            }
            binding.tvHighRadio.text = if (isHigh) "●" else "○"
            binding.tvHighRadio.setTextColor(if (isHigh) themeAccent else Color.parseColor("#78909C"))

            // Max card state
            val isMax = selected == TokenMode.MAX
            binding.cardModeMax.background = if (isMax) {
                ThemeManager.createDynamicCardDrawable(themeWarning, ColorUtils.setAlphaComponent(themeWarning, 22), 12f, 2f, context)
            } else {
                ThemeManager.createDynamicCardDrawable(Color.parseColor("#232C3D"), Color.parseColor("#121722"), 12f, 1f, context)
            }
            binding.tvMaxRadio.text = if (isMax) "●" else "○"
            binding.tvMaxRadio.setTextColor(if (isMax) themeWarning else Color.parseColor("#78909C"))
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
