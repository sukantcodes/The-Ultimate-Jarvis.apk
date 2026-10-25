package com.jarvis.assistant.audio

import android.util.Log
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

/**
 * On-device VAD for 16 kHz mono PCM16 input.
 *
 * AudioRecord can occasionally return a short read. WebRTC VAD only accepts complete
 * 20 ms frames, so partial reads are buffered instead of silently bypassing the VAD.
 * Energy detection remains a fail-safe if the native VAD is unavailable.
 */
class OnDeviceVoiceActivityDetector : AutoCloseable {
    companion object {
        private const val TAG = "JarvisVAD"
        private const val FRAME_BYTES = 640 // 320 PCM16 samples = 20 ms at 16 kHz
    }

    @Volatile private var vad: VadWebRTC? = null
    @Volatile private var initializationFailed = false
    private val pendingFrame = ByteArray(FRAME_BYTES)
    private var pendingBytes = 0

    @Synchronized
    private fun engineOrNull(): VadWebRTC? {
        vad?.let { return it }
        if (initializationFailed) return null
        return try {
            VadWebRTC(
                sampleRate = SampleRate.SAMPLE_RATE_16K,
                frameSize = FrameSize.FRAME_SIZE_320,
                mode = Mode.AGGRESSIVE,
                speechDurationMs = 0,
                silenceDurationMs = 0
            ).also { vad = it }
        } catch (error: Exception) {
            initializationFailed = true
            Log.e(TAG, "Could not initialize WebRTC VAD; using adaptive energy fallback", error)
            null
        } catch (error: LinkageError) {
            initializationFailed = true
            Log.e(TAG, "Native WebRTC VAD library unavailable; using adaptive energy fallback", error)
            null
        }
    }

    @Synchronized
    fun isSpeech(pcm16Mono: ByteArray, amplitude: Float, fallbackThreshold: Float): Boolean {
        if (pcm16Mono.isEmpty()) return false
        if (initializationFailed) return amplitude > fallbackThreshold

        var inputOffset = 0
        var evaluatedFrames = 0
        var speechDetected = false
        while (inputOffset < pcm16Mono.size) {
            val copyCount = minOf(FRAME_BYTES - pendingBytes, pcm16Mono.size - inputOffset)
            System.arraycopy(pcm16Mono, inputOffset, pendingFrame, pendingBytes, copyCount)
            pendingBytes += copyCount
            inputOffset += copyCount

            if (pendingBytes == FRAME_BYTES) {
                val speech = try {
                    engineOrNull()?.isSpeech(pendingFrame) ?: (amplitude > fallbackThreshold)
                } catch (error: Exception) {
                    disableNativeVad("WebRTC VAD frame failed", error)
                    amplitude > fallbackThreshold
                } catch (error: LinkageError) {
                    disableNativeVad("WebRTC VAD native call failed", error)
                    amplitude > fallbackThreshold
                }
                speechDetected = speechDetected || speech
                evaluatedFrames++
                pendingBytes = 0
                if (speechDetected) {
                    // Consume any remaining complete frames in the next call rather than
                    // returning early with stale bytes in the accumulator.
                    continue
                }
            }
        }

        // A short read is retained until a full frame arrives. Use the energy fallback
        // only when no complete frame could be evaluated during this call.
        return speechDetected || (evaluatedFrames == 0 && amplitude > fallbackThreshold && initializationFailed)
    }

    private fun disableNativeVad(message: String, error: Throwable) {
        Log.e(TAG, "$message; switching to adaptive energy fallback", error)
        initializationFailed = true
        try { vad?.close() } catch (_: Exception) {}
        vad = null
        pendingBytes = 0
    }

    @Synchronized
    override fun close() {
        try {
            vad?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Error closing WebRTC VAD", error)
        } finally {
            vad = null
            pendingBytes = 0
            initializationFailed = false
        }
    }
}
