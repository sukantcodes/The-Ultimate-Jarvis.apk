package com.jarvis.assistant.ui.chat

import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.databinding.FragmentChatBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import com.jarvis.assistant.offline.OfflineLlm
import com.jarvis.assistant.offline.OfflineModelDownloader
import com.jarvis.assistant.ui.offline.OfflineModelActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.Color
import android.content.res.ColorStateList
import com.jarvis.assistant.util.ThemeManager
import com.jarvis.assistant.voice.JarvisVoiceController
import java.io.File
import java.io.FileOutputStream
import com.jarvis.assistant.provider.AttachmentRef
import com.jarvis.assistant.provider.EngineState
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.SendOutcome

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    /**
     * Runs a chat control only while the screen exists, and turns an unexpected error into a toast with the
     * reason instead of crashing the whole app.
     */
    private fun View.onSafeClick(action: () -> Unit) {
        setOnClickListener {
            if (_binding == null || !isAdded) return@setOnClickListener
            try {
                action()
            } catch (e: Exception) {
                android.util.Log.e("ChatFragment", "Chat action failed", e)
                val ctx = context ?: return@setOnClickListener
                Toast.makeText(ctx, "Something went wrong: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private var selectedDocument: DocumentAnalysisClient.SelectedDocument? = null
    private var pendingExportText: String = ""
    private var offlineGenerationJob: Job? = null
    private var fileAnalysisJob: Job? = null
    private val chatModePrefs by lazy { requireContext().getSharedPreferences("jarvis_chatbox_preferences", android.content.Context.MODE_PRIVATE) }
    private var selectedChatMode: String = "auto"
    private val providerManager by lazy { ProviderRuntime.manager(requireContext()) }
    private val providerEngine by lazy { ProviderRuntime.chatEngine(requireContext()) }
    private var retrySnackbar: com.google.android.material.snackbar.Snackbar? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isVoiceListening: Boolean = false
    private var totalUsedTokens: Int = 0
    private var lastMessageOriginatedFromVoice: Boolean = false
    private var lastAutoSpokenTurnId: String = ""
    private var autoVoiceReplyEnabled: Boolean = false
    private var rvWorkSteps: RecyclerView? = null
    private val workStepAdapter = WorkStepAdapter()
    private val workSteps = mutableListOf<WorkStepCard>()

    private val voicePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startVoiceRecognition()
        } else {
            Toast.makeText(requireContext(), "Microphone permission is required for voice input.", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            takePhotoLauncher.launch(null)
        } else {
            Toast.makeText(requireContext(), "Camera permission is required to snap photos.", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        if (bitmap == null || _binding == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tempFile = File(requireContext().cacheDir, "camera_snap_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) {
                    FileOutputStream(tempFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                }
                val uri = Uri.fromFile(tempFile)
                val picked = DocumentAnalysisClient.SelectedDocument(uri, tempFile.name, "image/jpeg", tempFile.absolutePath)
                selectedDocument = DocumentAnalysisClient.persistForChat(requireContext(), picked)
                if (_binding != null) {
                    binding.etMessageInput.hint = "Ask about this photo…"
                    updateAttachmentPreview()
                    Toast.makeText(requireContext(), "Camera photo captured & attached for analysis!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Could not save photo: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null || _binding == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val picked = DocumentAnalysisClient.describe(requireContext(), uri)
                selectedDocument = DocumentAnalysisClient.persistForChat(requireContext(), picked)
                if (_binding != null) {
                    binding.etMessageInput.hint = "Ask about ${selectedDocument?.name}…"
                    updateAttachmentPreview()
                    Toast.makeText(requireContext(), "Image attached: ${selectedDocument?.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Could not load image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var pendingExportMarkdown: String = ""
    private val exportMdLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        val text = pendingExportMarkdown
        if (uri == null || text.isBlank()) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("Couldn't open target location.")
                }
                Toast.makeText(requireContext(), "Saved conversation as Markdown (.md).", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val exportPdfLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val turns = chatAdapter.currentList
        if (turns.isEmpty()) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        ChatExporter.generatePdf(requireContext(), turns, out)
                    } ?: error("Couldn't open PDF destination.")
                }
                Toast.makeText(requireContext(), "Saved conversation as PDF (.pdf).", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "PDF export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || _binding == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val picked = DocumentAnalysisClient.describe(requireContext(), uri)
                Toast.makeText(requireContext(), "Saving ${picked.name} to this chat…", Toast.LENGTH_SHORT).show()
                selectedDocument = DocumentAnalysisClient.persistForChat(requireContext(), picked)
                if (_binding != null) {
                    binding.etMessageInput.hint = "Ask about ${selectedDocument?.name}"
                    updateAttachmentPreview()
                    Toast.makeText(requireContext(), "Attached and saved: ${selectedDocument?.name}. Type a question or tap Send.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                selectedDocument = null
                if (_binding != null) Toast.makeText(requireContext(), "Couldn't save this attachment locally. Please check device storage and try again.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        val text = pendingExportText
        if (uri == null || text.isBlank()) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("Couldn't open the selected save location.")
                }
                Toast.makeText(requireContext(), "Response saved as a text file.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(requireContext(), error.message ?: "Couldn't save response.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val chatAdapter = JarvisChatAdapter(
        onEditUserMessage = { text ->
            binding.etMessageInput.setText(text)
            binding.etMessageInput.setSelection(text.length)
            binding.etMessageInput.requestFocus()
        },
        onSaveAssistantMessage = { text ->
            pendingExportText = text
            exportLauncher.launch("Jarvis-Edited-File.txt")
        },
        onOpenAttachment = { path, name, mime ->
            startActivity(Intent(requireContext(), ChatAttachmentPreviewActivity::class.java).apply {
                putExtra(ChatAttachmentPreviewActivity.EXTRA_PATH, path)
                putExtra(ChatAttachmentPreviewActivity.EXTRA_NAME, name)
                putExtra(ChatAttachmentPreviewActivity.EXTRA_MIME, mime)
            })
        }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)

        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        (binding.rvChatMessages.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.rvChatMessages.adapter = chatAdapter
        setupWorkStepsRecyclerView(view)

        selectedChatMode = chatModePrefs.getString("chat_mode", "auto") ?: "auto"
        refreshChatModeLabel()
        updateAutoApproveHeaderStatus()
        binding.btnSendMessage.onSafeClick { sendMessage() }
        binding.etMessageInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event?.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enterPressed) { sendMessage(); true } else false
        }
        binding.chipSuggestionExplain.onSafeClick { setPrompt("Explain a difficult topic to me in simple language, step by step, with examples.") }
        binding.chipSuggestionWrite.onSafeClick { setPrompt("Help me write something. Ask me what I want to create.") }
        binding.chipSuggestionAnalyze.onSafeClick { documentPicker.launch(arrayOf("*/*")) }
        binding.btnRemoveAttachment.onSafeClick {
            selectedDocument?.savedPath?.let { path -> runCatching { java.io.File(path).delete() } }
            selectedDocument = null
            updateAttachmentPreview()
            binding.etMessageInput.hint = "Message JARVIS…"
        }
        binding.btnStopChat.onSafeClick {
            offlineGenerationJob?.cancel()
            offlineGenerationJob = null
            fileAnalysisJob?.cancel()
            fileAnalysisJob = null
            providerEngine.cancel()
            binding.btnStopChat.visibility = View.GONE
            binding.tvChatStatus.text = "● READY"
            binding.btnSendMessage.isEnabled = true
            com.jarvis.assistant.background.JarvisConversationService.stopCurrentResponseImmediately()
            Toast.makeText(requireContext(), "JARVIS response stopped.", Toast.LENGTH_SHORT).show()
        }

        binding.btnChatNew.onSafeClick {
            AlertDialog.Builder(requireContext())
                .setTitle("New Conversation")
                .setMessage("Start a new conversation and clear current history?")
                .setPositiveButton("New Chat") { _, _ ->
                    if (_binding == null) return@setPositiveButton
                    offlineGenerationJob?.cancel()
                    fileAnalysisJob?.cancel()
                    providerEngine.cancel()
                    retrySnackbar?.dismiss()
                    JarvisConversationService.stopCurrentResponseImmediately()
                    selectedDocument?.savedPath?.let { path -> runCatching { java.io.File(path).delete() } }
                    JarvisApp.instance.chatRepository.clearHistory()
                    selectedDocument = null
                    totalUsedTokens = 0
                    clearWorkSteps()
                    binding.tvChatTokens.text = "⚡ 0 TOKENS"
                    updateAttachmentPreview()
                    binding.etMessageInput.hint = "Message JARVIS…"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnChatMenu.onSafeClick {
            (activity as? MainActivity)?.openNavigationDrawer()
        }

        binding.tvChatTokens.onSafeClick {
            TokenSettingsBottomSheet.show(requireContext(), totalUsedTokens) { _ ->
                updateTokenUsage(chatAdapter.currentList)
                updateAutoApproveHeaderStatus()
            }
        }

        binding.tvHardwareTelemetry.onSafeClick {
            com.jarvis.assistant.ui.telemetry.HardwareTelemetryBottomSheet.show(parentFragmentManager)
        }

        autoVoiceReplyEnabled = requireContext().getSharedPreferences("jarvis_voice_prefs", Context.MODE_PRIVATE)
            .getBoolean("key_auto_voice_reply", false)

        val themeAccent = try { ThemeManager.getThemeAccentColor() } catch (_: Exception) { Color.parseColor("#00E5FF") }
        val themeWarning = try { ThemeManager.getThemeWarningColor() } catch (_: Exception) { Color.parseColor("#FFD700") }

        fun updateSpeakerButtonVisual(isSpeaking: Boolean = JarvisVoiceController.isSpeaking()) {
            if (_binding == null) return
            val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Exception) { Color.parseColor("#00E5FF") }
            val warning = try { ThemeManager.getThemeWarningColor() } catch (_: Exception) { Color.parseColor("#FFD700") }
            if (isSpeaking) {
                binding.btnVoiceSpeaker.setImageResource(R.drawable.ic_cc_stop)
                binding.btnVoiceSpeaker.setColorFilter(warning)
                binding.btnVoiceSpeaker.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_work_progress_badge)
            } else if (autoVoiceReplyEnabled) {
                binding.btnVoiceSpeaker.setImageResource(R.drawable.ic_cc_volume)
                binding.btnVoiceSpeaker.setColorFilter(accent)
                binding.btnVoiceSpeaker.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_chat_token_badge)
            } else {
                binding.btnVoiceSpeaker.setImageResource(R.drawable.ic_cc_volume)
                binding.btnVoiceSpeaker.setColorFilter(Color.parseColor("#78909C"))
                binding.btnVoiceSpeaker.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_chat_token_badge)
            }
        }
        updateSpeakerButtonVisual()

        JarvisVoiceController.prewarm(requireContext())
        JarvisVoiceController.addSpeakingStateListener { speaking ->
            activity?.runOnUiThread { updateSpeakerButtonVisual(speaking) }
        }

        binding.btnVoiceSpeaker.onSafeClick {
            if (JarvisVoiceController.isSpeaking()) {
                JarvisVoiceController.stopSpeaking()
                updateSpeakerButtonVisual(false)
                Toast.makeText(requireContext(), "Voice reading stopped", Toast.LENGTH_SHORT).show()
            } else {
                autoVoiceReplyEnabled = !autoVoiceReplyEnabled
                requireContext().getSharedPreferences("jarvis_voice_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("key_auto_voice_reply", autoVoiceReplyEnabled).apply()
                updateSpeakerButtonVisual(false)
                val msg = if (autoVoiceReplyEnabled) "⚡ Auto Voice Reply: Enabled (Jarvis speaks answers)" else "Auto Voice Reply: Muted"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCamera.onSafeClick {
            val cameraPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            if (cameraPermission == PackageManager.PERMISSION_GRANTED) {
                takePhotoLauncher.launch(null)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnAttach.onSafeClick {
            val options = arrayOf("📷 Take Camera Photo", "🖼️ Pick Screenshot or Image", "📁 Browse All Files & Code")
            AlertDialog.Builder(requireContext())
                .setTitle("Attach to JARVIS Chat")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val cameraPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                            if (cameraPermission == PackageManager.PERMISSION_GRANTED) {
                                takePhotoLauncher.launch(null)
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                        1 -> imagePickerLauncher.launch("image/*")
                        2 -> documentPicker.launch(arrayOf("*/*"))
                    }
                }
                .show()
        }

        binding.btnVoiceInput.onSafeClick {
            if (isVoiceListening) {
                stopVoiceRecognitionAndSend()
            } else {
                checkVoicePermissionAndStart()
            }
        }
        binding.btnVoiceStopAndSend.onSafeClick {
            stopVoiceRecognitionAndSend()
        }
        binding.btnVoiceCancel.onSafeClick {
            cancelVoiceRecognition()
        }
        binding.btnChatTools.onSafeClick {
            ChatboxTools.show(
                context = requireContext(),
                input = binding.etMessageInput,
                turns = chatAdapter.currentList,
                onExportFormat = { format, content ->
                    when (format) {
                        ChatboxTools.ExportFormat.MARKDOWN -> {
                            pendingExportMarkdown = content
                            exportMdLauncher.launch("Jarvis-Conversation.md")
                        }
                        ChatboxTools.ExportFormat.PDF -> {
                            exportPdfLauncher.launch("Jarvis-Conversation.pdf")
                        }
                        ChatboxTools.ExportFormat.TXT -> {
                            pendingExportText = content
                            exportLauncher.launch("Jarvis-Conversation.txt")
                        }
                    }
                },
                onExport = { text ->
                    pendingExportText = text
                    exportLauncher.launch("Jarvis-Conversation.txt")
                }
            )
        }

        observeChatTurns()
        observeProviderEngine()
    }

    private fun showChatModePicker() {
        val active = providerManager.activeProfile()
        val labels = arrayOf(
            if (active != null) "Auto · ${active.displayName} (custom provider)" else "Auto · choose available brain",
            "Gemini Live · online",
            "Offline Brain · on-device"
        )
        val values = arrayOf("auto", "gemini", "offline")
        val selected = values.indexOf(selectedChatMode).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("Choose chat engine")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                if (_binding == null || !isAdded) { dialog.dismiss(); return@setSingleChoiceItems }
                selectedChatMode = values[which]
                chatModePrefs.edit().putString("chat_mode", selectedChatMode).apply()
                refreshChatModeLabel()
                dialog.dismiss()
                if (selectedChatMode == "offline" &&
                    !OfflineModelDownloader.isModelValid(OfflineModelDownloader.modelFile(requireContext()))
                ) {
                    Toast.makeText(requireContext(), "Download the Offline Brain model once to use this mode.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(requireContext(), OfflineModelActivity::class.java))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // The visible Auto/Jarvis selector chips were removed from the composer.
    // Keep the existing routing preference and provider status logic intact.
    private fun refreshChatModeLabel() {
        refreshProviderLabel()
    }

    /** Shows which provider and model will answer, so the user is never unsure which brain is in use. */
    private fun refreshProviderLabel() {
        if (_binding == null) return
        val view = binding.tvProviderModel
        val active = providerManager.activeProfile()
        if (active == null) {
            view.visibility = View.GONE
            return
        }
        val model = active.chatModel?.id ?: "no model selected"
        view.text = if (selectedChatMode != "offline") "Provider: ${active.displayName} · $model"
        else "Offline Brain selected · custom provider ${active.displayName} is not used"
        view.visibility = View.VISIBLE
    }

    private fun customProviderActive(): Boolean {
        if (selectedChatMode == "offline" || providerManager.activeProfile() == null) return false
        // The user may have routed normal chat to Gemini explicitly; then the built-in Gemini path answers.
        val route = ProviderRuntime.routing(requireContext()).get(com.jarvis.assistant.provider.WorkCategory.CHAT)
        return route?.isGemini != true
    }

    private fun sendViaProvider(text: String) {
        val document = selectedDocument
        if (document == null && text.isBlank()) return
        if (providerEngine.isBusy) {
            Toast.makeText(requireContext(), "JARVIS is still answering. Stop it first or wait for it to finish.", Toast.LENGTH_SHORT).show()
            return
        }
        clearWorkSteps()
        binding.btnSendMessage.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            var display = text
            var sendText = text
            var attachments = emptyList<com.jarvis.assistant.provider.ChatAttachment>()
            var ref: AttachmentRef? = null
            if (document != null) {
                val prompt = text.ifBlank { "Analyze this file and explain what it contains. Highlight key points and any issues." }
                val payload = try {
                    DocumentAnalysisClient.prepareForProvider(requireContext(), document)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (_binding != null) {
                        binding.btnSendMessage.isEnabled = true
                        Toast.makeText(requireContext(), e.message ?: "Couldn't read this file.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                if (payload.unsupportedReason != null) {
                    if (_binding != null) {
                        binding.btnSendMessage.isEnabled = true
                        Toast.makeText(requireContext(), payload.unsupportedReason, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                display = "📎 ${document.name}\n$prompt"
                sendText = buildString {
                    append("File name: ").append(document.name).append('\n').append(prompt)
                    if (payload.inlineText.isNotEmpty()) append("\n\n").append(payload.inlineText)
                }
                attachments = payload.attachments
                ref = document.savedPath?.let { AttachmentRef(it, document.name, document.mimeType) }
            }
            if (_binding == null) return@launch
            retrySnackbar?.dismiss()
            when (val outcome = providerEngine.send(display, sendText, attachments, ref)) {
                is SendOutcome.Started -> {
                    binding.etMessageInput.text?.clear()
                    if (document != null) {
                        selectedDocument = null
                        updateAttachmentPreview()
                        binding.etMessageInput.hint = "Message JARVIS…"
                    }
                }
                SendOutcome.NotActive -> {
                    binding.btnSendMessage.isEnabled = true
                    refreshProviderLabel()
                    Toast.makeText(requireContext(), "No custom provider is active any more.", Toast.LENGTH_LONG).show()
                }
                is SendOutcome.Blocked -> {
                    binding.btnSendMessage.isEnabled = true
                    showBlocked(outcome.reason)
                }
                SendOutcome.Busy -> binding.btnSendMessage.isEnabled = false
            }
        }
    }

    private fun showBlocked(reason: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("This provider can't answer")
            .setMessage(reason + "\n\nJARVIS will not switch to another provider on its own. Change the model in API Key & Model Settings, or pick another chat engine.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Open settings") { _, _ ->
                startActivity(Intent(requireContext(), com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java))
            }
            .show()
    }

    private fun observeProviderEngine() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    providerManager.state.collect { refreshProviderLabel() }
                }
                launch {
                    // "Switched to API key X because Y hit its limit" - the conversation continues on the next key.
                    providerEngine.keyNotices.collect { notice ->
                        if (_binding != null) com.google.android.material.snackbar.Snackbar.make(binding.root, notice, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                    }
                }
                launch {
                    com.jarvis.assistant.background.WebBuildOrchestrator.status.collect { st ->
                        if (_binding == null) return@collect
                        if (st.phase == com.jarvis.assistant.background.WebBuildOrchestrator.Phase.IDLE) return@collect
                        val eta = if (st.etaSec > 0) " · ~" + (if (st.etaSec < 60) "${st.etaSec}s" else "${(st.etaSec + 30) / 60}m") else ""
                        val file = if (st.currentFile.isNotBlank()) " · ${st.currentFile}" else ""
                        binding.tvChatStatus.text = "● ${st.stage.uppercase()} ${st.percent}%$file$eta"
                        binding.layoutWorkProgress.visibility = View.VISIBLE
                        binding.pbWorkProgress.progress = st.percent
                        binding.tvWorkProgressPercent.text = "${st.percent}%"
                    }
                }
                launch {
                    providerEngine.workProgress.collect { progress ->
                        if (_binding == null) return@collect
                        handleWorkProgress(progress)
                        if (progress.isWorking) {
                            binding.layoutWorkProgress.visibility = View.VISIBLE
                            binding.pbWorkProgress.progress = progress.percent
                            binding.tvWorkProgressPercent.text = "${progress.percent}%"
                            val tAccent = try { ThemeManager.getThemeAccentColor() } catch (_: Exception) { Color.parseColor("#00E5FF") }
                            val tSecondary = try { ThemeManager.getThemeSecondaryColor() } catch (_: Exception) { Color.parseColor("#7C4DFF") }
                            val tSuccess = try { ThemeManager.getThemeSuccessColor() } catch (_: Exception) { Color.parseColor("#00E676") }
                            val color = when {
                                progress.percent <= 35 -> tAccent
                                progress.percent <= 75 -> tSecondary
                                else -> tSuccess
                            }
                            binding.pbWorkProgress.progressTintList = ColorStateList.valueOf(color)
                            binding.tvWorkProgressPercent.setTextColor(color)
                        } else if (progress.percent == 100) {
                            binding.layoutWorkProgress.visibility = View.VISIBLE
                            binding.pbWorkProgress.progress = 100
                            binding.tvWorkProgressPercent.text = "100%"
                            val gold = try { ThemeManager.getThemeSuccessColor() } catch (_: Exception) { Color.parseColor("#00E676") }
                            binding.pbWorkProgress.progressTintList = android.content.res.ColorStateList.valueOf(gold)
                            binding.tvWorkProgressPercent.setTextColor(gold)
                            launch {
                                kotlinx.coroutines.delay(2500)
                                if (_binding != null && !providerEngine.workProgress.value.isWorking) {
                                    binding.layoutWorkProgress.visibility = View.GONE
                                }
                            }
                        } else {
                            binding.layoutWorkProgress.visibility = View.GONE
                        }
                    }
                }
                providerEngine.state.collect { state ->
                    if (_binding == null) return@collect
                    when (state) {
                        is EngineState.Streaming -> {
                            binding.btnStopChat.visibility = View.VISIBLE
                            binding.btnSendMessage.isEnabled = false
                            binding.tvChatStatus.text = "● ANSWERING"
                        }
                        is EngineState.Failed -> {
                            binding.btnStopChat.visibility = View.GONE
                            binding.btnSendMessage.isEnabled = true
                            binding.tvChatStatus.text = "● ERROR"
                            retrySnackbar = com.google.android.material.snackbar.Snackbar
                                .make(binding.root, state.error.message, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                                .setAction("Retry") {
                                    when (val o = providerEngine.retry(state.turnId)) {
                                        is SendOutcome.Blocked -> showBlocked(o.reason)
                                        SendOutcome.NotActive -> Toast.makeText(requireContext(), "No custom provider is active.", Toast.LENGTH_LONG).show()
                                        else -> Unit
                                    }
                                }
                            retrySnackbar?.show()
                        }
                        EngineState.Idle -> if (offlineGenerationJob == null && fileAnalysisJob == null) {
                            binding.btnStopChat.visibility = View.GONE
                            binding.btnSendMessage.isEnabled = true
                            binding.tvChatStatus.text = "● READY"
                        }
                    }
                }
            }
        }
    }

    private fun sendMessage() {
        clearWorkSteps()
        val text = binding.etMessageInput.text?.toString()?.trim().orEmpty()
        if (customProviderActive()) {
            sendViaProvider(text)
            return
        }
        val document = selectedDocument
        if (document != null) {
            val prompt = text.ifBlank { "Analyze this file and explain what it contains. Highlight key points and any issues." }
            binding.etMessageInput.text?.clear()
            binding.btnSendMessage.isEnabled = false
            binding.btnStopChat.visibility = View.VISIBLE
            binding.tvChatStatus.text = "● ANALYZING"
            Toast.makeText(requireContext(), "JARVIS is analyzing ${document.name}…", Toast.LENGTH_LONG).show()
            fileAnalysisJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val key = (requireActivity().application as JarvisApp).preferences.apiKey
                    val answer = DocumentAnalysisClient.analyze(requireContext(), document, prompt, key)
                    JarvisApp.instance.chatRepository.addTurn("📎 ${document.name}\n$prompt", answer, document.savedPath, document.name, document.mimeType)
                    selectedDocument = null
                    if (_binding != null) { updateAttachmentPreview(); binding.etMessageInput.hint = "Message JARVIS…" }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = "I couldn't analyze ${document.name}. ${error.message ?: "Please check your Gemini API key, connection, and file format."} Your attachment remains selected so you can retry or choose another file."
                    JarvisApp.instance.chatRepository.addTurn("📎 ${document.name}\n$prompt", message, document.savedPath, document.name, document.mimeType)
                } finally {
                    fileAnalysisJob = null
                    if (_binding != null) {
                        binding.btnSendMessage.isEnabled = true
                        binding.btnStopChat.visibility = View.GONE
                        binding.tvChatStatus.text = "● READY"
                    }
                }
            }
            return
        }

        if (text.isBlank()) return
        val appContext = requireContext().applicationContext
        val engineStore = com.jarvis.assistant.security.AiEngineStore(appContext)
        val selectedProviderHasKey = if (engineStore.useGroq) engineStore.hasGroqKey() else JarvisApp.instance.preferences.apiKey.isNotBlank()
        val forceOffline = selectedChatMode == "offline"
        val forceGemini = selectedChatMode == "gemini"
        if (forceGemini && JarvisApp.instance.preferences.apiKey.isBlank()) {
            Toast.makeText(requireContext(), "Add your Gemini API key in Settings before choosing Gemini mode.", Toast.LENGTH_LONG).show()
            return
        }
        if (forceOffline || (!forceGemini && (!selectedProviderHasKey || !OfflineModelDownloader.isInternetAvailable(appContext)))) {
            val modelFile = OfflineModelDownloader.modelFile(appContext)
            if (!OfflineModelDownloader.isModelValid(modelFile)) {
                Toast.makeText(requireContext(), "Offline Brain needs its model downloaded once. Open Offline JARVIS to download it; no API key is required.", Toast.LENGTH_LONG).show()
                startActivity(Intent(requireContext(), OfflineModelActivity::class.java))
                return
            }
            binding.etMessageInput.text?.clear()
            binding.btnSendMessage.isEnabled = false
            binding.btnStopChat.visibility = View.VISIBLE
            binding.tvChatStatus.text = "● THINKING"
            offlineGenerationJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val answer = OfflineLlm.generate(appContext, text)
                    JarvisApp.instance.chatRepository.addTurn(text, answer)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    JarvisApp.instance.chatRepository.addTurn(text, "Offline JARVIS could not start the local model. Open Offline JARVIS in the navigation menu and check model status. (" + (error.message ?: "unknown error") + ")")
                } finally {
                    offlineGenerationJob = null
                    if (_binding != null) {
                        binding.btnSendMessage.isEnabled = true
                        binding.btnStopChat.visibility = View.GONE
                        binding.tvChatStatus.text = "● READY"
                    }
                }
            }
            return
        }
        // Typing never opens the microphone: start a text-only session; voice needs the mic button or the activation phrase.
        if (!JarvisConversationService.isServiceRunning()) JarvisConversationService.startTextChatSession(requireContext().applicationContext)
        JarvisConversationService.sendTextMessage(text)
        binding.etMessageInput.text?.clear()
        binding.btnSendMessage.isEnabled = false
        binding.btnStopChat.visibility = View.VISIBLE
        binding.tvChatStatus.text = "● THINKING"
    }
    private fun setPrompt(prompt: String) {
        binding.etMessageInput.setText(prompt)
        binding.etMessageInput.setSelection(prompt.length)
        binding.etMessageInput.requestFocus()
        val keyboard = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.showSoftInput(binding.etMessageInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun updateAttachmentPreview() {
        val document = selectedDocument
        binding.layoutAttachmentPreview.visibility = if (document == null) View.GONE else View.VISIBLE
        binding.tvAttachmentName.text = document?.name.orEmpty()
    }

    private fun observeChatTurns() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chatTurns.collect { turns ->
                    if (_binding != null) {
                        updateTokenUsage(turns)
                        binding.layoutChatWelcome.visibility = if (turns.isEmpty()) View.VISIBLE else View.GONE
                        val pending = turns.lastOrNull()?.jarvisResponse.isNullOrBlank() || providerEngine.isBusy
                        if (!pending && offlineGenerationJob == null && fileAnalysisJob == null && selectedDocument == null) {
                            binding.btnStopChat.visibility = View.GONE
                            binding.btnSendMessage.isEnabled = true
                            binding.tvChatStatus.text = "● READY"

                            val latestTurn = turns.lastOrNull()
                            if (latestTurn != null && latestTurn.id != lastAutoSpokenTurnId && latestTurn.jarvisResponse.isNotBlank()) {
                                if (lastMessageOriginatedFromVoice || autoVoiceReplyEnabled) {
                                    lastAutoSpokenTurnId = latestTurn.id
                                    lastMessageOriginatedFromVoice = false
                                    JarvisVoiceController.speakTurnResponse(
                                        requireContext(),
                                        latestTurn.id,
                                        latestTurn.userTranscript,
                                        latestTurn.jarvisResponse
                                    )
                                }
                            }
                        } else if (pending) {
                            binding.btnStopChat.visibility = View.VISIBLE
                            binding.btnSendMessage.isEnabled = false
                            binding.tvChatStatus.text = "● THINKING"
                        }
                    }
                    chatAdapter.submitList(turns) {
                        if (turns.isNotEmpty() && _binding != null) {
                            val lm = binding.rvChatMessages.layoutManager as? LinearLayoutManager
                            if (lm != null) {
                                val lastVisible = lm.findLastVisibleItemPosition()
                                if (lastVisible >= turns.size - 3 || lastVisible < 0) {
                                    binding.rvChatMessages.scrollToPosition(turns.size - 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)
            val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Exception) { Color.parseColor("#00E5FF") }
            binding.tvChatTokens.setTextColor(accent)
            binding.btnSendMessage.imageTintList = ColorStateList.valueOf(accent)
            refreshProviderLabel()
            updateAutoApproveHeaderStatus()
        }
    }

    private fun updateAutoApproveHeaderStatus() {
        if (_binding == null) return
        val context = context ?: return
        val auto = com.jarvis.assistant.tools.PermissionPreferences.isAutoApproveAll(context)
        val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Exception) { Color.parseColor("#80D8FF") }
        if (auto) {
            binding.tvChatSubtitle.text = "Autonomous Workspace · ⚡ Auto-Approve Active"
            binding.tvChatSubtitle.setTextColor(accent)
        } else {
            binding.tvChatSubtitle.text = "Your intelligent workspace · Ready"
            binding.tvChatSubtitle.setTextColor(android.graphics.Color.parseColor("#DDEBFF"))
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    private fun estimateTokens(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val chars = text.length
        val words = text.trim().split(Regex("\\s+")).size
        return maxOf((words * 1.33f).toInt(), (chars / 3.8f).toInt()).coerceAtLeast(1)
    }

    private fun updateTokenUsage(turns: List<com.jarvis.assistant.data.model.ChatTurn>) {
        if (_binding == null) return
        var turnsTokens = 0
        for (turn in turns) {
            turnsTokens += estimateTokens(turn.userTranscript)
            turnsTokens += estimateTokens(turn.jarvisResponse)
        }
        val prefs = try { (activity?.application as? JarvisApp)?.preferences } catch (_: Exception) { null }
        val cumulative = prefs?.cumulativeChatTokens ?: 0
        val totalTokens = maxOf(cumulative, turnsTokens)
        if (prefs != null && totalTokens > cumulative) {
            prefs.cumulativeChatTokens = totalTokens
        }
        totalUsedTokens = totalTokens
        val display = if (totalTokens < 1000) {
            "⚡ $totalTokens TOKENS"
        } else {
            "⚡ ${"%.1f".format(totalTokens / 1000.0)}K TOKENS"
        }
        binding.tvChatTokens.text = display
        val themeAccent = try { ThemeManager.getThemeAccentColor() } catch (_: Exception) { Color.parseColor("#00E5FF") }
        binding.tvChatTokens.setTextColor(themeAccent)
    }

    private fun checkVoicePermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startVoiceRecognition()
        } else {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceRecognition() {
        if (_binding == null) return
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(requireContext(), "Speech recognition is not available on this device.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (_binding == null) return
                    binding.tvVoiceTranscript.text = "Listening… speak now"
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) {
                    if (_binding == null) return
                    binding.voiceWaveView.updateRms(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (_binding == null) return
                    binding.tvVoiceTranscript.text = "Processing speech…"
                }

                override fun onError(error: Int) {
                    if (_binding == null) return
                    hideVoiceListening()
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                            SpeechRecognizer.ERROR_NETWORK -> "Network issue with voice recognition."
                            else -> "Could not recognize speech ($error)."
                        }
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (_binding == null) return
                    hideVoiceListening()
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        lastMessageOriginatedFromVoice = true
                        binding.etMessageInput.setText(text)
                        sendMessage()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (_binding == null) return
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    if (!partial.isNullOrBlank()) {
                        binding.tvVoiceTranscript.text = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, requireContext().packageName)
            }

            recognizer.startListening(intent)
            speechRecognizer = recognizer
            isVoiceListening = true
            binding.layoutVoiceListening.visibility = View.VISIBLE
            binding.voiceWaveView.start()
            binding.tvVoiceTranscript.text = "Listening… speak now"
            binding.btnVoiceInput.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_cc_send)
            binding.btnVoiceInput.setColorFilter(android.graphics.Color.WHITE)
        } catch (e: Exception) {
            hideVoiceListening()
            Toast.makeText(requireContext(), "Could not start voice: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVoiceRecognitionAndSend() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
            hideVoiceListening()
        }
    }

    private fun cancelVoiceRecognition() {
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        hideVoiceListening()
    }

    private fun hideVoiceListening() {
        isVoiceListening = false
        if (_binding != null) {
            binding.layoutVoiceListening.visibility = View.GONE
            binding.voiceWaveView.stop()
            binding.btnVoiceInput.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_chat_attach_button)
            binding.btnVoiceInput.clearColorFilter()
        }
    }

    private fun setupWorkStepsRecyclerView(rootView: View) {
        val targetRv = binding.rvWorkSteps
        targetRv.layoutManager = LinearLayoutManager(requireContext())
        targetRv.adapter = workStepAdapter
        rvWorkSteps = targetRv
    }

    private fun handleWorkProgress(progress: com.jarvis.assistant.provider.LiveWorkProgress) {
        if (_binding == null) return
        // The floating work-step bubbles are intentionally gone: Jarvis reports real progress by voice, the
        // notification and the header status instead.
        if (workSteps.isNotEmpty()) clearWorkSteps()
        rvWorkSteps?.visibility = View.GONE
    }

    private fun markAllWorkStepsDone() {
        var changed = false
        for (i in workSteps.indices) {
            if (workSteps[i].status == WorkStepStatus.IN_PROGRESS) {
                workSteps[i] = workSteps[i].copy(status = WorkStepStatus.DONE)
                changed = true
            }
        }
        if (changed) {
            workStepAdapter.submitList(workSteps.toList())
        }
    }

    private fun clearWorkSteps() {
        workSteps.clear()
        workStepAdapter.submitList(emptyList())
        rvWorkSteps?.visibility = View.GONE
    }

    override fun onDestroyView() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isVoiceListening = false
        retrySnackbar?.dismiss()
        JarvisVoiceController.stopSpeaking()
        chatAdapter.release()
        rvWorkSteps = null
        super.onDestroyView()
        _binding = null
    }
}
