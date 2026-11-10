package com.jarvis.assistant.ui.chat

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApp
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.jarvis.assistant.tools.AgentToolApproval
import com.jarvis.assistant.tools.ConfirmRequest
import com.jarvis.assistant.tools.Confirmer
import com.jarvis.assistant.tools.Decision
import com.jarvis.assistant.provider.AttachmentRef
import com.jarvis.assistant.provider.EngineState
import com.jarvis.assistant.provider.ProviderRuntime
import com.jarvis.assistant.provider.SendOutcome

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
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

    private val chatToolConfirmer = object : Confirmer {
        override suspend fun confirm(request: ConfirmRequest): Decision = withContext(Dispatchers.Main.immediate) {
            if (!isAdded || _binding == null) return@withContext Decision.DENY
            suspendCancellableCoroutine { continuation ->
                val dialog = MaterialAlertDialogBuilder(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
                    .setTitle("JARVIS permission request")
                    .setMessage("${request.summary}\n\nTool: ${request.tool}\nCategory: ${request.group}\nRisk: ${request.risk.label}\n\nAllow this action?")
                    .setNegativeButton("Deny") { _, _ -> if (continuation.isActive) continuation.resume(Decision.DENY) }
                    .setNeutralButton("Allow once") { _, _ -> if (continuation.isActive) continuation.resume(Decision.ALLOW_ONCE) }
                    .setPositiveButton("Allow this session") { _, _ -> if (continuation.isActive) continuation.resume(Decision.ALLOW_SESSION) }
                    .create()
                dialog.setOnCancelListener { if (continuation.isActive) continuation.resume(Decision.DENY) }
                continuation.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
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
        AgentToolApproval.confirmer = chatToolConfirmer
        com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)

        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter

        selectedChatMode = chatModePrefs.getString("chat_mode", "auto") ?: "auto"
        refreshChatModeLabel()
        binding.btnSendMessage.setOnClickListener { sendMessage() }
        binding.etMessageInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event?.action == android.view.KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enterPressed) { sendMessage(); true } else false
        }
        binding.chipSuggestionExplain.setOnClickListener { setPrompt("Explain a difficult topic to me in simple language, step by step, with examples.") }
        binding.chipSuggestionWrite.setOnClickListener { setPrompt("Help me write something. Ask me what I want to create.") }
        binding.chipSuggestionAnalyze.setOnClickListener { documentPicker.launch(arrayOf("*/*")) }
        binding.btnRemoveAttachment.setOnClickListener {
            selectedDocument?.savedPath?.let { path -> runCatching { java.io.File(path).delete() } }
            selectedDocument = null
            updateAttachmentPreview()
            binding.etMessageInput.hint = "Message JARVIS…"
        }
        binding.btnStopChat.setOnClickListener {
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

        binding.btnChatNew.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
                .setTitle("New Conversation")
                .setMessage("Start a new conversation and clear current history?")
                .setPositiveButton("New Chat") { _, _ ->
                    offlineGenerationJob?.cancel()
                    fileAnalysisJob?.cancel()
                    providerEngine.cancel()
                    retrySnackbar?.dismiss()
                    JarvisConversationService.stopCurrentResponseImmediately()
                    selectedDocument?.savedPath?.let { path -> runCatching { java.io.File(path).delete() } }
                    JarvisApp.instance.chatRepository.clearHistory()
                    selectedDocument = null
                    updateAttachmentPreview()
                    binding.etMessageInput.hint = "Message JARVIS…"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnChatMenu.setOnClickListener {
            (activity as? MainActivity)?.openNavigationDrawer()
        }

        binding.chipPersona.setOnClickListener {
            Toast.makeText(requireContext(), "Persona: Jarvis Active", Toast.LENGTH_SHORT).show()
        }
        binding.chipMode.setOnClickListener { showChatModePicker() }
        binding.btnAttach.setOnClickListener {
            documentPicker.launch(arrayOf("*/*"))
        }
        binding.btnChatTools.setOnClickListener {
            ChatboxTools.show(requireContext(), binding.etMessageInput, chatAdapter.currentList) { text ->
                pendingExportText = text
                exportLauncher.launch("Jarvis-Conversation.txt")
            }
        }

        binding.btnChatTerminal.setOnClickListener {
            ChatTerminalDialog.show(requireContext())
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
        MaterialAlertDialogBuilder(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle("Choose chat engine")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
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

    private fun refreshChatModeLabel() {
        val label = when (selectedChatMode) {
            "gemini" -> "Gemini"
            "offline" -> "Offline"
            else -> "Auto"
        }
        val chip = binding.chipMode
        if (chip.childCount > 1 && chip.getChildAt(1) is android.widget.TextView) {
            (chip.getChildAt(1) as android.widget.TextView).text = label
        }
        chip.contentDescription = "Chat engine: $label. Tap to change."
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
        MaterialAlertDialogBuilder(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
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
                    providerEngine.toolActivity.collect { activity ->
                        if (_binding != null && providerEngine.isBusy) binding.tvChatStatus.text = "● ${activity.uppercase()}"
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
        val text = binding.etMessageInput.text?.toString()?.trim().orEmpty()
        if (customProviderActive()) {
            sendViaProvider(text)
            return
        }
        val document = selectedDocument
        if (document != null) {
            val prompt = text.ifBlank { "Analyze this file and explain what it contains. Highlight key points and any issues." }
            val appContext = requireContext().applicationContext
            val engineStore = com.jarvis.assistant.security.AiEngineStore(appContext)
            if (selectedChatMode == "offline") {
                Toast.makeText(requireContext(), "Document analysis is not available in Offline Brain yet. Choose Gemini or Groq to analyze this attachment.", Toast.LENGTH_LONG).show()
                return
            }
            val useGroqForDocument = selectedChatMode != "gemini" && engineStore.useGroq
            val documentProvider = if (useGroqForDocument) "groq" else "gemini"
            val documentKey = if (useGroqForDocument) engineStore.groqKey() else JarvisApp.instance.preferences.apiKey
            val documentModel = if (useGroqForDocument) engineStore.groqChatModel else "gemini-2.5-flash"
            if (documentKey.isBlank()) {
                Toast.makeText(requireContext(), "Add the selected $documentProvider API key in JARVIS Settings before analyzing this file.", Toast.LENGTH_LONG).show()
                return
            }
            binding.etMessageInput.text?.clear()
            binding.btnSendMessage.isEnabled = false
            binding.btnStopChat.visibility = View.VISIBLE
            binding.tvChatStatus.text = "● ANALYZING"
            Toast.makeText(requireContext(), "JARVIS is analyzing ${document.name}…", Toast.LENGTH_LONG).show()
            fileAnalysisJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val answer = DocumentAnalysisClient.analyze(
                        requireContext(), document, prompt, documentKey, documentProvider, documentModel
                    )
                    JarvisApp.instance.chatRepository.addTurn("📎 ${document.name}\n$prompt", answer, document.savedPath, document.name, document.mimeType)
                    selectedDocument = null
                    if (_binding != null) { updateAttachmentPreview(); binding.etMessageInput.hint = "Message JARVIS…" }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = "I couldn't analyze ${document.name} with $documentProvider. ${error.message ?: "Please check the selected provider, model, connection, and file format."} Your attachment remains selected so you can retry or choose another file."
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
        if (!JarvisConversationService.isServiceRunning()) (activity as? MainActivity)?.handleMicOrOrbClick()
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
                        binding.layoutChatWelcome.visibility = if (turns.isEmpty()) View.VISIBLE else View.GONE
                        val pending = turns.lastOrNull()?.jarvisResponse.isNullOrBlank() || providerEngine.isBusy
                        if (!pending && offlineGenerationJob == null && fileAnalysisJob == null && selectedDocument == null) {
                            binding.btnStopChat.visibility = View.GONE
                            binding.btnSendMessage.isEnabled = true
                            binding.tvChatStatus.text = "● READY"
                        } else if (pending) {
                            binding.btnStopChat.visibility = View.VISIBLE
                            binding.btnSendMessage.isEnabled = false
                            binding.tvChatStatus.text = "● THINKING"
                        }
                    }
                    chatAdapter.submitList(turns) {
                        if (turns.isNotEmpty()) binding.rvChatMessages.smoothScrollToPosition(turns.size - 1)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)
            refreshProviderLabel()
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

    override fun onDestroyView() {
        retrySnackbar?.dismiss()
        if (AgentToolApproval.confirmer === chatToolConfirmer) AgentToolApproval.confirmer = null
        chatAdapter.release()
        super.onDestroyView()
        _binding = null
    }
}
