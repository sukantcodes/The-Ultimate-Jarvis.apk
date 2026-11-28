package com.jarvis.assistant.ui.studyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.study.BoardBlock
import com.jarvis.assistant.study.DrawOp
import com.jarvis.assistant.study.Ink
import com.jarvis.assistant.study.StepKind
import com.jarvis.assistant.study.StudyPlayback
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The studio whiteboard canvas. Draws laid-out vector blocks in a 1000-unit coordinate space, renders subtle
 * blueprint dot-grid paper, supports pinch/double-tap zoom (including Fit-All overview zoom < 1x with horizontal
 * centring), keeps active diagrams/strokes visible above floating HUD overlays, and notifies the chrome smoothly.
 */
class StudyBoardView(context: Context) : View(context) {

    interface Host {
        fun onBlockTapped(index: Int)
        fun onBlockLongPressed(index: Int, lineText: String, fullBlockText: String)
        fun onUserPanned()
    }

    companion object {
        const val BOARD_WIDTH = 1000f
        const val MARGIN = 30f
        const val MIN_OVERVIEW_ZOOM = 0.32f
        const val MAX_ZOOM = 4f
    }

    var host: Host? = null
    /** Notifies UI chrome when zoom scale, follow-pen state, or offscreen-overflow status changes. */
    var onViewportChanged: ((zoom: Float, followPen: Boolean, hasOverflow: Boolean) -> Unit)? = null

    private val fonts = StudyFonts(context)
    private val painter = StudyPainter(fonts)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var selectionHighlightRect: RectF? = null

