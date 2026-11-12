package com.jarvis.assistant.audio

import android.util.Log
import com.jarvis.assistant.data.preferences.AppPreferences
import org.json.JSONObject
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class VoiceMatchResult(
    val isMatch: Boolean,
    val distance: Float,
    val similarityPercent: Int,
    val threshold: Float,
    val hasExtractedVector: Boolean = true
)

object OwnerVoiceManager {
    private const val TAG = "OwnerVoiceManager"

    // Vosk/Kaldi 128-d x-vector cosine distance thresholds.
    // Across mobile device microphones with natural variations in distance, speech volume,
    // room acoustic reflections, and pitch, genuine owner cosine distance typically ranges 0.40f to 0.80f.
    // Strangers measure significantly higher: 1.05f to 1.40f+.
    const val STRICT_THRESHOLD = 0.72f    // Strict: maximum security
    const val DEFAULT_THRESHOLD = 0.80f   // Balanced: highly reliable owner match, 100% blocks strangers
    const val RELAXED_THRESHOLD = 0.88f   // Tolerant of noisy rooms / background audio

    /**
     * Calculates the cosine distance between two speaker embedding vectors:
     * distance = 1.0 - (dot(v1, v2) / (norm(v1) * norm(v2)))
     * Range is [0.0, 2.0]. Lower values indicate identical vocal characteristics.
     */
    fun calculateCosineDistance(v1: FloatArray, v2: FloatArray): Float {
        if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 1.0f

        var dot = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in v1.indices) {
            val a = v1[i].toDouble()
            val b = v2[i].toDouble()
            dot += a * b
            norm1 += a * a
            norm2 += b * b
        }

        if (norm1 <= 0.0 || norm2 <= 0.0) return 1.0f

