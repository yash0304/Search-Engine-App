package com.sarvam.voiceassistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records 16 kHz mono 16-bit PCM and wraps it in a WAV container.
 *
 * [record] is a suspending call that runs entirely on [Dispatchers.IO] and returns only when
 * recording has finished, so nothing ever blocks the main thread. Stopping is cooperative:
 * the UI calls [requestStop] and the recording loop exits on its next iteration.
 */
class AudioRecorder(private val outputDir: File) {

    private val _amplitude = MutableStateFlow(0f)

    /** Normalised 0..1 microphone level, for the mic button animation. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Volatile
    private var stopRequested = false

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        private val HEADER_BYTES = WavHeader.SIZE
    }

    fun requestStop() {
        stopRequested = true
    }

    /**
     * Records until [requestStop] is called or [maxMillis] elapses.
     *
     * @throws IllegalStateException if the microphone could not be opened.
     * @return a complete WAV file.
     */
    @SuppressLint("MissingPermission") // Caller checks RECORD_AUDIO before invoking.
    suspend fun record(maxMillis: Long): File = withContext(Dispatchers.IO) {
        stopRequested = false
        _amplitude.value = 0f

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "This device cannot record 16 kHz mono audio." }

        // A larger buffer than the minimum reduces the chance of dropped samples.
        val bufferSize = minBuffer * 2

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release() // Otherwise the failed instance holds the mic handle open.
            error("Could not open the microphone. Another app may be using it.")
        }

        val wavFile = File(outputDir, "recording.wav")
        var pcmBytes = 0L

        try {
            RandomAccessFile(wavFile, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(HEADER_BYTES)) // Placeholder, patched once the size is known.

                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                val deadline = System.currentTimeMillis() + maxMillis

                while (!stopRequested && System.currentTimeMillis() < deadline) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    out.write(buffer, 0, read)
                    pcmBytes += read
                    _amplitude.value = rms(buffer, read)
                }

                out.seek(0)
                out.write(WavHeader.build(pcmBytes.toInt(), SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE))
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            _amplitude.value = 0f
        }

        wavFile
    }

    /** Root-mean-square level of a 16-bit little-endian PCM chunk, normalised to 0..1. */
    private fun rms(buffer: ByteArray, length: Int): Float {
        val samples = length / 2
        if (samples == 0) return 0f
        val shorts = ByteBuffer.wrap(buffer, 0, samples * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        for (i in 0 until samples) {
            val v = shorts.get(i).toDouble()
            sum += v * v
        }
        val level = sqrt(sum / samples) / Short.MAX_VALUE
        return min(1.0, level * 4).toFloat() // Speech rarely nears full scale; scale for visibility.
    }

}
