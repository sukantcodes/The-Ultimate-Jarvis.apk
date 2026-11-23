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
 * Loads the Vosk speech and speaker models from app-private storage.
 *
 * The models are no longer carried inside the APK (about 80 MB of it). The first launch downloads them once from the
 * project's GitHub release, checks their SHA-256 and unpacks them; after that voice recognition is fully offline.
 * A build that still bundles the model as an asset uses that copy instead and never touches the network.
 *
 * Installation is staged and validated before replacing a model directory. A failed
 * speaker-model load does not force an already-loaded speech model to be loaded again.
 */
object VoskModelManager {
    private const val TAG = "VoskModelManager"
    private const val MIN_MODEL_FILE_BYTES = 1_000_000L

    private const val RELEASE_BASE = "https://github.com/sukantcodes/The-Ultimate-Jarvis.apk/releases/download/vosk-models-v1/"
    // Published by .github/workflows/host-vosk-models.yml; the hash and size are checked before anything is unpacked.
    private val SPEECH_ARCHIVE = ModelDownloader.Spec(
        RELEASE_BASE + "model-en-us.zip", "b852797d9a7dae605298596353a1710f40746a77778fa8371b7988cf9e81e4bf", 41_204_444L
    )
    private val SPEAKER_ARCHIVE = ModelDownloader.Spec(
        RELEASE_BASE + "model-spk.zip", "2d54faf81c5e9a72e52049fa2923b8ab8c3493b55c8fcd56667a58f40ea888d0", 13_868_580L
    )

    private val downloader by lazy { ModelDownloader() }

    private val _downloadStatus = MutableStateFlow<String?>(null)
    /** Short text while a model is downloading or after a failed download, null otherwise. */
    val downloadStatus: StateFlow<String?> = _downloadStatus.asStateFlow()

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
                    ensureInstalled(
                        appContext.assets,
                        "model-en-us",
                        SPEECH_ARCHIVE,
                        "voice model",
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
                        ensureInstalled(
                            appContext.assets,
                            "model-spk",
                            SPEAKER_ARCHIVE,
                            "speaker model",
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
                if (model != null) _downloadStatus.value = null
            } catch (error: Exception) {
                _isModelReady.value = model != null
                _lastError.value = error.message ?: error.javaClass.simpleName
                _downloadStatus.value = friendlyDownloadError(error)
                Log.e(TAG, "Could not load Vosk speech model.", error)
            } finally {
                isInitializing.set(false)
            }
        }
    }

    private fun ensureInstalled(
        assets: AssetManager,
        assetPath: String,
        archive: ModelDownloader.Spec,
        label: String,
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
        val partFile = File(parent, targetDir.name + ".zip.part")

        try {
            if (assets.list(assetPath).isNullOrEmpty()) {
                downloadInto(stagingDir, partFile, archive, label)
            } else {
                copyAssetFolder(assets, assetPath, stagingDir)
            }
            if (!isValid(stagingDir)) {
                throw IOException("Model files are incomplete: $assetPath")
            }

            if (targetDir.exists() && !targetDir.deleteRecursively()) {
                throw IOException("Could not remove the incomplete model directory: ${targetDir.absolutePath}")
            }
            if (!stagingDir.renameTo(targetDir)) {
                throw IOException("Could not finalize model extraction: ${targetDir.absolutePath}")
            }
            partFile.delete()
            Log.i(TAG, "Validated and installed $assetPath.")
        } catch (error: Exception) {
            stagingDir.deleteRecursively()
            // A half-downloaded archive is kept so the next attempt resumes; downloadInto removes it if it was bad.
            throw error
        }
    }

    private fun downloadInto(stagingDir: File, partFile: File, archive: ModelDownloader.Spec, label: String) {
        val free = (partFile.parentFile ?: stagingDir).usableSpace
        if (free < archive.bytes * 3) {
            throw IOException("Not enough free storage to download the $label (needs about ${archive.bytes * 3 / 1_000_000} MB).")
        }
        _downloadStatus.value = "Downloading $label…"
        var lastPercent = -1
        downloader.download(archive, partFile) { done, total ->
            val percent = if (total > 0) (done * 100 / total).toInt() else 0
            if (percent != lastPercent) {
                lastPercent = percent
                _downloadStatus.value = "Downloading $label… $percent%"
            }
        }
        _downloadStatus.value = "Unpacking $label…"
        ModelArchive.extract(partFile, stagingDir)
    }

    private fun friendlyDownloadError(error: Exception): String = when (error) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "Voice model not downloaded yet. Connect to the internet once and JARVIS will download it (about 55 MB)."
        else -> "Voice model setup failed: ${error.message ?: error.javaClass.simpleName}. It will retry automatically."
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
