package com.jarvis.assistant.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvis.assistant.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Production-grade intro text sequence, drawn on one canvas (no per-frame allocation).
 *
 * Per slide: HUD corner brackets draw in, a tracked eyebrow collapses into place, a rule grows from the
 * centre, the title ignites letter by letter (rise + glow flare), a light sweep crosses it, then the
 * subtitle fades up and everything exits with a soft spread.
 *
 * Responsive: every size is derived from the usable window (minus system bars / cutout) and the title is
 * shrunk until its widest line and the whole block fit, so it holds on small phones, tall phones, foldables,
 * tablets, landscape and split-screen. Fonts are bundled (Orbitron + Rajdhani, SIL OFL).
 * Reduced motion (animator scale 0) shows each slide statically.
 */
class IntroTextSequenceView(context: Context) : View(context) {

    data class Slide(
        val eyebrow: String,
        val lines: List<String>,
        val subtitle: String,
        val accent: Int
    )

    var slides: List<Slide> = emptyList()

    private val density = resources.displayMetrics.density
    private val fontScale = resources.configuration.fontScale.coerceIn(1f, 1.15f)

    // ---- Timeline (ms) ---------------------------------------------------------------------------------
    private val slideMs = 3400f
    private val exitStart = 2900f
    private val reducedHoldMs = 1900f
    private val reducedShowT = 2700f

    private var index = 0
    private var slideElapsed = 0f
    private var lastFrameNanos = 0L
    private var running = false
    private var reduced = false
    private var onDone: (() -> Unit)? = null
    private var viewAlpha = 0f

