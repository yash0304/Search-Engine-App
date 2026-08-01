package com.sarvam.voiceassistant

/**
 * Valid speaker voices for `bulbul:v3`.
 *
 * Speaker names are case-sensitive and must be lowercase. Sending a name that is not
 * on this list makes the text-to-speech call fail with an HTTP 400.
 *
 * Note: the voices are not language-locked — any speaker can render any supported
 * language, so [defaultSpeakerFor] is a starting preference, not a constraint.
 */
object Voices {

    val ALL: List<String> = listOf(
        "shubh", "aditya", "ritu", "priya", "neha", "rahul", "pooja", "rohan",
        "simran", "kavya", "amit", "dev", "ishita", "shreya", "ratan", "varun",
        "manan", "sumit", "roopa", "kabir", "aayan", "ashutosh", "advait",
        "amelia", "sophia", "anand", "tanya", "tarun", "sunny", "mani", "gokul",
        "vijay", "shruti", "suhani", "mohit", "kavitha", "rehan", "soham", "rupali",
    )

    /** The documented API default when `speaker` is omitted. */
    const val DEFAULT = "shubh"

    private val byLanguage = mapOf(
        "gu-IN" to "ritu",
        "hi-IN" to "shubh",
        "en-IN" to "amelia",
    )

    fun defaultSpeakerFor(languageCode: String): String =
        byLanguage[languageCode] ?: DEFAULT

    fun isValid(speaker: String): Boolean = speaker in ALL
}

/** Languages this app offers for typed input and for forcing the transcription language. */
enum class Language(val code: String, val label: String, val nativeLabel: String) {
    AUTO("unknown", "Auto-detect", "Auto"),
    GUJARATI("gu-IN", "Gujarati", "ગુજરાતી"),
    HINDI("hi-IN", "Hindi", "हिन्दी"),
    ENGLISH("en-IN", "English", "English"),
    ;

    companion object {
        /** Language codes accepted by the speech-to-text endpoint. */
        fun spokenOrDefault(code: String): String =
            if (code.isBlank() || code == "unknown") "en-IN" else code
    }
}
