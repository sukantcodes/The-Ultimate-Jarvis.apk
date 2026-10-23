package com.jarvis.assistant.ui.chat

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.network.AiGatewayClient
import com.jarvis.assistant.security.AiGatewayStore
import com.jarvis.assistant.databinding.FragmentChatBinding
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.ui.home.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel get() = (requireActivity() as MainActivity).viewModel
    private var selectedDocument: DocumentAnalysisClient.SelectedDocument? = null
    private var pendingExportText: String = ""

    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || _binding == null) return@registerForActivityResult
        try {
            selectedDocument = DocumentAnalysisClient.describe(requireContext(), uri)
            binding.etMessageInput.hint = "Ask about ${selectedDocument?.name}"
            Toast.makeText(requireContext(), "Attached: ${selectedDocument?.name}. Type a question or tap Send to analyze.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            selectedDocument = null
            Toast.makeText(requireContext(), error.message ?: "Couldn't attach this file.", Toast.LENGTH_LONG).show()
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
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = chatAdapter

        binding.btnSendMessage.setOnClickListener { sendMessage() }

        binding.btnChatNew.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("New Conversation")
                .setMessage("Start a new conversation and clear current history?")
                .setPositiveButton("New Chat") { _, _ ->
                    JarvisApp.instance.chatRepository.clearHistory()
                    selectedDocument = null
                    binding.etMessageInput.hint = "Message Jarvis"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnChatMenu.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.TAB_HOME)
        }

        binding.chipPersona.setOnClickListener {
            Toast.makeText(requireContext(), "Persona: Jarvis Active", Toast.LENGTH_SHORT).show()
        }
        binding.chipMode.setOnClickListener {
            Toast.makeText(requireContext(), "Mode: Code & Assistant", Toast.LENGTH_SHORT).show()
        }
        binding.btnAttach.setOnClickListener {
            documentPicker.launch(arrayOf("*/*"))
        }

        observeChatTurns()
    }

    private fun sendMessage() {
        val text = binding.etMessageInput.text?.toString()?.trim().orEmpty()
        val document = selectedDocument
        if (document != null) {
            val prompt = text.ifBlank { "Analyze this file and explain what it contains. Highlight key points and any issues." }
            selectedDocument = null
            binding.etMessageInput.text?.clear()
            binding.etMessageInput.hint = "Message Jarvis"
            binding.btnSendMessage.isEnabled = false
            Toast.makeText(requireContext(), "Jarvis is reading and analyzing ${document.name}…", Toast.LENGTH_LONG).show()
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val key = (requireActivity().application as JarvisApp).preferences.apiKey
                    val answer = DocumentAnalysisClient.analyze(requireContext(), document, prompt, key)
                    JarvisApp.instance.chatRepository.addTurn("📎 ${document.name}\n$prompt", answer)
                } catch (error: Exception) {
                    val message = error.message ?: "Couldn't analyze this file."
                    JarvisApp.instance.chatRepository.addTurn("📎 ${document.name}\n$prompt", "I couldn't analyze this file. $message")
                } finally {
                    if (_binding != null) binding.btnSendMessage.isEnabled = true
                }
            }
            return
        }

        if (text.isBlank()) return
        val gatewayStore = AiGatewayStore(requireContext())
        val deviceAction = Regex(
            "\\b(open|launch|call|dial|send message|text |turn on|turn off|enable|disable|set alarm|set timer|volume|brightness|bluetooth|wifi|wi-fi|flashlight|torch|play music|pause music|take a photo|screenshot|navigate to)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
        if (gatewayStore.candidates().isNotEmpty() && !deviceAction) {
            binding.etMessageInput.text?.clear()
            binding.btnSendMessage.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val history = JarvisApp.instance.chatRepository.turns.value.takeLast(6).joinToString("\\n") {
                        "User: ${it.userTranscript}\\nJarvis: ${it.jarvisResponse}"
                    }
                    val prompt = if (history.isBlank()) text else "Recent conversation:\\n$history\\n\\nCurrent user request: $text"
                    val result = AiGatewayClient.complete(
                        requireContext(),
                        prompt,
                        "You are Jarvis, the user's Android AI assistant. Answer naturally and accurately. For requests requiring direct phone control, explain that device actions are handled by Jarvis's live assistant mode."
                    )
                    JarvisApp.instance.chatRepository.addTurn(text, result.text + "\\n\\n— ${AiGatewayStore.label(result.provider)} · ${result.latencyMs} ms")
                } catch (error: Exception) {
                    JarvisApp.instance.chatRepository.addTurn(text, "AI Gateway error: ${error.message ?: "No configured provider responded."}")
                } finally {
                    if (_binding != null) binding.btnSendMessage.isEnabled = true
                }
            }
            return
        }
        if (!JarvisConversationService.isServiceRunning()) {
            (activity as? MainActivity)?.handleMicOrOrbClick()
        }
        JarvisConversationService.sendTextMessage(text)
        binding.etMessageInput.text?.clear()
    }

    private fun observeChatTurns() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chatTurns.collect { turns ->
                    chatAdapter.submitList(turns) {
                        if (turns.isNotEmpty()) binding.rvChatMessages.smoothScrollToPosition(turns.size - 1)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            binding.root.visibility = View.VISIBLE
            com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)
        } else if (hidden && _binding != null) {
            binding.root.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