    // ---- Fonts -----------------------------------------------------------------------------------------
    private val titleFace: Typeface = load(R.font.orbitron, Typeface.create("sans-serif-black", Typeface.BOLD))
    private val eyebrowFace: Typeface = load(R.font.rajdhani_semibold, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    private val subtitleFace: Typeface = load(R.font.rajdhani_medium, Typeface.create("sans-serif", Typeface.NORMAL))

    private fun load(res: Int, fallback: Typeface): Typeface =
        try { ResourcesCompat.getFont(context, res) ?: fallback } catch (_: Exception) { fallback }

    // ---- Paints ----------------------------------------------------------------------------------------
    private val bgPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowMatrix = Matrix()
    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scanMatrix = Matrix()
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Orbitron is a variable font (wght 400-900); pin the heavy cut for a bold HUD look.
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = titleFace; setFontVariationSettings("'wght' 800") }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = titleFace; setFontVariationSettings("'wght' 800") }
    private val eyebrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = eyebrowFace }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = subtitleFace }
    private val sweepMatrix = Matrix()
    private val bracketLines = FloatArray(32)

    private var bgShader: LinearGradient? = null
    private var glowShader: RadialGradient? = null
    private var sweepShader: LinearGradient? = null
    private var scanShader: LinearGradient? = null
    private var titleShader: LinearGradient? = null

    // ---- Cached layout (rebuilt on size / slide change only) -------------------------------------------
    private var layoutW = 0
    private var layoutH = 0
    private var layoutIndex = -1
    private var cx = 0f
    private var areaTop = 0f
    private var areaBottom = 0f
    private var eyebrowLines: List<String> = emptyList()
    private var subtitleLines: List<String> = emptyList()
    private var titleSize = 0f
    private var eyebrowSize = 0f
    private var subtitleSize = 0f
    private var eyebrowY = 0f
    private var ruleY = 0f
    private var titleBaseY = 0f
    private var titleLineH = 0f
    private var subtitleY = 0f
    private var subtitleLineH = 0f
    private var blockLeft = 0f
    private var blockRight = 0f
    private var charCount = 0
    private var titleLineWidths = FloatArray(0)
    private var titleCharWidths: Array<FloatArray> = emptyArray()
    private var titleTrackPx = 0f

    private class Mote(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float)

    private val motes: List<Mote> = Random(7).let { rnd ->
        List(38) {
            Mote(rnd.nextFloat(), rnd.nextFloat(), 0.6f + rnd.nextFloat() * 1.3f,
                0.012f + rnd.nextFloat() * 0.03f, rnd.nextFloat() * 6.2832f)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // ---- Control ---------------------------------------------------------------------------------------

    /** Plays all slides once; [done] runs when the last one ends (or on [skipAll]). */
    fun start(done: () -> Unit) {
        if (slides.isEmpty()) { done(); return }
        onDone = done
        reduced = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) { false }
        index = 0
        slideElapsed = 0f
        viewAlpha = 0f
        lastFrameNanos = 0L
        running = true
        announce()
        postOnAnimation(frame)
    }

    /** Tap: jump to the next slide (finishes the sequence on the last one). */
    fun next() {
        if (!running) return
        advance()
    }

    fun stop() {
        running = false
        removeCallbacks(frame)
    }

    private fun finish() {
        if (!running) return
        running = false
        val cb = onDone
        onDone = null
        cb?.invoke()
    }

    private fun advance() {
        if (index + 1 >= slides.size) { finish(); return }
        index++
        slideElapsed = 0f
        announce()
    }

    private fun announce() {
        val s = slides.getOrNull(index) ?: return
        contentDescription = (s.eyebrow + ". " + s.lines.joinToString(" ") + ". " + s.subtitle)
        announceForAccessibility(contentDescription)
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            // Clamp so a hitch (font load, GC) never skips letters of the reveal.
            val dt = if (lastFrameNanos == 0L) 16f else min(48f, (now - lastFrameNanos) / 1_000_000f)
            lastFrameNanos = now
            slideElapsed += dt
            if (viewAlpha < 1f) viewAlpha = min(1f, viewAlpha + dt / 300f)
            val limit = if (reduced) reducedHoldMs else slideMs
            if (slideElapsed >= limit) advance()
            invalidate()
            if (running) postOnAnimation(this)
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    // ---- Layout ----------------------------------------------------------------------------------------

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun usableInsets() {
        val ins = ViewCompat.getRootWindowInsets(this)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        areaTop = (ins?.top ?: 0).toFloat()
        areaBottom = height - (ins?.bottom ?: 0).toFloat()
    }

    private fun wrap(text: String, paint: Paint, maxW: Float): List<String> {
        if (paint.measureText(text) <= maxW) return listOf(text)
        val words = text.split(' ')
        val out = ArrayList<String>()
        var cur = ""
        for (w in words) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (cur.isNotEmpty() && paint.measureText(trial) > maxW) { out.add(cur); cur = w } else cur = trial
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun buildLayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        usableInsets()
        val slide = slides.getOrNull(index) ?: return
        layoutW = width; layoutH = height; layoutIndex = index
        cx = w / 2f

        val availW = w * 0.88f
        val availH = max(1f, (areaBottom - areaTop)) * 0.74f
        val minDim = min(w, h)

        // Title: start from a window-relative size, then shrink until widest line AND whole block fit.
        titlePaint.letterSpacing = 0.06f
        var size = min(minDim * 0.14f, sp(64f))
        val minTitle = sp(15f)
        val lines = slide.lines
        fun blockHeight(s: Float): Float {
            val eb = clampPx(s * 0.30f, sp(11f), sp(17f)) * fontScale
            val sb = clampPx(s * 0.36f, sp(13f), sp(20f)) * fontScale
            return eb * 1.3f + s * 0.6f + lines.size * s * 1.28f + s * 0.45f + sb * 1.5f * 2f
        }
        while (size > minTitle) {
            titlePaint.textSize = size
            val widest = lines.maxOf { titlePaint.measureText(it) }
            if (widest <= availW && blockHeight(size) <= availH) break
            size *= 0.95f
        }
        titleSize = max(size, minTitle)
        titlePaint.textSize = titleSize
        sweepPaint.textSize = titleSize
        sweepPaint.letterSpacing = 0.06f
        titleTrackPx = titlePaint.letterSpacing * titleSize

        eyebrowSize = clampPx(titleSize * 0.30f, sp(11f), sp(17f)) * fontScale
        subtitleSize = clampPx(titleSize * 0.36f, sp(13f), sp(20f)) * fontScale
        eyebrowPaint.textSize = eyebrowSize
        eyebrowPaint.letterSpacing = 0.34f
        subtitlePaint.textSize = subtitleSize
        subtitlePaint.letterSpacing = 0.05f
        // Small text: shrink toward a readable floor, then wrap to at most two lines.
        while (eyebrowPaint.textSize > sp(10f) && eyebrowPaint.measureText(slide.eyebrow) > availW) eyebrowPaint.textSize *= 0.95f
        while (subtitlePaint.textSize > sp(12f) && subtitlePaint.measureText(slide.subtitle) > availW) subtitlePaint.textSize *= 0.95f
        eyebrowSize = eyebrowPaint.textSize
        subtitleSize = subtitlePaint.textSize
        eyebrowLines = wrap(slide.eyebrow, eyebrowPaint, availW).take(2)
        subtitleLines = wrap(slide.subtitle, subtitlePaint, availW).take(2)

        // Per-character widths (letters are drawn individually for the stagger).
        titleCharWidths = Array(lines.size) { li -> FloatArray(lines[li].length) { ci -> titlePaint.measureText(lines[li][ci].toString()) } }
        titleLineWidths = FloatArray(lines.size) { li -> max(0f, titleCharWidths[li].sum() - titleTrackPx) }
        charCount = lines.sumOf { l -> l.count { it != ' ' } }
        blockLeft = cx - (titleLineWidths.maxOrNull() ?: 0f) / 2f
        blockRight = cx + (titleLineWidths.maxOrNull() ?: 0f) / 2f

        // Vertical stack, centred inside the usable area.
        titleLineH = titleSize * 1.28f
        subtitleLineH = subtitleSize * 1.32f
        val eyebrowH = eyebrowLines.size * eyebrowSize * 1.3f
        val ruleGap = titleSize * 0.32f
        val titleH = lines.size * titleLineH
        val subGap = titleSize * 0.32f
        val subH = subtitleLines.size * subtitleLineH
        val total = eyebrowH + ruleGap + titleH + subGap + subH
        var y = (areaTop + areaBottom) / 2f - total / 2f
        eyebrowY = y + eyebrowSize
        y += eyebrowH
        ruleY = y + ruleGap * 0.5f
        y += ruleGap
        titleBaseY = y + titleSize * 0.98f
        y += titleH + subGap
        subtitleY = y + subtitleSize

        // Shaders that depend on size / accent.
        val deep = Color.rgb(3, 6, 14)
        val navy = Color.rgb(10, 20, 44)
        bgShader = LinearGradient(0f, 0f, 0f, h, intArrayOf(deep, navy, deep), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        bgPaint.shader = bgShader
        glowShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(120, Color.red(slide.accent), Color.green(slide.accent), Color.blue(slide.accent)),
                Color.argb(40, Color.red(slide.accent), Color.green(slide.accent), Color.blue(slide.accent)),
                Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint.shader = glowShader
        val light = blend(Color.WHITE, slide.accent, 0.38f)
        titleShader = LinearGradient(
            0f, titleBaseY - titleSize, 0f, titleBaseY + (lines.size - 1) * titleLineH,
            intArrayOf(Color.WHITE, light), null, Shader.TileMode.CLAMP
        )
        titlePaint.shader = titleShader
        val bw = max(1f, (blockRight - blockLeft) * 0.28f)
        sweepShader = LinearGradient(
            0f, 0f, bw, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(235, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        sweepPaint.shader = sweepShader
        scanShader = LinearGradient(
            0f, 0f, 0f, 1f,
            intArrayOf(Color.TRANSPARENT, Color.argb(60, Color.red(slide.accent), Color.green(slide.accent), Color.blue(slide.accent)), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        scanPaint.shader = scanShader
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutIndex = -1 // force rebuild (rotation, split-screen, fold)
    }

    // ---- Drawing ---------------------------------------------------------------------------------------

    private fun seg(t: Float, from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)
    private fun easeOut(p: Float): Float = 1f - (1f - p).pow(3)
    private fun easeIn(p: Float): Float = p * p * p
    private fun easeInOut(p: Float): Float = if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).pow(3) / 2f
    private fun clampPx(v: Float, lo: Float, hi: Float) = v.coerceIn(lo, hi)
    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )
    private fun alphaOf(c: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255f).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    override fun onDraw(canvas: Canvas) {
        val slide = slides.getOrNull(index) ?: return
        if (layoutIndex != index || layoutW != width || layoutH != height) buildLayout()
        if (titleShader == null) return

        val w = width.toFloat()
        val h = height.toFloat()
        val t = if (reduced) reducedShowT else slideElapsed
        val exit = easeIn(seg(t, exitStart, slideMs))
        val vis = (1f - exit) * viewAlpha
        val accent = slide.accent
        val minDim = min(w, h)

        // Backdrop (opaque, so the white overlay beneath never flashes through).
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Breathing accent glow behind the text block.
        val glowR = minDim * 0.78f * (1f + 0.05f * sin(t / 520f))
        glowMatrix.reset()
        glowMatrix.setScale(glowR, glowR)
        glowMatrix.postTranslate(cx, (areaTop + areaBottom) / 2f)
        glowShader?.setLocalMatrix(glowMatrix)
        glowPaint.alpha = (255f * easeOut(seg(t, 0f, 700f)) * vis).toInt()
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        // Dust motes drifting upward.
        for (m in motes) {
            val yy = ((m.y - (t / 1000f) * m.speed) % 1f + 1f) % 1f
            val tw = 0.5f + 0.5f * sin(t / 400f + m.phase)
            dustPaint.color = alphaOf(blend(Color.WHITE, accent, 0.5f), 0.55f * tw * vis)
            canvas.drawCircle(m.x * w, yy * h, m.r * density, dustPaint)
        }

        // Scan line sweeping down once per slide.
        val scanP = seg(t, 0f, 1500f)
        if (scanP in 0.001f..0.999f) {
            val band = minDim * 0.22f
            scanMatrix.reset()
            scanMatrix.setScale(1f, band)
            scanMatrix.postTranslate(0f, -band + (h + band) * easeInOut(scanP))
            scanShader?.setLocalMatrix(scanMatrix)
            scanPaint.alpha = (255f * vis).toInt()
            canvas.drawRect(0f, -band + (h + band) * easeInOut(scanP), w, (h + band) * easeInOut(scanP), scanPaint)
        }

        drawBrackets(canvas, w, h, accent, t, vis)
        drawEyebrow(canvas, accent, t, vis)

        // Rule growing from the centre.
        val ruleP = easeOut(seg(t, 250f, 950f))
        if (ruleP > 0f) {
            val half = min(minDim * 0.16f, (blockRight - blockLeft) / 2f + 8f * density) * ruleP
            rulePaint.strokeWidth = max(1f, 1.6f * density)
            rulePaint.color = alphaOf(accent, 0.95f * vis)
            canvas.drawLine(cx - half, ruleY, cx + half, ruleY, rulePaint)
            dotPaint.color = alphaOf(Color.WHITE, vis * ruleP)
            canvas.drawCircle(cx, ruleY, 2.2f * density * ruleP, dotPaint)
        }

        drawTitle(canvas, slide, t, exit, vis)
        drawSubtitle(canvas, t, vis)
        drawProgress(canvas, w, accent, vis)
    }

    private fun drawBrackets(canvas: Canvas, w: Float, h: Float, accent: Int, t: Float, vis: Float) {
        val p = easeOut(seg(t, 0f, 800f))
        if (p <= 0f) return
        val inset = max(20f * density, min(w, h) * 0.06f)
        val l = inset
        val top = max(inset, areaTop + 12f * density)
        val bottom = min(h - inset, areaBottom - 12f * density)
        val len = min(w, h) * 0.09f * p
        val r = w - inset
        var i = 0
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            bracketLines[i++] = x1; bracketLines[i++] = y1; bracketLines[i++] = x2; bracketLines[i++] = y2
        }
        line(l, top, l + len, top); line(l, top, l, top + len)
        line(r, top, r - len, top); line(r, top, r, top + len)
        line(l, bottom, l + len, bottom); line(l, bottom, l, bottom - len)
        line(r, bottom, r - len, bottom); line(r, bottom, r, bottom - len)
        bracketPaint.strokeWidth = max(1f, 1.4f * density)
        bracketPaint.color = alphaOf(accent, 0.6f * vis)
        canvas.drawLines(bracketLines, bracketPaint)
    }

    private fun drawEyebrow(canvas: Canvas, accent: Int, t: Float, vis: Float) {
        val p = easeOut(seg(t, 100f, 800f))
        if (p <= 0f) return
        // Tracking collapses from wide to final: the signature "boot" feel.
        eyebrowPaint.letterSpacing = 0.34f + (1f - p) * 0.5f
        eyebrowPaint.color = alphaOf(blend(Color.WHITE, accent, 0.55f), p * vis)
        eyebrowPaint.setShadowLayer(eyebrowSize * 0.6f, 0f, 0f, alphaOf(accent, 0.7f * p * vis))
        eyebrowPaint.textAlign = Paint.Align.CENTER
        var y = eyebrowY + (1f - p) * eyebrowSize * 0.5f
        for (line in eyebrowLines) {
            // CENTER alignment includes trailing tracking; nudge by half of it so the line stays optically centred.
            canvas.drawText(line, cx + eyebrowPaint.letterSpacing * eyebrowSize / 2f, y, eyebrowPaint)
            y += eyebrowSize * 1.3f
        }
    }

    private fun drawTitle(canvas: Canvas, slide: Slide, t: Float, exit: Float, vis: Float) {
        val stagger = if (charCount > 1) min(60f, 720f / (charCount - 1)) else 0f
        val charDur = 560f
        val titleStart = 380f
        val sweepP = seg(t, 1350f, 2250f)
        val accent = slide.accent
        titlePaint.textAlign = Paint.Align.LEFT
        sweepPaint.textAlign = Paint.Align.LEFT
        var g = 0
        for ((li, line) in slide.lines.withIndex()) {
            var x = cx - titleLineWidths[li] / 2f
            val y = titleBaseY + li * titleLineH
            for (ci in line.indices) {
                val ch = line[ci]
                val adv = titleCharWidths[li][ci]
                if (ch != ' ') {
                    val p = seg(t, titleStart + g * stagger, titleStart + g * stagger + charDur)
                    g++
                    if (p > 0f) {
                        val e = easeOut(p)
                        val spread = (x + adv / 2f - cx) * exit * 0.05f
                        val dy = (1f - e) * titleSize * 0.42f - exit * titleSize * 0.12f
                        val a = e * (1f - exit) * viewAlpha
                        // Flare: starts hot-white with a large glow, settles to the resting gradient.
                        val flare = (1f - p).pow(2)
                        titlePaint.alpha = (255f * a).toInt().coerceIn(0, 255)
                        titlePaint.setShadowLayer(
                            titleSize * (0.22f + 0.5f * flare),
                            0f, 0f, alphaOf(blend(accent, Color.WHITE, flare * 0.6f), (0.55f + 0.4f * flare) * a)
                        )
                        canvas.drawText(ch.toString(), x + spread, y + dy, titlePaint)
                    }
                }
                x += adv
            }
        }
        // Light sweep across the settled title.
        if (sweepP in 0.001f..0.999f) {
            val bw = max(1f, (blockRight - blockLeft) * 0.28f)
            val travel = (blockRight - blockLeft) + bw * 2f
            sweepMatrix.reset()
            sweepMatrix.setTranslate(blockLeft - bw + travel * easeInOut(sweepP) - bw / 2f, 0f)
            sweepShader?.setLocalMatrix(sweepMatrix)
            sweepPaint.clearShadowLayer()
            sweepPaint.alpha = (255f * (1f - exit) * viewAlpha).toInt().coerceIn(0, 255)
            for ((li, line) in slide.lines.withIndex()) {
                var x = cx - titleLineWidths[li] / 2f
                val y = titleBaseY + li * titleLineH
                for (ci in line.indices) {
                    if (line[ci] != ' ') canvas.drawText(line[ci].toString(), x, y, sweepPaint)
                    x += titleCharWidths[li][ci]
                }
            }
        }
    }

    private fun drawSubtitle(canvas: Canvas, t: Float, vis: Float) {
        val p = easeOut(seg(t, 1100f, 1800f))
        if (p <= 0f) return
        subtitlePaint.textAlign = Paint.Align.CENTER
        subtitlePaint.color = alphaOf(Color.rgb(176, 196, 224), p * vis)
        subtitlePaint.setShadowLayer(subtitleSize * 0.4f, 0f, 0f, alphaOf(Color.rgb(120, 180, 255), 0.35f * p * vis))
        var y = subtitleY + (1f - p) * subtitleSize * 0.8f
        for (line in subtitleLines) {
            canvas.drawText(line, cx, y, subtitlePaint)
            y += subtitleLineH
        }
    }

    private fun drawProgress(canvas: Canvas, w: Float, accent: Int, vis: Float) {
        val n = slides.size
        if (n < 2) return
        val dashW = 22f * density
        val gap = 8f * density
        val total = n * dashW + (n - 1) * gap
        var x = (w - total) / 2f
        val y = areaBottom - max(36f * density, height * 0.06f)
        rulePaint.strokeWidth = max(1f, 2f * density)
        for (i in 0 until n) {
            val active = i == index
            rulePaint.color = alphaOf(if (active) accent else Color.WHITE, (if (active) 0.95f else 0.22f) * vis)
            canvas.drawLine(x, y, x + dashW, y, rulePaint)
            x += dashW + gap
        }
    }
}
