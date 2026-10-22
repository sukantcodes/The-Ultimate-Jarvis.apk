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
import android.view.Surface
import android.view.TextureView
import android.animation.ValueAnimator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ActivityCameraScanBinding
import com.jarvis.assistant.util.PermissionHelper
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraScanActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraScanBinding
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var cameraId: String? = null
    private var torchEnabled = false
    private var scanAnimator: ValueAnimator? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCameraIfReady() else {
            binding.tvScanStatus.text = "CAMERA PERMISSION REQUIRED"
            Toast.makeText(this, "Camera permission is required to scan.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnScanClose.setOnClickListener { finish() }
        binding.btnScanTorch.setOnClickListener {
            torchEnabled = !torchEnabled
            updateTorch()
            binding.btnScanTorch.text = if (torchEnabled) "FLASH ON" else "FLASH OFF"
        }
        binding.btnScanCapture.setOnClickListener { captureImage() }
        binding.cameraPreview.surfaceTextureListener = surfaceListener
        scanAnimator = ValueAnimator.ofFloat(-220f, 220f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                binding.scanLaserLine.translationY = animation.animatedValue as Float
            }
            start()
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
        if (cameraDevice != null) return
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
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    saveCapturedImage(bytes)
                } finally {
                    image.close()
                }
            }, cameraHandler)
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            previewRequestBuilder = builder
            camera.createCaptureSession(listOf(previewSurface, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        runOnUiThread { binding.tvScanStatus.text = "OPTICAL SENSOR ONLINE • READY TO SCAN" }
                    } catch (_: Exception) {
                        runOnUiThread { binding.tvScanStatus.text = "PREVIEW START FAILED" }
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    runOnUiThread { binding.tvScanStatus.text = "CAMERA CONFIGURATION FAILED" }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            binding.tvScanStatus.text = "PREVIEW ERROR"
        }
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

    private fun captureImage() {
        val camera = cameraDevice ?: run {
            Toast.makeText(this, "Camera is not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val session = captureSession ?: return
        val reader = imageReader ?: return
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }
            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                    runOnUiThread { binding.tvScanStatus.text = "CAPTURED • PROCESSING IMAGE" }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Toast.makeText(this, "Capture failed: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveCapturedImage(bytes: ByteArray) {
        try {
            val dir = File(getExternalFilesDir(null), "Scans").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "JARVIS_SCAN_$stamp.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            runOnUiThread {
                binding.tvScanStatus.text = "IMAGE SAVED • ${file.name}"
                Toast.makeText(this, "Scan image saved in JARVIS/Scans.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread { binding.tvScanStatus.text = "SAVE FAILED" }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            if (scanAnimator?.isStarted != true) scanAnimator?.start()
            if (PermissionHelper.hasCameraPermission(this)) openCameraIfReady()
        }
    }

    override fun onPause() {
        scanAnimator?.cancel()
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
    }

    override fun onDestroy() {
        scanAnimator?.cancel()
        scanAnimator = null
        closeCamera()
        cameraThread?.quitSafely()
        try { cameraThread?.join(500) } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
        super.onDestroy()
    }
}
