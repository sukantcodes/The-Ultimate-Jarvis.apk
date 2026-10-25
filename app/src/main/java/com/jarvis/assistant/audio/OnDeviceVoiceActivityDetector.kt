package com.jarvis.assistant.audio

import android.util.Log
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

/**
 * Low-latency, on-device speech detector for the app's 16 kHz mono PCM16 stream.
 *
 * WebRTC VAD is the primary classifier. The existing adaptive energy threshold remains a
 * defensive fallback if native initialization is unavailable on a device.
 */
class OnDeviceVoiceActivityDetector : AutoCloseable {
    companion object {
        private const val TAG = "JarvisVAD"
        private const val EXPECTED_FRAME_BYTES = 640 // 320 samples × 2 bytes = 20 ms at 16 kHz
    }

    @Volatile private var vad: VadWebRTC? = null
    @Volatile private var initializationFailed = false

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

    fun isSpeech(pcm16Mono: ByteArray, amplitude: Float, fallbackThreshold: Float): Boolean {
        if (pcm16Mono.size != EXPECTED_FRAME_BYTES) return amplitude > fallbackThreshold
        return try {
            engineOrNull()?.isSpeech(pcm16Mono) ?: (amplitude > fallbackThreshold)
        } catch (error: Exception) {
            Log.e(TAG, "WebRTC VAD frame failed; using adaptive energy fallback", error)
            synchronized(this) {
                initializationFailed = true
                try { vad?.close() } catch (_: Exception) {}
                vad = null
            }
            amplitude > fallbackThreshold
        } catch (error: LinkageError) {
            Log.e(TAG, "WebRTC VAD native call failed; using adaptive energy fallback", error)
            synchronized(this) {
                initializationFailed = true
                try { vad?.close() } catch (_: Exception) {}
                vad = null
            }
            amplitude > fallbackThreshold
        }
    }

    @Synchronized
    override fun close() {
        try {
            vad?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Error closing WebRTC VAD", error)
        } finally {
            vad = null
        }
    }
}
