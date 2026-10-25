package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    @Test
    fun aShortReplyIsOnePiece() {
        assertEquals(listOf("Namaste! How can I help?"), SpeechText.chunks("  Namaste! How can I help?  "))
    }

    @Test
    fun aLongReplyIsSpokenInFullAcrossPieces() {
        val sentence = "This sentence is about forty characters. "
        val reply = sentence.repeat(60) // 2,460 characters: over the old 1,500 cut-off.
        val pieces = SpeechText.chunks(reply)

        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= SpeechText.CHUNK_CHARS })
        assertEquals(reply.trim(), pieces.joinToString(" "))
    }

    @Test
    fun piecesEndAtSentencesIncludingTheDanda() {
        val pieces = SpeechText.chunks("नमस्ते। आप कैसे हैं? ठीक हूँ।", max = 12)
        assertEquals(listOf("नमस्ते।", "आप कैसे हैं?", "ठीक हूँ।"), pieces)
    }

    @Test
    fun aSentenceWithNoBreaksIsStillSplit() {
        val pieces = SpeechText.chunks("word ".repeat(200), max = 100)
        assertTrue(pieces.all { it.length <= 100 })
        assertEquals(200, pieces.sumOf { it.split(' ').size })
    }

    @Test
    fun blankMeansNothingToSay() {
        assertEquals(emptyList<String>(), SpeechText.chunks("   "))
    }
}
