package com.jarvis.assistant.ui.owner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.jarvis.assistant.R
import com.jarvis.assistant.owner.OwnerPalette
import kotlin.math.ceil
import kotlin.math.max

private fun View.dp(v: Float): Float = v * resources.displayMetrics.density

private fun View.themeColor(attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
}

/** Rounds up to a tidy axis maximum (4, 5, 8, 10, 20, 40, 50...) so gridline labels are whole numbers. */
private fun niceMax(value: Int): Int {
    if (value <= 4) return 4
    var step = 1
    while (step * 4 < value) {
        step = when {
            step < 5 -> step + 1
            step < 10 -> 10
            else -> step * 2
        }
    }
    return step * 4
}

/** Multi-series line chart with area fill, gridlines, sparse date labels and an emphasised last point. */
class TrendChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    data class Series(val name: String, val color: Int, val values: List<Int>)

    private var labels: List<String> = emptyList()
    private var series: List<Series> = emptyList()
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10f); typeface = Typeface.MONOSPACE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.5f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setData(newLabels: List<String>, newSeries: List<Series>) {
        labels = newLabels; series = newSeries; invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), dp(220f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = labels.size
        if (n == 0 || series.isEmpty()) return
        val muted = themeColor(R.attr.themeTextMuted, OwnerPalette.GREY)
        grid.color = (muted and 0x00FFFFFF) or 0x33000000
        text.color = muted

        val left = dp(30f); val right = width - dp(10f); val top = dp(10f); val bottom = height - dp(24f)
        val yMax = niceMax(series.maxOf { s -> s.values.maxOrNull() ?: 0 })
        for (i in 0..4) {
            val y = bottom - (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, grid)
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText((yMax * i / 4).toString(), left - dp(5f), y + dp(3.5f), text)
        }
        fun x(i: Int) = if (n == 1) (left + right) / 2f else left + (right - left) * i / (n - 1f)
        fun y(v: Int) = bottom - (bottom - top) * v / yMax.toFloat()

        series.forEach { s ->
            if (s.values.isEmpty()) return@forEach
            val path = Path()
            s.values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            val area = Path(path).apply { lineTo(x(s.values.size - 1), bottom); lineTo(x(0), bottom); close() }
            fill.color = (s.color and 0x00FFFFFF) or 0x22000000
            canvas.drawPath(area, fill)
            line.color = s.color
            canvas.drawPath(path, line)
            dot.color = s.color
            canvas.drawCircle(x(s.values.size - 1), y(s.values.last()), dp(4f), dot)
        }

        val step = max(1, ceil(n / 6.0).toInt())
        text.textAlign = Paint.Align.CENTER
        for (i in 0 until n step step) canvas.drawText(labels[i], x(i), height - dp(6f), text)
    }
}

/** Ranked horizontal bars: label, proportional bar, count. Used for models, versions and sign-in methods. */
class DistributionView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var items: List<Pair<String, Int>> = emptyList()
    private var colors: IntArray = OwnerPalette.CYCLE
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13f); typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setData(data: List<Pair<String, Int>>, palette: IntArray = OwnerPalette.CYCLE) {
        items = data; colors = palette; requestLayout(); invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (items.size * dp(34f) + dp(4f)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return
        val primary = themeColor(R.attr.themeTextPrimary, 0xFFFFFFFF.toInt())
        val muted = themeColor(R.attr.themeTextMuted, OwnerPalette.GREY)
        labelPaint.color = primary; countPaint.color = primary
        track.color = (muted and 0x00FFFFFF) or 0x22000000

        val labelW = width * 0.38f
        val countW = dp(36f)
        val barLeft = labelW + dp(8f)
        val barRight = width - countW - dp(8f)
        val maxV = max(1, items.maxOf { it.second })
        items.forEachIndexed { i, (label, count) ->
            val cy = dp(2f) + dp(34f) * i + dp(17f)
            val shown = TextUtils.ellipsize(label, android.text.TextPaint(labelPaint), labelW, TextUtils.TruncateAt.END)
            canvas.drawText(shown.toString(), 0f, cy + dp(4.5f), labelPaint)
            rect.set(barLeft, cy - dp(5f), barRight, cy + dp(5f))
            canvas.drawRoundRect(rect, dp(5f), dp(5f), track)
            bar.color = colors[i % colors.size]
            rect.set(barLeft, cy - dp(5f), barLeft + (barRight - barLeft) * count / maxV.toFloat(), cy + dp(5f))
            canvas.drawRoundRect(rect, dp(5f), dp(5f), bar)
            canvas.drawText(count.toString(), width.toFloat(), cy + dp(4.5f), countPaint)
        }
    }
}
