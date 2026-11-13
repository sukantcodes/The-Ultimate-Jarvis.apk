package com.jarvis.assistant.tts.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import android.widget.Toast
import com.jarvis.assistant.tts.model.SpeakerProfile
import com.jarvis.assistant.tts.model.VoiceEngineType
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local neural speech engine powered by Sherpa-ONNX.
 *
 * - The reply is cut into sentences. Sentence N+1 is synthesised while sentence N is playing, so speech starts after
 *   the first sentence is ready instead of after the whole reply.
 * - Audio goes straight from memory into an AudioTrack (no files).
 * - Switching between Kokoro-82M Multi-Lang and Piper VITS unloads the previous model first.
 * - Amplitude is streamed to the HUD waveform visualizer.
 * - The native model is only touched under [initLock], one sentence at a time, so stop and release stay safe.
 * - When the model cannot start, [lastError] says why and [lastInitFailed] is true so callers can use another voice.
 */
class SherpaTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "SherpaTtsEngine"
        private const val SYNTH_THREADS = 4
    }

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val initLock = Any()

    @Volatile private var offlineTts: OfflineTts? = null
    @Volatile private var currentEngineType: VoiceEngineType? = null
    @Volatile private var currentAudioTrack: AudioTrack? = null
    @Volatile private var isPlayingAudio: Boolean = false

    /** Why the last attempt to start the model failed, in plain words; null when the last attempt worked. */
    @Volatile var lastError: String? = null
        private set

    /** True when the last attempt to start the model failed. A later successful attempt clears it. */
    @Volatile var lastInitFailed: Boolean = false
        private set

    private var playbackJob: Job? = null

    /**
     * Initializes or switches the active local engine (Kokoro or Piper).
     * Unloads the previous engine first, then checks free memory, so the check sees the memory that was just freed.
     */
    fun initializeEngine(engineType: VoiceEngineType): Boolean = synchronized(initLock) {
        if (!engineType.isLocal) return false
        if (offlineTts != null && currentEngineType == engineType) return true

        // Unload existing engine first to immediately release native heap allocations
        releaseNativeTts()

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val availMemMb = memInfo.availMem / (1024L * 1024L)

        if (memInfo.lowMemory || availMemMb < 150L) {
            return fail("Not enough free memory (${availMemMb} MB free). Close other apps and try again.")
        }

        val engineDir = TtsModelDownloader.resolveModelDir(context, engineType)
        if (engineDir == null || !TtsModelDownloader.isEngineInstalled(context, engineType)) {
            return fail("The ${engineType.displayName} files are missing or incomplete. Download the voice again.")
        }

        try {
            val ttsConfig = createConfigForEngine(engineType, engineDir)
            offlineTts = OfflineTts(null, ttsConfig)
            currentEngineType = engineType
            lastError = null
            lastInitFailed = false
            Log.i(TAG, "Initialized Sherpa-ONNX core for ${engineType.displayName} (${availMemMb}MB RAM available)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to instantiate OfflineTts native core for ${engineType.displayName}", e)
            offlineTts = null
            currentEngineType = null
            fail("The voice engine could not start (${e.javaClass.simpleName}${e.message?.let { ": " + it.take(100) } ?: ""}).")
        }
    }

    private fun fail(reason: String): Boolean {
        Log.w(TAG, reason)
        lastError = reason
        lastInitFailed = true
        return false
    }

    private fun createConfigForEngine(engineType: VoiceEngineType, dir: File): OfflineTtsConfig {
        val modelConfig = OfflineTtsModelConfig().apply {
            numThreads = SYNTH_THREADS
            debug = false
            provider = "cpu"
        }

        when (engineType) {
            VoiceEngineType.LOCAL_KOKORO -> {
                val modelFile = File(dir, "model.int8.onnx").takeIf { it.exists() }
                    ?: File(dir, "model.onnx").takeIf { it.exists() }
                    ?: File(dir, engineType.defaultModelFileName)

                val kokoroConfig = OfflineTtsKokoroModelConfig().apply {
                    model = modelFile.absolutePath
                    voices = File(dir, "voices.bin").absolutePath
                    tokens = File(dir, "tokens.txt").absolutePath
                    dataDir = File(dir, "espeak-ng-data").absolutePath

                    // Provide lexicon files for English & Chinese phonemization
                    val candidateLexicons = listOf(
                        File(dir, "lexicon-us-en.txt"),
                        File(dir, "lexicon-gb-en.txt"),
                        File(dir, "lexicon-zh.txt")
                    ).filter { it.exists() }

                    lexicon = if (candidateLexicons.isNotEmpty()) {
                        candidateLexicons.joinToString(",") { it.absolutePath }
                    } else {
                        dir.listFiles { f -> f.name.startsWith("lexicon") && f.name.endsWith(".txt") }
                            ?.joinToString(",") { it.absolutePath } ?: ""
                    }

                    val dictDirFile = File(dir, "dict")
                    if (dictDirFile.exists()) {
                        dictDir = dictDirFile.absolutePath
                    }

                    lengthScale = 1.0f
                }
                modelConfig.kokoro = kokoroConfig
            }
            VoiceEngineType.LOCAL_PIPER -> {
                val vitsConfig = OfflineTtsVitsModelConfig().apply {
                    model = File(dir, "en_GB-alan-medium.onnx").absolutePath
                    tokens = File(dir, "tokens.txt").absolutePath
                    dataDir = File(dir, "espeak-ng-data").absolutePath

                    val piperLexicon = File(dir, "lexicon.txt")
                    if (piperLexicon.exists()) {
                        lexicon = piperLexicon.absolutePath
                    }

                    lengthScale = 1.0f
                    noiseScale = 0.667f
                    noiseScaleW = 0.8f
                }
                modelConfig.vits = vitsConfig
            }
            else -> throw IllegalArgumentException("Engine $engineType is not a local model")
        }

        return OfflineTtsConfig().apply {
            model = modelConfig
            silenceScale = 0.2f
            maxNumSentences = 1
        }
    }

    /**
     * Synthesizes and plays speech entirely in RAM, sentence by sentence.
     *
     * @param text Clean sanitized plain speech text.
     * @param speakerProfile Speaker profile defining sid and engine type.
     * @param speed Speech speed rate (1.0f normal).
     * @param onAmplitude Callback delivering normalized audio RMS amplitude (0.0f..1.0f) for HUD visualizer.
     * @param onComplete Callback invoked once when speech completes. Not invoked when speech is stopped.
     */
    fun speak(
        text: String,
        speakerProfile: SpeakerProfile,
        speed: Float = 1.0f,
        onAmplitude: ((Float) -> Unit)? = null,
        onComplete: (() -> Unit)? = null
    ) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }

        stop()

        playbackJob = engineScope.launch {
            var completed = false
            var spokeAnything = false
            suspend fun fallBackToSystemVoice() {
                withContext(Dispatchers.Main) {
                    com.jarvis.assistant.voice.JarvisVoiceController.speakSystemTts(context, text)
                }
            }
            try {
                val ready = (offlineTts != null && currentEngineType == speakerProfile.engineType) ||
                    initializeEngine(speakerProfile.engineType)
                val tts = offlineTts
                if (!ready || tts == null) {
                    Log.e(TAG, "Local neural engine could not be readied (${lastError}); falling back to the system voice")
                    fallBackToSystemVoice()
                    return@launch
                }

                val pieces = SpeechChunker.split(text)
                spokeAnything = synthesizeAndPlay(tts, pieces, speakerProfile.speakerId, speed, onAmplitude)
                if (!spokeAnything) {
                    Log.w(TAG, "Generated audio waveform was empty; falling back to the system voice")
                    fallBackToSystemVoice()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Synthesis or in-memory playback failed", e)
                if (!spokeAnything) {
                    try { fallBackToSystemVoice() } catch (_: Exception) {}
                }
            } finally {
                if (isActive && !completed) {
                    completed = true
                    withContext(Dispatchers.Main) {
                        onAmplitude?.invoke(0f)
                        onComplete?.invoke()
                    }
                }
            }
        }
    }

    /**
     * Synthesizes the pieces one after another on a producer coroutine and plays each as soon as it arrives.
     * Returns true when at least one piece was played.
     */
    private suspend fun synthesizeAndPlay(
        tts: OfflineTts,
        pieces: List<String>,
        speakerId: Int,
        speed: Float,
        onAmplitude: ((Float) -> Unit)?
    ): Boolean = coroutineScope {
        val ready = Channel<GeneratedAudio>(capacity = 2)
        val producer = launch(Dispatchers.Default) {
            try {
                for (piece in pieces) {
                    ensureActive()
                    // One sentence at a time under the lock, so stop() and release() wait at most one sentence.
                    val audio: GeneratedAudio = synchronized(initLock) {
                        if (offlineTts !== tts) throw CancellationException("Voice engine was switched or released")
                        tts.generate(piece, speakerId, speed)
                    }
                    if (audio.samples.isNotEmpty()) ready.send(audio)
                }
            } finally {
                ready.close()
            }
        }

        var player: StreamPlayer? = null
        var played = false
        try {
            withContext(Dispatchers.IO) {
                for (audio in ready) {
                    val current = player ?: StreamPlayer(audio.sampleRate).also { player = it }
                    current.write(audio.samples, onAmplitude)
                    played = true
                }
                player?.drain()
            }
        } finally {
            producer.cancel()
            player?.release()
        }
        played
    }

    /** One AudioTrack kept open for the whole reply; sentences are written into it back to back. */
    private inner class StreamPlayer(private val sampleRate: Int) {
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        private val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        private var focusRequest: android.media.AudioFocusRequest? = null
        private var totalFrames = 0L
        val track: AudioTrack

        init {
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1
            if (currentVol == 0) {
                Log.w(TAG, "Device STREAM_MUSIC volume is currently 0 (muted)")
                engineScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Media volume is muted. Turn up volume to hear Jarvis.", Toast.LENGTH_SHORT).show()
                }
            }

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(maxOf(minBufferSize * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            currentAudioTrack = track
            isPlayingAudio = true

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* Ducking handled by framework */ }
                    .build()
                focusRequest = request
                audioManager?.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
            track.play()
        }

        private suspend fun stillPlaying(): Boolean = currentCoroutineContext().isActive && isPlayingAudio

        suspend fun write(samples: FloatArray, onAmplitude: ((Float) -> Unit)?) {
            val shortSamples = ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1.0f, 1.0f) * 32767f).toInt().toShort()
            }
            val chunkSize = 1024
            var offset = 0
            while (offset < shortSamples.size && stillPlaying()) {
                val count = minOf(chunkSize, shortSamples.size - offset)
                val written = track.write(shortSamples, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.e(TAG, "AudioTrack write failed: $written")
                    break
                }
                var sumSq = 0.0f
                for (i in offset until (offset + count)) {
                    val v = samples[i]
                    sumSq += v * v
                }
                val rms = kotlin.math.sqrt(sumSq / count).coerceIn(0f, 1f)
                val visualAmplitude = (rms * 2.8f).coerceIn(0f, 1f)
                onAmplitude?.let { cb -> withContext(Dispatchers.Main) { cb(visualAmplitude) } }
                offset += count
            }
            totalFrames += shortSamples.size
        }

        /** Waits until everything written has actually been played. */
        suspend fun drain() {
            val head = try { track.playbackHeadPosition.toLong() } catch (_: Exception) { 0L }
            val remainingMs = (((totalFrames - head).coerceAtLeast(0L)).toDouble() / sampleRate * 1000).toLong()
            val deadline = System.currentTimeMillis() + remainingMs + 1500L
            while (stillPlaying() && System.currentTimeMillis() < deadline) {
                if (track.playbackHeadPosition.toLong() >= totalFrames) break
                delay(25)
            }
        }

        fun release() {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.release()
            } catch (_: Exception) {}
            if (currentAudioTrack == track) {
                currentAudioTrack = null
                isPlayingAudio = false
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        }
    }

    fun stop() {
        isPlayingAudio = false
        playbackJob?.cancel()
        playbackJob = null
        try {
            val track = currentAudioTrack
            if (track != null) {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        } catch (_: Exception) {}
        currentAudioTrack = null
    }

    fun releaseNativeTts() = synchronized(initLock) {
        try {
            offlineTts?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Exception during native OfflineTts release", e)
        }
        offlineTts = null
        currentEngineType = null
    }

    fun release() {
        stop()
        releaseNativeTts()
        engineScope.cancel()
    }
}
