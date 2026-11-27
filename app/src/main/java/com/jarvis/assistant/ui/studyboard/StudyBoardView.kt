package com.jarvis.assistant.ui.studyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.study.BoardBlock
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
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = StudyPalette.PAPER_GRID
        alpha = 48
    }

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
    private var lastLiveFocusY: Float? = null

    fun setNarrationSpotlight(active: Boolean, blockIndex: Int, progressFrac: Float, transcript: String) {
        val clamped = progressFrac.coerceIn(0f, 1f)
        val changed = narratingActive != active ||
            narratingBlockIndex != blockIndex ||
            abs(narratingFraction - clamped) >= 0.01f ||
            narratingTranscript != transcript
        narratingActive = active
        narratingBlockIndex = blockIndex
        narratingFraction = clamped
        narratingTranscript = transcript
        if (!active) lastLiveFocusY = null
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
            if (followPen) changed = follow() || changed
            if (narratingActive || pb?.state == StudyPlayback.State.PLAYING) {
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
        drawDraftingDotGrid(canvas, viewTop, viewBottom, s)

        val pb = playback
        if (pb != null) {
            val cur = pb.current
            val finished = pb.state == StudyPlayback.State.FINISHED
            var tipDrawn = false
            var foundFocusY: Float? = null
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
                        b.isFinal -> painter.drawBlockBackground(canvas, b, BOARD_WIDTH, MARGIN, StudyPalette.color(Ink.RESULT), 26 * alpha / 255)
                        b.kind == StepKind.FORMULA -> painter.drawBlockBackground(canvas, b, BOARD_WIDTH, MARGIN, accent, 20 * alpha / 255)
                        b.kind == StepKind.DEFINITION || b.kind == StepKind.REQUIRED ->
                            painter.drawBlockBackground(canvas, b, BOARD_WIDTH, MARGIN, StudyPalette.color(Ink.VIOLET), 14 * alpha / 255)
                        b.kind == StepKind.GIVEN ->
                            painter.drawBlockBackground(canvas, b, BOARD_WIDTH, MARGIN, StudyPalette.color(Ink.TEAL), 14 * alpha / 255)
                    }
                }
                if (i == activeSpotlightIdx) {
                    painter.drawActiveStepSpotlight(canvas, b, BOARD_WIDTH, MARGIN, accent, pulsePhase)
                }
                val ops = b.allOps(showNotes)
                painter.drawOps(canvas, ops, fraction, alpha)
                if (i == activeSpotlightIdx) {
                    if (fraction < 1f) {
                        painter.drawWritingUnderline(canvas, accent, pulsePhase)
                        if (showPen && painter.tipVisible && !tipDrawn) {
                            val activeColor = if (painter.tipInk == Ink.INK) accent else StudyPalette.color(painter.tipInk)
                            painter.drawPen(canvas, painter.tipX, painter.tipY, activeColor, pulsePhase)
                            foundFocusY = painter.tipY
                            tipDrawn = true
                        }
                    } else if (narratingActive || alreadyPrintedReplay) {
                        // Already printed (replay, restored lesson, or finished writing while voice finishes):
                        // underline and place pulsing spotlight pin directly along the explained line or diagram label
                        val explainFrac = when {
                            narratingActive && narratingBlockIndex == i -> narratingFraction.coerceIn(0.02f, 1f)
                            alreadyPrintedReplay && pb.progress > 0f -> pb.progress.coerceIn(0.02f, 1f)
                            pb.progress in 0.01f..0.99f -> pb.progress
                            else -> 0.92f
                        }
                        val pinY = painter.drawLiveExplainFocus(
                            canvas, b, ops, explainFrac, narratingTranscript, accent, pulsePhase
                        )
                        if (pinY != null) foundFocusY = pinY
                    }
                }
                if (i == selectedBlock) painter.drawSelection(canvas, b, BOARD_WIDTH, MARGIN, accent)
                if (!b.isQuestion && bookmarkedBlocks.contains(b.stepIndex) && fraction >= 1f) {
                    painter.drawBookmark(canvas, b, BOARD_WIDTH, MARGIN, paperWarningColor())
                }
            }
            lastLiveFocusY = foundFocusY
        }
        canvas.restore()
    }

    private fun drawDraftingDotGrid(canvas: Canvas, viewTop: Float, viewBottom: Float, scale: Float) {
        if (scale < 0.25f) return
        val spacing = 44f
        val startY = max(spacing, floor(viewTop / spacing) * spacing)
        val endY = min(max(contentHeight(), viewBottom), viewBottom + spacing)
        val dotR = (1.45f / sqrtScale(scale)).coerceIn(1.1f, 2.2f)
        var y = startY
        while (y <= endY) {
            var x = MARGIN
            while (x <= BOARD_WIDTH - MARGIN) {
                canvas.drawCircle(x, y, dotR, gridPaint)
                x += spacing
            }
            y += spacing
        }
    }

    private fun sqrtScale(s: Float): Float = if (s > 1f) kotlin.math.sqrt(s) else 1f

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

    /** Keeps the active diagram, step, stylus tip, or live teacher pin safely inside the unobstructed visible board area. */
    private fun follow(): Boolean {
        val pb = playback ?: return false
        val targetBlockIdx = when {
            pb.state == StudyPlayback.State.PLAYING && pb.current in blocks.indices -> pb.current
            narratingActive && narratingBlockIndex in blocks.indices -> narratingBlockIndex
            else -> return false
        }
        if (height == 0 || width == 0) return false
        val b = blocks[targetBlockIdx]
        val bs = baseScale()
        val avail = availHeight()

        // If a diagram block is taller than the visible viewport at current zoom, ease cleanly toward a fit scale
        if (b.kind == StepKind.DIAGRAM && b.height * bs > avail * 0.92f) {
            val desiredZoom = ((avail * 0.92f) / (b.height * bs)).coerceIn(minAllowedZoom(), 1f)
            if (zoom > desiredZoom + 0.015f) {
                zoom += (desiredZoom - zoom) * 0.14f
            }
        }

        val s = scale()
        val blockTopPx = b.top * s + ty
        val blockBotPx = b.bottom * s + ty
        val beforeTy = ty

        if (b.height * s <= avail * 0.96f) {
            // Whole active block fits on screen: ensure both top AND bottom sit cleanly inside [8..avail-8]
            val pad = resources.displayMetrics.density * 8f
            val targetTy = when {
                blockTopPx < pad -> -(b.top * s - pad)
                blockBotPx > avail - pad -> -(b.bottom * s - (avail - pad))
                else -> ty
            }
            if (abs(targetTy - ty) > 0.5f) {
                ty += (targetTy - ty) * 0.16f
            }
        } else {
            // Long block: follow live teacher pin / stylus tip (when available) or progress safely above bottom controls
            val focusBoardY = lastLiveFocusY ?: if (painter.tipVisible) painter.tipY else (b.top + b.height * pb.progress)
            val focusPx = focusBoardY * s + ty
            if (focusPx < avail * 0.14f || focusPx > avail * 0.80f) {
                val targetTy = -(focusBoardY * s - avail * 0.50f)
                ty += (targetTy - ty) * 0.14f
            }
        }
        clampPan()
        notifyViewport()
        return abs(beforeTy - ty) > 0.2f
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
            val s = scale()
            if (s <= 0f) return false
            val by = (e.y - ty) / s
            val idx = blocks.indexOfFirst { by >= it.top && by < it.bottom }
            if (idx >= 0 && idx < min(limit, blocks.size)) host?.onBlockTapped(idx)
            return true
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
