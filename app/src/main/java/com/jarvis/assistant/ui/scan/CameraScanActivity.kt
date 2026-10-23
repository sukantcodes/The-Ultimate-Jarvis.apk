package com.jarvis.assistant.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.animation.ValueAnimator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.databinding.ActivityCameraScanBinding
import com.jarvis.assistant.util.PermissionHelper
import com.jarvis.assistant.util.SpeechAnnouncer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live visual assistant. The camera is active only while this foreground screen is visible.
 * It samples one frame at a time and sends it to Gemini for scene understanding; it does not
 * continuously upload a full-rate camera stream or save every frame to storage.
 */
class CameraScanActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraScanBinding
    private lateinit var appPreferences: AppPreferences
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var cameraId: String? = null
    private var torchEnabled = false
    private var scanAnimator: ValueAnimator? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var speechAnnouncer: SpeechAnnouncer? = null
    private val analysisInProgress = AtomicBoolean(false)
    private val analysisClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    @Volatile private var manualSaveRequested = false
    @Volatile private var capturePending = false
    @Volatile private var lastSpokenInsight = ""
    @Volatile private var lastSpokenAt = 0L
    @Volatile private var activityVisible = false
    private var apiKeyMissingReported = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCameraIfReady() else {
            binding.tvScanStatus.text = "CAMERA PERMISSION REQUIRED"
            Toast.makeText(this, "Camera permission is required for live scanning.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        appPreferences = AppPreferences(this)
        speechAnnouncer = SpeechAnnouncer(this)
        binding.btnScanClose.setOnClickListener { finish() }
        binding.btnScanTorch.setOnClickListener {
            torchEnabled = !torchEnabled
            updateTorch()
            binding.btnScanTorch.text = if (torchEnabled) "FLASH ON" else "FLASH OFF"
        }
        // This button remains an optional save-a-photo action. Live understanding starts by itself.
        binding.btnScanCapture.setOnClickListener {
            manualSaveRequested = true
            if (capturePending) {
                binding.tvScanStatus.text = "CAPTURE QUEUED"
            } else {
                captureStillFrame()
            }
        }
        binding.cameraPreview.surfaceTextureListener = surfaceListener
        scanAnimator = ValueAnimator.ofFloat(-220f, 220f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                binding.scanLaserLine.translationY = animation.animatedValue as Float
            }
        }
        startCameraThread()
        if (PermissionHelper.hasCameraPermission(this)) openCameraIfReady()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCameraThread() {
        cameraThread = HandlerThread("JarvisCameraScan").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = openCameraIfReady()
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = configurePreview()
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCameraIfReady() {
        if (!activityVisible || cameraDevice != null) return
        if (!binding.cameraPreview.isAvailable || !PermissionHelper.hasCameraPermission(this)) return
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
            val id = cameraId ?: run {
                binding.tvScanStatus.text = "NO CAMERA AVAILABLE"
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!activityVisible) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    configurePreview()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    runOnUiThread { binding.tvScanStatus.text = "CAMERA ERROR $error" }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            binding.tvScanStatus.text = "CAMERA UNAVAILABLE"
            Toast.makeText(this, "Unable to start camera: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun configurePreview() {
        val camera = cameraDevice ?: return
        val texture = binding.cameraPreview.surfaceTexture ?: return
        try {
            texture.setDefaultBufferSize(binding.cameraPreview.width.coerceAtLeast(640), binding.cameraPreview.height.coerceAtLeast(480))
            val previewSurface = Surface(texture)
            val reader = ImageReader.newInstance(1280, 720, android.graphics.ImageFormat.JPEG, 2)
            imageReader?.close()
            imageReader = reader
            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bytes = try {
                    val buffer = image.planes[0].buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } finally {
                    image.close()
                    capturePending = false
                }
                if (manualSaveRequested) {
                    manualSaveRequested = false
                    saveCapturedImage(bytes)
                }
                if (activityVisible) analyzeFrame(bytes)
            }, cameraHandler)
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            previewRequestBuilder = builder
            camera.createCaptureSession(listOf(previewSurface, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!activityVisible) {
                        session.close()
                        return
                    }
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        runOnUiThread {
                            binding.tvScanStatus.text = "LIVE CAMERA • STARTING VISUAL AI"
                            binding.tvScanInsight.text = "JARVIS is opening the live visual assistant…"
                        }
                        scheduleNextAnalysis(900L)
                    } catch (_: Exception) {
                        runOnUiThread { binding.tvScanStatus.text = "PREVIEW START FAILED" }
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    runOnUiThread { binding.tvScanStatus.text = "CAMERA CONFIGURATION FAILED" }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Camera preview configuration failed", e)
            binding.tvScanStatus.text = "PREVIEW ERROR"
        }
    }

    private fun scheduleNextAnalysis(delayMs: Long = ANALYSIS_INTERVAL_MS) {
        cameraHandler?.postDelayed({
            if (!activityVisible || cameraDevice == null || captureSession == null) return@postDelayed
            if (capturePending) {
                scheduleNextAnalysis()
                return@postDelayed
            }
            val key = appPreferences.apiKey
            if (key.isBlank()) {
                runOnUiThread {
                    binding.tvScanStatus.text = "API KEY REQUIRED FOR LIVE AI"
                    binding.tvScanInsight.text = "Add your Gemini API key in JARVIS Settings → API Connection. The camera preview works, but visual understanding needs an AI key."
                }
                if (!apiKeyMissingReported) {
                    apiKeyMissingReported = true
                    speechAnnouncer?.say("Please add your Gemini API key in settings to enable live visual understanding.")
                }
                return@postDelayed
            }
            captureStillFrame()
            scheduleNextAnalysis()
        }, delayMs)
    }

    private fun captureStillFrame() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        if (capturePending) return
        capturePending = true
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, 90)
                set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    capturePending = false
                    Log.w(TAG, "Live scan frame capture failed: ${failure.reason}")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            capturePending = false
            Log.w(TAG, "Could not capture live analysis frame", e)
            runOnUiThread { binding.tvScanStatus.text = "FRAME CAPTURE FAILED • RETRYING" }
        }
    }

    private fun analyzeFrame(jpegBytes: ByteArray) {
        val key = AppPreferences(this).apiKey
        if (key.isBlank() || !analysisInProgress.compareAndSet(false, true)) return
        runOnUiThread { binding.tvScanStatus.text = "LIVE VISUAL ANALYSIS • THINKING" }
        Thread({
            try {
                val imageData = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                val parts = JSONArray()
                    .put(JSONObject().put("text",
                        "You are JARVIS, a real-time visual assistant looking at a phone camera frame. " +
                        "Describe only what is visibly supported by this image. Identify the main objects, text, " +
                        "actions, hazards or useful details. Be concise, natural and practical, in 1-3 sentences. " +
                        "If the image is unclear, say so. Do not claim to see anything outside this frame. " +
                        "This is an ongoing live scan; mention only new or useful observations, not generic filler."
                    ))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", imageData)))
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                    .put("generationConfig", JSONObject().put("temperature", 0.25).put("maxOutputTokens", 160))
                    .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key")
                    .post(body)
                    .build()
                analysisClient.newCall(request).execute().use { response ->
                    val responseText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val apiMessage = try {
                            JSONObject(responseText).optJSONObject("error")?.optString("message")
                        } catch (_: Exception) { null }
                        val message = when (response.code) {
                            400, 401, 403 -> "Gemini API key/model access error"
                            429 -> "AI rate limit reached • waiting to retry"
                            else -> "Visual AI unavailable (HTTP ${response.code})"
                        }
                        Log.w(TAG, "Visual analysis failed: HTTP ${response.code}; ${apiMessage ?: "no details"}")
                        runOnUiThread {
                            if (activityVisible) {
                                binding.tvScanStatus.text = message.uppercase(Locale.US)
                                if (response.code == 400 || response.code == 401 || response.code == 403) {
                                    binding.tvScanInsight.text = "Live camera is running, but Gemini rejected the request. Check your API key and model access in Settings."
                                }
                            }
                        }
                        return@use
                    }
                    val root = JSONObject(responseText)
                    val candidates = root.optJSONArray("candidates")
                    val responseParts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val insight = buildString {
                        if (responseParts != null) for (i in 0 until responseParts.length()) {
                            val text = responseParts.optJSONObject(i)?.optString("text").orEmpty().trim()
                            if (text.isNotBlank()) {
                                if (isNotEmpty()) append(" ")
                                append(text)
                            }
                        }
                    }.trim()
                    if (insight.isNotBlank()) {
                        runOnUiThread {
                            if (!activityVisible) return@runOnUiThread
                            binding.tvScanStatus.text = "LIVE VISUAL AI • UPDATED"
                            binding.tvScanInsight.text = insight
                            val now = System.currentTimeMillis()
                            val changed = insight != lastSpokenInsight
                            if (changed && (now - lastSpokenAt >= SPEECH_COOLDOWN_MS)) {
                                lastSpokenInsight = insight
                                lastSpokenAt = now
                                speechAnnouncer?.say(insight)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Live visual analysis request failed", e)
                runOnUiThread {
                    if (activityVisible) binding.tvScanStatus.text = "VISUAL AI CONNECTION ISSUE • RETRYING"
                }
            } finally {
                analysisInProgress.set(false)
            }
        }, "JarvisVisualAnalysis").start()
    }

    private fun updateTorch() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            val hasFlash = cameraId?.let {
                (getSystemService(CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(it)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: false
            if (!hasFlash && torchEnabled) {
                torchEnabled = false
                Toast.makeText(this, "Flashlight is not available on this camera.", Toast.LENGTH_SHORT).show()
                binding.btnScanTorch.text = "FLASH OFF"
                return
            }
            builder.set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not change flashlight state.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCapturedImage(bytes: ByteArray) {
        try {
            val dir = File(getExternalFilesDir(null), "Scans").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "JARVIS_SCAN_$stamp.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            runOnUiThread {
                if (activityVisible) binding.tvScanStatus.text = "PHOTO SAVED • LIVE SCAN CONTINUES"
                Toast.makeText(this, "Photo saved in JARVIS/Scans.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread { binding.tvScanStatus.text = "PHOTO SAVE FAILED" }
        }
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        if (::binding.isInitialized) {
            if (scanAnimator?.isStarted != true) scanAnimator?.start()
            if (PermissionHelper.hasCameraPermission(this)) openCameraIfReady()
        }
    }

    override fun onPause() {
        activityVisible = false
        scanAnimator?.cancel()
        cameraHandler?.removeCallbacksAndMessages(null)
        closeCamera()
        super.onPause()
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        capturePending = false
    }

    override fun onDestroy() {
        activityVisible = false
        scanAnimator?.cancel()
        scanAnimator = null
        closeCamera()
        speechAnnouncer?.shutdown()
        speechAnnouncer = null
        cameraThread?.quitSafely()
        try { cameraThread?.join(500) } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
        analysisClient.dispatcher.cancelAll()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CameraScanActivity"
        private const val ANALYSIS_INTERVAL_MS = 4000L
        private const val SPEECH_COOLDOWN_MS = 9000L
    }
}
