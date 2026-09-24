package com.sarvam.voiceassistant

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A malformed WAV header does not crash — speech-to-text just silently returns nothing,
 * which is very hard to diagnose on a device. These assert the bytes directly.
 */
class WavHeaderTest {

    private val pcmSize = 32_000 // One second of 16 kHz mono 16-bit audio.
    private val header = WavHeader.build(pcmSize, 16_000, 1, 16)

    private fun ascii(offset: Int, length: Int) =
        String(header, offset, length, Charsets.US_ASCII)

    private fun int32(offset: Int) =
        ByteBuffer.wrap(header, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun int16(offset: Int) =
        ByteBuffer.wrap(header, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

    @Test
    fun `header is exactly 44 bytes`() {
        assertEquals(44, header.size)
        assertEquals(44, WavHeader.SIZE)
    }

    @Test
    fun `chunk identifiers are correct`() {
        assertEquals("RIFF", ascii(0, 4))
        assertEquals("WAVE", ascii(8, 4))
        assertEquals("fmt ", ascii(12, 4))
        assertEquals("data", ascii(36, 4))
    }

    @Test
    fun `riff size counts everything after the size field`() {
        assertEquals(pcmSize + 36, int32(4))
    }

    @Test
    fun `format block describes 16 bit mono pcm`() {
        assertEquals(16, int32(16)) // Subchunk size for PCM
        assertEquals(1, int16(20))  // Format 1 = PCM, not compressed
        assertEquals(1, int16(22))  // Mono
        assertEquals(16, int16(34)) // Bits per sample
    }

    @Test
    fun `sample rate and derived fields agree`() {
        assertEquals(16_000, int32(24))
        assertEquals(32_000, int32(28)) // byteRate = 16000 * 1 * 16/8
        assertEquals(2, int16(32))      // blockAlign = 1 * 16/8
    }

    @Test
    fun `data size is the pcm byte count`() {
        assertEquals(pcmSize, int32(40))
    }

    @Test
    fun `stereo and higher rates compute their own derived fields`() {
        val stereo = WavHeader.build(1_000, 44_100, 2, 16)
        val byteRate = ByteBuffer.wrap(stereo, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val blockAlign = ByteBuffer.wrap(stereo, 32, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        assertEquals(44_100 * 2 * 2, byteRate)
        assertEquals(4, blockAlign)
    }

    @Test
    fun `an empty recording still produces a valid header`() {
        val empty = WavHeader.build(0, 16_000, 1, 16)
        assertEquals(44, empty.size)
        assertEquals(36, ByteBuffer.wrap(empty, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(0, ByteBuffer.wrap(empty, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }
}
