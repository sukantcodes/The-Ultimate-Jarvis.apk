package com.jarvis.assistant.study.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.study.model.StudySolution
import com.jarvis.assistant.study.render.StudyDiagramRenderer
import com.jarvis.assistant.study.render.StudyMathRenderer
import com.jarvis.assistant.study.render.StudyWhiteboardView
import com.jarvis.assistant.util.JarvisFonts
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Exports Study Board lessons as clean multi-page A4 vector PDFs and crisp board PNG snapshots.
 */
object StudyExportManager {

    data class ExportedArtifact(
        val fileName: String,
        val mimeType: String,
        val cacheFile: File,
        val shareUri: Uri,
        val savedDisplayPath: String
    )

    private const val A4_WIDTH = 595
    private const val A4_HEIGHT = 842
    private const val MARGIN_X = 36f
    private const val MARGIN_TOP = 38f
    private const val MARGIN_BOTTOM = 40f

    suspend fun exportSolutionPdf(
        context: Context,
        solution: StudySolution
    ): Result<ExportedArtifact> = withContext(Dispatchers.IO) {
        runCatching {
            val appCtx = context.applicationContext
            val doc = PdfDocument()

            val accent = ensureReadablePrintAccent(ThemeManager.getThemeAccentColor())
            val secondary = ensureReadablePrintAccent(ThemeManager.getThemeSecondaryColor())
            val success = ensureReadablePrintAccent(ThemeManager.getThemeSuccessColor())
            val warning = ensureReadablePrintAccent(ThemeManager.getThemeWarningColor())
            val inkDark = ColorUtils.blendARGB(accent, Color.BLACK, 0.86f)
            val inkMuted = ColorUtils.blendARGB(secondary, Color.BLACK, 0.56f)
            val borderCol = ColorUtils.setAlphaComponent(accent, 95)

            val contentW = (A4_WIDTH - MARGIN_X * 2f)
            val innerW = contentW - 24f

            val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                textSize = 9.5f
                typeface = JarvisFonts.displayBold
            }
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkDark
                textSize = 13.5f
                typeface = JarvisFonts.semibold
            }
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkDark
                textSize = 11.2f
                typeface = JarvisFonts.medium
            }
            val mathPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkDark
                textSize = 12.6f
                typeface = JarvisFonts.serifSemibold
            }
            val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkMuted
                textSize = 9.8f
                typeface = JarvisFonts.regular
            }
            val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkDark
                textSize = 9.2f
                typeface = JarvisFonts.mono
            }

            val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.9f
            }

            var pageNumber = 1
            var currentPage = doc.startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, pageNumber).create())
            var canvas = currentPage.canvas
            var y = MARGIN_TOP

            fun drawPageBgAndHeader(c: Canvas, pNum: Int) {
                c.drawColor(Color.WHITE)
                val bar = Paint().apply { color = accent }
                c.drawRect(0f, 0f, A4_WIDTH.toFloat(), 4f, bar)
                c.drawText(
                    "JARVIS LIVE STUDY BOARD  ·  ${solution.subject.displayName.uppercase()}  ·  Page $pNum",
                    MARGIN_X,
                    A4_HEIGHT - 16f,
                    subPaint
                )
            }

            fun startNextPage() {
                doc.finishPage(currentPage)
                pageNumber++
                currentPage = doc.startPage(PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, pageNumber).create())
                canvas = currentPage.canvas
                y = MARGIN_TOP
                drawPageBgAndHeader(canvas, pageNumber)
            }

            fun ensureSpace(neededHeight: Float) {
                if (y + neededHeight > A4_HEIGHT - MARGIN_BOTTOM) {
                    startNextPage()
                }
            }

            drawPageBgAndHeader(canvas, pageNumber)

            // Header Meta
            val metaLine = buildString {
                append(solution.subject.displayName.uppercase())
                if (solution.topic.isNotBlank()) append("  ·  ").append(solution.topic)
                append("  ·  ").append(solution.educationLevel.label)
                append("  ·  ").append(solution.depth.label)
            }
            canvas.drawText(metaLine, MARGIN_X, y + 10f, metaPaint)
            y += 22f

            // Question Card
            val qLayout = staticLayout(StudyMathRenderer.normalizeSymbols(solution.question), titlePaint, innerW.toInt())
            val qBoxH = qLayout.height + 20f
            ensureSpace(qBoxH + 10f)
            val qRect = RectF(MARGIN_X, y, MARGIN_X + contentW, y + qBoxH)
            boxFill.color = ColorUtils.setAlphaComponent(accent, 14)
            boxStroke.color = borderCol
            canvas.drawRoundRect(qRect, 8f, 8f, boxFill)
            canvas.drawRoundRect(qRect, 8f, 8f, boxStroke)
            canvas.save()
            canvas.translate(MARGIN_X + 12f, y + 10f)
            qLayout.draw(canvas)
            canvas.restore()
            y += qBoxH + 12f

            // Given / Required / Formulas
            val infoLines = buildList {
                if (solution.givenValues.isNotEmpty()) add("Given: " + solution.givenValues.joinToString("; "))
                if (solution.requiredQuantity.isNotBlank()) add("Find: " + solution.requiredQuantity)
                if (solution.formulas.isNotEmpty()) add("Formula: " + solution.formulas.joinToString("  |  "))
                if (solution.definitions.isNotEmpty()) add("Key Concept: " + solution.definitions.joinToString("; "))
            }
            if (infoLines.isNotEmpty()) {
                val iLayout = staticLayout(StudyMathRenderer.normalizeSymbols(infoLines.joinToString("\n")), subPaint, innerW.toInt())
                val iH = iLayout.height + 16f
                ensureSpace(iH + 10f)
                val iRect = RectF(MARGIN_X, y, MARGIN_X + contentW, y + iH)
                boxFill.color = ColorUtils.setAlphaComponent(secondary, 14)
                boxStroke.color = ColorUtils.setAlphaComponent(secondary, 95)
                canvas.drawRoundRect(iRect, 6f, 6f, boxFill)
                canvas.drawRoundRect(iRect, 6f, 6f, boxStroke)
                canvas.save()
                canvas.translate(MARGIN_X + 12f, y + 8f)
                iLayout.draw(canvas)
                canvas.restore()
                y += iH + 12f
            }

            // Steps
            for (step in solution.steps) {
                val label = step.label.ifBlank { "Step ${step.stepIndex + 1}" }
                val cleanBody = StudyMathRenderer.normalizeSymbols(step.boardText)
                val bLayout = staticLayout(cleanBody, bodyPaint, innerW.toInt())
                val measuredMaths = step.mathExpressions.map {
                    StudyMathRenderer.measure(it, mathPaint, innerW - 8f, step.highlightTokens)
                }
                val codeLayout = if (step.codeBlock.isNotBlank()) staticLayout(step.codeBlock, monoPaint, innerW.toInt() - 16) else null
                val expLayout = if (step.explanation.isNotBlank()) {
                    staticLayout(StudyMathRenderer.normalizeSymbols(step.explanation), subPaint, innerW.toInt() - 14)
                } else null
                val diagramH = if (step.diagram != null && step.diagram.elements.isNotEmpty()) 148f else 0f

                var stepH = 26f + (if (cleanBody.isNotBlank()) bLayout.height + 8f else 0f)
                measuredMaths.forEach { stepH += it.totalHeight + 6f }
                if (codeLayout != null) stepH += codeLayout.height + 16f
                if (diagramH > 0f) stepH += diagramH + 8f
                if (expLayout != null) stepH += expLayout.height + 14f
                stepH += 10f

                ensureSpace(stepH + 10f)
                val cardRect = RectF(MARGIN_X, y, MARGIN_X + contentW, y + stepH)
                boxFill.color = Color.WHITE
                boxStroke.color = ColorUtils.setAlphaComponent(inkMuted, 60)
                canvas.drawRoundRect(cardRect, 8f, 8f, boxFill)
                canvas.drawRoundRect(cardRect, 8f, 8f, boxStroke)

                var sy = y + 14f
                val stepBadge = if (step.isBookmarked) "$label  ★" else label
                canvas.drawText(stepBadge.uppercase(), MARGIN_X + 12f, sy + 4f, metaPaint)
                sy += 12f

                if (cleanBody.isNotBlank()) {
                    canvas.save()
                    canvas.translate(MARGIN_X + 12f, sy)
                    bLayout.draw(canvas)
                    canvas.restore()
                    sy += bLayout.height + 8f
                }

                for (m in measuredMaths) {
                    StudyMathRenderer.draw(
                        canvas = canvas,
                        measured = m,
                        x = MARGIN_X + 14f,
                        y = sy,
                        progress = 1f,
                        textPaint = mathPaint,
                        accentColor = accent,
                        highlightBgColor = ColorUtils.setAlphaComponent(accent, 28)
                    )
                    sy += m.totalHeight + 6f
                }

                if (codeLayout != null) {
                    val codeRect = RectF(MARGIN_X + 12f, sy, MARGIN_X + contentW - 12f, sy + codeLayout.height + 10f)
                    boxFill.color = ColorUtils.setAlphaComponent(inkDark, 10)
                    canvas.drawRoundRect(codeRect, 5f, 5f, boxFill)
                    canvas.save()
                    canvas.translate(codeRect.left + 8f, codeRect.top + 5f)
                    codeLayout.draw(canvas)
                    canvas.restore()
                    sy += codeRect.height() + 8f
                }

                if (step.diagram != null && diagramH > 0f) {
                    val dRect = RectF(MARGIN_X + 12f, sy, MARGIN_X + contentW - 12f, sy + diagramH)
                    StudyDiagramRenderer.drawDiagram(
                        canvas = canvas,
                        spec = step.diagram,
                        bounds = dRect,
                        progress = 1f,
                        palette = StudyDiagramRenderer.DiagramPalette(
                            cardFillColor = ColorUtils.setAlphaComponent(accent, 8),
                            cardBorderColor = borderCol,
                            gridColor = ColorUtils.setAlphaComponent(inkMuted, 28),
                            inkColor = inkDark,
                            accentColor = accent,
                            secondaryColor = secondary,
                            successColor = success,
                            labelBgColor = Color.WHITE
                        ),
                        density = 0.85f
                    )
                    sy += diagramH + 8f
                }

                if (expLayout != null) {
                    val expRect = RectF(MARGIN_X + 12f, sy, MARGIN_X + contentW - 12f, sy + expLayout.height + 10f)
                    boxFill.color = ColorUtils.setAlphaComponent(accent, 14)
                    canvas.drawRoundRect(expRect, 5f, 5f, boxFill)
                    canvas.save()
                    canvas.translate(expRect.left + 7f, expRect.top + 5f)
                    expLayout.draw(canvas)
                    canvas.restore()
                }

                y += stepH + 10f
            }

            // Final Answer
            if (solution.finalAnswer.isNotBlank()) {
                val fLayout = staticLayout(StudyMathRenderer.normalizeSymbols(solution.finalAnswer), titlePaint, innerW.toInt())
                val fH = fLayout.height + 32f
                ensureSpace(fH + 10f)
                val fRect = RectF(MARGIN_X, y, MARGIN_X + contentW, y + fH)
                boxFill.color = ColorUtils.setAlphaComponent(success, 18)
                boxStroke.color = success
                canvas.drawRoundRect(fRect, 8f, 8f, boxFill)
                canvas.drawRoundRect(fRect, 8f, 8f, boxStroke)
                val oldColor = metaPaint.color
                metaPaint.color = success
                canvas.drawText("FINAL ANSWER", MARGIN_X + 12f, y + 16f, metaPaint)
                metaPaint.color = oldColor
                canvas.save()
                canvas.translate(MARGIN_X + 12f, y + 24f)
                fLayout.draw(canvas)
                canvas.restore()
                y += fH + 12f
            }

            // Doubts Clarifications
            if (solution.doubts.isNotEmpty()) {
                ensureSpace(44f)
                val oldColor = metaPaint.color
                metaPaint.color = warning
                canvas.drawText("CLARIFICATIONS & DOUBTS", MARGIN_X, y + 12f, metaPaint)
                metaPaint.color = oldColor
                y += 20f
                for (d in solution.doubts) {
                    val dText = "Q (${d.targetFocus}): ${d.question}\n${d.boardSummary}\n${d.explanation}".trim()
                    val dl = staticLayout(StudyMathRenderer.normalizeSymbols(dText), subPaint, innerW.toInt())
                    val dh = dl.height + 14f
                    ensureSpace(dh + 8f)
                    val dRect = RectF(MARGIN_X, y, MARGIN_X + contentW, y + dh)
                    boxFill.color = ColorUtils.setAlphaComponent(warning, 14)
                    boxStroke.color = warning
                    canvas.drawRoundRect(dRect, 6f, 6f, boxFill)
                    canvas.drawRoundRect(dRect, 6f, 6f, boxStroke)
                    canvas.save()
                    canvas.translate(MARGIN_X + 10f, y + 7f)
                    dl.draw(canvas)
                    canvas.restore()
                    y += dh + 8f
                }
            }

            doc.finishPage(currentPage)

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeTopic = solution.topic.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24).ifBlank { "Lesson" }
            val fileName = "JARVIS_Study_${safeTopic}_$stamp.pdf"
            val exportDir = File(appCtx.cacheDir, "study_exports").apply { mkdirs() }
            val cacheFile = File(exportDir, fileName)
            FileOutputStream(cacheFile).use { fos -> doc.writeTo(fos) }
            doc.close()

            val publicPath = savePdfToDownloads(appCtx, cacheFile, fileName)
            val shareUri = FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", cacheFile)

            ExportedArtifact(
                fileName = fileName,
                mimeType = "application/pdf",
                cacheFile = cacheFile,
                shareUri = shareUri,
                savedDisplayPath = publicPath
            )
        }
    }

    suspend fun exportBoardSnapshot(
        context: Context,
        whiteboardView: StudyWhiteboardView,
        solution: StudySolution
    ): Result<ExportedArtifact> {
        val bitmap = withContext(Dispatchers.Main) {
            whiteboardView.renderSnapshotBitmap(targetWidthPx = 1440)
        } ?: return Result.failure(IllegalStateException("Nothing on the whiteboard yet to export."))

        return withContext(Dispatchers.IO) {
            try {
                runCatching {
                    val appCtx = context.applicationContext
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val safeTopic = solution.topic.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24).ifBlank { "Board" }
                    val fileName = "JARVIS_Board_${safeTopic}_$stamp.png"
                    val exportDir = File(appCtx.cacheDir, "study_exports").apply { mkdirs() }
                    val cacheFile = File(exportDir, fileName)
                    FileOutputStream(cacheFile).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 96, fos)
                    }
                    val savedPath = saveImageToPictures(appCtx, cacheFile, fileName)
                    val shareUri = FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", cacheFile)
                    ExportedArtifact(
                        fileName = fileName,
                        mimeType = "image/png",
                        cacheFile = cacheFile,
                        shareUri = shareUri,
                        savedDisplayPath = savedPath
                    )
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun savePdfToDownloads(context: Context, source: File, fileName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/JARVIS Study Board")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = runCatching {
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()
            if (uri != null) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    }
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    )
                    return "Downloads/JARVIS Study Board/$fileName"
                }
            }
        }
        val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val outFile = File(docsDir, fileName)
        source.copyTo(outFile, overwrite = true)
        return outFile.absolutePath
    }

    private fun saveImageToPictures(context: Context, source: File, fileName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/JARVIS Study Board")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = runCatching {
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()
            if (uri != null) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    }
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null
                    )
                    return "Pictures/JARVIS Study Board/$fileName"
                }
            }
        }
        val picDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
        val outFile = File(picDir, fileName)
        source.copyTo(outFile, overwrite = true)
        return outFile.absolutePath
    }

    private fun ensureReadablePrintAccent(color: Int): Int {
        // Darken overly pale/bright neon theme accents slightly so A4 white-paper export stays legible.
        val lum = ColorUtils.calculateLuminance(color)
        return if (lum > 0.45) ColorUtils.blendARGB(color, Color.BLACK, 0.36f) else color
    }

    private fun staticLayout(text: CharSequence, paint: TextPaint, widthPx: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(64, widthPx))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.15f)
            .setIncludePad(false)
            .build()
}
