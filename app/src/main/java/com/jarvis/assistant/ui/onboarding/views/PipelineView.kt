package com.jarvis.assistant.ui.onboarding.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import com.jarvis.assistant.ui.onboarding.Palette
import com.jarvis.assistant.ui.onboarding.dp
import com.jarvis.assistant.ui.onboarding.withAlpha
import kotlin.math.abs
import kotlin.math.sin

/**
 * The eight-stage workflow track: voice command, understanding, app selection, navigation, element identification,
 * authorized action, result verification, voice response. A light travels along it; tapping a node pins that stage.
 */
class PipelineView(context: Context, private val stageCount: Int) : LoopingView(context, stageCount * STAGE_MS) {

    private var pinned = -1
    private var lastReported = -1
    var onStageChanged: ((Int) -> Unit)? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    init {
        isClickable = true
    }

    private fun currentStage(): Int = when {
        pinned >= 0 -> pinned
        !isLooping -> 0
        else -> (phase * stageCount).toInt().coerceIn(0, stageCount - 1)
    }

    private fun stageProgress(): Float = if (pinned >= 0 || !isLooping) 0f else (phase * stageCount) % 1f

    override fun onPhase(phase: Float) {
        report(currentStage())
    }

    private fun report(stage: Int) {
        if (stage != lastReported) {
            lastReported = stage
            onStageChanged?.invoke(stage)
        }
    }

    fun pin(stage: Int) {
        pinned = if (stage in 0 until stageCount) stage else -1
        report(currentStage())
        invalidate()
    }

    /** Resets so the owner can show a different app's notes from the first stage. */
    fun restart() {
        pinned = -1
        lastReported = -1
        report(currentStage())
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(260), widthMeasureSpec), dp(56))
    }

    private fun nodeX(i: Int): Float {
        val pad = dp(20).toFloat()
        return pad + i * (width - 2 * pad) / (stageCount - 1)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            var best = 0
            var bestDistance = Float.MAX_VALUE
            for (i in 0 until stageCount) {
                val d = abs(event.x - nodeX(i))
                if (d < bestDistance) {
                    bestDistance = d
                    best = i
                }
            }
            pin(if (pinned == best) -1 else best)
            performClick()
        }
        return super.onTouchEvent(event) || event.actionMasked == MotionEvent.ACTION_DOWN
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val stage = currentStage()
        val frac = stageProgress()
        val cy = height / 2f
        val radius = minOf(dp(14).toFloat(), (width - dp(40)) / (stageCount - 1) * 0.36f)

        linePaint.strokeWidth = dp(3).toFloat()
        for (i in 0 until stageCount - 1) {
            linePaint.color = withAlpha(Palette.TEXT, 40)
            canvas.drawLine(nodeX(i), cy, nodeX(i + 1), cy, linePaint)
            val fill = when {
                i < stage -> 1f
                i == stage -> frac
                else -> 0f
            }
            if (fill > 0f) {
                linePaint.color = withAlpha(Palette.CYAN, 220)
                canvas.drawLine(nodeX(i), cy, nodeX(i) + (nodeX(i + 1) - nodeX(i)) * fill, cy, linePaint)
            }
        }

        // Travelling spark
        if (isLooping && pinned < 0 && stage < stageCount - 1) {
            nodePaint.color = withAlpha(0xFFFFFFFF.toInt(), 230)
            canvas.drawCircle(nodeX(stage) + (nodeX(stage + 1) - nodeX(stage)) * frac, cy, dp(3).toFloat(), nodePaint)
        }

        numberPaint.textSize = radius * 1.05f
        for (i in 0 until stageCount) {
            val x = nodeX(i)
            when {
                i == stage -> {
                    val pulse = 0.5f + 0.5f * sin(phase * TWO_PI * 4)
                    nodePaint.color = withAlpha(Palette.CYAN, (60 + 60 * pulse).toInt())
                    canvas.drawCircle(x, cy, radius * (1.35f + 0.2f * pulse), nodePaint)
                    nodePaint.color = Palette.CYAN
                    canvas.drawCircle(x, cy, radius, nodePaint)
                    numberPaint.color = 0xFF04101C.toInt()
                }
                i < stage -> {
                    nodePaint.color = withAlpha(Palette.CYAN, 90)
                    canvas.drawCircle(x, cy, radius, nodePaint)
                    numberPaint.color = Palette.TEXT
                }
                else -> {
                    nodePaint.color = withAlpha(Palette.TEXT, 28)
                    canvas.drawCircle(x, cy, radius, nodePaint)
                    numberPaint.color = Palette.TEXT_DIM
                }
            }
            canvas.drawText((i + 1).toString(), x, cy + radius * 0.36f, numberPaint)
        }
    }

    private companion object {
        const val STAGE_MS = 1_900L
    }
}