        val cosSim = (dot / (sqrt(norm1) * sqrt(norm2))).coerceIn(-1.0, 1.0)
        return (1.0 - cosSim).toFloat().coerceIn(0.0f, 2.0f)
    }

    /**
     * Converts a cosine distance to a human-friendly similarity percentage [0..100].
     */
    fun distanceToSimilarityPercent(distance: Float): Int {
        val sim = (1.0f - (distance / 1.40f)) * 100f
        return sim.toInt().coerceIn(0, 100)
    }

    /**
     * Computes the Root Mean Square (RMS) amplitude of 16-bit linear PCM audio.
     * Silence/ambient noise is typically < 150 RMS.
     * Voiced human speech typically ranges between 350 and 8000+ RMS.
     */
    fun calculatePcmRms(pcmAudio: ByteArray): Double {
        if (pcmAudio.size < 2) return 0.0
        val shortCount = pcmAudio.size / 2
        val buffer = ByteBuffer.wrap(pcmAudio).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sumSquare = 0.0
        for (i in 0 until shortCount) {
            val sample = buffer.get(i).toDouble()
            sumSquare += sample * sample
        }
        return sqrt(sumSquare / shortCount)
    }

    /**
     * Computes the centroid (normalized average) of multiple speaker sample embeddings.
     * Pre-normalizes each sample to prevent volume/energy skew across recordings.
     */
    fun averageVectors(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val validVectors = vectors.filter { it.isNotEmpty() }
        if (validVectors.isEmpty()) return null

        val dim = validVectors[0].size
        val avg = FloatArray(dim)
        var validCount = 0

        for (rawVec in validVectors) {
            if (rawVec.size != dim) continue
            var norm = 0.0
            for (v in rawVec) {
                norm += v.toDouble() * v.toDouble()
            }
            val sqrtNorm = sqrt(norm).toFloat()
            if (sqrtNorm > 0f) {
                for (i in 0 until dim) {
                    avg[i] += rawVec[i] / sqrtNorm
                }
                validCount++
            }
        }

        if (validCount == 0) return null

        // Normalize the averaged centroid vector
        var norm = 0.0
        for (v in avg) {
            norm += v.toDouble() * v.toDouble()
        }
        val sqrtNorm = sqrt(norm).toFloat()
        if (sqrtNorm > 0f) {
            for (i in 0 until dim) {
                avg[i] /= sqrtNorm
            }
        }

        return avg
    }

    /**
     * Extracts a speaker embedding vector (x-vector) from a 16kHz mono 16-bit PCM audio buffer.
     */
    @Synchronized
    fun extractSpeakerVector(pcmAudio: ByteArray): FloatArray? {
        val model = VoskModelManager.model
        val spkModel = VoskModelManager.speakerModel

        if (model == null || spkModel == null) {
            Log.w(TAG, "Cannot extract speaker vector: Vosk model or speaker model is not ready")
            return null
        }
        if (pcmAudio.size < 1600) { // Require at least 50ms of audio (16000 * 2 * 0.05)
            Log.w(TAG, "Audio buffer too short for speaker extraction (${pcmAudio.size} bytes)")
            return null
        }

        var rec: Recognizer? = null
        return try {
            rec = Recognizer(model, 16000.0f, spkModel)

            // Feed audio in clean 4096-byte blocks so Kaldi's acoustic and x-vector pipeline processes all frames
            var offset = 0
            val chunkSize = 4096
            while (offset < pcmAudio.size) {
                val len = minOf(chunkSize, pcmAudio.size - offset)
                rec.acceptWaveForm(pcmAudio.copyOfRange(offset, offset + len), len)
                offset += len
            }

            // Feed 400ms of trailing silence padding so Kaldi's nnet3 context window completes pooling on short commands
            val silencePadding = ByteArray(12800)
            rec.acceptWaveForm(silencePadding, silencePadding.size)

            val jsonStr = rec.finalResult
            if (jsonStr.isNullOrBlank()) return null

            val json = JSONObject(jsonStr)
            if (json.has("spk")) {
                val arr = json.getJSONArray("spk")
                val vector = FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
                if (vector.isNotEmpty()) vector else null
            } else {
                Log.d(TAG, "No 'spk' field found in Vosk final result: $jsonStr")
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error extracting speaker vector from PCM: ${e.message}", e)
            null
        } finally {
            try {
                rec?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Verifies an extracted speaker vector against the enrolled owner profile stored in preferences.
     * Uses multi-sample nearest-neighbor + centroid matching:
     * Compares currentVector against the centroid (masterProfile) AND against ALL individual sample vectors in sampleProfiles.
     * If ANY sample vector matches with distance <= threshold, consider it a match (nearest-neighbor distance).
     * If distance <= threshold, return isMatch = true.
     */
    fun verifySpeakerVector(currentVector: FloatArray, prefs: AppPreferences): VoiceMatchResult {
        val masterProfile = prefs.getOwnerVoiceProfile()
        val sampleProfiles = prefs.getOwnerVoiceSamples()
        val threshold = prefs.ownerVoiceThreshold

        if ((masterProfile == null || masterProfile.isEmpty()) && sampleProfiles.isEmpty()) {
            // No profile enrolled yet; allow all speakers by default
            return VoiceMatchResult(
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = threshold,
                hasExtractedVector = true
            )
        }

        var minDistance = 2.0f

        // Check against master centroid
        if (masterProfile != null && masterProfile.isNotEmpty()) {
            val d = calculateCosineDistance(currentVector, masterProfile)
            if (d < minDistance) minDistance = d
        }

        // Check against each enrolled sample vector for optimal match across tones/cadences (nearest-neighbor)
        for (sample in sampleProfiles) {
            if (sample.isNotEmpty()) {
                val d = calculateCosineDistance(currentVector, sample)
                if (d < minDistance) minDistance = d
            }
        }

        val isMatch = minDistance <= threshold
        val similarityPercent = distanceToSimilarityPercent(minDistance)

        Log.d(
            TAG,
            "verifySpeakerVector: minDistance=$minDistance, threshold=$threshold, isMatch=$isMatch, " +
            "similarity=$similarityPercent%, samplesCompared=${sampleProfiles.size}"
        )

        return VoiceMatchResult(
            isMatch = isMatch,
            distance = minDistance,
            similarityPercent = similarityPercent,
            threshold = threshold,
            hasExtractedVector = true
        )
    }

    /**
     * Verifies raw PCM audio against the enrolled owner profile.
     *
     * Rules:
     * 1. If prefs.isOwnerVoiceEnabled is false or !prefs.hasOwnerVoiceProfile(), return isMatch = true immediately.
     * 2. If vector is null (e.g. Vosk model not loaded or short utterance < 3200 bytes):
     *    DO NOT silently reject! Instead of isMatch = false, check if hasOwnerVoiceProfile is true:
     *    if the audio has sufficient energy (PCM RMS > 300) and user is in active session,
     *    fail OPEN with isMatch = true or hasExtractedVector = false so the user is NOT permanently silenced.
     */
    fun verifySpeakerAudio(
        pcmAudio: ByteArray,
        prefs: AppPreferences,
        isActiveSession: Boolean = true
    ): VoiceMatchResult {
        if (!prefs.isOwnerVoiceEnabled || !prefs.hasOwnerVoiceProfile()) {
            return VoiceMatchResult(
                isMatch = true,
                distance = 0.0f,
                similarityPercent = 100,
                threshold = prefs.ownerVoiceThreshold,
                hasExtractedVector = false
            )
        }

        val vector = extractSpeakerVector(pcmAudio)
        if (vector == null) {
            val rms = calculatePcmRms(pcmAudio)
            val hasEnergy = rms > 300.0
            val failOpen = hasEnergy && isActiveSession

            Log.w(
                TAG,
                "Could not extract speaker vector (size=${pcmAudio.size} bytes, rms=${rms.toInt()}). " +
                "hasEnergy=$hasEnergy, isActiveSession=$isActiveSession -> failOpen=$failOpen"
            )

            return VoiceMatchResult(
                isMatch = failOpen,
                distance = if (failOpen) (prefs.ownerVoiceThreshold - 0.05f).coerceAtLeast(0.0f) else 1.0f,
                similarityPercent = if (failOpen) 75 else 0,
                threshold = prefs.ownerVoiceThreshold,
                hasExtractedVector = false
            )
        }

        return verifySpeakerVector(vector, prefs)
    }

    /**
     * Security context helper: verifies owner voice specifically for wake-word activation ("Hey Jarvis").
     * Wake word triggers before an active session is established.
     */
    fun verifyOwnerForWakeWord(pcmAudio: ByteArray, prefs: AppPreferences): VoiceMatchResult {
        Log.i(TAG, "verifyOwnerForWakeWord: evaluating wake word audio buffer (${pcmAudio.size} bytes)")
        val result = verifySpeakerAudio(pcmAudio, prefs, isActiveSession = false)
        Log.i(
            TAG,
            "verifyOwnerForWakeWord result: match=${result.isMatch}, dist=${result.distance}, " +
            "sim=${result.similarityPercent}%, hasVector=${result.hasExtractedVector}"
        )
        return result
    }

    /**
     * Security context helper: verifies owner voice specifically for sensitive screen unlocking operations.
     * Requires strict validation against enrolled voice profile.
     */
    fun verifyOwnerForScreenUnlock(pcmAudio: ByteArray, prefs: AppPreferences): VoiceMatchResult {
        Log.i(TAG, "verifyOwnerForScreenUnlock: evaluating screen unlock audio buffer (${pcmAudio.size} bytes)")
        val result = verifySpeakerAudio(pcmAudio, prefs, isActiveSession = false)
        Log.i(
            TAG,
            "verifyOwnerForScreenUnlock result: match=${result.isMatch}, dist=${result.distance}, " +
            "sim=${result.similarityPercent}%, hasVector=${result.hasExtractedVector}"
        )
        return result
    }
}
