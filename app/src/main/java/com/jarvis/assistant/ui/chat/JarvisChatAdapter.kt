package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.databinding.ItemNairaChatMessageBinding
import com.jarvis.assistant.databinding.LayoutCodeArtifactCardBinding
import com.jarvis.assistant.ui.chat.liquid.LiquidMotion
import com.jarvis.assistant.ui.chat.liquid.LiquidPane

class JarvisChatAdapter(
    private val onEditUserMessage: ((String) -> Unit)? = null,
    private val onSaveAssistantMessage: ((String) -> Unit)? = null,
    private val onOpenAttachment: ((String, String, String) -> Unit)? = null,
    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null,
    /** Called with the picture's file path when the person taps Edit under a picture Jarvis made. */
    private val onEditImage: ((String) -> Unit)? = null
) : ListAdapter<ChatTurn, JarvisChatAdapter.MessageViewHolder>(DiffCallback) {

    private var speechEngine: TextToSpeech? = null
    private var speechReady = false
    @Volatile private var speaking = false
    private var pendingSpeech = ""

    fun release() {
        stopSpeaking()
        speechEngine?.shutdown()
        speechEngine = null
        speechReady = false
        pendingSpeech = ""
    }

    fun isSpeaking(): Boolean = speaking

    fun stopSpeaking() {
        if (speaking) {
            speechEngine?.stop()
            speaking = false
            onSpeakingStateChanged?.invoke(false)
        }
    }

    fun speakResponse(context: Context, rawText: String, autoTriggered: Boolean = false) {
        val text = ChatImageRefs.forSpeech(rawText)
        val cleanedText = if (autoTriggered) {
            val stripped = text
                .replace(Regex("```[\\s\\S]*?```"), " I have written the code in the artifact window. ")
                .replace(Regex("`+([^`]+)`+"), "$1")
                .replace(Regex("[#*_~>]+"), "")
                .trim()
            if (stripped.isBlank()) "I have completed your task and prepared the files." else stripped
        } else {
            text
        }

        if (speaking) {
            stopSpeaking()
            return
        }

        if (speechEngine == null) {
            pendingSpeech = cleanedText
            speechEngine = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    speechReady = true
                    val engine = speechEngine
                    if (engine != null) {
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                speaking = true
                                onSpeakingStateChanged?.invoke(true)
                            }
                            override fun onDone(utteranceId: String?) {
                                speaking = false
                                onSpeakingStateChanged?.invoke(false)
                            }
                            override fun onError(utteranceId: String?) {
                                speaking = false
                                onSpeakingStateChanged?.invoke(false)
                            }
                        })
                        val languageResult = engine.setLanguage(Locale.getDefault())
                        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                            engine.setLanguage(Locale.US)
                        }
                        val queued = pendingSpeech
                        pendingSpeech = ""
                        if (queued.isNotBlank()) speakNow(queued)
                    }
                } else {
                    Toast.makeText(context, "Text-to-speech is unavailable on this device.", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (speechReady) {
            speakNow(cleanedText)
        } else {
            pendingSpeech = cleanedText
        }
    }

    private fun speak(context: Context, text: String) {
        speakResponse(context, text, autoTriggered = false)
    }

    private fun speakNow(text: String) {
        speechEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-chat-response")
        speaking = true
        onSpeakingStateChanged?.invoke(true)
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ChatTurn>() {
        const val PAYLOAD_RESPONSE_UPDATE = "PAYLOAD_RESPONSE_UPDATE"
        const val PAYLOAD_ACTIVITY = "PAYLOAD_ACTIVITY"

        override fun areItemsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatTurn, newItem: ChatTurn): Boolean = oldItem == newItem

        override fun getChangePayload(oldItem: ChatTurn, newItem: ChatTurn): Any? {
            if (oldItem.id == newItem.id &&
                oldItem.jarvisResponse != newItem.jarvisResponse &&
                oldItem.userTranscript == newItem.userTranscript &&
                oldItem.attachmentPath == newItem.attachmentPath) {
                return PAYLOAD_RESPONSE_UPDATE
            }
            return null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemNairaChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** Looks up what Jarvis did for a turn (set by the ChatBox from the chat engine). */
    var activityLookup: ((String) -> com.jarvis.assistant.provider.TurnActivity?)? = null

    /** The theme accent used to colour the activity block. */
    var activityAccent: () -> Int = { 0xFF00E5FF.toInt() }

    /** Redraws only the activity block of one turn: no full rebind, no text flicker. */
    fun refreshActivity(turnId: String) {
        val pos = currentList.indexOfFirst { it.id == turnId }
        if (pos >= 0) notifyItemChanged(pos, PAYLOAD_ACTIVITY)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_ACTIVITY)) {
            holder.bindActivity(getItem(position))
        } else if (payloads.contains(PAYLOAD_RESPONSE_UPDATE)) {
            holder.bindResponseUpdate(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class MessageViewHolder(
        private val binding: ItemNairaChatMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            MarkdownRenderer.attachLinkTouch(binding.tvAssistantMessage)
            // Long-press any message text to select and copy it (answers and your own messages).
            binding.tvAssistantMessage.setTextIsSelectable(true)
            binding.tvUserMessage.setTextIsSelectable(true)
            LiquidPane.ensure(binding.tvUserMessage, LiquidPane.Kind.USER, 24f)
            LiquidPane.ensure(binding.tvUserAttachment, LiquidPane.Kind.INSET, 18f)
            LiquidMotion.pressFeel(binding.tvUserAttachment)
            // Copy / edit / read-aloud / save: each icon is its own 3D glass bubble with spring press and liquid ripple.
            val pods = com.jarvis.assistant.ui.glass.DockPods
            listOf(binding.btnUserEdit, binding.btnUserCopy, binding.btnAssistantCopy, binding.btnAssistantPlay, binding.btnAssistantSave)
                .forEach { pods.install(it, heightDp = 38f, circle = true, group = "message") }
        }

        /** Keeps the finished blocks of the streaming answer rendered, so each update only parses the block being written. */
        private val markdown = IncrementalMarkdown()

        /** True between the first streamed words and the final bind of a turn; makes the orb swirl faster. */
        private var streamingNow = false

        /** The slow band of light that keeps crossing the answer panel while the answer is being written. */
        private var writingGlint: LiquidPane.GlintHandle? = null

        private fun stopWritingGlint() {
            writingGlint?.stop()
            writingGlint = null
        }

        /** Streamed updates are partial rebinds, so the end of an answer is noticed by the text going quiet. */
        private val onQuiet = Runnable {
            streamingNow = false
            stopWritingGlint()
            binding.orbAssistant.busy = binding.layoutThinking.visibility == View.VISIBLE
        }

        /** The glass panel only shows when it has something inside, and the answer gets space above it only under the activity block. */
        private fun syncGlass() {
            val activityShown = binding.inlineActivity.visibility == View.VISIBLE
            val thinking = binding.layoutThinking.visibility == View.VISIBLE
            val text = binding.layoutAssistantContainer.visibility == View.VISIBLE
            binding.layoutAssistantGlass.visibility = if (activityShown || thinking || text) View.VISIBLE else View.GONE
            binding.orbAssistant.busy = thinking || streamingNow
            val lp = binding.layoutAssistantContainer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val top = if (activityShown || thinking) (12 * binding.root.resources.displayMetrics.density).toInt() else 0
            if (lp.topMargin != top) {
                lp.topMargin = top
                binding.layoutAssistantContainer.layoutParams = lp
            }
        }

        /**
         * Fast partial rebind during streaming. Updates only the assistant text and hides the thinking
         * indicator without touching user bubble, attachments, or triggering full view re-layouts.
         */
        private var actionsHiddenFor: String? = null

        private fun assistantActions() = listOf(binding.btnAssistantCopy, binding.btnAssistantPlay, binding.btnAssistantSave)

        /** While an answer is being written its action icons wait; they spring in one after another when it is done. */
        private fun hideActionsWhileWriting(turn: ChatTurn) {
            if (!GlassMotion.animationsOn(binding.root) || actionsHiddenFor == turn.id.toString()) return
            actionsHiddenFor = turn.id.toString()
            for (v in assistantActions()) {
                v.animate().cancel()
                com.jarvis.assistant.ui.glass.ViewSprings.cancel(v)
                v.alpha = 0f
                v.scaleX = 0.6f
                v.scaleY = 0.6f
            }
        }

        private fun settleActions(turn: ChatTurn) {
            val reveal = actionsHiddenFor == turn.id.toString()
            actionsHiddenFor = null
            assistantActions().forEachIndexed { i, v ->
                v.animate().cancel()
                if (reveal) {
                    v.postDelayed({
                        v.animate().alpha(1f).setDuration(220L).start()
                        com.jarvis.assistant.ui.glass.ViewSprings.pop(v, 0.6f, 1f, 480f, 0.5f)
                    }, 70L * i)
                } else if (v.alpha != 1f || v.scaleX != 1f) {
                    com.jarvis.assistant.ui.glass.ViewSprings.cancel(v)
                    v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f
                }
            }
        }

        fun bindResponseUpdate(turn: ChatTurn) {
            try { hideActionsWhileWriting(turn) } catch (_: Throwable) { }
            val firstText = binding.layoutAssistantContainer.visibility != View.VISIBLE
            binding.layoutThinking.visibility = View.GONE
            streamingNow = true
            binding.root.removeCallbacks(onQuiet)
            binding.root.postDelayed(onQuiet, 1800L)
            renderAssistantContent(turn, isStreaming = true)
            syncGlass()
            if (binding.layoutAssistantContainer.visibility == View.VISIBLE && GlassMotion.animationsOn(binding.root)) {
                // The first words come into focus on the glass; while the answer is written a slow band of light keeps crossing the panel.
                if (firstText) LiquidMotion.focusIn(binding.layoutAssistantContainer)
                if (writingGlint == null) writingGlint = try { LiquidPane.glintLoop(binding.layoutAssistantGlass) } catch (_: Throwable) { null }
            }
        }

        /** Shows or updates the inline activity (spinner, steps, progress) for this turn. Returns true when it is shown. */
        fun bindActivity(turn: ChatTurn): Boolean {
            val activity = activityLookup?.invoke(turn.id)
            if (activity == null) {
                binding.inlineActivity.visibility = View.GONE
                syncGlass()
                return false
            }
            binding.inlineActivity.bind(activity, activityAccent())
            if (binding.inlineActivity.visibility == View.VISIBLE) binding.layoutThinking.visibility = View.GONE
            syncGlass()
            return binding.inlineActivity.visibility == View.VISIBLE
        }

        /** The word-by-word fade is decoration: if it ever fails the plain text is shown instead of closing the app. */
        private fun fade(rendered: CharSequence, streaming: Boolean): CharSequence = try {
            StreamFade.apply(binding.tvAssistantMessage, rendered, streaming)
        } catch (_: Throwable) {
            try { StreamFade.finish(binding.tvAssistantMessage) } catch (_: Throwable) { }
            rendered
        }

        /**
         * Sets the answer text. If a finished answer is re-bound with the very same text while the person is selecting it,
         * the text is left alone, because setText would cancel their selection.
         */
        private fun setAnswerText(rendered: CharSequence, streaming: Boolean) {
            val tv = binding.tvAssistantMessage
            if (!streaming && tv.hasSelection() && tv.text.toString() == rendered.toString()) return
            tv.text = fade(rendered, streaming)
        }

        private fun renderAssistantContent(turn: ChatTurn, isStreaming: Boolean) {
            val context = binding.root.context
            if (turn.jarvisResponse.isBlank()) {
                binding.layoutAssistantContainer.visibility = View.GONE
                binding.layoutAssistantImages.visibility = View.GONE
                return
            }
            binding.layoutAssistantContainer.visibility = View.VISIBLE
            binding.tvAssistantMessage.setLineSpacing(0f, 1.16f)   // airier lines read as organised paragraphs

            // Pictures Jarvis made are written into the answer as image lines; they are drawn as cards, the rest is text.
            val (rawResponse, pictures) = ChatImageRefs.extract(turn.jarvisResponse)
            try {
                ImageCardBinder.bind(
                    binding.layoutAssistantImages, pictures,
                    onOpen = { ref -> onOpenAttachment?.invoke(ref.path, java.io.File(ref.path).name, com.jarvis.assistant.tools.image.ImageGenProtocol.mimeFor(ref.path)) },
                    onEdit = { ref -> onEditImage?.invoke(ref.path) }
                )
            } catch (_: Throwable) { binding.layoutAssistantImages.visibility = View.GONE }
            if (rawResponse.isBlank()) {
                binding.tvAssistantMessage.visibility = View.GONE
                binding.layoutAssistantArtifacts.visibility = View.GONE
                binding.btnAssistantCopy.setOnClickListener { copyToClipboard(context, "Jarvis response", pictures.joinToString("\n") { it.caption }) }
                binding.btnAssistantPlay.setOnClickListener { }
                binding.btnAssistantSave.setOnClickListener { }
                return
            }
            if (rawResponse.contains("```")) {
                val parsed = CodeBlockParser.parse(rawResponse, isActivelyStreaming = isStreaming)
                val textBuilder = StringBuilder()
                val codeBlocks = ArrayList<ParsedContent.Code>()

                for (segment in parsed) {
                    when (segment) {
                        is ParsedContent.Text -> textBuilder.append(segment.text)
                        is ParsedContent.Code -> codeBlocks.add(segment)
                    }
                }

                val rendered: CharSequence = try {
                    val text = textBuilder.toString().trim()
                    if (text.isNotEmpty()) markdown.render(text, context.resources.displayMetrics.density, AnswerPalette.forKey(turn.id.toString()), isStreaming) else ""
                } catch (_: Throwable) {
                    textBuilder.toString()
                }

                if (rendered.isNotBlank()) {
                    setAnswerText(rendered, isStreaming)
                    binding.tvAssistantMessage.visibility = View.VISIBLE
                } else {
                    binding.tvAssistantMessage.visibility = View.GONE
                }

                binding.layoutAssistantArtifacts.visibility = View.VISIBLE
                val container = binding.layoutAssistantArtifacts
                while (container.childCount > codeBlocks.size) {
                    container.removeViewAt(container.childCount - 1)
                }
                for (i in codeBlocks.indices) {
                    val block = codeBlocks[i]
                    val cardBinding = if (i < container.childCount) {
                        LayoutCodeArtifactCardBinding.bind(container.getChildAt(i))
                    } else {
                        val card = LayoutCodeArtifactCardBinding.inflate(LayoutInflater.from(context), container, false)
                        container.addView(card.root)
                        card
                    }
                    CodeArtifactCardBinder.bind(cardBinding, block.language, block.code, block.isWriting)
                }
            } else {
                binding.layoutAssistantArtifacts.visibility = View.GONE
                binding.layoutAssistantArtifacts.removeAllViews()
                binding.tvAssistantMessage.visibility = View.VISIBLE
                val rendered: CharSequence = try {
                    markdown.render(rawResponse, context.resources.displayMetrics.density, AnswerPalette.forKey(turn.id.toString()), isStreaming)
                } catch (_: Throwable) {
                    rawResponse
                }
                setAnswerText(rendered, isStreaming)
            }

            binding.btnAssistantCopy.setOnClickListener { copyToClipboard(context, "Jarvis response", rawResponse) }
            binding.btnAssistantPlay.setOnClickListener {
                try { speak(context, rawResponse) } catch (_: Exception) {
                    Toast.makeText(context, "Couldn't read this aloud", Toast.LENGTH_SHORT).show()
                }
            }
            binding.btnAssistantSave.setOnClickListener { onSaveAssistantMessage?.invoke(rawResponse) }
        }

        fun bind(turn: ChatTurn) {
            val context = binding.root.context
            val isLast = bindingAdapterPosition == itemCount - 1
            val pending = turn.jarvisResponse.isBlank() && turn.userTranscript.isNotBlank() &&
                isLast && System.currentTimeMillis() - turn.timestamp < 180_000L
            val activityShown = bindActivity(turn)
            val hasActivity = activityLookup?.invoke(turn.id) != null
            binding.layoutThinking.visibility = if (pending && !hasActivity) View.VISIBLE else View.GONE
            if (pending && !hasActivity) {
                binding.thinkingIndicator.startTimeMs = turn.timestamp
            }
            if (activityShown) binding.layoutThinking.visibility = View.GONE
            val attachmentPath = turn.attachmentPath
            val attachmentName = turn.attachmentName.orEmpty()
            val attachmentMime = turn.attachmentMimeType.orEmpty()
            binding.tvUserAttachment.visibility = if (!attachmentPath.isNullOrBlank() && java.io.File(attachmentPath).isFile) View.VISIBLE else View.GONE
            binding.tvUserAttachment.text = "▣  " + attachmentName.ifBlank { "Open attachment" } + "   ·   Preview"
            binding.tvUserAttachment.setOnClickListener {
                if (!attachmentPath.isNullOrBlank()) onOpenAttachment?.invoke(attachmentPath, attachmentName, attachmentMime)
            }

            if (turn.userTranscript.isNotBlank()) {
                binding.layoutUserContainer.visibility = View.VISIBLE
                if (binding.tvUserMessage.text.toString() != turn.userTranscript) binding.tvUserMessage.text = turn.userTranscript
                binding.btnUserCopy.setOnClickListener { copyToClipboard(context, "User message", turn.userTranscript) }
                binding.btnUserEdit.setOnClickListener { onEditUserMessage?.invoke(turn.userTranscript) }
            } else {
                binding.layoutUserContainer.visibility = View.GONE
            }

            renderAssistantContent(turn, isStreaming = false)
            try { settleActions(turn) } catch (_: Throwable) { }
            binding.root.removeCallbacks(onQuiet)
            streamingNow = false
            stopWritingGlint()
            // A finished answer is always fully visible, even if a reveal was cut short by recycling or a fast rebind.
            binding.layoutAssistantContainer.animate().cancel()
            binding.layoutAssistantContainer.alpha = 1f
            binding.layoutAssistantContainer.translationY = 0f
            syncGlass()
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't copy this message", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
