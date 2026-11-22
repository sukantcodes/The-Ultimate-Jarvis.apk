package com.jarvis.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.View
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.LayoutCodeArtifactCardBinding
import com.jarvis.assistant.ui.chat.liquid.LiquidMotion
import com.jarvis.assistant.ui.chat.liquid.LiquidPane
import java.util.Locale

object CodeArtifactCardBinder {

    /** Per-card state kept on the view so recycled cards animate correctly and typing replays never leak. */
    private class CardState {
        var shown = 0
        var lastCode = ""
        var wasWriting = false
        var userToggled = false
        var entered = false
        var ticker: Runnable? = null
        var glint: LiquidPane.GlintHandle? = null
    }

    private val ui = Handler(Looper.getMainLooper())

    fun bind(
        binding: LayoutCodeArtifactCardBinding,
        language: String,
        code: String,
        isWriting: Boolean
    ) {
        val context = binding.root.context
        // The file card spans the answer panel and sits on its own frosted-glass sheet; its buttons are glass chips.
        LiquidPane.ensure(binding.root, LiquidPane.Kind.INSET, 22f)
        LiquidPane.ensure(binding.btnToggleMiniTerminal, LiquidPane.Kind.PILL, 14f)
        LiquidPane.ensure(binding.btnCardCopy, LiquidPane.Kind.PILL, 14f)
        LiquidPane.ensure(binding.btnCardExpand, LiquidPane.Kind.PILL, 14f)
        LiquidPane.ensure(binding.layoutMiniTerminal, LiquidPane.Kind.WINDOW, 16f)
        listOf(binding.btnToggleMiniTerminal, binding.btnCardCopy, binding.btnCardExpand).forEach { LiquidMotion.pressFeel(it) }
        val halfWidthPx = (context.resources.displayMetrics.widthPixels - 96f * context.resources.displayMetrics.density).toInt()
        val hasTarget = language.contains(':')
        val baseLang = if (hasTarget) language.substringBefore(':').trim() else language.trim()
        val targetName = if (hasTarget) language.substringAfter(':').trim() else ""
        val workType = WorkActionType.resolve(baseLang, targetName)

        val displayTitle = when {
            targetName.isNotBlank() -> targetName
            baseLang.isNotBlank() -> baseLang.uppercase(Locale.ROOT)
            else -> "CODE"
        }
        binding.tvCardLanguage.text = displayTitle

        // Apply Claude-style reactive icon animation
        WorkIconAnimator.applyWorkAnimation(
            iconView = binding.ivCardIcon,
            frameView = binding.flCardIconContainer,
            type = workType,
            isWriting = isWriting
        )

        // Terminal or File Title
        if (workType == WorkActionType.TERMINAL) {
            binding.tvTerminalTitle.text = if (targetName.isNotBlank()) "terminal: ~/workspace $ $targetName" else "terminal ~/workspace"
        } else {
            binding.tvTerminalTitle.text = if (targetName.isNotBlank()) "file: $targetName" else "$displayTitle snippet"
        }

        // Live status badge
        if (isWriting) {
            binding.tvWritingStatus.visibility = View.VISIBLE
            val label = workType.activeBadgeText(targetName.ifBlank { baseLang })
            binding.tvWritingStatus.text = label
            binding.tvWritingStatus.setTextColor(workType.getDynamicColor())
            LiquidPane.ensure(binding.tvWritingStatus, LiquidPane.Kind.PILL, 12f)
            binding.tvWritingStatus.clearAnimation()
            GlassMotion.breathe(binding.tvWritingStatus)
        } else {
            binding.tvWritingStatus.clearAnimation()
            GlassMotion.stopBreathe(binding.tvWritingStatus)
            binding.tvWritingStatus.visibility = View.VISIBLE
            val statusDone = workType.doneBadgeText(targetName.ifBlank { baseLang })
            binding.tvWritingStatus.text = statusDone
            val successColor = try { com.jarvis.assistant.util.ThemeManager.getThemeSuccessColor() } catch (_: Exception) { Color.parseColor("#00E676") }
            binding.tvWritingStatus.setTextColor(successColor)
            LiquidPane.ensure(binding.tvWritingStatus, LiquidPane.Kind.PILL, 12f)
        }

        val tagPrefix = when (workType) {
            WorkActionType.TERMINAL -> "Terminal"
            WorkActionType.WEB_SEARCH -> "Output"
            else -> "Code"
        }

        val state = (binding.root.tag as? CardState) ?: CardState().also { binding.root.tag = it }
        state.ticker?.let { ui.removeCallbacks(it) }
        state.ticker = null

        val res = context.resources
        val windowLines = res.getInteger(R.integer.chat_code_window_lines)
        val lines = code.split('\n')
        val total = lines.size
        binding.tvTerminalTitle.append(" · $total lines")
        binding.tvCardBottomHint.visibility = View.GONE

        // Lines are cut to what fits so every code line stays one row and the window never grows.
        val density = res.displayMetrics.density
        val charW = binding.tvPreviewCode.paint.measureText("M").coerceAtLeast(1f)
        val columnPx = halfWidthPx.toFloat()
        val maxChars = ((columnPx - 64f * density) / charW).toInt().coerceIn(12, 140)
        fun cut(l: String) = if (l.length > maxChars) l.take(maxChars - 1) + "…" else l

        fun render(from: Int, end: Int, cursor: Boolean) {
            val slice = lines.subList(from, end).joinToString("\n") { cut(it) } + if (cursor) "▌" else ""
            val nums = StringBuilder()
            for (n in (from + 1)..maxOf(end, from + 1)) { nums.append(n).append('\n') }
            binding.tvPreviewLineNumbers.text = nums.toString().trimEnd()
            binding.tvPreviewCode.text = CodeSyntaxHighlighter.highlight(slice, baseLang)
        }

        fun updateToggleText() {
            val isVisible = binding.layoutMiniTerminal.visibility == View.VISIBLE
            binding.tvToggleTagText.text = if (isVisible) "▲" else "▼"
            binding.btnToggleMiniTerminal.contentDescription = if (isVisible) "Hide the $tagPrefix window" else "Show the $tagPrefix window"
        }

        binding.btnToggleMiniTerminal.setOnClickListener {
            state.userToggled = true
            val willShow = binding.layoutMiniTerminal.visibility != View.VISIBLE
            GlassMotion.setExpanded(binding.layoutMiniTerminal, willShow, animate = true)
            binding.tvToggleTagText.animate().cancel()
            binding.tvToggleTagText.animate().rotationBy(180f).setDuration(240L).withEndAction {
                binding.tvToggleTagText.rotation = 0f
                updateToggleText()
            }.start()
        }

        if (isWriting) {
            // Live: a small fixed window that follows the newest lines, typed in with a cursor (like Claude).
            binding.tvPreviewCode.minLines = windowLines
            binding.tvPreviewLineNumbers.minLines = windowLines
            binding.tvPreviewCode.maxLines = windowLines
            if (!state.userToggled) GlassMotion.setExpanded(binding.layoutMiniTerminal, true, animate = state.entered)
            if (!state.entered) {
                state.entered = true
                LiquidMotion.arrive(binding.root, fade = true, riseDp = 16f)
            }
            // While code is being written, a slow band of light keeps travelling across the glass card.
            if (state.glint == null) binding.root.post {
                if (binding.root.tag === state && state.glint == null && state.wasWriting) {
                    state.glint = LiquidPane.glintLoop(binding.root)
                }
            }
            state.shown = if (code.startsWith(state.lastCode)) minOf(state.shown, total) else 0
            state.lastCode = code
            state.wasWriting = true
            val tick = object : Runnable {
                override fun run() {
                    if (binding.root.tag !== state) return
                    val step = maxOf(1, (total - state.shown) / 18)
                    state.shown = minOf(total, state.shown + step)
                    render(maxOf(0, state.shown - windowLines), state.shown, cursor = true)
                    if (state.shown < total) ui.postDelayed(this, 40L)
                }
            }
            state.ticker = tick
            tick.run()
        } else {
            binding.tvPreviewCode.minLines = 1
            binding.tvPreviewLineNumbers.minLines = 1
            binding.tvPreviewCode.maxLines = windowLines
            state.lastCode = code
            state.shown = total
            render(0, minOf(total, windowLines), cursor = false)
            val justFinished = state.wasWriting
            state.glint?.stop()
            state.glint = null
            if (justFinished) {
                // Done: one bright sweep over the card and the badge pops, then the window folds away.
                LiquidPane.glint(binding.root)
                GlassMotion.pop(binding.tvWritingStatus)
            }
            // Finished work collapses to a one-line header so the conversation stays compact; tap to reopen.
            if (!state.userToggled) GlassMotion.setExpanded(binding.layoutMiniTerminal, false, animate = justFinished)
            state.wasWriting = false
        }
        updateToggleText()

        val openViewer = {
            CodeViewerBottomSheet.show(context, baseLang, code, isWriting)
        }

        binding.btnCardExpand.setOnClickListener { openViewer() }

        binding.btnCardCopy.setOnClickListener {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("$displayTitle code", code))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't copy code", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
