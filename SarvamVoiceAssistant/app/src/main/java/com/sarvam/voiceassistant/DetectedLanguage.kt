package com.sarvam.voiceassistant

/**
 * Maps MediaPipe's language detector output onto the app's language codes.
 *
 * The detector answers with ISO 639 codes ("hi", "gu", "en"), sometimes with a script
 * ("hi-Latn" for Hindi typed in English letters). Only languages the app can actually speak
 * are returned, and only when the detector is confident — a guess on a two-word message
 * should not switch the voice.
 */
object DetectedLanguage {

    /** Below this the detector is guessing; the caller keeps what it already had. */
    const val MIN_CONFIDENCE = 0.6f

    private val codes = mapOf(
        "en" to "en-IN",
        "hi" to "hi-IN",
        "gu" to "gu-IN",
        "mr" to "mr-IN",
        "bn" to "bn-IN",
        "ta" to "ta-IN",
        "te" to "te-IN",
        "kn" to "kn-IN",
        "ml" to "ml-IN",
        "pa" to "pa-IN",
        "or" to "od-IN",
        "od" to "od-IN",
    )

    /** @return an app language code such as "gu-IN", or null when unsure or unsupported. */
    fun toAppCode(detectorCode: String?, probability: Float): String? {
        if (detectorCode.isNullOrBlank() || probability < MIN_CONFIDENCE) return null
        val base = detectorCode.lowercase().substringBefore('-').substringBefore('_')
        return codes[base]
    }
}
