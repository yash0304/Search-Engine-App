package com.sarvam.voiceassistant

/**
 * Splits a reply into pieces the speech APIs accept, at sentence ends, so a long answer is
 * spoken in full instead of being cut at the first request's character limit.
 */
object SpeechText {

    /** Well under bulbul's per-request limit, and short enough that the first piece starts fast. */
    const val CHUNK_CHARS = 450

    /** About four minutes of speech; anything longer is better read than heard. */
    const val MAX_CHARS = 6_000

    private val sentenceEnd = Regex("(?<=[.!?।॥])\\s+|\\n+")

    fun chunks(text: String, max: Int = CHUNK_CHARS): List<String> {
        val clean = text.trim().take(MAX_CHARS)
        if (clean.isEmpty()) return emptyList()

        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) pieces += current.toString().trim()
            current.clear()
        }

        for (sentence in clean.split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }) {
            for (part in splitLong(sentence, max)) {
                if (current.isNotEmpty() && current.length + 1 + part.length > max) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(part)
            }
        }
        flush()
        return pieces
    }

    /** A sentence longer than [max] is broken at spaces, or anywhere if it has none. */
    private fun splitLong(sentence: String, max: Int): List<String> {
        if (sentence.length <= max) return listOf(sentence)
        val parts = mutableListOf<String>()
        var rest = sentence
        while (rest.length > max) {
            val cut = rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
            parts += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }
}
