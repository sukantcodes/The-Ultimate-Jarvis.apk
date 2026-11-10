package com.jarvis.assistant.ui.chat

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Toast
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    private var chatStatusAnimator: ObjectAnimator? = null
    private var lastObservedAssistantResponse: String = ""
    private var speechRecognizer: SpeechRecognizer? = null

    private val voicePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceRecognition()
        else Toast.makeText(requireContext(), "Microphone permission is needed for voice dictation.", Toast.LENGTH_LONG).show()
    }

    private val chatToolConfirmer = object : Confirmer {
        override suspend fun confirm(request: ConfirmRequest): Decision = withContext(Dispatchers.Main.immediate) {
            if (!isAdded || _binding == null) return@withContext Decision.DENY
            suspendCancellableCoroutine { continuation ->
                val choices = if (request.destructive) {
                    arrayOf("Allow once", "Deny")
                } else {
                    arrayOf("Allow once", "Allow next identical call", "Allow this session", "Always allow this category")
                }
                val dialog = MaterialAlertDialogBuilder(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
                    .setTitle("JARVIS permission request")
                    .setMessage("\${request.summary}\n\nTool: \${request.tool}\nCategory: \${request.group}\nRisk: \${request.risk.label}\n\nChoose how this action may proceed.")
                    .setItems(choices) { _, which ->
                        val decision = if (request.destructive) {
                            if (which == 0) Decision.ALLOW_ONCE else Decision.DENY
                        } else {
                            when (which) {
                                0 -> Decision.ALLOW_ONCE
                                1 -> Decision.ALLOW_NEXT
                                2 -> Decision.ALLOW_SESSION
                                else -> Decision.ALLOW_ALWAYS
                            }
                        }
                        if (continuation.isActive) continuation.resume(decision)
                    }
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

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
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
            showExportFormatPicker(text, "Jarvis-Edited-File")
        },
        onOpenAttachment = { path, name, mime ->
            startActivity(Intent(requireContext(), ChatAttachmentPreviewActivity::class.java).apply {
                putExtra(ChatAttachmentPreviewActivity.EXTRA_PATH, path)
                putExtra(ChatAttachmentPreviewActivity.EXTRA_NAME, name)
                putExtra(ChatAttachmentPreviewActivity.EXTRA_MIME, mime)
            })
        },
        onEditAssistantMessage = { response -> showChatArtifactEditor(response) }
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
        AgentToolApproval.confirmer = CodingAgentPermissionBridge.confirmer
        CodingAgentPermissionBridge.attach(chatToolConfirmer)
        CodingAgentUiBridge.attachQuestion { question, options -> askCodingQuestion(question, options) }
        CodingAgentUiBridge.openProviderSettings = {
            if (isAdded) startActivity(Intent(requireContext(), com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java))
        }
        com.jarvis.assistant.util.ThemeManager.applyFragmentBackground(binding.root)

        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter

        selectedChatMode = chatModePrefs.getString("chat_mode", "auto") ?: "auto"
        refreshChatModeLabel()
        binding.btnSendMessage.setOnClickListener { sendMessage() }
        binding.btnChatVoiceInput.setOnClickListener { startVoiceInput() }
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
            if (selectedChatMode == "coding" || ClozedCodingAgentService.isRunning()) {
                ClozedCodingAgentService.stop(requireContext())
                setChatActivity("STOPPING")
                return@setOnClickListener
            }
            offlineGenerationJob?.cancel()
            offlineGenerationJob = null
            fileAnalysisJob?.cancel()
            fileAnalysisJob = null
            providerEngine.cancel()
            binding.btnStopChat.visibility = View.GONE
            setChatActivity("READY")
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
                    if (selectedChatMode == "coding") ClozedCodingAgentService.newSession(requireContext())
                    selectedDocument?.savedPath?.let { path -> runCatching { java.io.File(path).delete() } }
                    JarvisApp.instance.chatRepository.clearHistory()
                    lastObservedAssistantResponse = ""
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
            startActivity(Intent(requireContext(), com.jarvis.assistant.ui.settings.PersonaActivity::class.java))
        }
        binding.chipMode.setOnClickListener { showChatModePicker() }
        binding.btnAttach.setOnClickListener {
            ChatComposerMenu.show(requireContext(), listOf(
                ChatComposerMenu.Action("Add files", "Attach a document, image or code file", "＋") {
                    documentPicker.launch(arrayOf("*/*"))
                },
                ChatComposerMenu.Action("AI & models", "Providers, API connections and model routing", "✧") {
                    startActivity(Intent(requireContext(), com.jarvis.assistant.ui.settings.ApiConnectionActivity::class.java))
                },
                ChatComposerMenu.Action("Voice settings", "Voice input, hands-free controls and speech setup", "♫") {
                    startActivity(Intent(requireContext(), com.jarvis.assistant.ui.settings.HandsFreeActivity::class.java))
                },
                ChatComposerMenu.Action("Connectors & GitHub", "Manage connected accounts and integrations", "⌘") {
                    startActivity(Intent(requireContext(), ConnectorHubActivity::class.java))
                },
                ChatComposerMenu.Action("Workspace", "Browse, preview, edit and export ChatBox files", "▤") {
                    startActivity(Intent(requireContext(), WorkspaceActivity::class.java))
                },
                ChatComposerMenu.Action("Coding Agent + Terminal", "Use the JARVIS ChatBox with project-aware coding tools and a real terminal", ">_") {
                    activateCodingAgentMode()
                },
                ChatComposerMenu.Action("MCP servers", "Add Streamable HTTP tools and manage OAuth logins", "⌘") {
                    startActivity(Intent(requireContext(), McpServersActivity::class.java))
                },
                ChatComposerMenu.Action("Website preview", "Start or stop the private local preview server", "↗") {
                    LocalServerDialog.show(requireContext())
                },
                ChatComposerMenu.Action("Chat tools", "Writing, formatting, export and conversation utilities", "✦") {
                    ChatboxTools.show(requireContext(), binding.etMessageInput, chatAdapter.currentList) { text ->
                        showExportFormatPicker(text, "Jarvis-Conversation")
                    }
                },
                ChatComposerMenu.Action("Conversation history", "Review previous messages in this chat", "◷") {
                    ChatHistoryBottomSheet.newInstance().show(parentFragmentManager, ChatHistoryBottomSheet.TAG)
                },
                ChatComposerMenu.Action("Memory", "Open JARVIS memory and saved context controls", "◉") {
                    startActivity(Intent(requireContext(), com.jarvis.assistant.ui.memory.MemoryActivity::class.java))
                },
                ChatComposerMenu.Action("Automations", "Create and manage multi-step assistant workflows", "⏱") {
                    startActivity(Intent(requireContext(), com.jarvis.assistant.studio.ui.AutomationStudioActivity::class.java))
                }
            ))
        }
        binding.btnChatTools.setOnClickListener {
            ChatboxTools.show(requireContext(), binding.etMessageInput, chatAdapter.currentList) { text ->
                showExportFormatPicker(text, "Jarvis-Conversation")
            }
        }

        binding.btnChatTerminal.setOnClickListener {
            activateCodingAgentMode()
        }
        binding.btnChatServer.setOnClickListener {
            LocalServerDialog.show(requireContext())
        }

        observeChatTurns()
        observeProviderEngine()
    }

    /** Opens a focused in-chat editor and saves the edited response as a workspace artifact. */
    private fun showChatArtifactEditor(initialText: String) {
        val root = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 8, 16, 4)
        }
        val nameInput = android.widget.EditText(requireContext()).apply {
            hint = "Artifact filename (example: notes.md)"
            setSingleLine(true)
            setText("jarvis-artifact.md")
        }
        val editor = android.widget.EditText(requireContext()).apply {
            setText(initialText)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 12
            maxLines = 24
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(nameInput)
        root.addView(android.widget.ScrollView(requireContext()).apply {
            addView(editor)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.48f).toInt()
            )
        })
        val dialog = MaterialAlertDialogBuilder(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle("Edit response artifact")
            .setView(root)
            .setPositiveButton("Save to workspace", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rawName = nameInput.text?.toString()?.trim().orEmpty()
                val fileName = if (rawName.contains('.')) rawName else "$rawName.md"
                if (!fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) ||
                    fileName.endsWith(".") || fileName.contains("..")
                ) {
                    nameInput.error = "Use a simple filename without folders or special characters."
                    return@setOnClickListener
                }
                val content = editor.text?.toString().orEmpty()
                if (content.isBlank() || content.toByteArray(Charsets.UTF_8).size > 1_000_000) {
                    editor.error = "Artifact must contain text and stay below 1 MB."
                    return@setOnClickListener
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val workspace = com.jarvis.assistant.tools.Workspace(
                                java.io.File(requireContext().filesDir, "chatbox-workspace")
                            )
                            val target = workspace.resolve("artifacts/$fileName")
                            if (target.exists()) error("An artifact with that filename already exists. Choose another name.")
                            target.parentFile?.mkdirs()
                            val temp = java.io.File(target.parentFile, ".${target.name}.tmp")
                            temp.writeText(content, Charsets.UTF_8)
                            if (!temp.renameTo(target)) {
                                temp.delete()
                                error("Could not save the artifact to the workspace.")
                            }
                            workspace.relative(target)
                        }
                    }
                    result.onSuccess { relative ->
                        Toast.makeText(requireContext(), "Saved workspace artifact: $relative", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        startActivity(Intent(requireContext(), WorkspaceActivity::class.java))
                    }.onFailure { error ->
                        Toast.makeText(requireContext(), error.message ?: "Could not save artifact.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showExportFormatPicker(text: String, baseName: String) {
        val labels = arrayOf(
            "Plain text (.txt)", "Markdown (.md)", "JSON (.json)", "CSV (.csv)",
            "HTML (.html)", "XML (.xml)", "YAML (.yml)", "Kotlin (.kt)",
            "Java (.java)", "Python (.py)", "JavaScript (.js)", "TypeScript (.ts)",
            "CSS (.css)", "SQL (.sql)", "Shell script text (.sh)"
        )
        val extensions = arrayOf("txt", "md", "json", "csv", "html", "xml", "yml", "kt", "java", "py", "js", "ts", "css", "sql", "sh")
        MaterialAlertDialogBuilder(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
            .setTitle("Export response as")
            .setMessage("Choose the text format that matches the response content. JARVIS preserves the response exactly; it does not convert plain text into a binary Office, PDF, image, or audio file.")
            .setItems(labels) { _, which ->
                pendingExportText = text
                exportLauncher.launch(baseName + "." + extensions[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChatModePicker() {
        val active = providerManager.activeProfile()
        val labels = arrayOf(
            if (active != null) "Auto · ${active.displayName} (custom provider)" else "Auto · choose available brain",
            "Gemini Live · online",
            "Offline Brain · on-device",
            "Coding Agent + Terminal · project workspace"
        )
        val values = arrayOf("auto", "gemini", "offline", "coding")
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
            "coding" -> "Coding Agent"
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
        if (selectedChatMode == "coding") {
            val resolution = providerManager.resolveChat(com.jarvis.assistant.provider.WorkCategory.CODING)
            view.text = when (resolution) {
                is com.jarvis.assistant.provider.ChatResolution.Ready -> "Coding Agent · ${resolution.provider.label}"
                is com.jarvis.assistant.provider.ChatResolution.Blocked -> "Coding Agent · ${resolution.reason}"
                com.jarvis.assistant.provider.ChatResolution.NotActive -> "Coding Agent · connect a provider in AI & models"
            }
            view.visibility = View.VISIBLE
            return
        }
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
        if (selectedChatMode == "offline" || selectedChatMode == "coding" || providerManager.activeProfile() == null) return false
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
                        if (_binding != null && providerEngine.isBusy) setChatActivity("PROCESSING · ${activity.uppercase()}")
                    }
                }
                providerEngine.state.collect { state ->
                    if (_binding == null) return@collect
                    when (state) {
                        is EngineState.Streaming -> {
                            binding.btnStopChat.visibility = View.VISIBLE
                            binding.btnSendMessage.isEnabled = false
                            setChatActivity("ANSWERING")
                        }
                        is EngineState.Failed -> {
                            binding.btnStopChat.visibility = View.GONE
                            binding.btnSendMessage.isEnabled = true
                            setChatActivity("ERROR")
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
                            setChatActivity("READY")
                        }
                    }
                }
            }
        }
    }


    private fun activateCodingAgentMode() {
        selectedChatMode = "coding"
        chatModePrefs.edit().putString("chat_mode", selectedChatMode).apply()
        refreshChatModeLabel()
        Toast.makeText(requireContext(), "Coding Agent mode enabled. Send a project task in this ChatBox.", Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            MaterialAlertDialogBuilder(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
                .setTitle("Project folder access")
                .setMessage("JARVIS Coding Agent needs access to /storage/emulated/0/ClozedProjects so it can read and edit project files.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Grant access") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + requireContext().packageName))
                    runCatching { startActivity(intent) }.onFailure {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .show()
        }
    }

    private fun sendCodingMessage(text: String) {
        if (text.isBlank()) return
        if (selectedDocument != null) {
            Toast.makeText(requireContext(), "Remove the attachment or copy the file into the coding project folder first.", Toast.LENGTH_LONG).show()
            return
        }
        if (ClozedCodingAgentService.isRunning()) {
            Toast.makeText(requireContext(), "The coding agent is already working. Stop it or wait for it to finish.", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            activateCodingAgentMode()
            return
        }
        val turnId = JarvisApp.instance.chatRepository.beginPending(text, null, null, null)
        binding.etMessageInput.text?.clear()
        binding.btnSendMessage.isEnabled = false
        binding.btnStopChat.visibility = View.VISIBLE
        setChatActivity("THINKING")
        try {
            ClozedCodingAgentService.submit(requireContext(), text, turnId)
        } catch (error: Exception) {
            JarvisApp.instance.chatRepository.finishPending(turnId, "Coding agent could not start: ${error.message ?: error.javaClass.simpleName}")
            binding.btnSendMessage.isEnabled = true
            binding.btnStopChat.visibility = View.GONE
            setChatActivity("READY")
        }
    }

    private suspend fun askCodingQuestion(question: String, options: List<String>): String? =
        withContext(Dispatchers.Main.immediate) {
            if (!isAdded || _binding == null) return@withContext null
            suspendCancellableCoroutine { continuation ->
                val builder = MaterialAlertDialogBuilder(requireContext(), com.jarvis.assistant.R.style.ThemeOverlay_Jarvis_MaterialDialog)
                    .setTitle("JARVIS Coding Agent")
                    .setMessage(question)
                    .setNegativeButton("Cancel") { _, _ -> if (continuation.isActive) continuation.resume(null) }
                if (options.isNotEmpty()) {
                    builder.setItems(options.toTypedArray()) { _, which ->
                        if (continuation.isActive) continuation.resume(options.getOrNull(which))
                    }
                } else {
                    val answer = android.widget.EditText(requireContext()).apply {
                        hint = "Your answer"
                        setSingleLine(false)
                        minLines = 2
                        maxLines = 5
                    }
                    builder.setView(answer)
                    builder.setPositiveButton("Send") { _, _ ->
                        if (continuation.isActive) continuation.resume(answer.text?.toString()?.trim()?.takeIf { it.isNotBlank() })
                    }
                }
                val dialog = builder.create()
                dialog.setOnCancelListener { if (continuation.isActive) continuation.resume(null) }
                continuation.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }
        }

    private fun sendMessage() {
        val text = binding.etMessageInput.text?.toString()?.trim().orEmpty()
        if (isLocalServerRequest(text)) {
            binding.etMessageInput.text?.clear()
            LocalServerDialog.show(requireContext())
            return
        }
        if (selectedChatMode == "coding") {
            sendCodingMessage(text)
            return
        }
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
            setChatActivity("PROCESSING")
            Toast.makeText(requireContext(), "JARVIS is analyzing ${document.name}…", Toast.LENGTH_LONG).show()
            fileAnalysisJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val answer = DocumentAnalysisClient.analyze(
                        requireContext(), document, prompt, documentKey, documentProvider, documentModel
                    )
                    setChatActivity("ANSWERING")
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
                        setChatActivity("READY")
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
            setChatActivity("THINKING")
            offlineGenerationJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val answer = OfflineLlm.generate(appContext, text)
                    setChatActivity("ANSWERING")
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
                        setChatActivity("READY")
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
        setChatActivity("THINKING")
    }
    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(requireContext(), "Voice recognition is not available on this device.", Toast.LENGTH_LONG).show()
            return
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            voicePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceRecognition()
    }

    private fun startVoiceRecognition() {
        if (!isAdded || _binding == null) return
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (_binding != null) setChatActivity("LISTENING")
                    }
                    override fun onBeginningOfSpeech() {
                        if (_binding != null) setChatActivity("LISTENING")
                    }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        if (_binding != null) setChatActivity("PROCESSING VOICE")
                    }
                    override fun onError(error: Int) {
                        if (_binding != null) {
                            setChatActivity("READY")
                            val message = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't recognise that. Please try again."
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything. Tap the microphone and try again."
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                                else -> "Voice recognition stopped. Please try again."
                            }
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (_binding != null) {
                            if (spoken.isNotBlank()) {
                                binding.etMessageInput.setText(spoken)
                                binding.etMessageInput.setSelection(spoken.length)
                                binding.etMessageInput.requestFocus()
                            }
                            setChatActivity("READY")
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message to JARVIS")
                }
                recognizer.startListening(intent)
            }
        }.onFailure {
            speechRecognizer?.destroy()
            speechRecognizer = null
            if (_binding != null) {
                setChatActivity("READY")
                Toast.makeText(requireContext(), "Couldn't start voice recognition: ${it.message ?: "try again"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isLocalServerRequest(text: String): Boolean {
        val normalized = text.lowercase()
        val serverMention = normalized.contains("local server") ||
            normalized.contains("local website") ||
            normalized.contains("website locally") ||
            normalized.contains("web server")
        val actionMention = listOf("host", "start", "run", "serve", "preview", "launch").any { normalized.contains(it) }
        return text.length <= 240 && serverMention && actionMention
    }

    private fun setChatActivity(stage: String) {
        if (_binding == null) return
        val view = binding.tvChatStatus
        view.text = "● $stage"
        chatStatusAnimator?.cancel()
        chatStatusAnimator = null
        if (stage == "READY" || stage == "ERROR") {
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
        } else {
            chatStatusAnimator = ObjectAnimator.ofFloat(view, View.ALPHA, 0.62f, 1f).apply {
                duration = 720L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
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
                        val shouldFollowLatest = !binding.rvChatMessages.canScrollVertically(1)
                        binding.layoutChatWelcome.visibility = if (turns.isEmpty()) View.VISIBLE else View.GONE
                        val latestResponse = turns.lastOrNull()?.jarvisResponse.orEmpty()
                        if (latestResponse.isNotBlank() && latestResponse != lastObservedAssistantResponse) {
                            lastObservedAssistantResponse = latestResponse
                            setChatActivity("ANSWERING")
                        }
                        // An empty conversation is idle, not a pending assistant response.
                        val pending = turns.isNotEmpty() &&
                            (turns.lastOrNull()?.jarvisResponse.isNullOrBlank() || providerEngine.isBusy ||
                                ClozedCodingAgentService.isRunning())
                        if (!pending && offlineGenerationJob == null && fileAnalysisJob == null && selectedDocument == null) {
                            binding.btnStopChat.visibility = View.GONE
                            binding.btnSendMessage.isEnabled = true
                            setChatActivity("READY")
                        } else if (pending) {
                            binding.btnStopChat.visibility = View.VISIBLE
                            binding.btnSendMessage.isEnabled = false
                            setChatActivity("THINKING")
                        }
                        chatAdapter.submitList(turns) {
                            // Keep the newest answer visible without pulling users away from older messages.
                            if (turns.isNotEmpty() && shouldFollowLatest) {
                                binding.rvChatMessages.scrollToPosition(turns.size - 1)
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
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        chatStatusAnimator?.cancel()
        chatStatusAnimator = null
        retrySnackbar?.dismiss()
        CodingAgentPermissionBridge.detach(chatToolConfirmer)
        if (!ClozedCodingAgentService.isRunning() && AgentToolApproval.confirmer === CodingAgentPermissionBridge.confirmer) {
            AgentToolApproval.confirmer = null
        }
        CodingAgentUiBridge.detachQuestion()
        CodingAgentUiBridge.openProviderSettings = null
        chatAdapter.release()
        super.onDestroyView()
        _binding = null
    }
}
