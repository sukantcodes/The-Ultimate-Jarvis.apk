package com.jarvis.assistant.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.util.ThemeManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live camera scanner. Frames are sent only while the user has opened Scan and enabled Live Scan.
 */
class CameraScanActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var toggleButton: Button
    private lateinit var cameraExecutor: ExecutorService
    @Volatile private var liveScanEnabled = false
    @Volatile private var lastFrameAt = 0L

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else status.text = "Camera permission is required to scan objects or paper."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(20), dp(16), dp(18))
        }
        ThemeManager.applyDynamicBackground(root)

        root.addView(TextView(this).apply {
            text = "J.A.R.V.I.S  /  LIVE SCAN"
            textSize = 22f
            letterSpacing = 0.05f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }, matchWrap())

        root.addView(TextView(this).apply {
            text = "Point your camera at an object or paper. JARVIS will describe the live view and read visible text."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFB8C8D9.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(12))
        }, matchWrap())

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            background = GradientDrawable().apply {
                setColor(0x221FFFFF)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x6655DDF5)
            }
        }
        root.addView(previewView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        status = TextView(this).apply {
            text = "Requesting camera permission…"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF9FE8F5.toInt())
            setPadding(0, dp(12), 0, dp(10))
        }
        root.addView(status, matchWrap())

        toggleButton = Button(this).apply {
            text = "Start Live Scan"
            isEnabled = false
            setOnClickListener { setLiveScanEnabled(!liveScanEnabled) }
        }
        root.addView(toggleButton, matchWrap())

        root.addView(TextView(this).apply {
            text = "Live scan sends camera frames to your active JARVIS AI session. A visible indicator stays on while sharing; tap Stop to end it."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF8D9AAA.toInt())
            setPadding(0, dp(8), 0, 0)
        }, matchWrap())

        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        status.text = "Starting camera preview…"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        if (liveScanEnabled) processFrame(imageProxy)
                    } catch (_: Exception) {
                        // Keep the live preview running if an individual frame cannot be encoded.
                    } finally {
                        imageProxy.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                toggleButton.isEnabled = true
                setLiveScanEnabled(true)
            } catch (e: Exception) {
                status.text = "Could not start camera: ${e.localizedMessage ?: "camera unavailable"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < 1000L) return
        lastFrameAt = now
        val bitmap = imageProxy.toBitmap()
        val scaled = if (bitmap.width > 640) {
            val height = (bitmap.height * (640f / bitmap.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, 640, height, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else bitmap
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 72, output)
        scaled.recycle()
        val sent = JarvisConversationService.sendLiveScanFrame(output.toByteArray())
        runOnUiThread {
            status.text = if (sent) {
                "LIVE SCAN • Sharing camera frames with JARVIS"
            } else {
                "Camera is live, but JARVIS must be in LIVE voice mode to analyze it."
            }
        }
    }

    private fun setLiveScanEnabled(enabled: Boolean) {
        liveScanEnabled = enabled
        lastFrameAt = 0L
        if (enabled) {
            JarvisConversationService.resetLiveScanFrames()
            toggleButton.text = "Stop Live Scan"
            status.text = "LIVE SCAN • Point at an object or paper"
            Toast.makeText(this, "Live camera scan started", Toast.LENGTH_SHORT).show()
        } else {
            toggleButton.text = "Start Live Scan"
            status.text = "CAMERA READY • Live sharing is OFF"
            Toast.makeText(this, "Live camera scan stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        if (::toggleButton.isInitialized && ::status.isInitialized) setLiveScanEnabled(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
