package com.sarvam.voiceassistant

import org.json.JSONObject

/**
 * Request building for Sarvam's translation and transliteration endpoints, kept pure so the
 * routing rules — which are where a wrong choice silently becomes a 400 — are unit-tested.
 *
 * Rules are from the official `sarvamai` SDK (v0.1.34):
 *  - mayura:v1 covers 11 languages plus English, detects the source with "auto", and has
 *    tone modes (formal, modern-colloquial, classic-colloquial, code-mixed). Max 1000 chars.
 *  - sarvam-translate:v1 covers all 22 scheduled languages, formal only, needs an explicit
 *    source language. Max 2000 chars.
 */
object TextTools {

    const val MAYURA = "mayura:v1"
    const val SARVAM_TRANSLATE = "sarvam-translate:v1"

    private const val MAYURA_MAX = 1000
    private const val SARVAM_TRANSLATE_MAX = 2000
    const val TRANSLITERATE_MAX = 1000

    /** Every language code the translation API accepts, with the names a model might use. */
    private val languages = mapOf(
        "en-IN" to listOf("english"),
        "hi-IN" to listOf("hindi"),
        "gu-IN" to listOf("gujarati"),
        "bn-IN" to listOf("bengali", "bangla"),
        "ta-IN" to listOf("tamil"),
        "te-IN" to listOf("telugu"),
        "kn-IN" to listOf("kannada"),
        "ml-IN" to listOf("malayalam"),
        "mr-IN" to listOf("marathi"),
        "pa-IN" to listOf("punjabi", "panjabi"),
        "od-IN" to listOf("odia", "oriya"),
        "as-IN" to listOf("assamese"),
        "ur-IN" to listOf("urdu"),
        "ne-IN" to listOf("nepali"),
        "kok-IN" to listOf("konkani"),
        "ks-IN" to listOf("kashmiri"),
        "sd-IN" to listOf("sindhi"),
        "sa-IN" to listOf("sanskrit"),
        "sat-IN" to listOf("santali"),
        "mni-IN" to listOf("manipuri", "meitei"),
        "brx-IN" to listOf("bodo"),
        "mai-IN" to listOf("maithili"),
        "doi-IN" to listOf("dogri"),
    )

    /** Mayura's languages; also the only ones transliteration supports. */
    val MAYURA_LANGUAGES = setOf(
        "en-IN", "hi-IN", "gu-IN", "bn-IN", "ta-IN", "te-IN", "kn-IN", "ml-IN", "mr-IN", "pa-IN", "od-IN",
    )

    val TONES = setOf("formal", "modern-colloquial", "classic-colloquial", "code-mixed")

    /**
     * Resolves "Gujarati", "gujarati", "gu", "gu-IN" and "GU-in" to "gu-IN"; null when it is
     * not a language the API supports.
     */
    fun languageCode(nameOrCode: String?): String? {
        val value = nameOrCode?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        languages.keys.firstOrNull { it.lowercase() == value }?.let { return it }
        languages.keys.firstOrNull { it.substringBefore('-').lowercase() == value }?.let { return it }
        return languages.entries.firstOrNull { (_, names) -> value in names }?.key
    }

    fun languageName(code: String): String =
        languages[code]?.first()?.replaceFirstChar { it.uppercase() } ?: code

    sealed interface Plan {
        data class Request(val body: JSONObject) : Plan
        data class Refused(val reason: String) : Plan
    }

    /**
     * Builds a translation request, choosing the model from the languages involved.
     *
     * @param source a language, or null to detect it.
     * @param tone one of [TONES]; honoured only where Mayura can be used.
     */
    fun translation(text: String, target: String?, source: String? = null, tone: String? = null): Plan {
        val targetCode = languageCode(target)
            ?: return Plan.Refused("\"$target\" is not a language the translator supports.")
        val sourceCode = source?.let { languageCode(it) ?: return Plan.Refused("\"$it\" is not a supported language.") }

        if (sourceCode == targetCode) return Plan.Refused("The text is already in ${languageName(targetCode)}.")

        val mayuraCan = targetCode in MAYURA_LANGUAGES && (sourceCode == null || sourceCode in MAYURA_LANGUAGES)

        val body = JSONObject().put("target_language_code", targetCode)
        if (mayuraCan) {
            body.put("model", MAYURA)
                .put("input", text.take(MAYURA_MAX))
                .put("source_language_code", sourceCode ?: "auto")
                .put("mode", tone?.takeIf { it in TONES } ?: "formal")
        } else {
            // Sarvam-Translate cannot detect the source language, so it has to be known.
            val knownSource = sourceCode
                ?: return Plan.Refused(
                    "Say which language the text is in to translate into ${languageName(targetCode)}.",
                )
            body.put("model", SARVAM_TRANSLATE)
                .put("input", text.take(SARVAM_TRANSLATE_MAX))
                .put("source_language_code", knownSource)
                .put("mode", "formal")
        }
        return Plan.Request(body)
    }

    /** Builds a transliteration request: same words, different script. */
    fun transliteration(text: String, target: String?, source: String? = null): Plan {
        val targetCode = languageCode(target)
            ?: return Plan.Refused("\"$target\" is not a supported script.")
        if (targetCode !in MAYURA_LANGUAGES) {
            return Plan.Refused("Transliteration into ${languageName(targetCode)} is not supported.")
        }
        val sourceCode = source?.let { languageCode(it) }?.takeIf { it in MAYURA_LANGUAGES } ?: "auto"

        return Plan.Request(
            JSONObject()
                .put("input", text.take(TRANSLITERATE_MAX))
                .put("source_language_code", sourceCode)
                .put("target_language_code", targetCode),
        )
    }
}
