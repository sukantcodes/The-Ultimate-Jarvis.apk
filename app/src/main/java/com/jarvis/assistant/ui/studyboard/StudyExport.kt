package com.jarvis.assistant.ui.studyboard

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.jarvis.assistant.study.BoardBlock
import com.jarvis.assistant.study.Ink
import com.jarvis.assistant.study.StepKind
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.min
import kotlin.math.sqrt

/** Exports the finished board as clean notes (PDF) or as one picture. Both draw exactly what the board shows. */
object StudyExport {

    class ExportException(message: String) : Exception(message)

    private fun lowRam(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    private fun drawBlock(painter: StudyPainter, canvas: Canvas, b: BoardBlock, showNotes: Boolean, bookmarks: Set<Int>) {
        if (!b.isQuestion) {
            when {
                b.isFinal -> painter.drawBlockBackground(canvas, b, StudyBoardView.BOARD_WIDTH, StudyBoardView.MARGIN, StudyPalette.color(Ink.RESULT), 22)
                b.kind == StepKind.CODE -> painter.drawBlockBackground(canvas, b, StudyBoardView.BOARD_WIDTH, StudyBoardView.MARGIN, StudyPalette.color(Ink.TEAL), 12)
            }
        }
        painter.drawOps(canvas, b.allOps(showNotes), 1f)
        if (!b.isQuestion && bookmarks.contains(b.stepIndex)) painter.drawBookmark(canvas, b, StudyBoardView.BOARD_WIDTH, StudyBoardView.MARGIN, StudyPalette.color(Ink.GOLD))
    }

    /** One tall PNG of the whole board. The picture is scaled down on low-memory phones so it can never run out of memory. */
    fun writeImage(context: Context, blocks: List<BoardBlock>, showNotes: Boolean, bookmarks: Set<Int>, out: OutputStream) {
        if (blocks.isEmpty()) throw ExportException("The board is empty.")
        val heightUnits = blocks.last().bottom + 40f
        val maxPixels = if (lowRam(context)) 5_000_000f else 16_000_000f
        val scale = min(1.6f, sqrt(maxPixels / (StudyBoardView.BOARD_WIDTH * heightUnits)))
        val w = (StudyBoardView.BOARD_WIDTH * scale).toInt().coerceAtLeast(300)
        val h = (heightUnits * scale).toInt().coerceAtLeast(300)
        val bmp = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        catch (e: OutOfMemoryError) { throw ExportException("Not enough memory to export this board as an image. Export it as a PDF instead.") }
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(StudyPalette.PAPER)
            canvas.scale(scale, scale)
            val painter = StudyPainter(StudyFonts(context))
            for (b in blocks) drawBlock(painter, canvas, b, showNotes, bookmarks)
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw ExportException("Could not write the image.")
            out.flush()
        } finally { bmp.recycle() }
    }

    /** Paginated notes: a header, then the question, steps, answer and doubts. A step is never split across two pages. */
    fun writePdf(context: Context, title: String, subtitle: String, blocks: List<BoardBlock>, showNotes: Boolean, bookmarks: Set<Int>, out: OutputStream) {
        if (blocks.isEmpty()) throw ExportException("The board is empty.")
        val pageW = 595; val pageH = 842; val margin = 36f
        val scale = (pageW - margin * 2) / StudyBoardView.BOARD_WIDTH
        val top = margin + 40f
        val bottom = pageH - margin - 18f
        val doc = PdfDocument()
        val painter = StudyPainter(StudyFonts(context))
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 31, 43); textSize = 15f; isFakeBoldText = true }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 101, 120); textSize = 10f }
        try {
            var pageNo = 0
            var page: PdfDocument.Page? = null
            var canvas: Canvas? = null
            var y = top
            fun startPage() {
                page?.let { footer(canvas!!, sub, pageNo, pageW, pageH); doc.finishPage(it) }
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                canvas = page!!.canvas
                canvas!!.drawColor(Color.WHITE)
                if (pageNo == 1) {
                    canvas!!.drawText(title.take(70), margin, margin + 8f, head)
                    canvas!!.drawText((subtitle + "  ·  " + DateFormat.getDateInstance().format(Date())).take(110), margin, margin + 24f, sub)
                }
                y = if (pageNo == 1) top else margin
            }
            startPage()
            for (b in blocks) {
                var s = scale
                var h = b.height * s
                val room = bottom - (if (pageNo == 1) top else margin)
                if (h > room) { s = room / b.height; h = room }
                if (y + h > bottom && y > (if (pageNo == 1) top else margin)) startPage()
                val c = canvas!!
                c.save()
                c.translate(margin, y - b.top * s)
                c.scale(s, s)
                drawBlock(painter, c, b, showNotes, bookmarks)
                c.restore()
                y += h
            }
            footer(canvas!!, sub, pageNo, pageW, pageH)
            doc.finishPage(page!!)
            doc.writeTo(out)
            out.flush()
        } finally { doc.close() }
    }

    private fun footer(c: Canvas, p: Paint, n: Int, w: Int, h: Int) {
        c.drawText("JARVIS Study Board  ·  page $n", 36f, h - 26f, p)
    }
}
