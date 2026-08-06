package com.sarvam.voiceassistant

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the 44-byte canonical WAV header for 16-bit PCM.
 *
 * Pure and Android-free so it can be unit tested: a wrong byte here does not crash, it just
 * makes speech-to-text return nothing, which is painful to diagnose on a device.
 */
object WavHeader {

    const val SIZE = 44

    fun build(pcmSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        return ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcmSize)                 // Size of everything after this field
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                           // PCM subchunk size
            putShort(1)                          // Audio format 1 = PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmSize)
        }.array()
    }
}
