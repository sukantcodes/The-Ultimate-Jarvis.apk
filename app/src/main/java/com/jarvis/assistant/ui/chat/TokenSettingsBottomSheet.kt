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
        binding.tvTokensUsedSubtitle.text = "Current conversation: $currentTokensUsed tokens used"

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
            Toast.makeText(context, "Token mode set to ${selected.label}", Toast.LENGTH_SHORT).show()
            onModeChanged?.invoke(selected)
            dialog.dismiss()
        }

        dialog.show()
    }
}
