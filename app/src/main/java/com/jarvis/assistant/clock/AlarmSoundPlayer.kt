package com.jarvis.assistant.clock

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * Plays an alarm sound on the alarm audio stream. The ringing service and the tone preview both use this same class, so
 * what you hear when you preview a tone is exactly what plays when the alarm rings.
 */
class AlarmSoundPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    /** The sound that is actually playing (it can be a fallback if the chosen one could not be opened). */
    var playingUri: Uri? = null
        private set

    val isPlaying: Boolean
        get() = try { player?.isPlaying == true } catch (_: Exception) { false }

    /**
     * Tries each candidate in order and plays the first one that opens. Returns true if something is playing.
     * [onFinished] is called when a non-looping sound ends by itself.
     */
    fun start(candidates: List<Uri>, loop: Boolean, volume: Float = 1f, onFinished: (() -> Unit)? = null): Boolean {
        stop()
        for (uri in candidates) {
            if (tryStart(uri, loop, volume, onFinished)) return true
        }
        return false
    }

    private fun tryStart(uri: Uri, loop: Boolean, volume: Float, onFinished: (() -> Unit)?): Boolean {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.isLooping = loop
            mp.setVolume(volume, volume)
            if (!loop && onFinished != null) mp.setOnCompletionListener { onFinished() }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Player error $what/$extra on $uri")
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            playingUri = uri
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not play $uri: ${e.message}")
            runCatching { mp.release() }
            false
        }
    }

    fun setVolume(volume: Float) {
        try { player?.setVolume(volume, volume) } catch (_: Exception) {}
    }

    fun stop() {
        val mp = player
        player = null
        playingUri = null
        if (mp != null) {
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
    }

    companion object {
        private const val TAG = "AlarmSoundPlayer"
    }
}
