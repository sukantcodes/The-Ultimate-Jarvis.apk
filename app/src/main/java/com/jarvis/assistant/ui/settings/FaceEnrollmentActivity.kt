package com.jarvis.assistant.ui.settings

import android.graphics.Bitmap
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.jarvis.assistant.util.ThemeManager
import java.io.File
import java.io.FileOutputStream

/**
 * Explicit, user-started face reference enrollment. The camera is opened by the system camera app;
 * this screen never captures images in the background. This stores a private reference photo only:
 * face detection is not identity recognition or a secure authentication factor.
 */
class FaceEnrollmentActivity : AppCompatActivity() {
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var captureButton: Button
    private lateinit var removeButton: Button

    private val cameraCapture = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap == null) {
            status.text = "No photo captured."
        } else {
            preview.setImageBitmap(bitmap)
            validateAndSave(bitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 32, 28, 28)
        }
        ThemeManager.applyDynamicBackground(root)

        val title = TextView(this).apply {
            text = "Face Reference Setup"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(title, matchWrap())

        val description = TextView(this).apply {
            text = "Capture a face photo intentionally. If a face is detected, the reference image is stored privately on this device."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }
        root.addView(description, matchWrap())

        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Captured face reference preview"
        }
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        root.addView(status, matchWrap())

        captureButton = Button(this).apply {
            text = "Capture Face Photo"
            setOnClickListener { cameraCapture.launch(null) }
        }
        root.addView(captureButton, matchWrap())

        removeButton = Button(this).apply {
            text = "Remove Saved Photo"
            setOnClickListener { removeEnrollment() }
        }
        root.addView(removeButton, matchWrap())

        val limitation = TextView(this).apply {
            text = "Privacy note: this is local photo enrollment and face detection only. It does not yet identify who is holding the phone, and it does not run the camera in the background."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 0)
        }
        root.addView(limitation, matchWrap())
        setContentView(root)
        refreshStatus()
    }

    private fun validateAndSave(bitmap: Bitmap) {
        status.text = "Checking for a face…"
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
        FaceDetection.getClient(options)
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    status.text = "No face detected. Try again with your face clearly visible."
                    return@addOnSuccessListener
                }
                try {
                    FileOutputStream(referenceFile()).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_ENROLLED, true)
                        .apply()
                    status.text = "Face photo saved privately on this device."
                    Toast.makeText(this, "Face reference saved", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    status.text = "Could not save the photo. Please try again."
                }
            }
            .addOnFailureListener {
                status.text = "Face detection failed. Please try again."
            }
    }

    private fun removeEnrollment() {
        referenceFile().delete()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_ENROLLED).apply()
        preview.setImageDrawable(null)
        refreshStatus()
        Toast.makeText(this, "Saved face photo removed", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        val enrolled = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_ENROLLED, false) && referenceFile().exists()
        status.text = if (enrolled) "A face reference photo is saved on this device."
        else "No face reference photo saved."
        removeButton.isEnabled = enrolled
    }

    private fun referenceFile() = File(filesDir, "face_reference.jpg")

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        const val PREFS = "jarvis_face_reference"
        const val KEY_ENROLLED = "face_photo_enrolled"
    }
}
