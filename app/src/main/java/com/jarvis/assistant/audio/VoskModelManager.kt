package com.jarvis.assistant.audio

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads the bundled Vosk speech and speaker models from app-private storage.
 *
 * Extraction is staged and validated before replacing a model directory. A failed
 * speaker-model load does not force an already-loaded speech model to be loaded again.
 */
object VoskModelManager {
    private const val TAG = "VoskModelManager"
    private const val MIN_MODEL_FILE_BYTES = 1_000_000L

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _isSpeakerModelReady = MutableStateFlow(false)
    val isSpeakerModelReady: StateFlow<Boolean> = _isSpeakerModelReady.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    var model: Model? = null
        private set

    @Volatile
    var speakerModel: SpeakerModel? = null
        private set

    private val isInitializing = AtomicBoolean(false)

    fun init(context: Context, scope: CoroutineScope) {
        if (model != null && speakerModel != null) return
        if (!isInitializing.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            try {
                if (model == null) {
                    val targetDir = File(appContext.filesDir, "vosk-model")
                    ensureExtracted(
                        appContext.assets,
                        "model-en-us",
                        targetDir,
                        ::isSpeechModelDirectoryValid
                    )

                    val loadedModel = Model(targetDir.absolutePath)
                    model = loadedModel
                    _isModelReady.value = true
                    Log.i(TAG, "Vosk speech model loaded.")
                }

                if (speakerModel == null) {
                    try {
                        val targetDir = File(appContext.filesDir, "vosk-spk-model")
                        ensureExtracted(
                            appContext.assets,
                            "model-spk",
                            targetDir,
                            ::isSpeakerModelDirectoryValid
                        )

                        val loadedSpeakerModel = SpeakerModel(targetDir.absolutePath)
                        speakerModel = loadedSpeakerModel
                        _isSpeakerModelReady.value = true
                        Log.i(TAG, "Vosk speaker model loaded.")
                    } catch (error: Exception) {
                        // Speaker verification is optional for speech recognition. Keep the
                        // speech model ready and let a later init() retry this model alone.
                        _isSpeakerModelReady.value = false
                        _lastError.value = "Speaker model: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "Could not load Vosk speaker model.", error)
                    }
                }

                if (speakerModel != null) _lastError.value = null
            } catch (error: Exception) {
                _isModelReady.value = model != null
                _lastError.value = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "Could not load Vosk speech model.", error)
            } finally {
                isInitializing.set(false)
            }
        }
    }

    private fun ensureExtracted(
        assets: AssetManager,
        assetPath: String,
        targetDir: File,
        isValid: (File) -> Boolean
    ) {
        if (isValid(targetDir)) return

        val parent = targetDir.parentFile
            ?: throw IOException("Model directory has no parent: ${targetDir.absolutePath}")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create model storage directory: ${parent.absolutePath}")
        }

        val stagingDir = File(parent, targetDir.name + ".staging")
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) {
            throw IOException("Could not create temporary model directory: ${stagingDir.absolutePath}")
        }

        try {
            copyAssetFolder(assets, assetPath, stagingDir)
            if (!isValid(stagingDir)) {
                throw IOException("Bundled model assets are incomplete: $assetPath")
            }

            if (targetDir.exists() && !targetDir.deleteRecursively()) {
                throw IOException("Could not remove the incomplete model directory: ${targetDir.absolutePath}")
            }
            if (!stagingDir.renameTo(targetDir)) {
                throw IOException("Could not finalize model extraction: ${targetDir.absolutePath}")
            }
            Log.i(TAG, "Validated and extracted $assetPath.")
        } catch (error: Exception) {
            stagingDir.deleteRecursively()
            throw error
        }
    }

    private fun isSpeechModelDirectoryValid(directory: File): Boolean {
        val acousticModel = File(directory, "am/final.mdl")
        return acousticModel.isFile &&
            acousticModel.length() >= MIN_MODEL_FILE_BYTES &&
            File(directory, "conf/model.conf").isFile &&
            File(directory, "graph/Gr.fst").let { it.isFile && it.length() > 0L } &&
            File(directory, "graph/HCLr.fst").let { it.isFile && it.length() > 0L }
    }

    private fun isSpeakerModelDirectoryValid(directory: File): Boolean {
        val extractor = File(directory, "final.ext.raw")
        return extractor.isFile &&
            extractor.length() >= MIN_MODEL_FILE_BYTES &&
            File(directory, "transform.mat").let { it.isFile && it.length() > 0L } &&
            File(directory, "mfcc.conf").isFile
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, to: File) {
        val children = assetManager.list(fromAssetPath)
        if (children.isNullOrEmpty()) {
            to.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Could not create directory: ${parent.absolutePath}")
                }
            }
            assetManager.open(fromAssetPath).use { input ->
                FileOutputStream(to).use { output -> input.copyTo(output) }
            }
            return
        }

        if (!to.exists() && !to.mkdirs()) {
            throw IOException("Could not create directory: ${to.absolutePath}")
        }
        for (child in children) {
            val childAssetPath = if (fromAssetPath.isEmpty()) child else "$fromAssetPath/$child"
            copyAssetFolder(assetManager, childAssetPath, File(to, child))
        }
    }
}
