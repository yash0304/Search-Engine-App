package com.sarvam.voiceassistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock

/**
 * Plays raw 16-bit mono PCM as it arrives, for streamed replies.
 *
 * MediaPlayer needs a complete file, which is exactly what streaming avoids; AudioTrack in
 * stream mode plays each chunk the moment it is written.
 */
class PcmPlayer(private val sampleRate: Int = StreamingProtocol.TTS_SAMPLE_RATE) : PcmSink {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false
    private var framesWritten = 0L

    override fun start() {
        stopped = false
        framesWritten = 0

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "This device cannot play $sampleRate Hz audio." }

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            // A few hundred ms of headroom so network jitter does not cause gaps.
            .setBufferSizeInBytes(maxOf(minBuffer * 4, sampleRate / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    override fun write(pcm: ByteArray) {
        val output = track ?: return
        var offset = 0
        while (offset < pcm.size && !stopped) {
            val written = output.write(pcm, offset, pcm.size - offset)
            if (written <= 0) return // Released underneath us by stop().
            offset += written
        }
        framesWritten += offset / BYTES_PER_FRAME
    }

    override fun drainAndStop() {
        val output = track ?: return
        // Wait for the playback position to reach what was written. Bounded by how long
        // the audio is, plus slack, so a stalled device cannot hang the turn.
        val audioMillis = framesWritten * 1000 / sampleRate
        val deadline = SystemClock.elapsedRealtime() + audioMillis + DRAIN_SLACK_MS
        while (!stopped && SystemClock.elapsedRealtime() < deadline) {
            val played = runCatching { output.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(framesWritten)
            if (played >= framesWritten) break
            SystemClock.sleep(POLL_MS)
        }
        stop()
    }

    override fun stop() {
        stopped = true
        val output = track ?: return
        track = null
        // pause() + flush() first so a write() blocked on another thread returns promptly.
        runCatching { output.pause() }
        runCatching { output.flush() }
        runCatching { output.stop() }
        runCatching { output.release() }
    }

    private companion object {
        const val BYTES_PER_FRAME = 2
        const val DRAIN_SLACK_MS = 2_000L
        const val POLL_MS = 20L
    }
}
