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
import com.jarvis.assistant.ui.glass.GlassChips
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.sample
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
import com.jarvis.assistant.provider.ChatTokenLedger
import com.jarvis.assistant.provider.TokenEstimator
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
        },
        onEditImage = { path -> attachPictureForEdit(path) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    /** Shows the public links (port forwarding) above the composer, live, with Copy, Share and Stop. */
    private fun bindPortForwardStrip() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // Building the tool layer touches storage, so it is done off the main thread.
            val manager = withContext(Dispatchers.Default) {
                runCatching { com.jarvis.assistant.tools.ToolRuntime.get(appContext).tunnels }.getOrNull()
            } ?: return@launch
            if (_binding == null) return@launch
            binding.portForwardStrip.onStop = { id -> manager.stopById(id) }
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.tunnels.collect { list -> _binding?.portForwardStrip?.render(list) }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)
        bindPortForwardStrip()

        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.itemAnimator = ChatItemAnimator()
        binding.rvChatMessages.adapter = chatAdapter
        com.jarvis.assistant.ui.chat.liquid.LiquidMotion.headerIn(binding.layoutChatHeader)
        setupInlineActivity()

        // "offline" was a saved choice in older versions; the offline brain no longer exists, so it means Auto now.
        selectedChatMode = chatModePrefs.getString("chat_mode", "auto")?.takeIf { it == "auto" || it == "gemini" } ?: "auto"
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
            binding.etMessageInput.hint = workModeHint()
        }
        // The composer is a LiquidGlassLayout (live blur + prism refraction); controls on it get light glass chips.
        // (the composer icons get their own 3D glass pods in setupComposerPods() below)
        setupWorkModes()
        setupHeaderGlass()
        setupComposerPods()
        applyPlainLookIfGlassOff()
        binding.btnStopChat.onSafeClick {
            com.jarvis.assistant.background.ChatboxHandoffTracker.noteUserStop()
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
                    fileAnalysisJob?.cancel()
                    providerEngine.cancel()
                    retrySnackbar?.dismiss()
                    JarvisConversationService.stopCurrentResponseImmediately()
                    selectedDocument?.savedPath?.let { path -> runCatching { java.io.File(path).delete() } }
                    JarvisApp.instance.chatRepository.clearHistory()
                    ChatTokenLedger.reset()
                    selectedDocument = null
                    totalUsedTokens = 0
                    binding.tvChatTokens.text = "⚡ 0 TOKENS"
                    updateAttachmentPreview()
                    binding.etMessageInput.hint = workModeHint()
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
                if (com.jarvis.assistant.ui.glass.GlassMode.plain()) binding.btnVoiceSpeaker.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_work_progress_badge)
            } else if (autoVoiceReplyEnabled) {
                binding.btnVoiceSpeaker.setImageResource(R.drawable.ic_cc_volume)
                binding.btnVoiceSpeaker.setColorFilter(accent)
                if (com.jarvis.assistant.ui.glass.GlassMode.plain()) binding.btnVoiceSpeaker.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_chat_token_badge)
            } else {
                binding.btnVoiceSpeaker.setImageResource(R.drawable.ic_cc_volume)
                binding.btnVoiceSpeaker.setColorFilter(Color.parseColor("#78909C"))
                if (com.jarvis.assistant.ui.glass.GlassMode.plain()) binding.btnVoiceSpeaker.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_chat_token_badge)
            }
            // The speaker keeps its glass pod; it glows while Jarvis speaks or auto voice reply is on.
            com.jarvis.assistant.ui.glass.DockPods.setGlow(binding.btnVoiceSpeaker, isSpeaking || autoVoiceReplyEnabled)
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
        observeChatTurns()
        observeProviderEngine()
    }

    private fun showChatModePicker() {
        val active = providerManager.activeProfile()
        val labels = arrayOf(
            if (active != null) "Auto · ${active.displayName} (custom provider)" else "Auto · choose available brain",
            "Gemini Live · online"
        )
        val values = arrayOf("auto", "gemini")
        val selected = values.indexOf(selectedChatMode).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("Choose chat engine")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                if (_binding == null || !isAdded) { dialog.dismiss(); return@setSingleChoiceItems }
                selectedChatMode = values[which]
                chatModePrefs.edit().putString("chat_mode", selectedChatMode).apply()
                refreshChatModeLabel()
                dialog.dismiss()
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
        view.text = "Provider: ${active.displayName} · $model"
        view.visibility = View.VISIBLE
    }

    // ---- Ask / Debug / Code / Develop ---------------------------------------------------------------------------

    private var workModeNoticeShown = false

    private fun currentWorkMode(): com.jarvis.assistant.provider.ChatWorkMode =
        com.jarvis.assistant.provider.ChatWorkModeStore.get(requireContext())

    /** The hint the message box shows in the current mode (also used after an attachment is cleared). */
    private fun workModeHint(): String =
        if (selectedDocument != null) "Message JARVIS…" else if (_binding == null) "Message JARVIS…" else currentWorkMode().inputHint

    /** With Liquid Glass off, the header and composer return to their original simple look. */
    private fun applyPlainLookIfGlassOff() {
        if (!com.jarvis.assistant.ui.glass.GlassMode.plain()) return
        val white = Color.WHITE
        binding.cardInputContainer.background = Glass.panel(requireContext(), 30f)
        binding.btnChatMenu.imageTintList = android.content.res.ColorStateList.valueOf(white)
        binding.btnChatNew.imageTintList = android.content.res.ColorStateList.valueOf(white)
        binding.tvChatTitle.setTextColor(white)
        binding.tvChatSubtitle.setTextColor(Color.parseColor("#DDEBFF"))
        binding.tvChatTokens.setTextColor(Color.parseColor("#E1F5FE"))
        binding.tvChatStatus.setTextColor(Color.parseColor("#D9FFF7"))
    }

    /** Every composer icon is its own 3D glass bubble with the same spring press and in-pod liquid ripple as the dock. */
    private fun setupComposerPods() {
        val pods = com.jarvis.assistant.ui.glass.DockPods
        val g = "composer"
        pods.install(binding.btnCamera, heightDp = 40f, circle = true, group = g)
        pods.install(binding.btnAttach, heightDp = 40f, circle = true, group = g)
        pods.install(binding.btnVoiceInput, heightDp = 40f, circle = true, group = g)
        pods.install(binding.btnSendMessage, heightDp = 44f, circle = true, group = g)
        pods.install(binding.btnStopChat, heightDp = 40f, circle = true, group = g)
        pods.install(binding.btnRemoveAttachment, heightDp = 34f, circle = true, group = g)
        pods.install(binding.btnVoiceStopAndSend, heightDp = 32f, circle = true, group = g)
        pods.install(binding.btnVoiceCancel, heightDp = 32f, circle = true, group = g)
    }

    /** The top bar is a liquid glass bubble; every control in it is its own 3D glass pod with spring press and in-pod ripple. */
    private fun setupHeaderGlass() {
        val pods = com.jarvis.assistant.ui.glass.DockPods
        val g = "chatHeader"
        pods.install(binding.btnChatMenu, heightDp = 44f, circle = true, group = g)
        pods.install(binding.btnChatNew, heightDp = 44f, circle = true, group = g)
        pods.install(binding.btnVoiceSpeaker, heightDp = 32f, circle = true, group = g)
        pods.install(binding.tvChatTokens, heightDp = 30f, maxWidthDp = 400f, padDp = 0f, group = g)
        pods.install(binding.tvHardwareTelemetry, heightDp = 36f, maxWidthDp = 400f, padDp = 0f, group = g)
        pods.install(binding.tvChatStatus, heightDp = 30f, maxWidthDp = 400f, padDp = 0f, interactive = false, group = g)
        pods.install(binding.layoutWorkProgress, heightDp = 30f, maxWidthDp = 400f, padDp = 0f, interactive = false, group = g)
    }

    private fun setupWorkModes() {
        val start = currentWorkMode()
        binding.workModeSwitcher.setMode(start, animate = false)
        applyWorkMode(start, animate = false)
        binding.workModeSwitcher.onModeSelected = { mode ->
            com.jarvis.assistant.provider.ChatWorkModeStore.set(requireContext(), mode)
            applyWorkMode(mode, animate = true)
            if (mode != com.jarvis.assistant.provider.ChatWorkMode.DEVELOP && !workModeNoticeShown && !customProviderActive()) {
                workModeNoticeShown = true
                Toast.makeText(requireContext(), "Modes work with your custom model. Add one in API Key & Model Settings.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Smoothly moves the composer to a mode: the accent bar takes the mode's colour and the hint cross-fades. */
    private fun applyWorkMode(mode: com.jarvis.assistant.provider.ChatWorkMode, animate: Boolean) {
        val color = try {
            when (mode) {
                com.jarvis.assistant.provider.ChatWorkMode.ASK -> ThemeManager.getThemeSuccessColor()
                com.jarvis.assistant.provider.ChatWorkMode.DEBUG -> ThemeManager.getThemeWarningColor()
                com.jarvis.assistant.provider.ChatWorkMode.CODE -> ThemeManager.getThemeSecondaryColor()
                com.jarvis.assistant.provider.ChatWorkMode.DEVELOP -> ThemeManager.getThemeAccentColor()
            }
        } catch (_: Exception) { return }
        val bar = binding.composerAccentBar
        val input = binding.etMessageInput
        if (!animate) {
            bar.setBackgroundColor(color)
            input.hint = workModeHint()
            return
        }
        GlassMotion.sweep(binding.cardInputContainer, 30f)
        val from = (bar.background as? android.graphics.drawable.ColorDrawable)?.color ?: color
        android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, color).apply {
            duration = 380
            addUpdateListener { if (_binding != null) bar.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
        bar.animate().cancel()
        bar.alpha = 0.45f
        bar.animate().alpha(1f).setDuration(160).withEndAction { if (_binding != null) bar.animate().alpha(0.45f).setDuration(420).start() }.start()
        input.animate().cancel()
        input.animate().alpha(0f).setDuration(110).withEndAction {
            if (_binding == null) return@withEndAction
            input.hint = workModeHint()
            input.animate().alpha(1f).setDuration(220).start()
        }.start()
    }

    private fun customProviderActive(): Boolean {
        if (providerManager.activeProfile() == null) return false
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
            val outcome = try { providerEngine.send(display, sendText, attachments, ref) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                if (_binding != null) {
                    binding.btnSendMessage.isEnabled = true
                    Toast.makeText(requireContext(), "Couldn't send: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            when (outcome) {
                is SendOutcome.Started -> {
                    binding.etMessageInput.text?.clear()
                    if (document != null) {
                        selectedDocument = null
                        updateAttachmentPreview()
                        binding.etMessageInput.hint = workModeHint()
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
                launch { observeInlineActivity() }
                launch {
                    providerEngine.workProgress.collect { progress ->
                        if (_binding == null) return@collect
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
                        EngineState.Idle -> {
                            if (fileAnalysisJob == null) {
                                binding.btnStopChat.visibility = View.GONE
                                binding.btnSendMessage.isEnabled = true
                                binding.tvChatStatus.text = "● READY"
                            }
                        }
                    }
                }
            }
        }
    }

    /** Puts a picture Jarvis made into the message box as the attachment to change, and asks what to change. */
    private fun attachPictureForEdit(path: String) {
        val file = File(path)
        if (_binding == null || !file.isFile) return
        selectedDocument = DocumentAnalysisClient.SelectedDocument(Uri.fromFile(file), file.name, com.jarvis.assistant.tools.image.ImageGenProtocol.mimeFor(file.name), file.absolutePath)
        updateAttachmentPreview()
        binding.etMessageInput.hint = "Tell JARVIS what to change in this picture…"
        binding.etMessageInput.requestFocus()
        val keyboard = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.showSoftInput(binding.etMessageInput, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * A clear picture request ("draw a cat on the moon", or an attached photo plus "remove the background") is done
     * right away, in every chat mode and with every provider, and the finished picture appears in the chat by itself.
     * Returns true when the message was handled here.
     */
    private fun tryImageShortcut(text: String): Boolean {
        val document = selectedDocument
        val isImage = document != null && (document.mimeType.startsWith("image/") ||
            document.name.substringAfterLast('.', "").lowercase() in com.jarvis.assistant.tools.image.ImageSession.IMAGE_EXTENSIONS)
        val kind = com.jarvis.assistant.tools.image.ImageIntent.classify(text, isImage)
        if (kind == com.jarvis.assistant.tools.image.ImageIntent.Kind.NONE) return false
        val app = requireContext().applicationContext
        if (providerEngine.isBusy || fileAnalysisJob != null) {
            Toast.makeText(app, "JARVIS is still working. Stop it first or wait for it to finish.", Toast.LENGTH_SHORT).show()
            return true
        }
        if (JarvisApp.instance.preferences.apiKey.isBlank()) {
            Toast.makeText(app, "Add your Gemini (Google AI Studio) API key in Settings to create and edit pictures.", Toast.LENGTH_LONG).show()
            return true
        }
        val edit = kind == com.jarvis.assistant.tools.image.ImageIntent.Kind.EDIT
        val source = if (edit) document else null
        val chat = JarvisApp.instance.chatRepository
        val display = if (source != null) "📎 ${source.name}\n$text" else text
        binding.etMessageInput.text?.clear()
        binding.btnSendMessage.isEnabled = false
        binding.btnStopChat.visibility = View.VISIBLE
        binding.tvChatStatus.text = if (edit) "● EDITING PICTURE" else "● CREATING PICTURE"
        val turnId = chat.beginPending(display, source?.savedPath, source?.name, source?.mimeType)
        fileAnalysisJob = com.jarvis.assistant.tools.image.ImageRuntime.scope.launch {
            var finished = false
            try {
                val inputs = if (source != null) {
                    val encoded = withContext(Dispatchers.IO) { source.savedPath?.let { com.jarvis.assistant.tools.image.ImageTools.encode(File(it)) } }
                    if (encoded == null) {
                        chat.finishPending(turnId, "⚠ I couldn't read that picture. Please attach it again.")
                        finished = true
                        return@launch
                    }
                    listOf(encoded)
                } else emptyList()
                val prompt = if (source != null) com.jarvis.assistant.tools.image.ImageTools.editPrompt(text) else text
                when (val outcome = com.jarvis.assistant.tools.image.ImageRuntime.service(app).run(prompt, inputs, null, null)) {
                    is com.jarvis.assistant.tools.image.ImageOutcome.Done -> {
                        com.jarvis.assistant.tools.image.ImageSession.lastGenerated = outcome.files.first().absolutePath
                        val words = outcome.note.take(400).ifBlank { if (edit) "Here's your edited picture." else "Here's your picture." }
                        chat.finishPending(turnId, words + "\n\n" + outcome.files.joinToString("\n") { ChatImageRefs.line(it.absolutePath, text) })
                        finished = true
                        if (edit) selectedDocument = null
                    }
                    is com.jarvis.assistant.tools.image.ImageOutcome.Failed -> {
                        chat.finishPending(turnId, "⚠ ${outcome.message}")
                        finished = true
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) { chat.finishPending(turnId, "Stopped.") }
                finished = true
                throw e
            } catch (e: Exception) {
                chat.finishPending(turnId, "⚠ The picture could not be made: ${e.message ?: "unknown problem"}")
                finished = true
            } finally {
                if (!finished) chat.finishPending(turnId, "⚠ The picture could not be made.")
                fileAnalysisJob = null
                if (_binding != null) {
                    updateAttachmentPreview()
                    if (selectedDocument == null) binding.etMessageInput.hint = workModeHint()
                    binding.btnSendMessage.isEnabled = true
                    binding.btnStopChat.visibility = View.GONE
                    binding.tvChatStatus.text = "● READY"
                }
            }
        }
        return true
    }

    private fun sendMessage() {
        val text = binding.etMessageInput.text?.toString()?.trim().orEmpty()
        if (tryImageShortcut(text)) return
        if (text.isNotBlank() && com.jarvis.assistant.udn.UdnVoiceRouter.matches(requireContext().applicationContext, text)) {
            binding.etMessageInput.text?.clear()
            val appCtx = requireContext().applicationContext
            kotlinx.coroutines.GlobalScope.launch {
                val reply = try {
                    com.jarvis.assistant.udn.UdnVoiceRouter.handle(appCtx, text)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    "I couldn't complete that device command."
                }
                JarvisApp.instance.chatRepository.addTurn(text, reply)
            }
            return
        }
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
                    if (_binding != null) { updateAttachmentPreview(); binding.etMessageInput.hint = workModeHint() }
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
        val forceGemini = selectedChatMode == "gemini"
        if (forceGemini && JarvisApp.instance.preferences.apiKey.isBlank()) {
            Toast.makeText(requireContext(), "Add your Gemini API key in Settings before choosing Gemini mode.", Toast.LENGTH_LONG).show()
            return
        }
        // Typing never opens the microphone: start a text-only session; voice needs the mic button or the activation phrase.
        try {
            if (!JarvisConversationService.isServiceRunning()) JarvisConversationService.startTextChatSession(requireContext().applicationContext)
            JarvisConversationService.sendTextMessage(text)
        } catch (e: Exception) {
            // e.g. Android refusing to start the chat service: say so instead of closing the app.
            Toast.makeText(requireContext(), "Couldn't start the chat service: ${e.message ?: e.javaClass.simpleName}. Please try again.", Toast.LENGTH_LONG).show()
            return
        }
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
        // Keeps the badge moving while an answer (or a long tool run) is still going, not only when a turn is saved.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ChatTokenLedger.state.sample(150L).collect {
                    if (_binding != null) updateTokenUsage(chatAdapter.currentList)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // While an answer streams, rows change on every word. Handing the list over ~20 times a second (always the
                // latest state, never dropping the final one) keeps the chat smooth without changing what is shown.
                viewModel.chatTurns.sample(50L).collect { turns ->
                    if (_binding != null) {
                        updateTokenUsage(turns)
                        binding.layoutChatWelcome.visibility = if (turns.isEmpty()) View.VISIBLE else View.GONE
                        val pending = turns.lastOrNull()?.jarvisResponse.isNullOrBlank() || providerEngine.isBusy
                        if (!pending && fileAnalysisJob == null && selectedDocument == null) {
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
                    val workTurns = turns.filter { turn ->
                        turn.userTranscript != "(Audio query)" && turn.jarvisResponse != "(Audio response)"
                    }
                    chatAdapter.submitList(workTurns) {
                        if (workTurns.isNotEmpty() && _binding != null) {
                            val lm = binding.rvChatMessages.layoutManager as? LinearLayoutManager
                            if (lm != null) {
                                val lastVisible = lm.findLastVisibleItemPosition()
                                if (lastVisible >= workTurns.size - 3 || lastVisible < 0) {
                                    binding.rvChatMessages.scrollToPosition(workTurns.size - 1)
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
            val tv = android.util.TypedValue()
            val secondary = if (context.theme.resolveAttribute(R.attr.themeTextSecondary, tv, true) && tv.data != 0) tv.data else android.graphics.Color.GRAY
            binding.tvChatSubtitle.setTextColor(if (com.jarvis.assistant.ui.glass.GlassMode.plain()) Color.parseColor("#DDEBFF") else secondary)
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

    private fun updateTokenUsage(turns: List<com.jarvis.assistant.data.model.ChatTurn>) {
        if (_binding == null) return
        // The total is the sum of the provider's own usage numbers (input + output of every request in this chat),
        // so it matches the provider dashboard. See ChatTokenLedger.
        ChatTokenLedger.refresh()
        val snapshot = ChatTokenLedger.state.value
        var totalTokens = snapshot.total
        var approximate = snapshot.approximate
        if (totalTokens == 0) {
            // Nothing reported for this chat yet (for example turns answered by an engine that reports no usage).
            var estimated = 0
            for (turn in turns) {
                estimated += TokenEstimator.estimate(turn.userTranscript)
                estimated += TokenEstimator.estimate(turn.jarvisResponse)
            }
            if (estimated > 0) {
                totalTokens = estimated
                approximate = true
            }
        }
        totalUsedTokens = totalTokens
        val number = if (totalTokens < 10_000_000) {
            java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(totalTokens.toLong())
        } else {
            "${"%.1f".format(java.util.Locale.US, totalTokens / 1_000_000.0)}M"
        }
        val display = "⚡ ${if (approximate) "≈" else ""}$number TOKENS"
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

    private var wakePausedForMic = false
    private var lastPartialText = ""
    private var voiceBusyRetried = false
    private var micPulse: android.animation.ObjectAnimator? = null

    private fun startVoiceRecognition() {
        if (_binding == null) return
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(requireContext(), "Speech recognition is not available on this device.", Toast.LENGTH_SHORT).show()
            return
        }
        lastPartialText = ""
        voiceBusyRetried = false
        showVoiceListeningUi()
        // The background wake-word listener uses the same speech recognizer and only one can listen at a time, so it rests
        // while you dictate here and comes back when you are done.
        val app = requireContext().applicationContext
        if (com.jarvis.assistant.background.JarvisWakeWordService.isRunning()) {
            wakePausedForMic = true
            com.jarvis.assistant.background.JarvisWakeWordService.stopService(app)
            binding.root.postDelayed({ if (_binding != null && isVoiceListening) beginRecognizer() }, 450L)
        } else {
            beginRecognizer()
        }
    }

    /** The listening bar opens as a liquid glass bubble in the app colours, and the mic pulses while it listens. */
    private fun showVoiceListeningUi() {
        isVoiceListening = true
        val bar = binding.layoutVoiceListening
        val accent = activityAccent()
        if (com.jarvis.assistant.ui.glass.GlassMode.enabled()) {
            com.jarvis.assistant.ui.glass.LiquidGlass.apply(bar, 24f, accent)
        }
        binding.tvVoiceTranscript.setTextColor(
            android.util.TypedValue().also { requireContext().theme.resolveAttribute(R.attr.themeTextPrimary, it, true) }.data
        )
        binding.tvVoiceTranscript.text = "Listening… speak now"
        binding.voiceWaveView.refreshColors()
        bar.animate().cancel()
        bar.visibility = View.VISIBLE
        if (com.jarvis.assistant.ui.glass.GlassMode.enabled()) {
            val d = resources.displayMetrics.density
            bar.alpha = 0f; bar.scaleX = 0.92f; bar.scaleY = 0.92f; bar.translationY = 18f * d
            bar.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(420L)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.9f)).start()
            com.jarvis.assistant.ui.chat.GlassMotion.sweep(bar, 24f, 140L)
        }
        binding.voiceWaveView.start()
        com.jarvis.assistant.ui.glass.DockPods.setGlow(binding.btnVoiceInput, true)
        if (com.jarvis.assistant.ui.glass.GlassMode.plain()) {
            binding.btnVoiceInput.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_cc_send)
            binding.btnVoiceInput.setColorFilter(android.graphics.Color.WHITE)
        }
        micPulse?.cancel()
        micPulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            binding.btnVoiceInput,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)
        ).apply {
            duration = 650L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun beginRecognizer() {
        if (_binding == null || !isVoiceListening) return
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
                    // Recognizer still busy (the wake-word listener was just stopped): one quiet retry before giving up.
                    if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) && !voiceBusyRetried && isVoiceListening) {
                        voiceBusyRetried = true
                        binding.root.postDelayed({ if (_binding != null && isVoiceListening) beginRecognizer() }, 600L)
                        return
                    }
                    val heard = lastPartialText
                    hideVoiceListening()
                    if (heard.isNotBlank() && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                        lastMessageOriginatedFromVoice = true
                        binding.etMessageInput.setText(heard)
                        sendMessage()
                        return
                    }
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
                        .ifBlank { lastPartialText }
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
                        lastPartialText = partial
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }

            recognizer.startListening(intent)
            speechRecognizer = recognizer
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

    private fun resumeWakeWordIfPaused() {
        if (!wakePausedForMic) return
        wakePausedForMic = false
        val app = context?.applicationContext ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try { com.jarvis.assistant.background.JarvisConversationService.startWakeWordMonitoring(app) } catch (_: Throwable) {}
        }, 900L)
    }

    private fun hideVoiceListening() {
        isVoiceListening = false
        micPulse?.cancel(); micPulse = null
        resumeWakeWordIfPaused()
        if (_binding != null) {
            binding.btnVoiceInput.scaleX = 1f
            binding.btnVoiceInput.scaleY = 1f
            val bar = binding.layoutVoiceListening
            if (bar.visibility == View.VISIBLE && com.jarvis.assistant.ui.glass.GlassMode.enabled()) {
                val d = resources.displayMetrics.density
                bar.animate().cancel()
                bar.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).translationY(10f * d).setDuration(220L)
                    .withEndAction {
                        if (_binding != null && !isVoiceListening) {
                            bar.visibility = View.GONE
                            bar.alpha = 1f; bar.scaleX = 1f; bar.scaleY = 1f; bar.translationY = 0f
                        }
                    }.start()
            } else {
                bar.visibility = View.GONE
            }
            binding.voiceWaveView.stop()
            com.jarvis.assistant.ui.glass.DockPods.setGlow(binding.btnVoiceInput, false)
            if (com.jarvis.assistant.ui.glass.GlassMode.plain()) {
                binding.btnVoiceInput.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_chat_attach_button)
                binding.btnVoiceInput.clearColorFilter()
            }
        }
    }

    private fun activityAccent(): Int = try { ThemeManager.getThemeAccentColor() } catch (_: Exception) { Color.parseColor("#00E5FF") }

    /** The spinner, steps and progress Jarvis shows inside each message are read from the chat engine. */
    private fun setupInlineActivity() {
        chatAdapter.activityLookup = { turnId -> providerEngine.turnWork.get(turnId) }
        chatAdapter.activityAccent = { activityAccent() }
    }

    /** Redraws just the activity block of a turn whenever its work changes, and keeps the newest line in view. */
    private suspend fun observeInlineActivity() {
        var previous: Map<String, com.jarvis.assistant.provider.TurnActivity> = emptyMap()
        providerEngine.turnWork.all.collect { now ->
            if (_binding == null) return@collect
            val rv = binding.rvChatMessages
            val atBottom = !rv.canScrollVertically(1)
            var touchedLast = false
            val lastId = chatAdapter.currentList.lastOrNull()?.id
            for ((id, activity) in now) {
                if (previous[id] != activity) {
                    chatAdapter.refreshActivity(id)
                    if (id == lastId) touchedLast = true
                }
            }
            previous = now
            if (touchedLast && atBottom && chatAdapter.itemCount > 0) {
                rv.post { if (_binding != null) rv.scrollToPosition(chatAdapter.itemCount - 1) }
            }
        }
    }

    override fun onDestroyView() {
        micPulse?.cancel(); micPulse = null
        resumeWakeWordIfPaused()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isVoiceListening = false
        retrySnackbar?.dismiss()
        JarvisVoiceController.stopSpeaking()
        chatAdapter.release()
        super.onDestroyView()
        _binding = null
    }
}
