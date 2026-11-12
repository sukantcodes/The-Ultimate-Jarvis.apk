package com.jarvis.assistant.ui.settings.tts

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.jarvis.assistant.databinding.FragmentTtsSettingsBinding
import com.jarvis.assistant.tts.model.DownloadState
import com.jarvis.assistant.tts.model.SpeakerProfile
import com.jarvis.assistant.tts.model.VoiceEngineType
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch

/**
 * Controller managing the Cybernetic HUD Voice Settings interface.
 * Implements dynamic glassmorphism panels, theme-reactive holographic visualizer,
 * seamless model deployment flows, and immediate voice validation.
 */
class TtsSettingsFragment : Fragment() {

    private var _binding: FragmentTtsSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TtsSettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTtsSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyThemeStyling()
        setupListeners()
        observeViewModel()
    }

    private fun applyThemeStyling() {
        val context = requireContext()
        val themeAccent = try { ThemeManager.getThemeAccentColor() } catch (_: Exception) { Color.parseColor("#2F6BFF") }
        val themeSecondary = try { ThemeManager.getThemeSecondaryColor() } catch (_: Exception) { Color.parseColor("#8B5CF6") }
        val themeSuccess = try { ThemeManager.getThemeSuccessColor() } catch (_: Exception) { Color.parseColor("#00E676") }

        // Top Header
        binding.tvEngineBadge.background = ThemeManager.createDynamicBadgeDrawable(themeAccent, themeSecondary, 10f, context)
        binding.tvEngineBadge.setTextColor(Color.BLACK)

        // Visualizer Container Card
        binding.containerVisualizerCard.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = ColorUtils.setAlphaComponent(themeAccent, 90),
            fillColor = Color.parseColor("#101A2E"),
            cornerRadiusDp = 16f,
            strokeWidthDp = 1.2f,
            context = context
        )

        // Model Deploy Card
        binding.cardModelDeploy.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = Color.parseColor("#232C3D"),
            fillColor = Color.parseColor("#0E1626"),
            cornerRadiusDp = 14f,
            strokeWidthDp = 1f,
            context = context
        )

        // Deploy button
        binding.btnDeployModel.background = ThemeManager.createDynamicBadgeDrawable(themeAccent, themeSecondary, 12f, context)

        // Speaker Persona Card
        binding.cardSpeakerDetail.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = Color.parseColor("#1E2A3E"),
            fillColor = Color.parseColor("#0D1522"),
            cornerRadiusDp = 12f,
            strokeWidthDp = 1f,
            context = context
        )

        // Action Buttons
        binding.btnTestVoice.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = themeAccent,
            fillColor = Color.parseColor("#142036"),
            cornerRadiusDp = 12f,
            strokeWidthDp = 1.5f,
            context = context
        )
        binding.btnSaveAndActivate.background = ThemeManager.createDynamicBadgeDrawable(themeAccent, themeSecondary, 12f, context)

        binding.visualizerOrb.updateThemeColors()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            activity?.onBackPressedDispatcher?.onBackPressed()
        }

        binding.cardEngineKokoro.setOnClickListener {
            viewModel.selectEngine(VoiceEngineType.LOCAL_KOKORO)
        }

        binding.cardEnginePiper.setOnClickListener {
            viewModel.selectEngine(VoiceEngineType.LOCAL_PIPER)
        }

        binding.cardEngineGemini.setOnClickListener {
            viewModel.selectEngine(VoiceEngineType.CLOUD_GEMINI)
        }

        binding.cardEngineEleven.setOnClickListener {
            viewModel.selectEngine(VoiceEngineType.CLOUD_ELEVENLABS)
        }

        binding.btnDeployModel.setOnClickListener {
            viewModel.deployCurrentEngine()
        }

        binding.btnTestVoice.setOnClickListener {
            viewModel.testVoiceValidation()
        }

        binding.btnSaveAndActivate.setOnClickListener {
            viewModel.saveAndActivate {
                Toast.makeText(requireContext(), "Neural Voice parameters saved & verified", Toast.LENGTH_SHORT).show()
            }
        }

        binding.sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val speed = 0.75f + (progress * 0.05f)
                    binding.tvSpeedValue.text = String.format("%.2fx", speed)
                    viewModel.setSpeed(speed)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.selectedEngine.collect { renderEngineSelection(it) } }
                launch { viewModel.selectedSpeaker.collect { renderSelectedSpeaker(it) } }
                launch { viewModel.downloadState.collect { renderDownloadState(it) } }
                launch { viewModel.isEngineInstalled.collect { renderDeploymentState(it) } }
                launch { viewModel.amplitude.collect { binding.visualizerOrb.setAmplitude(it) } }
                launch { viewModel.isSpeaking.collect { renderSpeakingIndicator(it) } }
                launch { viewModel.speechSpeed.collect { renderSpeed(it) } }
            }
        }
    }

    private fun renderEngineSelection(engine: VoiceEngineType) {
        val themeAccent = ThemeManager.getThemeAccentColor()
        val context = requireContext()

        val cards = listOf(
            Triple(VoiceEngineType.LOCAL_KOKORO, binding.cardEngineKokoro, binding.tvRadioKokoro),
            Triple(VoiceEngineType.LOCAL_PIPER, binding.cardEnginePiper, binding.tvRadioPiper),
            Triple(VoiceEngineType.CLOUD_GEMINI, binding.cardEngineGemini, binding.tvRadioGemini),
            Triple(VoiceEngineType.CLOUD_ELEVENLABS, binding.cardEngineEleven, binding.tvRadioEleven)
        )

        for ((type, card, radio) in cards) {
            val isSelected = type == engine
            if (isSelected) {
                card.background = ThemeManager.createDynamicCardDrawable(
                    strokeColor = themeAccent,
                    fillColor = ColorUtils.setAlphaComponent(themeAccent, 24),
                    cornerRadiusDp = 14f,
                    strokeWidthDp = 1.8f,
                    context = context
                )
                radio.text = "●"
                radio.setTextColor(themeAccent)
            } else {
                card.background = ThemeManager.createDynamicCardDrawable(
                    strokeColor = Color.parseColor("#232C3D"),
                    fillColor = Color.parseColor("#121722"),
                    cornerRadiusDp = 14f,
                    strokeWidthDp = 1f,
                    context = context
                )
                radio.text = "○"
                radio.setTextColor(Color.parseColor("#78909C"))
            }
        }

        binding.tvEngineBadge.text = if (engine.isLocal) "★ LOCAL NEURAL" else "★ CLOUD SERVICE"

        // Populate speakers for selected engine
        populateSpeakerChips(engine)
    }

    private fun populateSpeakerChips(engine: VoiceEngineType) {
        val context = requireContext()
        val themeAccent = ThemeManager.getThemeAccentColor()
        binding.chipGroupSpeakers.removeAllViews()

        val speakers = SpeakerProfile.speakersFor(engine)
        val current = viewModel.selectedSpeaker.value

        for (speaker in speakers) {
            val chip = Chip(context).apply {
                text = speaker.displayName
                isCheckable = true
                isChecked = speaker.id == current.id
                chipBackgroundColor = ColorStateList.valueOf(
                    if (isChecked) ColorUtils.setAlphaComponent(themeAccent, 60) else Color.parseColor("#1B2538")
                )
                setTextColor(if (isChecked) Color.WHITE else Color.parseColor("#B0BEC5"))
                chipStrokeWidth = 1.5f * resources.displayMetrics.density
                chipStrokeColor = ColorStateList.valueOf(
                    if (isChecked) themeAccent else Color.parseColor("#2C3A52")
                )
                setOnClickListener {
                    viewModel.selectSpeaker(speaker)
                }
            }
            binding.chipGroupSpeakers.addView(chip)
        }
    }

    private fun renderSelectedSpeaker(speaker: SpeakerProfile) {
        binding.tvVisualizerSpeakerInfo.text = "${speaker.displayName} · ${speaker.language}"
        binding.tvSpeakerDetailName.text = "${speaker.displayName} (${speaker.gender})"
        binding.tvSpeakerDetailTone.text = "Tone: ${speaker.tone} · Engine: ${speaker.engineType.displayName}"
        populateSpeakerChips(viewModel.selectedEngine.value)
    }

    private fun renderDeploymentState(isInstalled: Boolean) {
        val engine = viewModel.selectedEngine.value
        if (!engine.isLocal) {
            binding.cardModelDeploy.visibility = View.GONE
            binding.containerSpeakerSelection.visibility = View.VISIBLE
            return
        }

        binding.cardModelDeploy.visibility = View.VISIBLE

        if (isInstalled) {
            binding.tvDeployStatusBadge.text = "READY & DEPLOYED"
            binding.tvDeployStatusBadge.setTextColor(ThemeManager.getThemeSuccessColor())
            binding.tvDeployDescription.text = "Neural weights validated in internal memory. Ready for zero-latency synthesis."
            binding.btnDeployModel.visibility = View.GONE
            binding.containerDownloading.visibility = View.GONE
            binding.containerSpeakerSelection.visibility = View.VISIBLE
        } else {
            binding.tvDeployStatusBadge.text = "UNINSTALLED"
            binding.tvDeployStatusBadge.setTextColor(Color.parseColor("#FFB74D"))
            binding.tvDeployDescription.text = "Model archive: ${engine.archiveName} · Memory: ~${engine.ramFootprintMb} MB RAM. Operates safely offline."
            binding.btnDeployModel.visibility = View.VISIBLE
            binding.containerDownloading.visibility = View.GONE
            binding.containerSpeakerSelection.visibility = View.GONE
        }
    }

    private fun renderDownloadState(state: DownloadState) {
        when (state) {
            is DownloadState.Idle -> {
                binding.containerDownloading.visibility = View.GONE
            }
            is DownloadState.Downloading -> {
                binding.containerDownloading.visibility = View.VISIBLE
                binding.btnDeployModel.visibility = View.GONE
                binding.pbModelDownload.isIndeterminate = state.progress < 0
                if (state.progress >= 0) {
                    binding.pbModelDownload.progress = state.progress
                    val mbDownloaded = state.bytesDownloaded / (1024f * 1024f)
                    val mbTotal = state.totalBytes / (1024f * 1024f)
                    binding.tvDownloadProgressDetail.text = String.format("Streaming neural core... %d%% (%.1f / %.1f MB)", state.progress, mbDownloaded, mbTotal)
                } else {
                    binding.tvDownloadProgressDetail.text = "Connecting to official distribution mirror..."
                }
            }
            is DownloadState.Extracting -> {
                binding.containerDownloading.visibility = View.VISIBLE
                binding.pbModelDownload.isIndeterminate = true
                binding.tvDownloadProgressDetail.text = "Unpacking and verifying weights: ${state.currentFile}"
            }
            is DownloadState.Ready -> {
                binding.containerDownloading.visibility = View.GONE
                viewModel.refreshInstallationState()
            }
            is DownloadState.Error -> {
                binding.containerDownloading.visibility = View.GONE
                binding.btnDeployModel.visibility = View.VISIBLE
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderSpeakingIndicator(speaking: Boolean) {
        if (speaking) {
            binding.tvVisualizerStatus.text = "SYNTHESIZING & SPEAKING..."
            binding.tvVisualizerStatus.setTextColor(ThemeManager.getThemeSuccessColor())
        } else {
            binding.tvVisualizerStatus.text = "STANDBY · READY"
            binding.tvVisualizerStatus.setTextColor(ThemeManager.getThemeAccentColor())
        }
    }

    private fun renderSpeed(speed: Float) {
        binding.tvSpeedValue.text = String.format("%.2fx", speed)
        val progress = ((speed - 0.75f) / 0.05f).toInt().coerceIn(0, 14)
        binding.sbSpeed.progress = progress
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
