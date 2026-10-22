package com.jarvis.assistant.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.jarvis.assistant.util.ThemeManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Guided, user-controlled local face reference enrollment.
 * This stores a reference photo after detecting a face; it is not biometric authentication.
 */
class FaceEnrollmentActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var capture: Button
    private lateinit var retake: Button
    private lateinit var savedPreview: ImageView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraReady = false
    private var checking = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else status.text = "Camera permission is needed to enroll a face."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(18))
            setBackgroundColor(Color.rgb(8, 18, 35))
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
            contentDescription = "Back"
        }
        header.addView(back, LinearLayout.LayoutParams(dp(44), dp(48)))
        header.addView(TextView(this).apply {
            text = "Face enrollment"
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val intro = TextView(this).apply {
            text = "SECURE YOUR JARVIS EXPERIENCE\nCenter your face in the frame and use good lighting."
            textSize = 13f
            setTextColor(0xFFB8D2E8.toInt())
            letterSpacing = .03f
            setPadding(dp(4), dp(8), dp(4), dp(14))
        }
        root.addView(intro)

        val cameraCard = FrameLayout(this).apply {
            background = rounded(0xFF102A46.toInt(), 24, 0xFF35C9F4.toInt())
            clipToOutline = true
        }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        cameraCard.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val faceGuide = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), 0xFF39D6F5.toInt())
            }
            isClickable = false
            contentDescription = "Center your face inside this guide"
        }
        cameraCard.addView(faceGuide, FrameLayout.LayoutParams(dp(210), dp(270), Gravity.CENTER))
        root.addView(cameraCard, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(4)
            bottomMargin = dp(12)
        })

        val stateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(0xFF142D49.toInt(), 18, 0x6639CFF5)
        }
        status = TextView(this).apply {
            text = "Preparing camera…"
            textSize = 15f
            setTextColor(0xFF9CEBFA.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        stateCard.addView(status)
        stateCard.addView(TextView(this).apply {
            text = "Your reference photo stays in this app's private storage."
            textSize = 12f
            setTextColor(0xFF9FB3C9.toInt())
            setPadding(0, dp(5), 0, 0)
        })
        root.addView(stateCard, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        capture = Button(this).apply {
            text = "Capture face"
            isEnabled = false
            setOnClickListener { captureFace() }
        }
        root.addView(capture, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        retake = Button(this).apply {
            text = "Remove saved face reference"
            setOnClickListener { removeEnrollment() }
        }
        root.addView(retake, LinearLayout.LayoutParams(-1, dp(48)))
        savedPreview = ImageView(this).apply {
            visibility = View.GONE
            contentDescription = "Saved face reference"
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
        refreshSavedState()
    }

    private fun startCamera() {
        status.text = "Opening front camera…"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, previewUseCase, imageCapture)
                cameraReady = true
                capture.isEnabled = true
                status.text = "Ready • Keep your face centered"
            } catch (_: Exception) {
                status.text = "Could not open front camera. Check camera access and try again."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureFace() {
        val useCase = imageCapture ?: return
        if (checking) return
        checking = true
        capture.isEnabled = false
        status.text = "Checking your photo…"
        val tempFile = File(cacheDir, "face_capture_temp.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(tempFile).build()
        useCase.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
                tempFile.delete()
                runOnUiThread {
                    if (bitmap == null) {
                        checking = false
                        capture.isEnabled = cameraReady
                        status.text = "Could not read the photo. Please try again."
                    } else validateAndSave(bitmap)
                }
            }
            override fun onError(exception: ImageCaptureException) {
                tempFile.delete()
                runOnUiThread {
                    checking = false
                    capture.isEnabled = cameraReady
                    status.text = "Capture failed. Please try again."
                }
            }
        })
    }

    private fun validateAndSave(bitmap: Bitmap) {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .build()
        )
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                checking = false
                capture.isEnabled = cameraReady
                if (faces.isEmpty()) {
                    bitmap.recycle()
                    status.text = "No face detected • Center your face and try again"
                    return@addOnSuccessListener
                }
                try {
                    FileOutputStream(referenceFile()).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_ENROLLED, true).apply()
                    status.text = "✓ Face reference saved on this device"
                    Toast.makeText(this, "Face reference saved", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    status.text = "Could not save the reference. Please try again."
                } finally {
                    bitmap.recycle()
                    detector.close()
                }
            }
            .addOnFailureListener {
                checking = false
                capture.isEnabled = cameraReady
                status.text = "Face detection failed. Please try again."
                bitmap.recycle()
                detector.close()
            }
    }

    private fun removeEnrollment() {
        referenceFile().delete()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_ENROLLED).apply()
        refreshSavedState()
        Toast.makeText(this, "Saved face reference removed", Toast.LENGTH_SHORT).show()
    }

    private fun refreshSavedState() {
        val enrolled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENROLLED, false) && referenceFile().exists()
        retake.isEnabled = enrolled
        if (!::status.isInitialized) return
        if (!cameraReady) status.text = if (enrolled) "Saved face reference exists • Camera not ready" else "No face reference loaded yet"
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun ImageProxy.toBitmapCompat(): Bitmap? {
        return try {
            val buffer = planes.firstOrNull()?.buffer ?: return null
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
    }

    private fun referenceFile() = File(filesDir, "face_reference.jpg")
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "jarvis_face_reference"
        const val KEY_ENROLLED = "face_photo_enrolled"
    }
}
