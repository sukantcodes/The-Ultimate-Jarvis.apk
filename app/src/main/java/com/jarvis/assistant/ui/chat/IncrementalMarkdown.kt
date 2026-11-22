package com.jarvis.assistant.ui.chat

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan

/**
 * Renders a growing answer without re-parsing all of it on every update.
 *
 * Streamed answers only grow at the end, so every block above the last blank line is final. Those blocks are rendered
 * once and kept; each update only parses the block still being written and joins it on. Cost per update stays flat
 * instead of growing with the length of the answer, which is what keeps long answers smooth.
 *
 * The finished answer (streaming = false) always goes through the normal full render, so the final text is exactly what
 * [MarkdownRenderer] produces. One instance belongs to one message row (it holds that row's cache).
 */
class IncrementalMarkdown {

    private var prefixSource = ""
    private var prefixOut: CharSequence = ""
    private var density = 0f
    private var accent = 0
    private var accent2 = 0
    private var emphasis = 0
    private var variant = -1

    fun reset() {
        prefixSource = ""
        prefixOut = ""
        variant = -1
    }

    fun render(source: String, density: Float, palette: AnswerPalette.Palette, streaming: Boolean): CharSequence {
        if (!streaming) {
            reset()
            return MarkdownRenderer.render(source, density, palette)
        }
        val normalized = if (source.indexOf('\r') >= 0) source.replace("\r\n", "\n") else source
        val split = MarkdownBlocks.stableSplit(normalized)
        if (split < 0) {
            reset()
            return MarkdownRenderer.render(normalized, density, palette)
        }
        val prefix = normalized.substring(0, split)
        val tail = normalized.substring(split).trimStart('\n')

        val sameLook = this.density == density && accent == palette.accent && accent2 == palette.accent2 &&
            emphasis == palette.emphasis && variant == palette.variant
        if (!sameLook || !prefix.startsWith(prefixSource) || prefixSource.isEmpty()) {
            // First time, a different look, or the text changed above the split: render all finished blocks once.
            prefixOut = MarkdownRenderer.render(prefix, density, palette)
            prefixSource = prefix
            this.density = density; accent = palette.accent; accent2 = palette.accent2
            emphasis = palette.emphasis; variant = palette.variant
        } else if (prefix.length > prefixSource.length) {
            // New blocks finished since last time: render only those and add them to the kept part.
            val delta = prefix.substring(prefixSource.length).trimStart('\n')
            if (delta.isNotEmpty()) prefixOut = join(prefixOut, MarkdownRenderer.render(delta, density, palette))
            prefixSource = prefix
        }
        return join(prefixOut, MarkdownRenderer.render(tail, density, palette))
    }

    /** Two rendered parts with the same line break and half-height gap the renderer puts between blocks after a blank line. */
    private fun join(a: CharSequence, b: CharSequence): CharSequence {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = SpannableStringBuilder(a)
        out.append('\n')
        val gapAt = out.length
        out.append('\n')
        out.setSpan(RelativeSizeSpan(0.7f), gapAt, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append(b)
        return out
    }
}
