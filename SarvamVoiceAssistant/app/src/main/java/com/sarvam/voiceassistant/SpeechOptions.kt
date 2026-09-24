package com.sarvam.voiceassistant

/**
 * Speech-to-text choices exposed in Settings. Values are the literals the `sarvamai` SDK
 * (v0.1.34) accepts for both the REST and WebSocket endpoints.
 */
object SpeechOptions {

    data class Mode(val value: String, val label: String, val example: String)

    /** Example outputs are Sarvam's own, for "मेरा फोन नंबर है 9840950950". */
    val MODES = listOf(
        Mode("transcribe", "Transcribe", "मेरा फोन नंबर है 9840950950"),
        Mode("translate", "Translate to English", "My phone number is 9840950950"),
        Mode("codemix", "Mixed script", "मेरा phone number है 9840950950"),
        Mode("translit", "Roman letters", "mera phone number hai 9840950950"),
        Mode("verbatim", "Word for word", "मेरा फोन नंबर है नौ आठ चार zero…"),
    )

    const val DEFAULT_MODE = "transcribe"

    /** v4 is Sarvam's newest; v3 stays the default until v4 has been heard on this app. */
    val STT_MODELS = listOf("saaras:v3", "saaras:v4")
    const val DEFAULT_STT_MODEL = "saaras:v3"

    fun validMode(value: String): String = value.takeIf { v -> MODES.any { it.value == v } } ?: DEFAULT_MODE

    fun validModel(value: String): String = value.takeIf { it in STT_MODELS } ?: DEFAULT_STT_MODEL

    fun label(mode: String): String = MODES.firstOrNull { it.value == mode }?.label ?: mode
}

/**
 * Chooses the language to *speak* a reply in, from the reply itself.
 *
 * Speaking in the question's language fails whenever the two differ — ask in English for a
 * Gujarati phrase and an English voice mangles the Gujarati. The script a reply is written
 * in identifies its language for free, on the device; Sarvam's language-ID API would cost
 * more per reply than synthesising it.
 */
object ReplyLanguage {

    /** Languages bulbul:v3 can speak. */
    val SPOKEN = setOf(
        "hi-IN", "bn-IN", "ta-IN", "te-IN", "kn-IN", "ml-IN", "mr-IN", "gu-IN", "pa-IN", "od-IN", "en-IN",
    )

    private val scripts = listOf(
        0x0A80..0x0AFF to "gu-IN", // Gujarati
        0x0980..0x09FF to "bn-IN", // Bengali
        0x0B80..0x0BFF to "ta-IN", // Tamil
        0x0C00..0x0C7F to "te-IN", // Telugu
        0x0C80..0x0CFF to "kn-IN", // Kannada
        0x0D00..0x0D7F to "ml-IN", // Malayalam
        0x0A00..0x0A7F to "pa-IN", // Gurmukhi
        0x0B00..0x0B7F to "od-IN", // Odia
        0x0900..0x097F to "hi-IN", // Devanagari: Hindi, unless the user is speaking Marathi
    )

    /**
     * @param fallback the language of the question, used for Latin-script replies and to
     *   tell Marathi from Hindi, which share Devanagari.
     */
    fun detect(reply: String, fallback: String): String {
        val counts = IntArray(scripts.size)
        var latin = 0
        for (char in reply) {
            val code = char.code
            if (char in 'a'..'z' || char in 'A'..'Z') latin++
            for ((i, entry) in scripts.withIndex()) {
                if (code in entry.first) counts[i]++
            }
        }

        val best = counts.indices.maxByOrNull { counts[it] } ?: return spoken(fallback)
        // A reply mostly in English that quotes a few words in another script stays English.
        if (counts[best] == 0 || counts[best] < latin / 2) return spoken(fallback)

        val detected = scripts[best].second
        return if (detected == "hi-IN" && fallback == "mr-IN") "mr-IN" else detected
    }

    private fun spoken(code: String): String = if (code in SPOKEN) code else "en-IN"
}
