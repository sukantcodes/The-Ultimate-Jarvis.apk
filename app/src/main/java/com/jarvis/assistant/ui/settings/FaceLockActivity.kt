package com.jarvis.assistant.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.jarvis.assistant.databinding.ActivityFaceLockBinding
import com.jarvis.assistant.util.ThemeManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs

class FaceLockActivity : AppCompatActivity() {
    private lateinit var b: ActivityFaceLockBinding
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("jarvis_face_profiles", MODE_PRIVATE) }
    private val detector by lazy { FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL).enableTracking().build()) }
    private var scanning = false
    private var step = 0
    private var lastPrompt = 0L
    private val angles = linkedSetOf<String>()
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startCamera() else b.tvScanStatus.text = "Camera permission is needed." }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this); super.onCreate(savedInstanceState)
        b = ActivityFaceLockBinding.inflate(layoutInflater); setContentView(b.root); ThemeManager.applyDynamicBackground(b.root)
        b.btnBack.setOnClickListener { finish() }
        b.btnStartScan.setOnClickListener {
            if (b.etProfileName.text.toString().trim().isEmpty()) { b.etProfileName.error = "Enter a name"; return@setOnClickListener }
            scanning = true; step = 0; angles.clear(); b.btnSaveProfile.isEnabled = false
            b.tvScanStatus.text = "Look straight at the camera."
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        b.btnCancelScan.setOnClickListener { scanning = false; unbindCamera(); b.btnStartScan.isEnabled = true; b.tvScanStatus.text = "Scan cancelled." }
        b.btnSaveProfile.setOnClickListener { saveProfile() }
        b.btnDeleteAll.setOnClickListener { androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Delete all face data?").setMessage("Remove all saved local enrollment records?").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> prefs.edit().remove("profiles").apply(); showProfiles() }.show() }
        showProfiles()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!scanning || isFinishing) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(b.cameraPreview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(worker) { proxy ->
                    val media = proxy.image
                    if (media == null || !scanning) { proxy.close(); return@setAnalyzer }
                    detector.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { faces -> val now=System.currentTimeMillis(); if(now-lastPrompt>500){lastPrompt=now; runOnUiThread { guide(faces) }} }
                        .addOnCompleteListener { proxy.close() }
                }
                provider.unbindAll(); provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (_: Exception) { runOnUiThread { b.tvScanStatus.text = "Unable to start front camera." } }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun guide(faces: List<Face>) {
        if (!scanning) return
        if (faces.size != 1) { b.tvScanStatus.text = if(faces.isEmpty()) "Face not found. Move into frame." else "Only one person should be in frame."; return }
        val f=faces[0]; val yaw=f.headEulerAngleY; val pitch=f.headEulerAngleX
        val matched = when(step) {
            0 -> abs(yaw)<12 && abs(pitch)<15
            1 -> yaw < -18
            2 -> yaw > 18
            3 -> pitch < -12
            4 -> pitch > 12
            else -> false
        }
        if (matched) {
            angles.add(listOf("Front","Left","Right","Up","Down")[step]); step++
            if(step==5) { b.tvScanStatus.text="Guided scan complete. Save this profile."; b.btnSaveProfile.isEnabled=true; scanning=false; unbindCamera() }
            else b.tvScanStatus.text=listOf("Now turn your face left.","Now turn your face right.","Now look slightly up.","Now look slightly down.")[step]
        } else b.tvScanStatus.text=listOf("Look straight at the camera.","Turn your face left.","Turn your face right.","Look slightly up.","Look slightly down.").getOrElse(step){"Scan complete."}
    }

    private fun unbindCamera() { try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) {} }

    private fun saveProfile() {
        if(angles.size<5) return
        val name=b.etProfileName.text.toString().trim()
        val arr=JSONArray(prefs.getString("profiles","[]"))
        arr.put(JSONObject().put("name",name).put("angles",JSONArray(angles.toList())).put("savedAt",System.currentTimeMillis()))
        prefs.edit().putString("profiles",arr.toString()).apply()
        b.etProfileName.text?.clear(); b.btnStartScan.isEnabled=true; b.btnSaveProfile.isEnabled=false
        b.tvScanStatus.text="Local scan record saved. This prototype does not identify a person or unlock the device."
        showProfiles()
    }

    private fun showProfiles() {
        val arr=JSONArray(prefs.getString("profiles","[]"))
        b.tvProfiles.text=if(arr.length()==0) "No saved profiles yet." else buildString { for(i in 0 until arr.length()) append("• ").append(arr.optJSONObject(i)?.optString("name","Person") ?: "Person").append(" — saved on this phone\n") }
    }

    override fun onDestroy() { scanning=false; unbindCamera(); detector.close(); worker.shutdown(); super.onDestroy() }
}