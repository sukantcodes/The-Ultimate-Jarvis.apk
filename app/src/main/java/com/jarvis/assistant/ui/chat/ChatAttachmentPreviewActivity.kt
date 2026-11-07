package com.jarvis.assistant.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Private, in-app attachment viewer. It never exposes the selected file through a public URI
 * and only opens files stored in this app's chat_attachments directory.
 */
class ChatAttachmentPreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "chat_preview_path"
        const val EXTRA_NAME = "chat_preview_name"
        const val EXTRA_MIME = "chat_preview_mime"
        private const val MAX_PREVIEW_FILE_BYTES = 256L * 1024L * 1024L
        private const val MAX_RENDER_WIDTH = 1600
    }

    private lateinit var content: FrameLayout
    private lateinit var pageControls: View
    private lateinit var pageLabel: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdfPageIndex = 0
    private var videoView: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_attachment_preview)
        content = findViewById(R.id.previewContent)
        pageControls = findViewById(R.id.pdfControls)
        pageLabel = findViewById(R.id.tvPdfPage)
        previousButton = findViewById(R.id.btnPdfPrevious)
        nextButton = findViewById(R.id.btnPdfNext)
        findViewById<ImageButton>(R.id.btnPreviewClose).setOnClickListener { finish() }

        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "Attachment" }
        val mime = intent.getStringExtra(EXTRA_MIME).orEmpty()
        findViewById<TextView>(R.id.tvPreviewTitle).text = name
        findViewById<TextView>(R.id.tvPreviewSubtitle).text = mime.ifBlank { "LOCAL ATTACHMENT" }.uppercase()

        val file = File(path).canonicalFile
        val privateDirectory = File(filesDir, "chat_attachments").canonicalFile
        if (!file.isFile || !file.path.startsWith(privateDirectory.path + File.separator) ||
            file.length() > MAX_PREVIEW_FILE_BYTES
        ) {
            showMessage("This attachment is no longer available or is too large to preview.")
            return
        }

        previousButton.setOnClickListener { renderPdfPage(pdfPageIndex - 1) }
        nextButton.setOnClickListener { renderPdfPage(pdfPageIndex + 1) }
        when {
            mime.startsWith("image/") || file.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp", "gif") ->
                showImage(file)
            mime.startsWith("video/") || file.extension.lowercase() in listOf("mp4", "m4v", "3gp", "webm", "mov") ->
                showVideo(file)
            mime == "application/pdf" || file.extension.equals("pdf", true) ->
                openPdf(file)
            file.extension.lowercase() in listOf("xlsx", "pptx", "docx", "ods", "odp", "odt") ->
                showOfficeText(file, name, mime)
            file.extension.lowercase() in listOf("xls", "ppt") ->
                showMessage("This is a legacy binary Office file. Convert it to .xlsx or .pptx to preview it inside JARVIS. Your original attachment is unchanged.")
            else -> showOfficeText(file, name, mime)
        }
    }

    private fun showImage(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            showMessage("JARVIS couldn't decode this image.")
            return
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_RENDER_WIDTH * 2 ||
            bounds.outHeight / sample > MAX_RENDER_WIDTH * 2
        ) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
        if (bitmap == null) {
            showMessage("This image format isn't supported by the device image decoder.")
            return
        }
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Preview of ${file.name}"
        }
        content.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun showVideo(file: File) {
        val player = VideoView(this)
        videoView = player
        val controller = MediaController(this)
        controller.setAnchorView(player)
        player.setMediaController(controller)
        player.setVideoURI(Uri.fromFile(file))
        player.setOnErrorListener { _, what, extra ->
            showMessage("This video format or codec isn't supported on this device (error $what/$extra).")
            true
        }
        content.addView(player, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        player.setOnPreparedListener { media ->
            media.isLooping = false
            player.start()
        }
    }

    private fun openPdf(file: File) {
        try {
            pdfDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pdfDescriptor!!)
            if (pdfRenderer!!.pageCount <= 0) throw IllegalStateException("The PDF has no pages.")
            pdfPageIndex = 0
            pageControls.visibility = View.VISIBLE
            renderPdfPage(0)
        } catch (error: Exception) {
            showMessage("JARVIS couldn't render this PDF: ${error.message ?: "unsupported or damaged PDF"}.")
        }
    }

    private fun renderPdfPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index !in 0 until renderer.pageCount) return
        pdfPageIndex = index
        content.removeAllViews()
        try {
            renderer.openPage(index).use { page ->
                val targetWidth = minOf(MAX_RENDER_WIDTH, resources.displayMetrics.widthPixels - dp(56)).coerceAtLeast(320)
                val scale = targetWidth.toFloat() / page.width.toFloat()
                val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val image = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    contentDescription = "PDF page ${index + 1}"
                }
                val scroll = ScrollView(this).apply { addView(image) }
                content.addView(scroll, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }
            pageLabel.text = "${index + 1} / ${renderer.pageCount}"
            previousButton.isEnabled = index > 0
            nextButton.isEnabled = index < renderer.pageCount - 1
        } catch (error: Exception) {
            showMessage("Couldn't render PDF page ${index + 1}: ${error.message.orEmpty()}")
        }
    }

    private fun showOfficeText(file: File, name: String, mime: String) {
        showMessage("Preparing preview…")
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    DocumentAnalysisClient.previewOfficeText(file.absolutePath, name, mime)
                }
            }
            result.onSuccess { text ->
                content.removeAllViews()
                val body = TextView(this@ChatAttachmentPreviewActivity).apply {
                    setText(text)
                    setTextIsSelectable(true)
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 14f
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                }
                val scroll = ScrollView(this@ChatAttachmentPreviewActivity).apply { addView(body) }
                content.addView(scroll, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
            }.onFailure { error ->
                showMessage(error.message ?: "JARVIS couldn't preview this document.")
            }
        }
    }

    private fun showMessage(message: String) {
        content.removeAllViews()
        pageControls.visibility = View.GONE
        val label = TextView(this).apply {
            text = message
            setTextColor(getColor(com.jarvis.assistant.R.color.white))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        videoView?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        videoView?.stopPlayback()
        pdfRenderer?.close()
        pdfDescriptor?.close()
        super.onDestroy()
    }
}
