package com.jarvis.assistant.ui.telemetry

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.SheetHardwareTelemetryBinding
import com.jarvis.assistant.telemetry.FullTelemetrySnapshot
import com.jarvis.assistant.telemetry.HardwareTelemetryEngine
import com.jarvis.assistant.telemetry.ThermalStatus
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Iron-Man Real-Time Cybernetic Hardware Telemetry Bottom Sheet.
 * Features live dynamic color synchronization with Jarvis ThemeManager,
 * real-time 1000ms hardware polling loop, and vocalized Iron-Man system reporting.
 */
class HardwareTelemetryBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetHardwareTelemetryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), R.style.ThemeOverlay_Jarvis_BottomSheet).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetHardwareTelemetryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applyDynamicCyberneticTheme()
        setupActionListeners()

        // Capture initial snapshot immediately for zero-latency display
        val initialSnapshot = HardwareTelemetryEngine.captureSnapshot(requireContext())
        updateUi(initialSnapshot)

        // Resumed coroutine telemetry polling loop (1000ms cadence)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    val snapshot = HardwareTelemetryEngine.captureSnapshot(requireContext())
                    updateUi(snapshot)
                    delay(1000L)
                }
            }
        }
    }

    private fun applyDynamicCyberneticTheme() {
        val context = requireContext()
        val accentColor = ThemeManager.getThemeAccentColor()
        val secondaryColor = ThemeManager.getThemeSecondaryColor()
        val cardBgColor = resolveThemeColor(R.attr.themeCardBg)
        val cardBorderColor = resolveThemeColor(R.attr.themeCardBorder)

        // Header status badge
        binding.tvHudStatusBadge.background = ThemeManager.createDynamicBadgeDrawable(
            startColor = accentColor,
            endColor = secondaryColor,
            cornerRadiusDp = 10f,
            context = context
        )

        // Card backgrounds with cybernetic theme strokes
        binding.cardCpu.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = accentColor,
            fillColor = cardBgColor,
            cornerRadiusDp = 14f,
            strokeWidthDp = 1.2f,
            context = context
        )

        binding.cardMemory.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = secondaryColor,
            fillColor = cardBgColor,
            cornerRadiusDp = 14f,
            strokeWidthDp = 1.2f,
            context = context
        )

        binding.cardStorageNetwork.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = accentColor,
            fillColor = cardBgColor,
            cornerRadiusDp = 14f,
            strokeWidthDp = 1.2f,
            context = context
        )

        // Action buttons
        binding.btnVocalizeReport.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = accentColor,
            fillColor = ColorUtils.setAlphaComponent(accentColor, 40),
            cornerRadiusDp = 12f,
            strokeWidthDp = 1.5f,
            context = context
        )

        binding.btnDismissHud.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = cardBorderColor,
            fillColor = cardBgColor,
            cornerRadiusDp = 12f,
            strokeWidthDp = 1.2f,
            context = context
        )

        // Dynamic tints for icons and progress bars
        binding.ivCpuIcon.imageTintList = ColorStateList.valueOf(accentColor)
        binding.pbCpuLoad.progressTintList = ColorStateList.valueOf(accentColor)

        binding.ivMemoryIcon.imageTintList = ColorStateList.valueOf(secondaryColor)
        binding.pbMemoryLoad.progressTintList = ColorStateList.valueOf(secondaryColor)

        binding.ivStorageIcon.imageTintList = ColorStateList.valueOf(accentColor)
        binding.pbStorageLoad.progressTintList = ColorStateList.valueOf(accentColor)

        binding.ivVocalizeIcon.imageTintList = ColorStateList.valueOf(accentColor)
    }

    private fun setupActionListeners() {
        binding.btnDismissHud.setOnClickListener {
            dismiss()
        }

        binding.btnVocalizeReport.setOnClickListener {
            vocalizeReport()
        }
    }

    private fun updateUi(snapshot: FullTelemetrySnapshot) {
        val context = context ?: return
        val accentColor = ThemeManager.getThemeAccentColor()
        val secondaryColor = ThemeManager.getThemeSecondaryColor()
        val successColor = ThemeManager.getThemeSuccessColor()
        val warningColor = ThemeManager.getThemeWarningColor()
        val cardBgColor = resolveThemeColor(R.attr.themeCardBg)
        val cardBorderColor = resolveThemeColor(R.attr.themeCardBorder)

        // 1. CPU SoC
        binding.tvCpuLoadBadge.text = "${snapshot.cpu.loadPercent}% LOAD"
        binding.tvCpuLoadBadge.background = ThemeManager.createDynamicBadgeDrawable(
            startColor = ColorUtils.setAlphaComponent(accentColor, 60),
            endColor = accentColor,
            cornerRadiusDp = 8f,
            context = context
        )
        binding.tvCpuDetails.text = "${snapshot.cpu.coreCount} Cores • ${snapshot.cpu.currentFreqMhz} MHz / ${snapshot.cpu.maxFreqMhz} MHz"
        binding.pbCpuLoad.progress = snapshot.cpu.loadPercent.coerceIn(0, 100)

        // 2. Memory RAM
        val usedGb = snapshot.memory.usedBytes / (1024.0 * 1024.0 * 1024.0)
        val totalGb = snapshot.memory.totalBytes / (1024.0 * 1024.0 * 1024.0)
        val freeGb = snapshot.memory.availableBytes / (1024.0 * 1024.0 * 1024.0)
        val memPercent = (snapshot.memory.usedFraction * 100).roundToInt()

        binding.tvMemoryLoadBadge.text = "$memPercent% USED"
        binding.tvMemoryLoadBadge.background = ThemeManager.createDynamicBadgeDrawable(
            startColor = ColorUtils.setAlphaComponent(secondaryColor, 60),
            endColor = secondaryColor,
            cornerRadiusDp = 8f,
            context = context
        )
        binding.tvMemoryDetails.text = String.format(
            Locale.US,
            "Used: %.1f GB • Total: %.1f GB (%.1f GB Free)",
            usedGb,
            totalGb,
            freeGb
        )
        binding.pbMemoryLoad.progress = memPercent.coerceIn(0, 100)

        // 3. Battery & Thermals
        val batteryColor = when (snapshot.battery.thermalStatus) {
            ThermalStatus.NOMINAL -> successColor
            ThermalStatus.ELEVATED, ThermalStatus.CRITICAL_OVERHEAT -> warningColor
        }

        binding.cardBattery.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = batteryColor,
            fillColor = cardBgColor,
            cornerRadiusDp = 14f,
            strokeWidthDp = 1.2f,
            context = context
        )
        binding.ivBatteryIcon.imageTintList = ColorStateList.valueOf(batteryColor)
        binding.pbBatteryLevel.progressTintList = ColorStateList.valueOf(batteryColor)

        val tempStr = String.format(Locale.US, "%.1f°C", snapshot.battery.temperatureCelsius)
        val thermalStatusText = when (snapshot.battery.thermalStatus) {
            ThermalStatus.NOMINAL -> "$tempStr • NOMINAL"
            ThermalStatus.ELEVATED -> "$tempStr • ELEVATED"
            ThermalStatus.CRITICAL_OVERHEAT -> "$tempStr • CRITICAL"
        }
        binding.tvThermalStatusBadge.text = thermalStatusText
        binding.tvThermalStatusBadge.background = ThemeManager.createDynamicBadgeDrawable(
            startColor = ColorUtils.setAlphaComponent(batteryColor, 60),
            endColor = batteryColor,
            cornerRadiusDp = 8f,
            context = context
        )

        binding.tvBatteryDetails.text = "Level: ${snapshot.battery.levelPercent}% • ${snapshot.battery.chargeType} • ${snapshot.battery.voltageMv} mV"
        binding.tvThermalDetails.text = "Thermal Core: $tempStr • Status: ${snapshot.battery.thermalStatus.name}"
        binding.pbBatteryLevel.progress = snapshot.battery.levelPercent.coerceIn(0, 100)

        // 4. Internal Storage & Network
        val freeStorageGb = snapshot.storage.freeBytes / (1024.0 * 1024.0 * 1024.0)
        val totalStorageGb = snapshot.storage.totalBytes / (1024.0 * 1024.0 * 1024.0)
        val storagePercent = (snapshot.storage.usedFraction * 100).roundToInt()

        binding.tvStorageDetails.text = String.format(
            Locale.US,
            "Internal Flash: %.1f GB Free of %.1f GB",
            freeStorageGb,
            totalStorageGb
        )
        binding.pbStorageLoad.progress = storagePercent.coerceIn(0, 100)

        if (snapshot.network.isConnected) {
            binding.tvNetworkBadge.text = "${snapshot.network.typeName} ONLINE"
            binding.tvNetworkBadge.background = ThemeManager.createDynamicBadgeDrawable(
                startColor = ColorUtils.setAlphaComponent(accentColor, 60),
                endColor = accentColor,
                cornerRadiusDp = 8f,
                context = context
            )
        } else {
            binding.tvNetworkBadge.text = "OFFLINE"
            binding.tvNetworkBadge.background = ThemeManager.createDynamicBadgeDrawable(
                startColor = ColorUtils.setAlphaComponent(cardBorderColor, 80),
                endColor = cardBorderColor,
                cornerRadiusDp = 8f,
                context = context
            )
        }

        val rxFormatted = formatSpeed(snapshot.network.rxSpeedKbps)
        val txFormatted = formatSpeed(snapshot.network.txSpeedKbps)
        binding.tvNetworkDetails.text = "Active Link: ${snapshot.network.typeName} • Rx: $rxFormatted • Tx: $txFormatted"
    }

    private fun formatSpeed(kbps: Long): String {
        return when {
            kbps >= 1000L -> String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
            else -> "$kbps Kbps"
        }
    }

    private fun vocalizeReport() {
        val context = context ?: return
        val speechReport = HardwareTelemetryEngine.generateIronManSpeechReport(context)

        try {
            val dispatcher = com.jarvis.assistant.tts.engine.JarvisVoiceDispatcher.getInstance(context)
            dispatcher.speak(speechReport)
        } catch (_: Throwable) {
            com.jarvis.assistant.voice.JarvisVoiceController.speakTurnResponse(
                context = context,
                turnId = "telemetry_${System.currentTimeMillis()}",
                userPrompt = "System status report",
                fullResponse = speechReport
            )
        }
    }

    private fun resolveThemeColor(@AttrRes attrRes: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attrRes, typedValue, true)
        return typedValue.data
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "HardwareTelemetryBottomSheet"

        fun newInstance(): HardwareTelemetryBottomSheet = HardwareTelemetryBottomSheet()

        fun show(fragmentManager: FragmentManager, tag: String = TAG): HardwareTelemetryBottomSheet {
            val existing = fragmentManager.findFragmentByTag(tag) as? HardwareTelemetryBottomSheet
            if (existing != null && existing.isAdded) {
                return existing
            }
            return newInstance().apply {
                show(fragmentManager, tag)
            }
        }

        fun show(context: Context): HardwareTelemetryBottomSheet? {
            val activity = when (context) {
                is FragmentActivity -> context
                is android.content.ContextWrapper -> context.baseContext as? FragmentActivity
                else -> null
            }
            return activity?.let { show(it.supportFragmentManager) }
        }
    }
}
