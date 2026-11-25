package com.jarvis.assistant.study.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.study.model.BoardPlaybackStatus
import com.jarvis.assistant.study.model.StudyDoubtEntry
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.model.StudyStep
import com.jarvis.assistant.ui.chat.CodeSyntaxHighlighter
import com.jarvis.assistant.util.JarvisFonts
import com.jarvis.assistant.util.ThemeManager
import kotlin.math.max
import kotlin.math.min

/**
 * High-contrast, hardware-accelerated 60 FPS Classroom Whiteboard Canvas.
 *
 * Strictly zero static hex colors: dynamically resolves all inks, fills, borders, highlight glows,
 * and diagram accents from JARVIS's active theme attributes & [ThemeManager].
 */
class StudyWhiteboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class ThemePalette(
        val boardBg: Int,
        val gridLine: Int,
        val cardBg: Int,
        val cardBorder: Int,
        val inkPrimary: Int,
        val inkSecondary: Int,
        val accent: Int,
        val secondary: Int,
        val success: Int,
        val warning: Int,
        val highlightGlow: Int,
        val codeBg: Int
    )

    private data class MeasuredStepLayout(
        val step: StudyStep,
        val cardRect: RectF,
        val bookmarkHitRect: RectF,
        val headerY: Float,
        val bodyLayout: StaticLayout,
        val bodyY: Float,
        val mathItems: List<Pair<Float, StudyMathRenderer.MeasuredExpression>>,
        val codeLayout: StaticLayout?,
        val codeBoxRect: RectF?,
        val diagramRect: RectF?,
        val explanationLayout: StaticLayout?,
        val explanationBoxRect: RectF?,
        val doubtCards: List<MeasuredDoubtCard>
    )

    private data class MeasuredDoubtCard(
        val doubt: StudyDoubtEntry,
        val boxRect: RectF,
        val questionLayout: StaticLayout,
        val questionY: Float,
        val summaryLayout: StaticLayout,
        val summaryY: Float,
        val mathItems: List<Pair<Float, StudyMathRenderer.MeasuredExpression>>,
        val diagramRect: RectF?
    )

    private data class BoardGeometry(
        val contentWidth: Float,
        val contentHeight: Float,
        val headerMetaY: Float,
        val questionLayout: StaticLayout?,
        val questionRect: RectF?,
        val contextCardRect: RectF?,
        val contextLayout: StaticLayout?,
        val stepLayouts: List<MeasuredStepLayout>,
        val finalAnswerRect: RectF?,
        val finalMath: StudyMathRenderer.MeasuredExpression?,
        val finalTextLayout: StaticLayout?,
        val overallDoubtCards: List<MeasuredDoubtCard>
    )

    interface Listener {
        fun onFrameTick(deltaMs: Long): StudyAnimationEngine.FrameOutcome
        fun onStepTapped(stepIndex: Int)
        fun onStepBookmarkTapped(stepIndex: Int)
        fun onUserScrolledManually(followingLive: Boolean)
    }

    private val density = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private var listener: Listener? = null
    private var engine: StudyAnimationEngine? = null
    private var palette: ThemePalette = resolvePalette()
    private var showGrid: Boolean = true
    private var showExplanations: Boolean = true
    private var geometry: BoardGeometry? = null
    private var lastLayoutWidth: Int = -1
    private var lastSolutionSignature: Int = 0

    // Viewport pan & zoom
    private var zoomScale: Float = 1.0f
    private var panX: Float = 0f
    private var panY: Float = 0f
    private var autoFollowWriting: Boolean = true
    private var latestNibY: Float = 0f

    // 60 FPS frame ticker using postOnAnimation
    private var isTicking: Boolean = false
    private var lastFrameNanos: Long = 0L
    private var nibPulsePhase: Float = 0f

    private val scroller = OverScroller(context)
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    // Reusable paints
    private val bgPaint = Paint()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val nibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nibHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val eraseSweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = JarvisFonts.displayBold
        textSize = 13.5f * sp
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = JarvisFonts.medium
        textSize = 15.5f * sp
    }
    private val mathPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = JarvisFonts.serifSemibold
        textSize = 18.0f * sp
    }
    private val explainPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = JarvisFonts.regular
        textSize = 13.5f * sp
    }
    private val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = JarvisFonts.mono
        textSize = 12.8f * sp
    }

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!isTicking || !isAttachedToWindow) return
            val now = System.nanoTime()
            val dtMs = if (lastFrameNanos == 0L) 16L else ((now - lastFrameNanos) / 1_000_000L).coerceIn(1L, 64L)
            lastFrameNanos = now
            nibPulsePhase = (nibPulsePhase + dtMs / 240f) % (Math.PI.toFloat() * 2f)

            val outcome = listener?.onFrameTick(dtMs)
            if (outcome?.changed == true) {
                refreshLayoutIfNeeded(force = false)
                if (autoFollowWriting && isActiveAnimating()) {
                    scrollToFollowNib()
                }
                invalidate()
            } else if (isActiveAnimating()) {
                invalidate()
            }

            if (scroller.computeScrollOffset()) {
                panX = scroller.currX.toFloat()
                panY = scroller.currY.toFloat()
                clampPan()
                invalidate()
            }

            if (shouldContinueTicker()) {
                postOnAnimation(this)
            } else {
                isTicking = false
                lastFrameNanos = 0L
            }
        }
    }

    fun attachEngine(engine: StudyAnimationEngine, listener: Listener) {
        this.engine = engine
        this.listener = listener
        syncWithEngine(forceLayout = true)
    }

    fun applyThemeRefresh() {
        palette = resolvePalette()
        refreshLayoutIfNeeded(force = true)
        invalidate()
    }

    fun setDisplayOptions(showGrid: Boolean, showExplanations: Boolean) {
        val changed = this.showGrid != showGrid || this.showExplanations != showExplanations
        this.showGrid = showGrid
        this.showExplanations = showExplanations
        if (changed) {
            refreshLayoutIfNeeded(force = true)
            invalidate()
        }
    }

    fun syncWithEngine(forceLayout: Boolean = false) {
        refreshLayoutIfNeeded(force = forceLayout)
        ensureTickerRunning()
        invalidate()
    }

    fun getZoomScale(): Float = zoomScale

    fun zoomIn() {
        setZoomScale(zoomScale * 1.18f)
    }

    fun zoomOut() {
        setZoomScale(zoomScale / 1.18f)
    }

    fun resetZoomAndPan() {
        zoomScale = 1.0f
        panX = 0f
        panY = 0f
        autoFollowWriting = true
        listener?.onUserScrolledManually(true)
        clampPan()
        invalidate()
    }

    fun followLiveWriting() {
        autoFollowWriting = true
        listener?.onUserScrolledManually(true)
        scrollToFollowNib()
        invalidate()
    }

    fun scrollToStep(stepIndex: Int) {
        val geom = geometry ?: return
        val card = geom.stepLayouts.firstOrNull { it.step.stepIndex == stepIndex } ?: return
        val targetY = (card.cardRect.top * zoomScale) - 24f * density
        panY = max(0f, targetY)
        clampPan()
        invalidate()
    }

    /**
     * Renders a crisp offscreen snapshot bitmap of the completed/visible board for image export.
     * Bounds pixel height safely to prevent OOM on low-RAM phones.
     */
    fun renderSnapshotBitmap(targetWidthPx: Int = 1440): Bitmap? {
        val sol = engine?.getSolution() ?: return null
        val pal = resolvePalette()
        val renderW = targetWidthPx.coerceIn(720, 1800)
        val snapGeom = buildGeometry(sol, renderW.toFloat(), pal)
        val maxH = (snapGeom.contentHeight + 40f * density).toInt().coerceIn(720, 4800)
        val bmp = Bitmap.createBitmap(renderW, maxH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val scaleIfClipped = if (snapGeom.contentHeight > maxH) {
            maxH.toFloat() / snapGeom.contentHeight
        } else 1f
        if (scaleIfClipped < 1f) c.scale(scaleIfClipped, scaleIfClipped)
        drawBoardContent(
            canvas = c,
            sol = sol,
            geom = snapGeom,
            pal = pal,
            overrideFullReveal = true,
            widthPx = renderW.toFloat(),
            heightPx = maxH / scaleIfClipped
        )
        return bmp
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        palette = resolvePalette()
        ensureTickerRunning()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isTicking = false
        removeCallbacks(frameRunnable)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != lastLayoutWidth) {
            refreshLayoutIfNeeded(force = true)
            clampPan()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pal = palette
        bgPaint.color = pal.boardBg
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (showGrid) {
            drawSubtleGrid(canvas, w, h, pal)
        }

        val sol = engine?.getSolution()
        val geom = geometry
        if (sol == null || geom == null) {
            drawEmptyPromptHint(canvas, w, h, pal)
            return
        }

        canvas.save()
        canvas.translate(-panX, -panY)
        canvas.scale(zoomScale, zoomScale)
        drawBoardContent(canvas, sol, geom, pal, overrideFullReveal = false, widthPx = geom.contentWidth, heightPx = geom.contentHeight)
        canvas.restore()

        val eraseP = engine?.getEraseProgress() ?: 0f
        if (engine?.getStatus() == BoardPlaybackStatus.ERASING && eraseP > 0f) {
            val sweepW = w * eraseP
            eraseSweepPaint.color = pal.boardBg
            eraseSweepPaint.alpha = (235 * eraseP).toInt().coerceIn(20, 245)
            canvas.drawRect(0f, 0f, sweepW, h, eraseSweepPaint)
            eraseSweepPaint.color = pal.accent
            eraseSweepPaint.alpha = 180
            canvas.drawRect(sweepW - 4f * density, 0f, sweepW, h, eraseSweepPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val handled = gestureDetector.onTouchEvent(event)
        return handled || super.onTouchEvent(event)
    }

    private fun drawBoardContent(
        canvas: Canvas,
        sol: StudySolution,
        geom: BoardGeometry,
        pal: ThemePalette,
        overrideFullReveal: Boolean,
        widthPx: Float,
        heightPx: Float
    ) {
        val marginX = 16f * density
        val maxContentW = (widthPx - marginX * 2f).coerceAtLeast(160f)
        var activeNib: PointF? = null

        // Subject / Level / Status Header Line
        titlePaint.color = pal.accent
        titlePaint.textSize = 12.5f * sp
        val levelLabel = sol.educationLevel.label
        val headerText = buildString {
            append(sol.subject.displayName.uppercase())
            if (sol.topic.isNotBlank()) append("  ·  ").append(sol.topic)
            if (levelLabel.isNotBlank()) append("  ·  ").append(levelLabel)
        }
        canvas.drawText(headerText, marginX, geom.headerMetaY, titlePaint)

        // Question Banner (ONLY actual question, zero greeting/filler)
        geom.questionRect?.let { qr ->
            cardFillPaint.color = pal.cardBg
            cardBorderPaint.color = ColorUtils.setAlphaComponent(pal.accent, 110)
            cardBorderPaint.strokeWidth = 1.4f * density
            canvas.drawRoundRect(qr, 14f * density, 14f * density, cardFillPaint)
            canvas.drawRoundRect(qr, 14f * density, 14f * density, cardBorderPaint)

            geom.questionLayout?.let { ql ->
                canvas.save()
                canvas.translate(qr.left + 14f * density, qr.top + 12f * density)
                ql.draw(canvas)
                canvas.restore()
            }
        }

        // Given / Required / Formula Strip
        geom.contextCardRect?.let { cr ->
            cardFillPaint.color = ColorUtils.setAlphaComponent(pal.secondary, 22)
            cardBorderPaint.color = ColorUtils.setAlphaComponent(pal.secondary, 95)
            cardBorderPaint.strokeWidth = 1.1f * density
            canvas.drawRoundRect(cr, 12f * density, 12f * density, cardFillPaint)
            canvas.drawRoundRect(cr, 12f * density, 12f * density, cardBorderPaint)

            geom.contextLayout?.let { cl ->
                canvas.save()
                canvas.translate(cr.left + 14f * density, cr.top + 10f * density)
                cl.draw(canvas)
                canvas.restore()
            }
        }

        val eng = engine
        val activeIdx = eng?.getActiveStepIndex() ?: 0
        val focusedIdx = eng?.getFocusedStepIndex() ?: -1

        // Ordered Lesson Steps
        for (item in geom.stepLayouts) {
            val stepIdx = item.step.stepIndex
            val rawProg = if (overrideFullReveal) 1f else (eng?.getStepProgress(stepIdx) ?: 1f)
            if (rawProg <= 0.005f) continue

            val textPhase = (rawProg / 0.72f).coerceIn(0f, 1f)
            val diagramPhase = if (item.step.diagram != null) {
                ((rawProg - 0.50f) / 0.44f).coerceIn(0f, 1f)
            } else 1f
            val explainPhase = ((rawProg - 0.78f) / 0.22f).coerceIn(0f, 1f)

            val isFocused = !overrideFullReveal && stepIdx == focusedIdx
            val isActive = !overrideFullReveal && stepIdx == activeIdx && isActiveAnimating()

            // Step Card Background + Dynamic Border
            cardFillPaint.color = pal.cardBg
            cardBorderPaint.color = when {
                isActive -> pal.accent
                isFocused -> pal.secondary
                else -> pal.cardBorder
            }
            cardBorderPaint.strokeWidth = (if (isActive || isFocused) 1.75f else 1.0f) * density
            canvas.drawRoundRect(item.cardRect, 14f * density, 14f * density, cardFillPaint)
            canvas.drawRoundRect(item.cardRect, 14f * density, 14f * density, cardBorderPaint)

            // Step Pill Label
            val badgeLabel = item.step.label.ifBlank { "Step ${stepIdx + 1}" }
            titlePaint.color = if (isActive) pal.accent else pal.secondary
            titlePaint.textSize = 12.2f * sp
            canvas.drawText(badgeLabel.uppercase(), item.cardRect.left + 14f * density, item.headerY, titlePaint)

            // Bookmark Ribbon Pill
            val bmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (item.step.isBookmarked) pal.warning else ColorUtils.setAlphaComponent(pal.inkSecondary, 110)
                style = if (item.step.isBookmarked) Paint.Style.FILL else Paint.Style.STROKE
                strokeWidth = 1.4f * density
            }
            val bmCx = item.bookmarkHitRect.centerX()
            val bmCy = item.bookmarkHitRect.centerY()
            val starR = 7.5f * density
            val bmPath = android.graphics.Path().apply {
                moveTo(bmCx - starR * 0.7f, bmCy - starR)
                lineTo(bmCx + starR * 0.7f, bmCy - starR)
                lineTo(bmCx + starR * 0.7f, bmCy + starR)
                lineTo(bmCx, bmCy + starR * 0.45f)
                lineTo(bmCx - starR * 0.7f, bmCy + starR)
                close()
            }
            canvas.drawPath(bmPath, bmPaint)

            // Step Handwriting / Progressive Text Reveal
            drawProgressiveStaticLayout(
                canvas = canvas,
                layout = item.bodyLayout,
                x = item.cardRect.left + 14f * density,
                y = item.bodyY,
                progress = if (item.mathItems.isEmpty()) textPhase else (textPhase * 1.4f).coerceAtMost(1f)
            )?.let { nib -> activeNib = nib }

            // Mathematical Expressions (Progressive Character/Fraction Reveal)
            if (item.mathItems.isNotEmpty() && textPhase > 0.2f) {
                val mathTotal = item.mathItems.size
                val mathSpan = 1f / mathTotal.toFloat()
                val mathP = ((textPhase - 0.2f) / 0.8f).coerceIn(0f, 1f)
                mathPaint.color = pal.inkPrimary
                item.mathItems.forEachIndexed { mIdx, (mathY, measured) ->
                    val localP = ((mathP - mIdx * mathSpan) / mathSpan).coerceIn(0f, 1f)
                    if (localP > 0.01f) {
                        StudyMathRenderer.draw(
                            canvas = canvas,
                            measured = measured,
                            x = item.cardRect.left + 18f * density,
                            y = mathY,
                            progress = localP,
                            textPaint = mathPaint,
                            accentColor = pal.accent,
                            highlightBgColor = pal.highlightGlow
                        )?.let { nib -> activeNib = nib }
                    }
                }
            }

            // Computer Science Syntax-Highlighted Code Card
            if (item.codeBoxRect != null && item.codeLayout != null && textPhase > 0.15f) {
                cardFillPaint.color = pal.codeBg
                cardBorderPaint.color = pal.cardBorder
                cardBorderPaint.strokeWidth = 1f * density
                canvas.drawRoundRect(item.codeBoxRect, 10f * density, 10f * density, cardFillPaint)
                canvas.drawRoundRect(item.codeBoxRect, 10f * density, 10f * density, cardBorderPaint)
                drawProgressiveStaticLayout(
                    canvas = canvas,
                    layout = item.codeLayout,
                    x = item.codeBoxRect.left + 10f * density,
                    y = item.codeBoxRect.top + 8f * density,
                    progress = ((textPhase - 0.15f) / 0.85f).coerceIn(0f, 1f)
                )?.let { nib -> activeNib = nib }
            }

            // Progressive Vector Diagram Card
            if (item.diagramRect != null && item.step.diagram != null && diagramPhase > 0.01f) {
                val dPal = StudyDiagramRenderer.DiagramPalette(
                    cardFillColor = pal.codeBg,
                    cardBorderColor = pal.cardBorder,
                    gridColor = pal.gridLine,
                    inkColor = pal.inkPrimary,
                    accentColor = pal.accent,
                    secondaryColor = pal.secondary,
                    successColor = pal.success,
                    labelBgColor = pal.cardBg
                )
                StudyDiagramRenderer.drawDiagram(
                    canvas = canvas,
                    spec = item.step.diagram,
                    bounds = item.diagramRect,
                    progress = diagramPhase,
                    palette = dPal,
                    density = density
                )?.let { nib -> activeNib = nib }
            }

            // Toggleable Short Teacher Explanation Strip
            if (showExplanations && item.explanationBoxRect != null && item.explanationLayout != null && explainPhase > 0.05f) {
                cardFillPaint.color = ColorUtils.setAlphaComponent(pal.accent, (22 * explainPhase).toInt())
                canvas.drawRoundRect(item.explanationBoxRect, 9f * density, 9f * density, cardFillPaint)
                canvas.save()
                explainPaint.alpha = (255 * explainPhase).toInt()
                canvas.translate(item.explanationBoxRect.left + 10f * density, item.explanationBoxRect.top + 7f * density)
                item.explanationLayout.draw(canvas)
                explainPaint.alpha = 255
                canvas.restore()
            }

            // Anchored Step Doubts
            for (dCard in item.doubtCards) {
                drawDoubtCard(canvas, dCard, pal)
            }
        }

        // Highlighted Final Answer Card
        val finProg = if (overrideFullReveal) 1f else (eng?.getFinalAnswerProgress() ?: 0f)
        if (geom.finalAnswerRect != null && finProg > 0.02f) {
            cardFillPaint.color = ColorUtils.setAlphaComponent(pal.success, (32 * finProg).toInt())
            cardBorderPaint.color = ColorUtils.setAlphaComponent(pal.success, (220 * finProg).toInt())
            cardBorderPaint.strokeWidth = 1.9f * density
            canvas.drawRoundRect(geom.finalAnswerRect, 14f * density, 14f * density, cardFillPaint)
            canvas.drawRoundRect(geom.finalAnswerRect, 14f * density, 14f * density, cardBorderPaint)

            titlePaint.color = pal.success
            titlePaint.textSize = 12f * sp
            canvas.drawText("FINAL ANSWER", geom.finalAnswerRect.left + 14f * density, geom.finalAnswerRect.top + 22f * density, titlePaint)

            if (geom.finalMath != null) {
                mathPaint.color = pal.inkPrimary
                StudyMathRenderer.draw(
                    canvas = canvas,
                    measured = geom.finalMath,
                    x = geom.finalAnswerRect.left + 14f * density,
                    y = geom.finalAnswerRect.top + 30f * density,
                    progress = finProg,
                    textPaint = mathPaint,
                    accentColor = pal.success,
                    highlightBgColor = pal.highlightGlow
                )
            } else if (geom.finalTextLayout != null) {
                drawProgressiveStaticLayout(
                    canvas = canvas,
                    layout = geom.finalTextLayout,
                    x = geom.finalAnswerRect.left + 14f * density,
                    y = geom.finalAnswerRect.top + 30f * density,
                    progress = finProg
                )
            }
        }

        // Overall / Non-Step-Anchored Doubts
        for (dCard in geom.overallDoubtCards) {
            drawDoubtCard(canvas, dCard, pal)
        }

        // Glowing Active Writing Nib Indicator
        activeNib?.let { nib ->
            latestNibY = nib.y
            val pulse = 1f + 0.25f * kotlin.math.sin(nibPulsePhase)
            nibHaloPaint.color = ColorUtils.setAlphaComponent(pal.accent, 75)
            canvas.drawCircle(nib.x, nib.y, 9.5f * density * pulse, nibHaloPaint)
            nibPaint.color = pal.accent
            canvas.drawCircle(nib.x, nib.y, 4.0f * density, nibPaint)
        } ?: run {
            geom.stepLayouts.getOrNull(activeIdx)?.cardRect?.let { r ->
                latestNibY = r.bottom
            }
        }
    }

    private fun drawDoubtCard(canvas: Canvas, card: MeasuredDoubtCard, pal: ThemePalette) {
        cardFillPaint.color = ColorUtils.setAlphaComponent(pal.warning, 20)
        cardBorderPaint.color = ColorUtils.setAlphaComponent(pal.warning, 145)
        cardBorderPaint.strokeWidth = 1.2f * density
        canvas.drawRoundRect(card.boxRect, 12f * density, 12f * density, cardFillPaint)
        canvas.drawRoundRect(card.boxRect, 12f * density, 12f * density, cardBorderPaint)

        canvas.save()
        canvas.translate(card.boxRect.left + 12f * density, card.questionY)
        card.questionLayout.draw(canvas)
        canvas.restore()

        canvas.save()
        canvas.translate(card.boxRect.left + 12f * density, card.summaryY)
        card.summaryLayout.draw(canvas)
        canvas.restore()

        mathPaint.color = pal.inkPrimary
        for ((my, m) in card.mathItems) {
            StudyMathRenderer.draw(canvas, m, card.boxRect.left + 14f * density, my, 1f, mathPaint, pal.warning, pal.highlightGlow)
        }

        card.diagramRect?.let { dr ->
            card.doubt.diagram?.let { spec ->
                StudyDiagramRenderer.drawDiagram(
                    canvas = canvas,
                    spec = spec,
                    bounds = dr,
                    progress = 1f,
                    palette = StudyDiagramRenderer.DiagramPalette(
                        cardFillColor = pal.codeBg,
                        cardBorderColor = pal.cardBorder,
                        gridColor = pal.gridLine,
                        inkColor = pal.inkPrimary,
                        accentColor = pal.warning,
                        secondaryColor = pal.secondary,
                        successColor = pal.success,
                        labelBgColor = pal.cardBg
                    ),
                    density = density
                )
            }
        }
    }

    private fun drawProgressiveStaticLayout(
        canvas: Canvas,
        layout: StaticLayout,
        x: Float,
        y: Float,
        progress: Float
    ): PointF? {
        if (progress <= 0.005f) return null
        if (progress >= 0.995f) {
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
            return null
        }

        val totalChars = layout.text.length.coerceAtLeast(1)
        val visibleChars = max(1, (totalChars * progress).toInt()).coerceAtMost(totalChars)
        val visibleLine = layout.getLineForOffset(visibleChars - 1)

        var nibPoint: PointF? = null
        canvas.save()
        canvas.translate(x, y)
        for (line in 0..visibleLine) {
            val top = layout.getLineTop(line).toFloat()
            val bot = layout.getLineBottom(line).toFloat()
            canvas.save()
            if (line < visibleLine) {
                canvas.clipRect(0f, top, layout.width.toFloat(), bot)
                layout.draw(canvas)
            } else {
                val endX = layout.getPrimaryHorizontal(visibleChars)
                canvas.clipRect(0f, top, endX.coerceAtLeast(8f), bot)
                layout.draw(canvas)
                nibPoint = PointF(x + endX, y + (top + bot) * 0.55f)
            }
            canvas.restore()
        }
        canvas.restore()
        return nibPoint
    }

    private fun drawSubtleGrid(canvas: Canvas, w: Float, h: Float, pal: ThemePalette) {
        gridPaint.color = pal.gridLine
        val spacing = 28f * density * zoomScale
        if (spacing < 10f) return
        val offsetX = -(panX % spacing)
        val offsetY = -(panY % spacing)
        var gx = offsetX
        while (gx < w) {
            canvas.drawLine(gx, 0f, gx, h, gridPaint)
            gx += spacing
        }
        var gy = offsetY
        while (gy < h) {
            canvas.drawLine(0f, gy, w, gy, gridPaint)
            gy += spacing
        }
    }

    private fun drawEmptyPromptHint(canvas: Canvas, w: Float, h: Float, pal: ThemePalette) {
        val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.inkSecondary
            textSize = 15f * sp
            typeface = JarvisFonts.medium
        }
        val title = "Ask by text, voice, photo, or PDF below"
        val sub = "JARVIS writes only verified educational steps, equations & diagrams here."
        val tw = hintPaint.measureText(title)
        canvas.drawText(title, ((w - tw) / 2f).coerceAtLeast(16f * density), h * 0.46f, hintPaint)

        hintPaint.textSize = 12.5f * sp
        hintPaint.alpha = 180
        val subLayout = buildStaticLayout(sub, hintPaint, (w - 48f * density).toInt().coerceAtLeast(180))
        canvas.save()
        canvas.translate(24f * density, h * 0.46f + 16f * density)
        subLayout.draw(canvas)
        canvas.restore()
    }

    private fun refreshLayoutIfNeeded(force: Boolean) {
        val w = width
        if (w <= 0) return
        val sol = engine?.getSolution()
        if (sol == null) {
            geometry = null
            lastSolutionSignature = 0
            return
        }
        val sig = computeSignature(sol)
        if (force || w != lastLayoutWidth || sig != lastSolutionSignature) {
            lastLayoutWidth = w
            lastSolutionSignature = sig
            geometry = buildGeometry(sol, w.toFloat(), palette)
            clampPan()
        }
    }

    private fun computeSignature(sol: StudySolution): Int {
        var h = sol.id.hashCode()
        h = 31 * h + sol.question.hashCode()
        h = 31 * h + sol.steps.size
        sol.steps.forEach { s ->
            h = 31 * h + s.boardText.hashCode()
            h = 31 * h + s.isBookmarked.hashCode()
            h = 31 * h + (s.diagram?.elements?.size ?: 0)
        }
        h = 31 * h + sol.finalAnswer.hashCode()
        h = 31 * h + sol.doubts.size
        h = 31 * h + showExplanations.hashCode()
        return h
    }

    private fun buildGeometry(sol: StudySolution, viewWidthPx: Float, pal: ThemePalette): BoardGeometry {
        val marginX = 16f * density
        val contentW = (viewWidthPx - marginX * 2f).coerceAtLeast(180f)
        val innerPad = 14f * density
        val innerW = (contentW - innerPad * 2f).coerceAtLeast(120f)
        var y = 26f * density

        val headerMetaY = y
        y += 14f * density

        var qRect: RectF? = null
        var qLayout: StaticLayout? = null
        if (sol.question.isNotBlank()) {
            val qPaint = TextPaint(bodyPaint).apply {
                color = pal.inkPrimary
                typeface = JarvisFonts.semibold
            }
            val ql = buildStaticLayout(StudyMathRenderer.normalizeSymbols(sol.question), qPaint, innerW.toInt())
            val top = y
            val bot = top + ql.height + 24f * density
            qRect = RectF(marginX, top, marginX + contentW, bot)
            qLayout = ql
            y = bot + 12f * density
        }

        // Given / Required / Formulas summary card
        var ctxRect: RectF? = null
        var ctxLayout: StaticLayout? = null
        val ctxLines = buildList {
            if (sol.givenValues.isNotEmpty()) add("Given: " + sol.givenValues.joinToString("; "))
            if (sol.requiredQuantity.isNotBlank()) add("Find: " + sol.requiredQuantity)
            if (sol.formulas.isNotEmpty()) add("Formula: " + sol.formulas.joinToString("  |  "))
            if (sol.definitions.isNotEmpty()) add("Key Concept: " + sol.definitions.joinToString("; "))
        }
        if (ctxLines.isNotEmpty()) {
            val cp = TextPaint(explainPaint).apply { color = pal.inkPrimary }
            val cl = buildStaticLayout(StudyMathRenderer.normalizeSymbols(ctxLines.joinToString("\n")), cp, innerW.toInt())
            val top = y
            val bot = top + cl.height + 20f * density
            ctxRect = RectF(marginX, top, marginX + contentW, bot)
            ctxLayout = cl
            y = bot + 14f * density
        }

        val stepLayouts = ArrayList<MeasuredStepLayout>(sol.steps.size)
        val doubtsByStep = sol.doubts.groupBy { it.targetStepIndex }

        for (step in sol.steps) {
            val cardTop = y
            var cy = cardTop + 28f * density
            val headerY = cy - 4f * density
            cy += 8f * density

            val bPaint = TextPaint(bodyPaint).apply { color = pal.inkPrimary }
            val cleanBody = StudyMathRenderer.normalizeSymbols(step.boardText)
            val bLayout = buildStaticLayout(cleanBody, bPaint, innerW.toInt())
            val bodyY = cy
            if (cleanBody.isNotBlank()) {
                cy += bLayout.height + 10f * density
            }

            mathPaint.color = pal.inkPrimary
            val mathItems = ArrayList<Pair<Float, StudyMathRenderer.MeasuredExpression>>()
            for (expr in step.mathExpressions) {
                val measured = StudyMathRenderer.measure(
                    raw = expr,
                    paint = mathPaint,
                    maxWidthPx = (innerW - 12f * density).coerceAtLeast(100f),
                    highlightTokens = step.highlightTokens
                )
                mathItems += cy to measured
                cy += measured.totalHeight + 10f * density
            }

            var cBoxRect: RectF? = null
            var cLayout: StaticLayout? = null
            if (step.codeBlock.isNotBlank()) {
                val highlighted = highlightCodeDynamically(step.codeBlock, step.codeLanguage, pal)
                val cPaint = TextPaint(codePaint).apply { color = pal.inkPrimary }
                val cl = buildStaticLayout(highlighted, cPaint, (innerW - 20f * density).toInt().coerceAtLeast(100))
                val cTop = cy
                val cBot = cTop + cl.height + 16f * density
                cBoxRect = RectF(marginX + innerPad, cTop, marginX + contentW - innerPad, cBot)
                cLayout = cl
                cy = cBot + 10f * density
            }

            var dRect: RectF? = null
            if (step.diagram != null && step.diagram.elements.isNotEmpty()) {
                val dHeight = (innerW * 0.54f).coerceIn(170f * density, 270f * density)
                dRect = RectF(marginX + innerPad, cy, marginX + contentW - innerPad, cy + dHeight)
                cy += dHeight + 10f * density
            }

            var expRect: RectF? = null
            var expLayout: StaticLayout? = null
            if (showExplanations && step.explanation.isNotBlank()) {
                val ep = TextPaint(explainPaint).apply { color = pal.inkSecondary }
                val el = buildStaticLayout(
                    StudyMathRenderer.normalizeSymbols(step.explanation),
                    ep,
                    (innerW - 20f * density).toInt().coerceAtLeast(100)
                )
                expRect = RectF(marginX + innerPad, cy, marginX + contentW - innerPad, cy + el.height + 14f * density)
                expLayout = el
                cy = expRect.bottom + 8f * density
            }

            val cardBot = cy + 6f * density
            val cardRect = RectF(marginX, cardTop, marginX + contentW, cardBot)
            val bmRect = RectF(cardRect.right - 46f * density, cardTop, cardRect.right, cardTop + 44f * density)

            y = cardBot + 12f * density

            val stepDoubts = doubtsByStep[step.stepIndex].orEmpty()
            val measuredDoubts = ArrayList<MeasuredDoubtCard>(stepDoubts.size)
            for (d in stepDoubts) {
                val dCard = measureDoubtCard(d, marginX + 14f * density, y, (contentW - 14f * density).coerceAtLeast(160f), pal)
                measuredDoubts += dCard
                y = dCard.boxRect.bottom + 10f * density
            }

            stepLayouts += MeasuredStepLayout(
                step = step,
                cardRect = cardRect,
                bookmarkHitRect = bmRect,
                headerY = headerY,
                bodyLayout = bLayout,
                bodyY = bodyY,
                mathItems = mathItems,
                codeLayout = cLayout,
                codeBoxRect = cBoxRect,
                diagramRect = dRect,
                explanationLayout = expLayout,
                explanationBoxRect = expRect,
                doubtCards = measuredDoubts
            )
        }

        // Final Answer Box
        var finalRect: RectF? = null
        var finalMath: StudyMathRenderer.MeasuredExpression? = null
        var finalTxt: StaticLayout? = null
        if (sol.finalAnswer.isNotBlank()) {
            val top = y
            val hasLatex = sol.finalAnswer.contains("\\") || sol.finalAnswer.contains("^") || sol.finalAnswer.contains("_")
            val contentH = if (hasLatex) {
                val m = StudyMathRenderer.measure(sol.finalAnswer, mathPaint, innerW)
                finalMath = m
                m.totalHeight
            } else {
                val fp = TextPaint(bodyPaint).apply {
                    color = pal.inkPrimary
                    typeface = JarvisFonts.semibold
                }
                val fl = buildStaticLayout(StudyMathRenderer.normalizeSymbols(sol.finalAnswer), fp, innerW.toInt())
                finalTxt = fl
                fl.height.toFloat()
            }
            val bot = top + 34f * density + contentH + 14f * density
            finalRect = RectF(marginX, top, marginX + contentW, bot)
            y = bot + 14f * density
        }

        val overallDoubts = doubtsByStep[null].orEmpty().map { d ->
            val card = measureDoubtCard(d, marginX, y, contentW, pal)
            y = card.boxRect.bottom + 10f * density
            card
        }

        return BoardGeometry(
            contentWidth = viewWidthPx,
            contentHeight = max(y + 40f * density, height.toFloat()),
            headerMetaY = headerMetaY,
            questionLayout = qLayout,
            questionRect = qRect,
            contextCardRect = ctxRect,
            contextLayout = ctxLayout,
            stepLayouts = stepLayouts,
            finalAnswerRect = finalRect,
            finalMath = finalMath,
            finalTextLayout = finalTxt,
            overallDoubtCards = overallDoubts
        )
    }

    private fun measureDoubtCard(
        d: StudyDoubtEntry,
        left: Float,
        top: Float,
        width: Float,
        pal: ThemePalette
    ): MeasuredDoubtCard {
        val innerW = (width - 24f * density).coerceAtLeast(100f)
        var cy = top + 10f * density

        val qp = TextPaint(explainPaint).apply {
            color = pal.warning
            typeface = JarvisFonts.semibold
        }
        val ql = buildStaticLayout("Doubt: ${StudyMathRenderer.normalizeSymbols(d.question)}", qp, innerW.toInt())
        val qY = cy
        cy += ql.height + 6f * density

        val spPaint = TextPaint(explainPaint).apply { color = pal.inkPrimary }
        val sl = buildStaticLayout(StudyMathRenderer.normalizeSymbols("${d.boardSummary}\n${d.explanation}".trim()), spPaint, innerW.toInt())
        val sY = cy
        cy += sl.height + 8f * density

        val mathItems = d.mathExpressions.map { expr ->
            val m = StudyMathRenderer.measure(expr, mathPaint, innerW)
            val entry = cy to m
            cy += m.totalHeight + 8f * density
            entry
        }

        var dRect: RectF? = null
        if (d.diagram != null && d.diagram.elements.isNotEmpty()) {
            val dH = (innerW * 0.45f).coerceIn(140f * density, 210f * density)
            dRect = RectF(left + 12f * density, cy, left + width - 12f * density, cy + dH)
            cy += dH + 8f * density
        }

        return MeasuredDoubtCard(
            doubt = d,
            boxRect = RectF(left, top, left + width, cy + 4f * density),
            questionLayout = ql,
            questionY = qY,
            summaryLayout = sl,
            summaryY = sY,
            mathItems = mathItems,
            diagramRect = dRect
        )
    }

    private fun highlightCodeDynamically(code: String, language: String, pal: ThemePalette): CharSequence {
        val themeRef = CodeSyntaxHighlighter.Theme.CYBER_DARK
        val highlighted = CodeSyntaxHighlighter.highlight(code, language.ifBlank { "kotlin" }, themeRef)
        if (highlighted !is android.text.Spannable) return highlighted
        val spans = highlighted.getSpans(0, highlighted.length, android.text.style.ForegroundColorSpan::class.java)
        for (span in spans) {
            val start = highlighted.getSpanStart(span)
            val end = highlighted.getSpanEnd(span)
            val flags = highlighted.getSpanFlags(span)
            val mappedColor = when (span.foregroundColor) {
                themeRef.keywordColor -> pal.accent
                themeRef.functionColor -> pal.secondary
                themeRef.stringColor -> pal.success
                themeRef.numberColor -> pal.warning
                themeRef.typeColor -> ColorUtils.blendARGB(pal.accent, pal.success, 0.45f)
                themeRef.commentColor -> pal.inkSecondary
                else -> pal.inkPrimary
            }
            highlighted.removeSpan(span)
            highlighted.setSpan(android.text.style.ForegroundColorSpan(mappedColor), start, end, flags)
        }
        return highlighted
    }

    private fun buildStaticLayout(text: CharSequence, paint: TextPaint, widthPx: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(16))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f * density, 1.12f)
            .setIncludePad(false)
            .build()

    private fun setZoomScale(newZoom: Float) {
        zoomScale = newZoom.coerceIn(0.6f, 2.6f)
        clampPan()
        invalidate()
    }

    private fun scrollToFollowNib() {
        val h = height.toFloat()
        if (h <= 0f) return
        val nibScreenY = (latestNibY * zoomScale) - panY
        val desiredAnchor = h * 0.68f
        if (nibScreenY > desiredAnchor || nibScreenY < 60f * density) {
            panY = max(0f, (latestNibY * zoomScale) - desiredAnchor)
            clampPan()
        }
    }

    private fun clampPan() {
        val geom = geometry ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val scaledW = geom.contentWidth * zoomScale
        val scaledH = geom.contentHeight * zoomScale
        val maxPanX = max(0f, scaledW - w)
        val maxPanY = max(0f, scaledH - h)
        panX = panX.coerceIn(0f, maxPanX)
        panY = panY.coerceIn(0f, maxPanY)
    }

    private fun isActiveAnimating(): Boolean {
        val s = engine?.getStatus()
        return s == BoardPlaybackStatus.PLAYING || s == BoardPlaybackStatus.STREAMING || s == BoardPlaybackStatus.ERASING
    }

    private fun shouldContinueTicker(): Boolean =
        isAttachedToWindow && (isActiveAnimating() || !scroller.isFinished)

    private fun ensureTickerRunning() {
        if (!isTicking && isAttachedToWindow && shouldContinueTicker()) {
            isTicking = true
            lastFrameNanos = 0L
            postOnAnimation(frameRunnable)
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prevZoom = zoomScale
            val nextZoom = (zoomScale * detector.scaleFactor).coerceIn(0.6f, 2.6f)
            val focusX = detector.focusX
            val focusY = detector.focusY
            val factor = nextZoom / prevZoom
            zoomScale = nextZoom
            panX = (panX + focusX) * factor - focusX
            panY = (panY + focusY) * factor - focusY
            clampPan()
            invalidate()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            panX += distanceX
            panY += distanceY
            if (autoFollowWriting) {
                autoFollowWriting = false
                listener?.onUserScrolledManually(false)
            }
            clampPan()
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val geom = geometry ?: return false
            val maxX = max(0f, geom.contentWidth * zoomScale - width).toInt()
            val maxY = max(0f, geom.contentHeight * zoomScale - height).toInt()
            scroller.fling(
                panX.toInt(), panY.toInt(),
                (-velocityX).toInt(), (-velocityY).toInt(),
                0, maxX, 0, maxY
            )
            ensureTickerRunning()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomScale > 1.15f) {
                resetZoomAndPan()
            } else {
                setZoomScale(1.65f)
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val geom = geometry ?: return false
            val boardX = (e.x + panX) / zoomScale
            val boardY = (e.y + panY) / zoomScale
            for (item in geom.stepLayouts) {
                if (item.bookmarkHitRect.contains(boardX, boardY)) {
                    listener?.onStepBookmarkTapped(item.step.stepIndex)
                    return true
                }
                if (item.cardRect.contains(boardX, boardY)) {
                    listener?.onStepTapped(item.step.stepIndex)
                    return true
                }
            }
            return false
        }
    }

    private fun resolvePalette(): ThemePalette {
        val accent = ThemeManager.getThemeAccentColor()
        val secondary = ThemeManager.getThemeSecondaryColor()
        val success = ThemeManager.getThemeSuccessColor()
        val warning = ThemeManager.getThemeWarningColor()
        val isLight = ThemeManager.isCurrentThemeLight()

        var boardBg = resolveAttrColor(R.attr.themeBg)
        if (android.graphics.Color.alpha(boardBg) == 0) {
            val prefs = (context.applicationContext as? JarvisApp)?.preferences
            boardBg = ThemeManager.getThemeBgColor(prefs?.appTheme ?: "void", prefs?.customThemeColor)
        }
        val cardBg = resolveAttrColor(R.attr.themeCardBg).let { c ->
            if (android.graphics.Color.alpha(c) == 0) {
                ColorUtils.blendARGB(boardBg, accent, 0.08f)
            } else c
        }
        val cardBorder = resolveAttrColor(R.attr.themeCardBorder).let { c ->
            if (android.graphics.Color.alpha(c) == 0) ColorUtils.setAlphaComponent(accent, 90) else c
        }
        val inkPrimary = resolveAttrColor(R.attr.themeTextPrimary)
        val inkSecondary = resolveAttrColor(R.attr.themeTextSecondary)
        val gridLine = ColorUtils.setAlphaComponent(inkSecondary, if (isLight) 28 else 22)
        val highlightGlow = ColorUtils.setAlphaComponent(accent, if (isLight) 42 else 54)
        val codeBg = ColorUtils.blendARGB(boardBg, cardBg, 0.55f)

        return ThemePalette(
            boardBg = boardBg,
            gridLine = gridLine,
            cardBg = cardBg,
            cardBorder = cardBorder,
            inkPrimary = inkPrimary,
            inkSecondary = inkSecondary,
            accent = accent,
            secondary = secondary,
            success = success,
            warning = warning,
            highlightGlow = highlightGlow,
            codeBg = codeBg
        )
    }

    private fun resolveAttrColor(attrRes: Int): Int {
        val tv = TypedValue()
        val ok = context.theme.resolveAttribute(attrRes, tv, true)
        if (!ok) return 0
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }
}
