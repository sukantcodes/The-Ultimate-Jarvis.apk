package com.jarvis.assistant.ui.studyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import com.jarvis.assistant.study.BoardBlock
import com.jarvis.assistant.study.StepKind
import com.jarvis.assistant.study.StudyPlayback
import kotlin.math.max
import kotlin.math.min

/**
 * The whiteboard. It draws laid-out blocks in a fixed 1000-unit-wide space scaled to the screen, so rotation and export give
 * the same picture. Writing is driven by [StudyPlayback]; this view only turns "block N is x% written" into pixels. Pan with
 * one finger, zoom with two, double-tap to zoom, tap a step to ask about it.
 */
class StudyBoardView(context: Context) : View(context) {

    interface Host {
        fun onBlockTapped(index: Int)
        fun onUserPanned()
    }

    companion object {
        const val BOARD_WIDTH = 1000f
        const val MARGIN = 30f
    }

    var host: Host? = null
    private val fonts = StudyFonts(context)
    private val painter = StudyPainter(fonts)

    private var blocks: List<BoardBlock> = emptyList()
    private var playback: StudyPlayback? = null
    var showNotes: Boolean = true
        set(v) { field = v; invalidate() }
    var selectedBlock: Int = -1
        set(v) { field = v; invalidate() }
    var bookmarkedBlocks: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }
    var followPen: Boolean = true

    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f

    // erase / restore animation of the tail of the board
    private var limit = Int.MAX_VALUE
    private var animFrom = 0
    private var animTo = 0
    private var animAlpha = 1f
    private var animating = false
    private var animator: ValueAnimator? = null

    private var ticking = false
    private var lastTick = 0L
    private val accent = 0xFF0B63C5.toInt()

    init {
        setBackgroundColor(StudyPalette.PAPER)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, resources.displayMetrics.density * 20f)
            }
        }
        contentDescription = "Study whiteboard"
    }

    fun bind(playback: StudyPlayback, blocks: List<BoardBlock>, eraseLimit: Int) {
        this.playback = playback
        this.blocks = blocks
        this.limit = eraseLimit
        clampPan()
        invalidate()
        kick()
    }

    fun setBlocks(list: List<BoardBlock>) {
        blocks = list
        clampPan()
        invalidate()
        kick()
    }

    /** Starts the animation clock if anything on the board is moving. Safe to call at any time. */
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
            if (changed || animating) invalidate()
            val moving = pb?.state == StudyPlayback.State.PLAYING || animating
            if (moving && isAttachedToWindow) postOnAnimation(this) else ticking = false
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); kick() }
    override fun onDetachedFromWindow() { animator?.cancel(); ticking = false; super.onDetachedFromWindow() }

    // ----- erase / redo -------------------------------------------------------------------------------------

    /** Fades blocks in or out so that only blocks below [newLimit] remain. */
    fun setEraseLimit(newLimit: Int) {
        if (newLimit == limit) return
        animator?.cancel() // its end listener settles the limit before the lines below read it
        val shown = min(limit, blocks.size)
        val target = min(newLimit, blocks.size)
        if (target == shown) { limit = newLimit; invalidate(); return }
        val erasing = target < shown
        animFrom = min(shown, target)
        animTo = max(shown, target)
        animating = true
        limit = animTo // while fading, everything up to the larger bound is drawn
        animator = ValueAnimator.ofFloat(if (erasing) 1f else 0f, if (erasing) 0f else 1f).apply {
            duration = 280L
            addUpdateListener { animAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { animating = false; limit = newLimit; invalidate() }
            })
            start()
        }
        kick()
    }

    // ----- drawing --------------------------------------------------------------------------------------------

    private fun scale(): Float = (width / BOARD_WIDTH) * zoom

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pb = playback ?: return
        val s = scale()
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(s, s)
        val viewTop = -ty / s
        val viewBottom = (height - ty) / s
        val cur = pb.current
        val finished = pb.state == StudyPlayback.State.FINISHED
        var tipDrawn = false
        val shown = min(limit, blocks.size)
        for (i in 0 until shown) {
            val b = blocks[i]
            if (b.bottom < viewTop - 40f || b.top > viewBottom + 40f) continue
            val fraction: Float = when {
                pb.state == StudyPlayback.State.IDLE -> 0f
                finished -> 1f
                i < cur -> 1f
                i == cur -> pb.progress
                pb.othersStayVisible && i <= pb.highWater -> 1f
                else -> 0f
            }
            if (fraction <= 0f) continue
            var alpha = 255
            if (animating && i >= animFrom && i < animTo) alpha = (255 * animAlpha).toInt()
            if (b.kind == StepKind.FORMULA && !b.isQuestion) painter.drawBlockBackground(canvas, b, BOARD_WIDTH, MARGIN, 0xFF0B63C5.toInt(), (0x14 * alpha / 255) )
            if (b.isFinal) painter.drawBlockBackground(canvas, b, BOARD_WIDTH, MARGIN, 0xFF0A8A56.toInt(), (0x1A * alpha / 255))
            val ops = b.allOps(showNotes)
            // the pen writes the step first and its note afterwards
            painter.drawOps(canvas, ops, fraction, alpha)
            if (i == cur && fraction < 1f && painter.tipVisible && !tipDrawn) { painter.drawPen(canvas, painter.tipX, painter.tipY, accent); tipDrawn = true }
            if (i == selectedBlock) painter.drawSelection(canvas, b, BOARD_WIDTH, MARGIN, accent)
            if (!b.isQuestion && bookmarkedBlocks.contains(b.stepIndex) && fraction >= 1f) painter.drawBookmark(canvas, b, BOARD_WIDTH, MARGIN, 0xFFE0A100.toInt())
        }
        canvas.restore()
    }

    // ----- pan / zoom -------------------------------------------------------------------------------------------

    private fun contentHeight(): Float = (blocks.lastOrNull()?.bottom ?: 0f) + 60f

    private fun clampPan() {
        val s = scale()
        val minTy = min(0f, height - contentHeight() * s)
        ty = ty.coerceIn(minTy, 0f)
        val minTx = min(0f, width - BOARD_WIDTH * s)
        tx = if (zoom <= 1.001f) 0f else tx.coerceIn(minTx, 0f)
    }

    /** Keeps the pen in view while writing, easing the board up rather than jumping. */
    private fun follow(): Boolean {
        val pb = playback ?: return false
        if (pb.state != StudyPlayback.State.PLAYING || pb.current !in blocks.indices || height == 0) return false
        val b = blocks[pb.current]
        val s = scale()
        val penY = (b.top + b.height * pb.progress) * s + ty
        if (penY in (height * 0.12f)..(height * 0.80f)) return false
        val target = -((b.top + b.height * pb.progress) * s - height * 0.55f)
        val before = ty
        ty += (target - ty) * 0.14f
        clampPan()
        return before != ty
    }

    /** Scrolls so that block [index] is comfortably in view (used when an answer refers to a step). */
    fun reveal(index: Int) {
        if (index !in blocks.indices || height == 0) return
        val s = scale()
        val b = blocks[index]
        val top = b.top * s + ty
        val bottom = b.bottom * s + ty
        if (top >= 0 && bottom <= height) return
        val target = -(b.top * s - height * 0.2f)
        ValueAnimator.ofFloat(ty, target).apply {
            duration = 320L
            addUpdateListener { ty = it.animatedValue as Float; clampPan(); invalidate() }
            start()
        }
    }

    fun scrollToEnd() {
        followPen = true
        val s = scale()
        ty = min(0f, height - contentHeight() * s)
        invalidate()
    }

    fun zoomBy(factor: Float) = zoomAround(width / 2f, height / 2f, factor)
    fun resetZoom() { zoom = 1f; tx = 0f; clampPan(); invalidate() }

    private fun zoomAround(fx: Float, fy: Float, factor: Float) {
        val newZoom = (zoom * factor).coerceIn(1f, 4f)
        val real = newZoom / zoom
        tx = fx - (fx - tx) * real
        ty = fy - (fy - ty) * real
        zoom = newZoom
        clampPan()
        invalidate()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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
            followPen = false; host?.onUserPanned()
            tx -= dx; ty -= dy
            clampPan(); invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.2f) resetZoom() else zoomAround(e.x, e.y, 2f)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); clampPan() }
    override fun performClick(): Boolean { super.performClick(); return true }
}
