package com.jarvis.assistant.ui.chat

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ReplacementSpan
import android.text.style.UpdateAppearance
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * How an answer appears while it streams in, the way a good chat app does it:
 *  - new words do not pop in; each one fades up from soft to full, one after another;
 *  - the pace adapts to the stream: the words of a chunk are spread over the time the next chunk is expected to take
 *    (measured from how fast chunks are actually arriving), so a burst reads as smooth typing and a slow stream
 *    still looks alive instead of lurching;
 *  - a soft, breathing dot sits at the end of the text while the answer is being written and disappears the moment it is done;
 *  - words that are still fading when the next chunk arrives keep fading (they do not jump to full);
 *  - nothing of this runs when system animations are off, and a huge chunk (history restore, pasted block) simply appears.
 * It only changes how text is painted (character styles), never the text itself, so selecting and copying work as before.
 */
object StreamFade {

    private const val KEY = 0x4A4C5346 // "JLSF"
    private const val MAX_SPANS = 48
    private const val FADE_MS = 260L
    private const val MAX_CHUNK_CHARS = 900

    private class Range(val start: Int, var end: Int, val startAt: Long, val dur: Long)

    private class FadeSpan(val range: Range) : CharacterStyle(), UpdateAppearance {
        var alpha = 0f
        override fun updateDrawState(tp: TextPaint) {
            if (alpha < 1f) tp.color = ColorUtils.setAlphaComponent(tp.color, (Color.alpha(tp.color) * alpha).toInt().coerceIn(0, 255))
        }
    }

    private class CaretSpan(private val color: Int, private val density: Float) : ReplacementSpan() {
        var pulse = 1f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = (11f * density).toInt()
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
            val cy = (y + p.fontMetrics.ascent * 0.38f)
            val r = 3.6f * density * (0.82f + 0.18f * pulse)
            val cx = x + 5.5f * density
            paint.color = ColorUtils.setAlphaComponent(color, (70 * pulse).toInt().coerceIn(0, 255))
            canvas.drawCircle(cx, cy, r * 1.9f, paint)
            paint.color = ColorUtils.setAlphaComponent(color, (150 + 105 * pulse).toInt().coerceIn(0, 255))
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    private class State {
        var plainLen = 0
        var interval = 140f
        var lastChunkAt = 0L
        var active = false
        var ranges = ArrayList<Range>()
        val spans = ArrayList<FadeSpan>()
        var caret: CaretSpan? = null
        var tv: TextView? = null
        val tick = object : Runnable {
            override fun run() {
                val view = tv ?: return
                if (!active || !view.isAttachedToWindow) return
                update(this@State, SystemClock.uptimeMillis())
                view.invalidate()
                view.postOnAnimation(this)
            }
        }
    }

    private fun state(tv: TextView): State {
        val existing = tv.getTag(KEY) as? State
        if (existing != null) return existing
        return State().also { it.tv = tv; tv.setTag(KEY, it) }
    }

    private fun update(st: State, now: Long) {
        for (s in st.spans) {
            val t = ((now - s.range.startAt).toFloat() / s.range.dur).coerceIn(0f, 1f)
            s.alpha = 1f - (1f - t) * (1f - t) // decelerate: quick rise, soft finish
        }
        st.caret?.pulse = 0.5f + 0.5f * sin(2.0 * PI * ((now % 1300L) / 1300.0)).toFloat()
    }

    /**
     * Returns the text to show. While [streaming] it carries the fade and the caret; otherwise it is [rendered] untouched
     * (and any running effect on [tv] stops).
     */
    fun apply(tv: TextView, rendered: CharSequence, streaming: Boolean): CharSequence {
        if (!streaming || !GlassMotion.animationsOn(tv)) {
            finish(tv)
            return rendered
        }
        val st = state(tv)
        val now = SystemClock.uptimeMillis()
        val len = rendered.length
        val prev = st.plainLen
        if (st.lastChunkAt != 0L) st.interval = st.interval * 0.6f + (now - st.lastChunkAt).coerceIn(40L, 600L).toFloat() * 0.4f
        st.lastChunkAt = now

        val out = SpannableStringBuilder(rendered)
        st.spans.clear()

        // Words that were still fading keep fading.
        val ranges = ArrayList<Range>()
        for (r in st.ranges) {
            if (now < r.startAt + r.dur && r.start < len) { r.end = min(r.end, len); ranges.add(r) }
        }
        // The new words of this chunk (the very first chunk has no fade: the answer panel itself comes into focus).
        if (prev in 1 until len && len - prev <= MAX_CHUNK_CHARS) {
            val tail = rendered.subSequence(prev, len)
            val tokens = ArrayList<IntArray>()
            var i = 0
            while (i < tail.length) {
                var j = i
                while (j < tail.length && !tail[j].isWhitespace()) j++
                while (j < tail.length && tail[j].isWhitespace()) j++
                tokens.add(intArrayOf(prev + i, prev + j))
                i = j
            }
            if (tokens.isNotEmpty()) {
                val group = max(1, ceil(tokens.size / MAX_SPANS.toDouble()).toInt())
                val groups = ceil(tokens.size / group.toDouble()).toInt()
                val spread = (st.interval * 0.9f).coerceIn(90f, 420f)
                val per = spread / groups
                for (g in 0 until groups) {
                    val a = tokens[g * group][0]
                    val b = tokens[min(tokens.size - 1, g * group + group - 1)][1]
                    ranges.add(Range(a, b, now + (g * per).toLong(), FADE_MS))
                }
            }
        }
        st.ranges = ranges
        for (r in ranges) {
            if (r.end <= r.start) continue
            val span = FadeSpan(r)
            out.setSpan(span, r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            st.spans.add(span)
        }

        // The breathing dot at the end of the text.
        val accent = try { ThemeManager.getThemeAccentColor() } catch (_: Throwable) { tv.currentTextColor }
        val caret = CaretSpan(accent, tv.resources.displayMetrics.density)
        out.append('\u200B')
        out.setSpan(caret, len, len + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        st.caret = caret
        st.plainLen = len

        update(st, now)
        if (!st.active) {
            st.active = true
            tv.postOnAnimation(st.tick)
        }
        return out
    }

    /** Stops the effect on [tv] (the answer is finished, or the view is reused for another message). */
    fun finish(tv: TextView) {
        val st = tv.getTag(KEY) as? State ?: return
        st.active = false
        tv.removeCallbacks(st.tick)
        st.ranges = ArrayList()
        st.spans.clear()
        st.caret = null
        st.plainLen = 0
        st.lastChunkAt = 0L
    }
}
