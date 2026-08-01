package com.sarvam.voiceassistant

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Plays a WAV file and suspends until playback actually finishes.
 *
 * The original implementation fired a MediaPlayer and returned immediately, so the UI showed
 * "Ready" while the assistant was still talking and the player leaked if the screen was closed
 * mid-sentence. Here the player is retained so it can be stopped, and always released.
 */
class AudioPlayer {

    private var player: MediaPlayer? = null

    suspend fun play(file: File): Unit = suspendCancellableCoroutine { continuation ->
        stop()

        val mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
        player = mediaPlayer

        var settled = false
        fun finish(block: () -> Unit) {
            if (settled) return
            settled = true
            release(mediaPlayer)
            block()
        }

        mediaPlayer.setOnCompletionListener { finish { continuation.resume(Unit) } }
        mediaPlayer.setOnErrorListener { _, what, extra ->
            finish { continuation.resumeWithException(SarvamException("Could not play the reply (error $what/$extra).")) }
            true
        }

        continuation.invokeOnCancellation { finish {} }

        try {
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.prepare() // Local file: returns promptly.
            mediaPlayer.start()
        } catch (e: Exception) {
            finish { continuation.resumeWithException(SarvamException("Could not play the reply.", e)) }
        }
    }

    fun stop() {
        player?.let(::release)
        player = null
    }

    private fun release(mediaPlayer: MediaPlayer) {
        runCatching { if (mediaPlayer.isPlaying) mediaPlayer.stop() }
        runCatching { mediaPlayer.release() }
        if (player === mediaPlayer) player = null
    }
}
