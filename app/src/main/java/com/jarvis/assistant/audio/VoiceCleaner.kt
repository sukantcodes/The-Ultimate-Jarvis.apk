package com.jarvis.assistant.audio

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Removes steady background noise (fans, traffic, hum, room noise) from 16 kHz, 16-bit mono microphone audio
 * before it is sent to the AI, and quiets far-away voices and sounds when [strong] is on.
 *
 * It works on the sound in short overlapping slices: it keeps an estimate of the noise in each frequency band,
 * lowers the bands that contain mostly noise, and keeps the bands that contain your voice. A rumble filter removes
 * very low sounds first. The delay it adds is about 16 milliseconds.
 *
 * Feed it chunks of any size in order. Each call returns the cleaned audio that is ready (it can be slightly
 * shorter or longer than the chunk, and empty on the very first call).
 */
class VoiceCleaner(strong: Boolean) {
    private val bias = if (strong) 2.0 else 1.5
    private val gainFloor = if (strong) 0.06 else 0.20
    private val useGate = strong

    private val window = DoubleArray(N) { sqrt(0.5 - 0.5 * cos(2.0 * PI * (it + 0.5) / N)) }
    private val cosTable = DoubleArray(N) { cos(2.0 * PI * it / N) }
    private val sinTable = DoubleArray(N) { sin(2.0 * PI * it / N) }
    private val reverse = IntArray(N)

    private var inBuf = DoubleArray(4096)
    private var inLen = H
    private val acc = DoubleArray(N)
    private val re = DoubleArray(N)
    private val im = DoubleArray(N)
    private val xr = DoubleArray(K)
    private val xi = DoubleArray(K)
    private val z = DoubleArray(N)

    private val smooth = DoubleArray(K)
    private val curMin = DoubleArray(K)
    private val ring = Array(U) { DoubleArray(K) }
    private var ringPos = 0
    private var sub = 0
    private val gPrev = DoubleArray(K) { 1.0 }
    private val gammaPrev = DoubleArray(K) { 1.0 }
    private var first = true

    private var speechRef = 0.0
    private var gateGain = 1.0

    private var hpX = 0.0
    private var hpY = 0.0
    private val hpA = exp(-2.0 * PI * 90.0 / 16000.0)

    init {
        val bits = Integer.numberOfTrailingZeros(N)
        for (i in 0 until N) {
            var r = 0
            var v = i
            for (b in 0 until bits) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            reverse[i] = r
        }
    }

    fun process(pcm: ByteArray): ByteArray {
        val count = pcm.size / 2
        if (count == 0) return ByteArray(0)
        if (inLen + count > inBuf.size) inBuf = inBuf.copyOf(max(inBuf.size * 2, inLen + count))

        for (i in 0 until count) {
            val sample = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toDouble()
            hpY = hpA * (hpY + sample - hpX)
            hpX = sample
            inBuf[inLen] = hpY
            inLen++
        }

        val out = ByteArrayOutputStream(count * 2 + 512)
        while (inLen >= N) {
            processFrame()
            for (n in 0 until H) {
                val rounded = Math.round(acc[n]).toInt()
                val value = if (rounded > 32767) 32767 else if (rounded < -32768) -32768 else rounded
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
            }
            System.arraycopy(acc, H, acc, 0, N - H)
            for (n in N - H until N) acc[n] = 0.0
            System.arraycopy(inBuf, H, inBuf, 0, inLen - H)
            inLen -= H
        }
        return out.toByteArray()
    }

    private fun processFrame() {
        for (n in 0 until N) {
            re[n] = inBuf[n] * window[n]
            im[n] = 0.0
        }
        fft()
        for (k in 0 until K) {
            xr[k] = re[k]
            xi[k] = im[k]
        }

        if (first) {
            for (k in 0 until K) {
                val p = xr[k] * xr[k] + xi[k] * xi[k]
                smooth[k] = p
                curMin[k] = p
                for (u in 0 until U) ring[u][k] = p
            }
            first = false
        }

        for (k in 0 until K) {
            val p = xr[k] * xr[k] + xi[k] * xi[k]
            smooth[k] = 0.85 * smooth[k] + 0.15 * p
            if (smooth[k] < curMin[k]) curMin[k] = smooth[k]
        }
        sub++
        if (sub >= SUBLEN) {
            System.arraycopy(curMin, 0, ring[ringPos], 0, K)
            ringPos = (ringPos + 1) % U
            System.arraycopy(smooth, 0, curMin, 0, K)
            sub = 0
        }

        var noiseSum = 0.0
        for (k in 0 until K) {
            var m = curMin[k]
            for (u in 0 until U) {
                if (ring[u][k] < m) m = ring[u][k]
            }
            val noise = max(m * bias, 1.0)
            noiseSum += noise

            val p = xr[k] * xr[k] + xi[k] * xi[k]
            val gamma = p / noise
            var prior = 0.9 * gPrev[k] * gPrev[k] * gammaPrev[k] + 0.1 * max(gamma - 1.0, 0.0)
            if (prior < XI_MIN) prior = XI_MIN
            var gain = prior / (1.0 + prior)
            if (gain < gainFloor) gain = gainFloor
            gPrev[k] = gain
            gammaPrev[k] = gamma

            // The inverse transform is done with the conjugate trick, so store the conjugate of the cleaned spectrum.
            re[k] = xr[k] * gain
            im[k] = -xi[k] * gain
        }
        for (k in 1 until N / 2) {
            re[N - k] = re[k]
            im[N - k] = -im[k]
        }
        fft()
        for (n in 0 until N) z[n] = (re[n] / N) * window[n]

        if (useGate) {
            var energy = 0.0
            for (n in 0 until H) energy += z[n] * z[n]
            val level = sqrt(energy / H + 1e-9)
            val noiseLevel = sqrt(noiseSum / K / (N * 0.5) + 1e-9)
            if (level > 600.0 && level > 2.0 * noiseLevel) {
                speechRef = if (speechRef == 0.0) level else 0.97 * speechRef + 0.03 * level
            } else {
                speechRef *= 0.9997
            }
            // Sounds far quieter than your usual speaking level (people or a TV in the distance) are turned down.
            val target = if (speechRef > 0.0 && level < 0.22 * speechRef) 0.18 else 1.0
            gateGain += (target - gateGain) * (if (target > gateGain) 0.6 else 0.25)
            for (n in 0 until N) z[n] *= gateGain
        }

        for (n in 0 until N) acc[n] += z[n]
    }

    /** In-place forward FFT of [re] and [im]. */
    private fun fft() {
        for (i in 0 until N) {
            val j = reverse[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var length = 2
        while (length <= N) {
            val half = length / 2
            val step = N / length
            var i = 0
            while (i < N) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = -sinTable[k * step]
                    val a = i + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                i += length
            }
            length *= 2
        }
    }

    companion object {
        private const val N = 512
        private const val H = 256
        private const val K = 257
        private const val SUBLEN = 16
        private const val U = 6
        private const val XI_MIN = 0.001
    }
}
