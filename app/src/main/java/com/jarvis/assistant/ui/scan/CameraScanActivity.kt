package com.jarvis.assistant.ui.scan

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.util.ThemeManager
import java.io.ByteArrayOutputStream

/** User-initiated camera scan. The system camera is opened explicitly; no background capture occurs. */
class CameraScanActivity : AppCompatActivity() {
    private var capturedBitmap: Bitmap? = null
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var analyzeButton: Button

    private val cameraCapture = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        capturedBitmap = bitmap
        if (bitmap == null) {
            status.text = "No image captured. Tap Capture to try again."
            analyzeButton.isEnabled = false
        } else {
            preview.setImageBitmap(bitmap)
            status.text = "Image captured. Ready for JARVIS analysis."
            analyzeButton.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
        }
        ThemeManager.applyDynamicBackground(root)

        root.addView(TextView(this).apply {
            text = "JARVIS SCAN"
            textSize = 25f
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }, matchWrap())

        root.addView(TextView(this).apply {
            text = "Capture a scene or object, then ask JARVIS to explain what it sees."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFB8C8D9.toInt())
            setPadding(0, dp(8), 0, dp(14))
        }, matchWrap())

        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Camera scan preview"
            background = GradientDrawable().apply {
                setColor(0x221FFFFF)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x6655DDF5)
            }
        }
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        status = TextView(this).apply {
            text = "Opening camera…"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF9FE8F5.toInt())
            setPadding(0, dp(14), 0, dp(12))
        }
        root.addView(status, matchWrap())

        val captureButton = Button(this).apply {
            text = "Capture / Retake"
            setOnClickListener { cameraCapture.launch(null) }
        }
        root.addView(captureButton, matchWrap())

        analyzeButton = Button(this).apply {
            text = "Analyze with JARVIS"
            isEnabled = false
            setOnClickListener { analyzeCapturedImage() }
        }
        root.addView(analyzeButton, matchWrap())

        root.addView(TextView(this).apply {
            text = "Privacy: the photo is sent to your active JARVIS AI session for analysis. It is not captured in the background."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF8D9AAA.toInt())
            setPadding(0, dp(10), 0, 0)
        }, matchWrap())

        setContentView(root)
        // Open the camera immediately when the user selects Scan.
        root.post { cameraCapture.launch(null) }
    }

    private fun analyzeCapturedImage() {
        val bitmap = capturedBitmap ?: return
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
        val sent = JarvisConversationService.sendScanImage(output.toByteArray())
        if (sent) {
            status.text = "Sent to JARVIS. Ask it to describe the scan; voice response will arrive in the active session."
            Toast.makeText(this, "Scan sent to JARVIS", Toast.LENGTH_LONG).show()
        } else {
            status.text = "Start JARVIS voice mode and wait until it is LIVE, then tap Analyze again."
            Toast.makeText(this, "JARVIS must be LIVE before analyzing a scan.", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
