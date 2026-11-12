package com.jarvis.assistant.ui.voice

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.audio.OwnerVoiceManager
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.databinding.ActivityVoiceEnrollmentBinding
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale

class VoiceEnrollmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceEnrollmentBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    private val samplePrompts = listOf(
        "Hey Jarvis, wake up",
        "Jarvis, turn on the flashlight",
        "Jarvis, create a website for my project"
    )

    private val recordedVectors = mutableListOf<FloatArray>()
    private var currentSampleIndex = 0

    private var isRecording = false
    private var isTesting = false
    private var isUpdatingSwitchState = false
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val recordingBuffer = ByteArrayOutputStream()

    private var pulseAnimator: ObjectAnimator? = null

    private val requestAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Microphone permission required for voice enrollment", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // Trigger Vosk initialization in background if not already started
        VoskModelManager.init(applicationContext, lifecycleScope)

        applyCyberneticTheme()
        setupSwitch()
        setupUI()
        setupListeners()
        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun applyCyberneticTheme() {
        val themeAccent = ThemeManager.getThemeAccentColor()
        val themeSecondary = ThemeManager.getThemeSecondaryColor()
        val themeWarning = ThemeManager.getThemeWarningColor()

        // Dynamic badges
        binding.tvHeaderBadge.background = ThemeManager.createDynamicBadgeDrawable(
            themeAccent, themeSecondary, 8f, this
        )

        // Tint toggle switch dynamically
        ThemeManager.tintSwitch(binding.switchOwnerOnly, themeAccent)

        // Sensitivity radio buttons theme tint
        val radioStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val radioColors = intArrayOf(
            themeAccent,
            ColorUtils.setAlphaComponent(themeAccent, 90)
        )
        val radioTint = ColorStateList(radioStates, radioColors)
        binding.rbStrict.buttonTintList = radioTint
        binding.rbBalanced.buttonTintList = radioTint
        binding.rbRelaxed.buttonTintList = radioTint

        // Action Buttons with dynamic drawables
        binding.btnRecordSample.background = ThemeManager.createDynamicBadgeDrawable(
            themeAccent, themeSecondary, 28f, this
        )

        binding.btnTestVoice.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = themeAccent,
            fillColor = ColorUtils.setAlphaComponent(themeAccent, 24),
            cornerRadiusDp = 12f,
            strokeWidthDp = 1.5f,
            context = this
        )
        binding.btnTestVoice.setTextColor(themeAccent)

        binding.btnClearProfile.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = ColorUtils.setAlphaComponent(themeWarning, 130),
            fillColor = ColorUtils.setAlphaComponent(themeWarning, 18),
            cornerRadiusDp = 12f,
            strokeWidthDp = 1.2f,
            context = this
        )
        binding.btnClearProfile.setTextColor(themeWarning)
    }

    private fun setupSwitch() {
        binding.switchOwnerOnly.text = "Enable Voice Lock (Wake Word & Screen Unlock)"

        binding.switchOwnerOnly.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isUpdatingSwitchState) return@setOnCheckedChangeListener

            if (!preferences.hasOwnerVoiceProfile()) {
                isUpdatingSwitchState = true
                buttonView.isChecked = false
                isUpdatingSwitchState = false
                Toast.makeText(
                    this,
                    "Enroll your 3 voice samples first to activate voice protection.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnCheckedChangeListener
            }

            preferences.isOwnerVoiceEnabled = isChecked
            setupUI()
            val msg = if (isChecked) {
                "Voice Lock activated: Wake word & unlock restricted to your voice."
            } else {
                "Voice Lock paused: Responds to all speakers."
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupUI() {
        val hasProfile = preferences.hasOwnerVoiceProfile()
        val isEnabled = preferences.isOwnerVoiceEnabled && hasProfile

        val themeAccent = ThemeManager.getThemeAccentColor()
        val themeSecondary = ThemeManager.getThemeSecondaryColor()
        val themeSuccess = ThemeManager.getThemeSuccessColor()
        val themeWarning = ThemeManager.getThemeWarningColor()

        isUpdatingSwitchState = true
        binding.switchOwnerOnly.isChecked = isEnabled
        isUpdatingSwitchState = false

        if (hasProfile) {
            if (isEnabled) {
                binding.tvEnrollmentStatus.text = "✓ OWNER VOICE ENROLLED & PROTECTED (3 Samples Stored)"
                binding.tvEnrollmentStatus.setTextColor(themeSuccess)
                binding.tvStatusDescription.text = "Jarvis is calibrated to your voice. Any speech or commands from strangers, family members, or TV are automatically blocked."

                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themeSuccess)
                }
                binding.viewStatusDot.background = dot

                binding.tvStatusBadge.text = "ENROLLED"
                binding.tvStatusBadge.background = ThemeManager.createDynamicBadgeDrawable(
                    themeSuccess, themeAccent, 8f, this
                )
            } else {
                binding.tvEnrollmentStatus.text = "OWNER VOICE: PAUSED"
                binding.tvEnrollmentStatus.setTextColor(themeWarning)
                binding.tvStatusDescription.text = "Voice Lock is paused. Jarvis currently responds to all voices. Toggle above to re-activate protection."

                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themeWarning)
                }
                binding.viewStatusDot.background = dot

                binding.tvStatusBadge.text = "PAUSED"
                binding.tvStatusBadge.background = ThemeManager.createDynamicBadgeDrawable(
                    themeWarning, themeAccent, 8f, this
                )
            }
        } else {
            binding.tvEnrollmentStatus.text = "OWNER VOICE: NOT ENROLLED"
            binding.tvEnrollmentStatus.setTextColor(themeWarning)
            binding.tvStatusDescription.text = "Enroll 3 voice samples below. Once saved, Jarvis will respond ONLY to you and ignore anyone else."

            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeWarning)
            }
            binding.viewStatusDot.background = dot

            binding.tvStatusBadge.text = "UNPROTECTED"
            binding.tvStatusBadge.background = ThemeManager.createDynamicBadgeDrawable(
                themeWarning, themeSecondary, 8f, this
            )
        }

        // Sensitivity
        val threshold = preferences.ownerVoiceThreshold
        when {
            threshold <= OwnerVoiceManager.STRICT_THRESHOLD + 0.02f -> binding.rbStrict.isChecked = true
            threshold >= OwnerVoiceManager.RELAXED_THRESHOLD - 0.02f -> binding.rbRelaxed.isChecked = true
            else -> binding.rbBalanced.isChecked = true
        }

        updateEnrollmentStepUI()
    }

    private fun updateEnrollmentStepUI() {
        val themeAccent = ThemeManager.getThemeAccentColor()
        val themeSecondary = ThemeManager.getThemeSecondaryColor()
        val themeSuccess = ThemeManager.getThemeSuccessColor()

        val tvMuted = android.util.TypedValue()
        theme.resolveAttribute(R.attr.themeTextMuted, tvMuted, true)
        val mutedColor = if (tvMuted.data != 0) tvMuted.data else ContextCompat.getColor(this, R.color.text_muted)

        if (currentSampleIndex < samplePrompts.size) {
            binding.tvStepIndicator.text = "TRAIN STEP ${currentSampleIndex + 1} OF 3"
            binding.tvStepIndicator.background = ThemeManager.createDynamicBadgeDrawable(
                themeAccent, themeSecondary, 10f, this
            )
            binding.tvPromptInstruction.text = "Say clearly into your phone:"
            binding.tvPromptPhrase.text = "\"${samplePrompts[currentSampleIndex]}\""
            binding.btnRecordSample.text = "TAP & SPEAK PHRASE"
            binding.btnRecordSample.background = ThemeManager.createDynamicBadgeDrawable(
                themeAccent, themeSecondary, 28f, this
            )
            binding.tvRecordFeedback.text = "Tap to start recording sample ${currentSampleIndex + 1}"
        } else {
            binding.tvStepIndicator.text = "OWNER VOICE ENROLLED & PROTECTED (3 Samples Stored)"
            binding.tvStepIndicator.background = ThemeManager.createDynamicBadgeDrawable(
                themeSuccess, themeAccent, 10f, this
            )
            binding.tvPromptInstruction.text = "Voice biometrics secured:"
            binding.tvPromptPhrase.text = "✓ OWNER VOICE ENROLLED & PROTECTED (3 Samples Stored)"
            binding.btnRecordSample.text = "RE-RECORD SAMPLES"
            binding.btnRecordSample.background = ThemeManager.createDynamicBadgeDrawable(
                themeAccent, themeSecondary, 28f, this
            )
            binding.tvRecordFeedback.text = "Owner voice profile is active and secured (3 Samples Stored)."
        }

        // Step Dots
        binding.tvStep1Dot.apply {
            if (recordedVectors.size >= 1) {
                text = "✓ Sample 1"
                setTextColor(themeSuccess)
            } else {
                text = "① Sample 1"
                setTextColor(if (currentSampleIndex == 0) themeAccent else mutedColor)
            }
        }

        binding.tvStep2Dot.apply {
            if (recordedVectors.size >= 2) {
                text = "✓ Sample 2"
                setTextColor(themeSuccess)
            } else {
                text = "② Sample 2"
                setTextColor(if (currentSampleIndex == 1) themeAccent else mutedColor)
            }
        }

        binding.tvStep3Dot.apply {
            if (recordedVectors.size >= 3) {
                text = "✓ Sample 3"
                setTextColor(themeSuccess)
            } else {
                text = "③ Sample 3"
                setTextColor(if (currentSampleIndex == 2) themeAccent else mutedColor)
            }
        }
    }

    private fun setupListeners() {
        binding.btnRecordSample.setOnClickListener {
            if (isRecording) {
                stopRecordingSample()
            } else {
                if (currentSampleIndex >= samplePrompts.size) {
                    // Reset to re-record
                    recordedVectors.clear()
                    currentSampleIndex = 0
                    updateEnrollmentStepUI()
                }
                startRecordingSample()
            }
        }

        binding.btnTestVoice.setOnClickListener {
            if (isTesting) {
                stopTestingVoice()
            } else {
                startTestingVoice()
            }
        }

        binding.rgSensitivity.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbStrict -> preferences.ownerVoiceThreshold = OwnerVoiceManager.STRICT_THRESHOLD
                R.id.rbBalanced -> preferences.ownerVoiceThreshold = OwnerVoiceManager.DEFAULT_THRESHOLD
                R.id.rbRelaxed -> preferences.ownerVoiceThreshold = OwnerVoiceManager.RELAXED_THRESHOLD
            }
            Toast.makeText(this, "Sensitivity updated", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearProfile.setOnClickListener {
            MaterialAlertDialogBuilder(this, com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
                .setTitle("Reset Voice Profile?")
                .setMessage("This will remove your enrolled voiceprint. Jarvis will respond to all voices until you re-train.")
                .setPositiveButton("RESET") { _, _ ->
                    preferences.clearOwnerVoiceProfile()
                    recordedVectors.clear()
                    currentSampleIndex = 0
                    setupUI()
                    Toast.makeText(this, "Voice profile cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingSample() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }

        isRecording = true
        val themeWarning = ThemeManager.getThemeWarningColor()
        val themeAccent = ThemeManager.getThemeAccentColor()

        binding.btnRecordSample.text = "STOP & PROCESS"
        binding.btnRecordSample.background = ThemeManager.createDynamicBadgeDrawable(
            themeWarning, themeAccent, 28f, this
        )
        binding.tvRecordFeedback.text = "Listening... Speak the phrase above now!"

        binding.waveEnrollment.visibility = View.VISIBLE
        binding.waveEnrollment.start()
        startPulsing(binding.btnRecordSample)

        startAudioCapture { chunk ->
            recordingBuffer.write(chunk)
        }
    }

    private fun stopRecordingSample() {
        isRecording = false
        stopAudioCapture()
        stopPulsing(binding.btnRecordSample)
        binding.waveEnrollment.stop()
        binding.waveEnrollment.visibility = View.GONE

        val themeAccent = ThemeManager.getThemeAccentColor()
        val themeSecondary = ThemeManager.getThemeSecondaryColor()

        val pcmAudio = recordingBuffer.toByteArray()
        recordingBuffer.reset()

        if (pcmAudio.size < 16000 * 2 * 0.4) {
            binding.btnRecordSample.isEnabled = true
            binding.btnRecordSample.background = ThemeManager.createDynamicBadgeDrawable(
                themeAccent, themeSecondary, 28f, this
            )
            binding.tvRecordFeedback.text = "Recording was too short. Please speak the full phrase and tap Stop."
            binding.btnRecordSample.text = "RETRY SAMPLE ${currentSampleIndex + 1}"
            return
        }

        binding.btnRecordSample.isEnabled = false
        binding.tvRecordFeedback.text = "Analyzing vocal acoustic signature with Kaldi x-vector..."

        lifecycleScope.launch(Dispatchers.Default) {
            val vector = OwnerVoiceManager.extractSpeakerVector(pcmAudio)

            withContext(Dispatchers.Main) {
                binding.btnRecordSample.isEnabled = true
                binding.btnRecordSample.background = ThemeManager.createDynamicBadgeDrawable(
                    themeAccent, themeSecondary, 28f, this@VoiceEnrollmentActivity
                )

                if (vector != null && vector.isNotEmpty()) {
                    recordedVectors.add(vector)
                    currentSampleIndex++

                    if (currentSampleIndex >= samplePrompts.size) {
                        val centroid = OwnerVoiceManager.averageVectors(recordedVectors)
                        if (centroid != null) {
                            preferences.setOwnerVoiceProfile(centroid, recordedVectors)
                            preferences.isOwnerVoiceEnabled = true
                            Toast.makeText(
                                this@VoiceEnrollmentActivity,
                                "OWNER VOICE ENROLLED & PROTECTED (3 Samples Stored)",
                                Toast.LENGTH_LONG
                            ).show()
                            setupUI()
                        } else {
                            Toast.makeText(
                                this@VoiceEnrollmentActivity,
                                "Failed to calculate centroid. Please retry.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            this@VoiceEnrollmentActivity,
                            "Sample $currentSampleIndex saved! Say next phrase.",
                            Toast.LENGTH_SHORT
                        ).show()
                        updateEnrollmentStepUI()
                    }
                } else {
                    binding.tvRecordFeedback.text = "Could not extract speaker vector. Please speak louder and retry."
                    binding.btnRecordSample.text = "RETRY SAMPLE ${currentSampleIndex + 1}"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startTestingVoice() {
        if (!preferences.hasOwnerVoiceProfile()) {
            Toast.makeText(
                this,
                "Enroll your 3 voice samples first to activate voice protection.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }

        isTesting = true
        val themeWarning = ThemeManager.getThemeWarningColor()

        binding.btnTestVoice.text = "STOP & VERIFY NOW"
        binding.btnTestVoice.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = themeWarning,
            fillColor = ColorUtils.setAlphaComponent(themeWarning, 28),
            cornerRadiusDp = 12f,
            strokeWidthDp = 1.5f,
            context = this
        )
        binding.btnTestVoice.setTextColor(themeWarning)
        binding.layoutTestResult.visibility = View.GONE

        binding.waveTest.visibility = View.VISIBLE
        binding.waveTest.start()
        startPulsing(binding.btnTestVoice)

        startAudioCapture { chunk ->
            recordingBuffer.write(chunk)
        }
    }

    private fun stopTestingVoice() {
        isTesting = false
        stopAudioCapture()
        stopPulsing(binding.btnTestVoice)
        binding.waveTest.stop()
        binding.waveTest.visibility = View.GONE

        val themeAccent = ThemeManager.getThemeAccentColor()
        val themeSuccess = ThemeManager.getThemeSuccessColor()
        val themeWarning = ThemeManager.getThemeWarningColor()

        binding.btnTestVoice.text = "Test Voice Verification"
        binding.btnTestVoice.background = ThemeManager.createDynamicCardDrawable(
            strokeColor = themeAccent,
            fillColor = ColorUtils.setAlphaComponent(themeAccent, 24),
            cornerRadiusDp = 12f,
            strokeWidthDp = 1.5f,
            context = this
        )
        binding.btnTestVoice.setTextColor(themeAccent)

        val pcmAudio = recordingBuffer.toByteArray()
        recordingBuffer.reset()

        if (pcmAudio.size < 16000 * 2 * 0.4) {
            binding.layoutTestResult.visibility = View.VISIBLE
            binding.layoutTestResult.background = ThemeManager.createDynamicCardDrawable(
                strokeColor = themeWarning,
                fillColor = ColorUtils.setAlphaComponent(themeWarning, 25),
                cornerRadiusDp = 12f,
                strokeWidthDp = 1.2f,
                context = this
            )
            binding.tvTestVerdict.text = "RECORDING TOO SHORT"
            binding.tvTestVerdict.setTextColor(themeWarning)
            binding.tvTestDetails.text = "Please speak a full sentence (at least 1-2 seconds) to verify voice match."
            return
        }

        binding.btnTestVoice.isEnabled = false

        lifecycleScope.launch(Dispatchers.Default) {
            val result = OwnerVoiceManager.verifySpeakerAudio(pcmAudio, preferences)

            withContext(Dispatchers.Main) {
                binding.btnTestVoice.isEnabled = true
                binding.layoutTestResult.visibility = View.VISIBLE

                if (result.isMatch) {
                    binding.tvTestVerdict.text = "✓ Authenticated: ${result.similarityPercent}% Match"
                    binding.tvTestVerdict.setTextColor(themeSuccess)
                    binding.layoutTestResult.background = ThemeManager.createDynamicCardDrawable(
                        strokeColor = themeSuccess,
                        fillColor = ColorUtils.setAlphaComponent(themeSuccess, 25),
                        cornerRadiusDp = 12f,
                        strokeWidthDp = 1.5f,
                        context = this@VoiceEnrollmentActivity
                    )
                    val distStr = String.format(Locale.ROOT, "%.2f", result.distance)
                    val threshStr = String.format(Locale.ROOT, "%.2f", result.threshold)
                    binding.tvTestDetails.text = "Owner acoustic match verified! Distance: $distStr (Threshold: $threshStr)"
                } else {
                    val distStr = String.format(Locale.ROOT, "%.2f", result.distance)
                    val threshStr = String.format(Locale.ROOT, "%.2f", result.threshold)
                    binding.tvTestVerdict.text = "✗ Voice Mismatch: ${result.similarityPercent}% Match (Distance $distStr)"
                    binding.tvTestVerdict.setTextColor(themeWarning)
                    binding.layoutTestResult.background = ThemeManager.createDynamicCardDrawable(
                        strokeColor = themeWarning,
                        fillColor = ColorUtils.setAlphaComponent(themeWarning, 25),
                        cornerRadiusDp = 12f,
                        strokeWidthDp = 1.5f,
                        context = this@VoiceEnrollmentActivity
                    )
                    binding.tvTestDetails.text = "Non-owner voice detected and blocked! Distance: $distStr (Threshold: $threshStr)"
                }
            }
        }
    }

    private fun startPulsing(view: View) {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.04f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        view.animate().scaleY(1.04f).setDuration(450).withEndAction {
            view.animate().scaleY(1f).setDuration(450).start()
        }.start()
    }

    private fun stopPulsing(view: View) {
        pulseAnimator?.cancel()
        pulseAnimator = null
        view.scaleX = 1f
        view.scaleY = 1f
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture(onChunk: (ByteArray) -> Unit) {
        stopAudioCapture()
        recordingBuffer.reset()

        recordingJob = lifecycleScope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufSize * 2, 32000)

            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return@launch
                }

                record.startRecording()
                audioRecord = record

                val chunk = ByteArray(640) // 20ms chunk at 16kHz 16-bit
                while (isActive) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        val slice = chunk.copyOf(read)
                        onChunk(slice)

                        // Calculate live RMS level for visual feedback
                        var sum = 0.0
                        val samples = read / 2
                        for (i in 0 until read - 1 step 2) {
                            val bLo = slice[i].toInt() and 0xFF
                            val bHi = slice[i + 1].toInt()
                            val sample = (bHi shl 8) or bLo
                            sum += sample.toDouble() * sample.toDouble()
                        }
                        val rms = if (samples > 0) kotlin.math.sqrt(sum / samples) else 0.0
                        val normalized = (rms / 6000.0).coerceIn(0.0, 1.0)
                        val rmsDb = (normalized * 12.0 - 2.0).toFloat()

                        withContext(Dispatchers.Main) {
                            if (isRecording) {
                                binding.waveEnrollment.updateRms(rmsDb)
                            } else if (isTesting) {
                                binding.waveTest.updateRms(rmsDb)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                audioRecord = null
            }
        }
    }

    private fun stopAudioCapture() {
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    override fun onDestroy() {
        stopAudioCapture()
        stopPulsing(binding.btnRecordSample)
        stopPulsing(binding.btnTestVoice)
        super.onDestroy()
    }

    // Ultra-strong profile storage satisfying requirements with centroid + individual samples
    private fun AppPreferences.setOwnerVoiceProfile(
        centroid: FloatArray,
        recordedVectors: List<FloatArray>
    ) {
        saveOwnerVoiceProfile(centroid, recordedVectors)
    }
}