    private var blocks: List<BoardBlock> = emptyList()
    private var playback: StudyPlayback? = null
    var showNotes: Boolean = true
    var showPen: Boolean = true
        set(v) { field = v; invalidate() }
    var selectedBlock: Int = -1
        set(v) { field = v; invalidate() }
    var bookmarkedBlocks: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }
    var followPen: Boolean = true
        set(v) {
            if (field != v) {
                field = v
                notifyViewport(force = true)
            }
        }

    /** Bottom height reserved for floating Zoom / Fit overlay pills inside the frame. */
    var safeBottomPx: Float = resources.displayMetrics.density * 72f
        set(v) {
            val clamped = max(0f, v)
            if (abs(field - clamped) >= 1f) {
                field = clamped
                clampPan()
                invalidate()
                notifyViewport(force = true)
            }
        }

    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastReportedZoom = -1f
    private var lastReportedFollow = true
    private var lastReportedOverflow = false

    // Erase / restore fade animation
    private var limit = Int.MAX_VALUE
    private var animFrom = 0
    private var animTo = 0
    private var animAlpha = 1f
    private var animating = false
    private var animator: ValueAnimator? = null
    private var zoomAnimator: ValueAnimator? = null
    private var autoFittedFinishForCount = -1

    private var ticking = false
    private var lastTick = 0L
    private var pulsePhase = 0f
    private var cachedThemeAccent = 0
    private var paperAccent = StudyPalette.color(Ink.ACCENT)

    private var narratingActive = false
    private var narratingBlockIndex = -1
    private var narratingFraction = 0f
    private var narratingTranscript = ""
    private var lastLiveFocusTop: Float? = null
    private var lastLiveFocusBottom: Float? = null
    private var lastLiveFocusY: Float? = null

    fun setNarrationSpotlight(active: Boolean, blockIndex: Int, progressFrac: Float, transcript: String) {
        val clamped = progressFrac.coerceIn(0f, 1f)
        val changed = narratingActive != active ||
            narratingBlockIndex != blockIndex ||
            abs(narratingFraction - clamped) >= 0.01f ||
            narratingTranscript != transcript
        if (active && !narratingActive) {
            autoFittedFinishForCount = -1
        }
        narratingActive = active
        narratingBlockIndex = blockIndex
        narratingFraction = clamped
        narratingTranscript = transcript
        if (!active) {
            lastLiveFocusTop = null
            lastLiveFocusBottom = null
            lastLiveFocusY = null
        }
        if (changed) invalidate()
        if (active) kick()
    }

    init {
        setBackgroundColor(StudyPalette.PAPER)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, resources.displayMetrics.density * 22f)
            }
        }
        contentDescription = "Study whiteboard"
        refreshPaperAccent()
    }

    private fun refreshPaperAccent(): Int {
        val raw = runCatching { ThemeManager.getThemeAccentColor() }.getOrDefault(StudyPalette.color(Ink.ACCENT))
        if (raw == cachedThemeAccent) return paperAccent
        cachedThemeAccent = raw
        val hsv = FloatArray(3)
        Color.colorToHSV(raw, hsv)
        if (hsv[1] > 0.12f) hsv[1] = hsv[1].coerceAtLeast(0.68f)
        if (hsv[2] > 0.62f) hsv[2] = 0.62f
        val candidate = Color.HSVToColor(hsv)
        paperAccent = if (ColorUtils.calculateContrast(candidate, StudyPalette.PAPER) >= 4.2) candidate else StudyPalette.color(Ink.ACCENT)
        return paperAccent
    }

    private fun paperWarningColor(): Int {
        val raw = runCatching { ThemeManager.getThemeWarningColor() }.getOrDefault(StudyPalette.color(Ink.WARN))
        val hsv = FloatArray(3)
        Color.colorToHSV(raw, hsv)
        if (hsv[2] > 0.66f) hsv[2] = 0.66f
        if (hsv[1] > 0.12f) hsv[1] = hsv[1].coerceAtLeast(0.75f)
        return Color.HSVToColor(hsv)
    }

    private fun baseScale(): Float = if (width > 0) width / BOARD_WIDTH else 1f
    private fun scale(): Float = baseScale() * zoom
    private fun availHeight(): Float = (height - safeBottomPx).coerceAtLeast(height * 0.45f)
    private fun contentHeight(): Float = (blocks.lastOrNull()?.bottom ?: 0f) + 48f

    fun fitAllZoom(): Float {
        if (width <= 0 || height <= 0 || blocks.isEmpty()) return 1f
        val ch = contentHeight()
        if (ch <= 0f) return 1f
        val bs = baseScale()
        val desiredScale = min(bs, (availHeight() - resources.displayMetrics.density * 12f) / ch)
        return (desiredScale / bs).coerceIn(MIN_OVERVIEW_ZOOM, 1f)
    }

    private fun minAllowedZoom(): Float = min(1f, fitAllZoom())

    private fun hasOverflow(): Boolean {
        if (width <= 0 || height <= 0 || blocks.isEmpty()) return false
        val s = scale()
        val ch = contentHeight()
        return ch * s > availHeight() + resources.displayMetrics.density * 8f || BOARD_WIDTH * s > width + 4f
    }

    private fun notifyViewport(force: Boolean = false) {
        val ov = hasOverflow()
        if (force || abs(zoom - lastReportedZoom) >= 0.02f || followPen != lastReportedFollow || ov != lastReportedOverflow) {
            lastReportedZoom = zoom
            lastReportedFollow = followPen
            lastReportedOverflow = ov
            onViewportChanged?.invoke(zoom, followPen, ov)
        }
    }

    fun bind(playback: StudyPlayback, blocks: List<BoardBlock>, eraseLimit: Int) {
        this.playback = playback
        this.blocks = blocks
        this.limit = eraseLimit
        autoFittedFinishForCount = -1
        clampPan()
        invalidate()
        notifyViewport(true)
        kick()
    }

    fun setBlocks(list: List<BoardBlock>) {
        val cleared = list.isEmpty() && blocks.isNotEmpty()
        blocks = list
        if (cleared) {
            zoom = 1f
            tx = 0f
            ty = 0f
            autoFittedFinishForCount = -1
        }
        clampPan()
        invalidate()
        notifyViewport(true)
        kick()
    }

    fun kick() {
        if (ticking || !isAttachedToWindow) return
        ticking = true
        lastTick = System.nanoTime()
        postOnAnimation(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = ((now - lastTick) / 1_000_000L).coerceIn(0L, 64L)
            lastTick = now
            val pb = playback
            var changed = false
            if (pb != null && pb.advance(dt)) changed = true
            if (followPen) changed = follow(dt) || changed
            if (narratingActive || pb?.state == StudyPlayback.State.PLAYING) {
                autoFittedFinishForCount = -1
                pulsePhase += dt * 0.006f
                changed = true
            }
            if (pb != null && pb.state == StudyPlayback.State.FINISHED && !narratingActive && followPen && blocks.isNotEmpty() && autoFittedFinishForCount != blocks.size) {
                autoFittedFinishForCount = blocks.size
                val fitZ = fitAllZoom()
                if (fitZ in 0.72f..0.985f && abs(zoom - fitZ) > 0.02f) {
                    fitToBoard()
                }
            }
            if (changed || animating) invalidate()
            val moving = pb?.state == StudyPlayback.State.PLAYING || animating || narratingActive
            if (moving && isAttachedToWindow) postOnAnimation(this) else ticking = false
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); refreshPaperAccent(); kick() }
    override fun onDetachedFromWindow() {
        animator?.cancel()
        zoomAnimator?.cancel()
        ticking = false
        super.onDetachedFromWindow()
    }

    // ----- erase / redo -------------------------------------------------------------------------------------

    fun setEraseLimit(newLimit: Int) {
        if (newLimit == limit) return
        animator?.cancel()
        val shown = min(limit, blocks.size)
        val target = min(newLimit, blocks.size)
        if (target == shown) { limit = newLimit; invalidate(); return }
        val erasing = target < shown
        animFrom = min(shown, target)
        animTo = max(shown, target)
        animating = true
        limit = animTo
        animator = ValueAnimator.ofFloat(if (erasing) 1f else 0f, if (erasing) 0f else 1f).apply {
            duration = 260L
            addUpdateListener { animAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    animating = false; limit = newLimit; clampPan(); invalidate(); notifyViewport(true)
                }
            })
            start()
        }
        kick()
    }

    // ----- drawing --------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = scale()
        if (s <= 0f || width == 0 || height == 0) return
        val accent = refreshPaperAccent()

        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(s, s)

        val viewTop = (-ty / s).coerceAtLeast(0f)
        val viewBottom = ((height - ty) / s).coerceAtLeast(viewTop)

        selectionHighlightRect?.let { selRect ->
            selectionPaint.color = ColorUtils.setAlphaComponent(accent, 42)
            canvas.drawRoundRect(selRect, 10f, 10f, selectionPaint)
        }

        val pb = playback
        if (pb != null) {
            val cur = pb.current
            val finished = pb.state == StudyPlayback.State.FINISHED
            var tipDrawn = false
            var foundFocus: StudyPainter.HighlightFocus? = null
            val activeSpotlightIdx = when {
                narratingActive && narratingBlockIndex in blocks.indices -> narratingBlockIndex
                pb.state == StudyPlayback.State.PLAYING && cur in blocks.indices -> cur
                else -> -1
            }
            val shown = min(limit, blocks.size)
            for (i in 0 until shown) {
                val b = blocks[i]
                if (b.bottom < viewTop - 50f || b.top > viewBottom + 50f) continue
                val alreadyPrintedReplay = pb.othersStayVisible && i == cur && i <= pb.highWater
                val fraction: Float = when {
                    pb.state == StudyPlayback.State.IDLE -> 0f
                    finished -> 1f
                    alreadyPrintedReplay -> 1f
                    i < cur -> 1f
                    i == cur -> pb.progress
                    pb.othersStayVisible && i <= pb.highWater -> 1f
                    else -> 0f
                }
                if (fraction <= 0f) continue
                var alpha = 255
                if (animating && i >= animFrom && i < animTo) alpha = (255 * animAlpha).toInt()
                if (!b.isQuestion) {
                    when {
                        b.isFinal -> painter.drawBlockBackground(canvas, b, BOARD_WIDTH, MARGIN, StudyPalette.color(Ink.RESULT), 22 * alpha / 255)
                        b.kind == StepKind.CODE -> painter.drawBlockBackground(canvas, b, BOARD_WIDTH, MARGIN, StudyPalette.color(Ink.TEAL), 12 * alpha / 255)
                    }
                }
                val explainFrac = when {
                    narratingActive && narratingBlockIndex == i -> narratingFraction.coerceIn(0.02f, 1f)
                    alreadyPrintedReplay && pb.progress > 0f -> pb.progress.coerceIn(0.02f, 1f)
                    pb.progress in 0.01f..0.99f -> pb.progress
                    else -> 0.92f
                }
                if (i == activeSpotlightIdx) {
                    painter.drawActiveStepSpotlight(canvas, b, BOARD_WIDTH, MARGIN, accent, pulsePhase)
                    if (fraction < 1f) {
                        foundFocus = painter.drawWritingParagraphHighlight(
                            canvas, b, showNotes, BOARD_WIDTH, MARGIN, fraction, accent, pulsePhase
                        )
                    } else if (narratingActive || alreadyPrintedReplay) {
                        foundFocus = painter.drawLiveParagraphFocus(
                            canvas, b, showNotes, BOARD_WIDTH, MARGIN, explainFrac, narratingTranscript, accent, pulsePhase
                        )
                    }
                }
                val ops = b.allOps(showNotes)
                painter.drawOps(canvas, ops, fraction, alpha)
                if (i == activeSpotlightIdx) {
                    if (fraction < 1f) {
                        if (showPen && painter.tipVisible && !tipDrawn) {
                            val activeColor = if (painter.tipInk == Ink.INK) accent else StudyPalette.color(painter.tipInk)
                            painter.drawPen(canvas, painter.tipX, painter.tipY, activeColor, pulsePhase)
                            if (foundFocus == null) {
                                foundFocus = StudyPainter.HighlightFocus(
                                    (painter.tipY - 28f).coerceAtLeast(b.top),
                                    (painter.tipY + 28f).coerceAtMost(b.bottom),
                                    painter.tipY
                                )
                            }
                            tipDrawn = true
                        }
                    } else if (narratingActive || alreadyPrintedReplay) {
                        painter.drawLiveDiagramBeacon(canvas, b, showNotes, explainFrac, narratingTranscript, accent, pulsePhase)
                    }
                }
                if (i == selectedBlock) painter.drawSelection(canvas, b, BOARD_WIDTH, MARGIN, accent)
                if (!b.isQuestion && bookmarkedBlocks.contains(b.stepIndex) && fraction >= 1f) {
                    painter.drawBookmark(canvas, b, BOARD_WIDTH, MARGIN, paperWarningColor())
                }
            }
            if (foundFocus == null && activeSpotlightIdx in blocks.indices) {
                val activeBlk = blocks[activeSpotlightIdx]
                val pFrac = (if (narratingActive && narratingBlockIndex == activeSpotlightIdx) narratingFraction else pb.progress).coerceIn(0.15f, 0.85f)
                foundFocus = StudyPainter.HighlightFocus(activeBlk.top, activeBlk.bottom, activeBlk.top + activeBlk.height * pFrac)
            }
            lastLiveFocusTop = foundFocus?.topY
            lastLiveFocusBottom = foundFocus?.bottomY
            lastLiveFocusY = foundFocus?.centerY
        }
        canvas.restore()
    }

    private fun sqrtScale(s: Float): Float = if (s > 1f) kotlin.math.sqrt(s) else 1f

    fun clearTextSelectionHighlight() {
        if (selectionHighlightRect != null) {
            selectionHighlightRect = null
            invalidate()
        }
    }

    fun scrollToTop() {
        followPen = false
        ty = 0f
        clampPan()
        invalidate()
        notifyViewport(true)
    }

    private fun extractLineHit(block: BoardBlock, bx: Float, by: Float): Pair<String, RectF> {
        val glyphs = block.allOps(showNotes).filterIsInstance<DrawOp.Glyphs>()
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.y }, { it.x }))
        if (glyphs.isEmpty()) {
            return block.reconstructPlainText(showNotes) to RectF(MARGIN - 8f, block.top, BOARD_WIDTH - MARGIN + 8f, block.bottom - 12f)
        }
        val lines = ArrayList<MutableList<DrawOp.Glyphs>>()
        var cur = ArrayList<DrawOp.Glyphs>()
        var anchorY = glyphs.first().y
        var tol = (glyphs.first().size * 0.48f).coerceAtLeast(10f)
        for (g in glyphs) {
            if (abs(g.y - anchorY) <= tol) {
                cur.add(g)
            } else {
                lines.add(cur)
                cur = arrayListOf(g)
                anchorY = g.y
                tol = (g.size * 0.48f).coerceAtLeast(10f)
            }
        }
        if (cur.isNotEmpty()) lines.add(cur)

        val chosen = lines.minByOrNull { line ->
            val midY = line.map { it.y - it.size * 0.3f }.average().toFloat()
            val minX = line.minOf { it.x }
            val maxX = line.maxOf { it.x + it.text.length * it.size * 0.55f }
            val dx = if (bx < minX) minX - bx else if (bx > maxX) bx - maxX else 0f
            abs(by - midY) + dx * 0.25f
        } ?: return block.reconstructPlainText(showNotes) to RectF(MARGIN - 8f, block.top, BOARD_WIDTH - MARGIN + 8f, block.bottom - 12f)

        val sorted = chosen.sortedBy { it.x }
        val text = sorted.joinToString(" ") { it.text.trim() }.trim()
        val maxFs = sorted.maxOf { it.size }
        val minY = sorted.minOf { it.y - it.size * 0.95f }
        val maxY = sorted.maxOf { it.y + it.size * 0.35f }
        val minX = (sorted.minOf { it.x } - 8f).coerceAtLeast(MARGIN - 12f)
        val maxX = (sorted.maxOf { it.x + it.text.length * it.size * 0.56f } + 8f).coerceAtMost(BOARD_WIDTH - MARGIN + 12f)
        return text to RectF(minX, minY - maxFs * 0.08f, max(maxX, minX + 40f), maxY + maxFs * 0.08f)
    }

    // ----- pan / zoom / visibility guarantees -------------------------------------------------------------------

    private fun clampPan() {
        if (width <= 0 || height <= 0) return
        val minZ = minAllowedZoom()
        if (zoom < minZ) zoom = minZ
        if (zoom > MAX_ZOOM) zoom = MAX_ZOOM
        val s = scale()
        val bwPx = BOARD_WIDTH * s
        tx = if (bwPx <= width + 0.5f) {
            (width - bwPx) * 0.5f
        } else {
            tx.coerceIn(width - bwPx, 0f)
        }
        val avail = availHeight()
        val chPx = contentHeight() * s
        ty = if (chPx <= avail) {
            0f
        } else {
            val minTy = height - safeBottomPx - chPx
            ty.coerceIn(minTy, 0f)
        }
    }

    private var followSettling = false

    /** Keeps the active diagram, step, stylus tip, or highlighted paragraph safely centered in the visible board area. */
    private fun follow(dtMs: Long = 16L): Boolean {
        val pb = playback ?: return false
        val targetBlockIdx = when {
            narratingActive && narratingBlockIndex in blocks.indices -> narratingBlockIndex
            pb.state == StudyPlayback.State.PLAYING && pb.current in blocks.indices -> pb.current
            else -> return false
        }
        if (height == 0 || width == 0) return false
        val b = blocks[targetBlockIdx]
        val bs = baseScale()
        val avail = availHeight()

        val beforeZoom = zoom
        val ease = (dtMs / 105f).coerceIn(0.08f, 0.24f)
        // Keep active teaching text crisp at 1x zoom; fit tall diagrams comfortably in viewport
        if (b.kind == StepKind.DIAGRAM && b.height * bs > avail * 0.92f) {
            val desiredZoom = ((avail * 0.92f) / (b.height * bs)).coerceIn(minAllowedZoom(), 1f)
            if (zoom > desiredZoom + 0.015f) {
                zoom += (desiredZoom - zoom) * ease
            }
        } else if (zoom < 0.96f) {
            zoom += (1f - zoom) * ease
        }

        val s = scale()
        val hiTop = lastLiveFocusTop?.takeIf { it in (b.top - 16f)..b.bottom } ?: b.top
        val hiBot = lastLiveFocusBottom?.takeIf { it in b.top..(b.bottom + 16f) } ?: b.bottom
        val hiMid = lastLiveFocusY?.takeIf { it in (b.top - 16f)..(b.bottom + 16f) } ?: ((hiTop + hiBot) * 0.5f)
        val hiSpanPx = (hiBot - hiTop).coerceAtLeast(24f) * s
        val topPad = avail * 0.12f
        val botPad = avail * 0.74f
        val hiTopPx = hiTop * s + ty
        val hiBotPx = hiBot * s + ty
        val beforeTy = ty

        if (hiSpanPx <= botPad - topPad) {
            val idealCenterY = avail * 0.42f
            val desiredTy = -(hiMid * s - idealCenterY)
            if (hiTopPx < topPad || hiBotPx > botPad) followSettling = true
            if (followSettling) {
                val delta = desiredTy - ty
                if (abs(delta) <= avail * 0.025f) {
                    followSettling = false
                } else {
                    ty += delta * ease
                }
            }
        } else {
            val focusMidPx = hiMid * s + ty
            val desiredTy = -(hiMid * s - avail * 0.42f)
            if (focusMidPx < avail * 0.16f || focusMidPx > avail * 0.68f) followSettling = true
            if (followSettling) {
                val delta = desiredTy - ty
                if (abs(delta) <= avail * 0.025f) {
                    followSettling = false
                } else {
                    ty += delta * ease
                }
            }
        }
        clampPan()
        notifyViewport()
        return abs(beforeTy - ty) > 0.2f || abs(beforeZoom - zoom) > 0.002f
    }

    /** Immediately follows and scrolls cleanly onto whichever block or paragraph is currently highlighted. */
    fun scrollToActive() {
        followPen = true
        autoFittedFinishForCount = -1
        val pb = playback
        val activeIdx = when {
            narratingActive && narratingBlockIndex in blocks.indices -> narratingBlockIndex
            pb != null && pb.current in blocks.indices -> pb.current
            else -> -1
        }
        if (activeIdx in blocks.indices && height > 0 && width > 0) {
            val b = blocks[activeIdx]
            if (b.kind != StepKind.DIAGRAM && zoom < 0.98f) zoom = 1f
            val s = scale()
            val focusY = lastLiveFocusY?.takeIf { it in b.top..b.bottom } ?: (b.top + b.height * 0.45f)
            ty = -(focusY * s - availHeight() * 0.42f)
            clampPan()
            invalidate()
            notifyViewport(true)
            kick()
        } else {
            scrollToEnd()
        }
    }

    fun reveal(index: Int) {
        if (index !in blocks.indices || height == 0) return
        val s = scale()
        val avail = availHeight()
        val b = blocks[index]
        val top = b.top * s + ty
        val bottom = b.bottom * s + ty
        if (top >= 0 && bottom <= avail) return
        val target = -(b.top * s - avail * 0.18f)
        ValueAnimator.ofFloat(ty, target).apply {
            duration = 320L
            addUpdateListener { ty = it.animatedValue as Float; clampPan(); invalidate() }
            start()
        }
    }

    fun scrollToEnd() {
        followPen = true
        val s = scale()
        val avail = availHeight()
        ty = if (contentHeight() * s <= avail) 0f else (height - safeBottomPx - contentHeight() * s)
        clampPan()
        invalidate()
        notifyViewport(true)
    }

    /** Smoothly fits all written content inside the visible unobstructed whiteboard viewport. */
    fun fitToBoard() {
        if (width == 0 || height == 0 || blocks.isEmpty()) return
        val targetZ = fitAllZoom()
        animateZoomTo(width / 2f, 0f, targetZ, forceCenter = true)
    }

    fun zoomBy(factor: Float) = animateZoomTo(width / 2f, availHeight() / 2f, (zoom * factor).coerceIn(minAllowedZoom(), MAX_ZOOM))

    fun resetZoom() {
        if (width == 0 || height == 0) {
            zoom = 1f; tx = 0f; clampPan(); invalidate(); notifyViewport(true)
            return
        }
        animateZoomTo(width / 2f, 0f, 1f)
    }

    private fun animateZoomTo(fx: Float, fy: Float, targetZoom: Float, forceCenter: Boolean = false) {
        zoomAnimator?.cancel()
        val startZoom = zoom
        val startTx = tx
        val startTy = ty
        val endZoom = targetZoom.coerceIn(minAllowedZoom(), MAX_ZOOM)
        if (abs(endZoom - startZoom) < 0.005f && !forceCenter) {
            clampPan(); invalidate(); notifyViewport(true)
            return
        }
        val real = endZoom / startZoom
        val endScale = baseScale() * endZoom
        val endTx = if (BOARD_WIDTH * endScale <= width + 0.5f) (width - BOARD_WIDTH * endScale) * 0.5f else fx - (fx - startTx) * real
        val endTy = if (forceCenter || contentHeight() * endScale <= availHeight()) 0f else fy - (fy - startTy) * real
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                zoom = startZoom + (endZoom - startZoom) * p
                tx = startTx + (endTx - startTx) * p
                ty = startTy + (endTy - startTy) * p
                clampPan()
                invalidate()
                notifyViewport(p >= 1f)
            }
            start()
        }
    }

    private fun zoomAround(fx: Float, fy: Float, factor: Float) {
        val newZoom = (zoom * factor).coerceIn(minAllowedZoom(), MAX_ZOOM)
        val real = newZoom / zoom
        tx = fx - (fx - tx) * real
        ty = fy - (fy - ty) * real
        zoom = newZoom
        clampPan()
        invalidate()
        notifyViewport()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            zoomAnimator?.cancel()
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            followPen = false; host?.onUserPanned()
            zoomAround(d.focusX, d.focusY, d.scaleFactor)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            zoomAnimator?.cancel()
            followPen = false; host?.onUserPanned()
            tx -= dx; ty -= dy
            clampPan(); invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.2f || zoom < 0.96f) resetZoom() else animateZoomTo(e.x, e.y, 2f)
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            clearTextSelectionHighlight()
            val s = scale()
            if (s <= 0f) return false
            val by = (e.y - ty) / s
            val idx = blocks.indexOfFirst { by >= it.top && by < it.bottom }
            if (idx >= 0 && idx < min(limit, blocks.size)) host?.onBlockTapped(idx)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (scaleDetector.isInProgress) return
            val s = scale()
            if (s <= 0f || blocks.isEmpty()) return
            val bx = (e.x - tx) / s
            val by = (e.y - ty) / s
            val shown = min(limit, blocks.size)
            val idx = (0 until shown).firstOrNull { i -> by >= blocks[i].top - 14f && by <= blocks[i].bottom + 14f } ?: return
            val blk = blocks[idx]
            val (lineText, hiRect) = extractLineHit(blk, bx, by)
            val fullText = blk.reconstructPlainText(showNotes).ifBlank { lineText }
            if (lineText.isBlank() && fullText.isBlank()) return
            selectionHighlightRect = hiRect
            invalidate()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            host?.onBlockLongPressed(idx, lineText.ifBlank { fullText }, fullText)
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
        notifyViewport(true)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
