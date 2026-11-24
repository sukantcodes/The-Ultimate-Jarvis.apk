package com.jarvis.assistant.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import com.jarvis.assistant.provider.ChatWorkMode
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.abs
import kotlin.math.min

/**
 * The small glass mode selector in the ChatBox composer: Ask, Debug, Code, Develop.
 *
 * Four round glass segments sit in one glossy track. The chosen mode is a glowing pill that glides to its place with a
 * soft overshoot, squashes under the finger, and opens up to show its name while the others fold back to an icon.
 * Every colour comes from the active theme. The visible track is 30dp tall; the touch area is 44dp.
 */
class WorkModeSwitcher @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onModeSelected: ((ChatWorkMode) -> Unit)? = null

    private val modes = ChatWorkMode.values()
    private val density = resources.displayMetrics.density
    private val scaledText = resources.displayMetrics.scaledDensity

    private val trackH = 30f * density
    private val collapsedW = 34f * density
    private val gap = 3f * density
    private val glyph = 13f * density

    private var selected = ChatWorkMode.DEFAULT.ordinal
    /** The pill's position as a fractional index; animates between segments. */
    private var position = selected.toFloat()
    private var press = 0f
    private var pressedIndex = -1
    private var lastTouchX = -1f

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = com.jarvis.assistant.util.JarvisFonts.bold
        textSize = 10f * scaledText
        letterSpacing = 0.07f
    }
    private val glyphText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = com.jarvis.assistant.util.JarvisFonts.bold
        textAlign = Paint.Align.CENTER
        textSize = glyph * 1.15f
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelWidths = FloatArray(modes.size) { labelPaint.measureText(modes[it].label.uppercase()) }

    private val rect = RectF()
    private val pill = RectF()
    private val path = Path()
    private var anim: ValueAnimator? = null
    private var pressAnim: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    fun setMode(mode: ChatWorkMode, animate: Boolean) {
        val target = mode.ordinal
        selected = target
        updateDescription()
        anim?.cancel()
        if (!animate) {
            position = target.toFloat()
            invalidate()
            return
        }
        val from = position
        anim = ValueAnimator.ofFloat(from, target.toFloat()).apply {
            duration = (260 + 90 * abs(target - from)).toLong().coerceAtMost(560L)
            interpolator = OvershootInterpolator(1.15f)
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun updateDescription() {
        contentDescription = "Chat mode: ${modes[selected].label}. Double tap to change."
    }

    // ---- geometry -------------------------------------------------------------------------------------------

    /** How open each segment is: 1 under the pill, 0 when it is a plain icon. */
    private fun openness(i: Int): Float = 1f - min(1f, abs(position - i))

    private fun segmentWidth(i: Int): Float = collapsedW + (labelWidths[i] + 6f * density) * openness(i)

    private fun totalWidth(): Float = modes.indices.sumOf { segmentWidth(it).toDouble() }.toFloat() + gap * (modes.size - 1)

    private fun maxWidth(): Float {
        val widest = labelWidths.maxOrNull()!! + 6f * density
        return collapsedW * modes.size + widest + gap * (modes.size - 1) + 4f * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (maxWidth() + 2f * density).toInt()
        val h = (44f * density).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    private fun segmentRect(i: Int, startX: Float, top: Float, out: RectF): RectF {
        var x = startX
        for (k in 0 until i) x += segmentWidth(k) + gap
        out.set(x, top, x + segmentWidth(i), top + trackH)
        return out
    }

    // ---- colour ---------------------------------------------------------------------------------------------

    private fun modeColor(mode: ChatWorkMode): Int = try {
        when (mode) {
            ChatWorkMode.ASK -> ThemeManager.getThemeSuccessColor()
            ChatWorkMode.DEBUG -> ThemeManager.getThemeWarningColor()
            ChatWorkMode.CODE -> ThemeManager.getThemeSecondaryColor()
            ChatWorkMode.DEVELOP -> ThemeManager.getThemeAccentColor()
        }
    } catch (_: Throwable) { Color.WHITE }

    private fun pillColor(): Int {
        val a = position.coerceIn(0f, (modes.size - 1).toFloat())
        val lo = a.toInt()
        val hi = min(lo + 1, modes.size - 1)
        return ColorUtils.blendARGB(modeColor(modes[lo]), modeColor(modes[hi]), a - lo)
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true) && tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) tv.data else fallback
    }

    // ---- drawing --------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val top = (height - trackH) / 2f
        val total = totalWidth()
        val startX = width - total - 1f * density
        val radius = trackH / 2f

        // Glass track.
        rect.set(startX - 3f * density, top - 2f * density, startX + total + 3f * density, top + trackH + 2f * density)
        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0x40FFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
        fill.shader = null
        stroke.strokeWidth = 1f * density
        stroke.shader = null
        stroke.color = 0x99FFFFFF.toInt()
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, stroke)
        stroke.color = ColorUtils.setAlphaComponent(modeColor(ChatWorkMode.DEFAULT), 0x30)
        val inner = RectF(rect).apply { inset(1.2f * density, 1.2f * density) }
        canvas.drawRoundRect(inner, inner.height() / 2f, inner.height() / 2f, stroke)

        // The pill: spans the segment under it, stretching across neighbours while it glides.
        val lo = position.toInt().coerceIn(0, modes.size - 1)
        val hi = min(lo + 1, modes.size - 1)
        val frac = (position - lo).coerceIn(0f, 1f)
        val a = segmentRect(lo, startX, top, RectF())
        val b = segmentRect(hi, startX, top, RectF())
        pill.set(a.left + (b.left - a.left) * frac, top, a.right + (b.right - a.right) * frac, top + trackH)
        val squashX = 1f + 0.05f * press
        val squashY = 1f - 0.10f * press
        val cx = pill.centerX(); val cy = pill.centerY()
        val pw = pill.width() * squashX; val ph = pill.height() * squashY
        pill.set(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f)

        val color = pillColor()
        for (i in 3 downTo 1) {
            glow.color = ColorUtils.setAlphaComponent(color, 16 + (3 - i) * 6)
            val g = i * 2.4f * density
            canvas.drawRoundRect(pill.left - g, pill.top - g * 0.6f, pill.right + g, pill.bottom + g * 0.9f, ph / 2f + g, ph / 2f + g, glow)
        }
        fill.shader = LinearGradient(
            pill.left, pill.top, pill.right, pill.bottom,
            ColorUtils.blendARGB(color, Color.WHITE, 0.28f), ColorUtils.blendARGB(color, Color.BLACK, 0.18f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(pill, ph / 2f, ph / 2f, fill)
        // Gloss: a bright band across the upper half, and a faint light along the bottom edge.
        fill.shader = LinearGradient(0f, pill.top, 0f, pill.centerY(), 0xA6FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        val gloss = RectF(pill.left + 2.5f * density, pill.top + 1.5f * density, pill.right - 2.5f * density, pill.centerY())
        canvas.drawRoundRect(gloss, gloss.height() / 2f, gloss.height() / 2f, fill)
        fill.shader = LinearGradient(0f, pill.centerY(), 0f, pill.bottom, 0x00FFFFFF, 0x38FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(pill, ph / 2f, ph / 2f, fill)
        fill.shader = null
        stroke.strokeWidth = 1f * density
        stroke.color = 0xCCFFFFFF.toInt()
        canvas.drawRoundRect(pill, ph / 2f, ph / 2f, stroke)

        // Icons and the open segment's name.
        val muted = themeColor(R.attr.themeTextSecondary, 0xFFB0B8C8.toInt())
        val seg = RectF()
        for (i in modes.indices) {
            segmentRect(i, startX, top, seg)
            val open = openness(i)
            val ink = ColorUtils.blendARGB(ColorUtils.setAlphaComponent(muted, 215), Color.WHITE, open)
            val iconCx = seg.left + collapsedW / 2f + (if (open > 0f) 1f * density * open else 0f)
            drawGlyph(canvas, modes[i], iconCx, seg.centerY(), ink)
            if (open > 0.02f) {
                labelPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, (255 * open * open).toInt())
                val textX = seg.left + collapsedW - 3f * density
                canvas.drawText(modes[i].label.uppercase(), textX, seg.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
            }
        }
    }

    private fun drawGlyph(canvas: Canvas, mode: ChatWorkMode, cx: Float, cy: Float, ink: Int) {
        val g = glyph
        stroke.color = ink
        stroke.strokeWidth = 1.5f * density
        glyphText.color = ink
        when (mode) {
            ChatWorkMode.ASK -> {
                glyphText.textSize = g * 1.2f
                canvas.drawText("?", cx, cy - (glyphText.ascent() + glyphText.descent()) / 2f, glyphText)
            }
            ChatWorkMode.DEBUG -> {
                val bw = g * 0.46f; val bh = g * 0.62f
                canvas.drawOval(cx - bw / 2f, cy - bh * 0.35f, cx + bw / 2f, cy + bh * 0.65f, stroke)
                canvas.drawCircle(cx, cy - bh * 0.5f, g * 0.17f, stroke)
                for (k in -1..1) {
                    val y = cy + k * g * 0.2f + g * 0.08f
                    canvas.drawLine(cx - bw / 2f, y, cx - bw / 2f - g * 0.3f, y + k * g * 0.12f, stroke)
                    canvas.drawLine(cx + bw / 2f, y, cx + bw / 2f + g * 0.3f, y + k * g * 0.12f, stroke)
                }
            }
            ChatWorkMode.CODE -> {
                glyphText.textSize = g * 0.92f
                canvas.drawText("</>", cx, cy - (glyphText.ascent() + glyphText.descent()) / 2f, glyphText)
            }
            ChatWorkMode.DEVELOP -> {
                path.reset()
                path.moveTo(cx + g * 0.12f, cy - g * 0.52f)
                path.lineTo(cx - g * 0.30f, cy + g * 0.06f)
                path.lineTo(cx - g * 0.02f, cy + g * 0.06f)
                path.lineTo(cx - g * 0.14f, cy + g * 0.52f)
                path.lineTo(cx + g * 0.32f, cy - g * 0.12f)
                path.lineTo(cx + g * 0.04f, cy - g * 0.12f)
                path.close()
                fill.color = ink
                canvas.drawPath(path, fill)
            }
        }
    }

    // ---- touch ----------------------------------------------------------------------------------------------

    private fun indexAt(x: Float): Int {
        val startX = width - totalWidth() - 1f * density
        var cursor = startX
        for (i in modes.indices) {
            val end = cursor + segmentWidth(i) + gap / 2f
            if (x < end) return i
            cursor = end + gap / 2f
        }
        return modes.size - 1
    }

    private fun pressTo(target: Float) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(press, target).apply {
            duration = if (target > press) 110 else 360
            interpolator = if (target > press) DecelerateInterpolator() else OvershootInterpolator(2.4f)
            addUpdateListener { press = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = indexAt(event.x)
                lastTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                pressTo(1f)
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressTo(0f)
                val up = indexAt(event.x)
                if (up == pressedIndex) { lastTouchX = event.x; performClick() }
                pressedIndex = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressTo(0f); pressedIndex = -1; return true }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        // A tap picks the segment under the finger; a click with no position (TalkBack, keyboard) moves to the next mode.
        val target = if (lastTouchX >= 0f) indexAt(lastTouchX) else (selected + 1) % modes.size
        lastTouchX = -1f
        super.performClick()
        if (target != selected) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            setMode(modes[target], animate = true)
            onModeSelected?.invoke(modes[target])
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Switch to the next mode"))
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); pressAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
