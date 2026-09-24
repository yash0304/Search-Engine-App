package com.sarvam.voiceassistant

/** One dictionary sense, straight out of WordNet. */
data class Sense(
    val partOfSpeech: String,
    val definition: String,
    val synonyms: List<String>,
    val examples: List<String> = emptyList(),
)

/**
 * The outcome of a lookup.
 *
 * [NotFound] and [Unavailable] must stay distinct. Collapsing them — as an earlier version
 * did by returning an empty list for both — makes a broken dictionary claim that a perfectly
 * ordinary word does not exist, which is worse than admitting the lookup failed.
 */
sealed interface DictionaryResult {
    data class Found(val matchedForm: String, val senses: List<Sense>) : DictionaryResult

    /** The dictionary opened and genuinely does not contain the word. */
    data object NotFound : DictionaryResult

    /** The dictionary could not be opened or read at all. */
    data class Unavailable(val reason: String) : DictionaryResult
}

/**
 * Formats dictionary results for the model.
 *
 * The point of the offline dictionary is that the answer is looked up rather than invented,
 * so this hands over the real wording and is explicit about the two ways it can fail.
 *
 * Kept free of Android so it can be unit tested.
 */
object DictionaryFormatting {

    /** More than this is a monologue when spoken aloud. */
    const val MAX_SENSES = 3

    private val POS_NAMES = mapOf(
        "n" to "noun",
        "v" to "verb",
        "adj" to "adjective",
        "adv" to "adverb",
    )

    fun partOfSpeechName(tag: String): String = POS_NAMES[tag] ?: tag

    /**
     * WordNet packs definition and examples into one gloss:
     *   `lasting a very short time; "the ephemeral joys of childhood"`
     * The examples are quoted, so split on the first quoted section.
     */
    fun splitGloss(gloss: String): Pair<String, List<String>> {
        val examples = Regex("\"([^\"]+)\"").findAll(gloss).map { it.groupValues[1].trim() }.toList()
        val definition = gloss.substringBefore("; \"").trim().trim(';', ' ')
        return (definition.ifBlank { gloss.trim() }) to examples
    }

    fun format(word: String, result: DictionaryResult): String = when (result) {
        is DictionaryResult.Unavailable ->
            "The offline dictionary could not be opened (${result.reason}). Tell the user the " +
                "dictionary is unavailable on this device — do NOT invent a meaning, and do not " +
                "claim the word does not exist. You may offer to search the web instead."

        DictionaryResult.NotFound ->
            "\"$word\" is not in the offline dictionary. Say so plainly; do not invent " +
                "a meaning. You may offer to search the web instead."

        is DictionaryResult.Found -> formatFound(word, result)
    }

    private fun formatFound(word: String, found: DictionaryResult.Found): String {
        val heading = buildString {
            append("Dictionary entry for \"$word\"")
            if (!found.matchedForm.equals(word, ignoreCase = true)) {
                append(" (found under \"${found.matchedForm}\")")
            }
            append(", from WordNet. Read the definition as written, then explain it briefly.")
        }

        val body = found.senses.take(MAX_SENSES).mapIndexed { index, sense ->
            buildString {
                append("${index + 1}. [${partOfSpeechName(sense.partOfSpeech)}] ${sense.definition}")
                if (sense.synonyms.isNotEmpty()) {
                    append(" Synonyms: ${sense.synonyms.take(6).joinToString(", ")}.")
                }
                sense.examples.firstOrNull()?.let { append(" Example: \"$it\"") }
            }
        }

        val more = (found.senses.size - MAX_SENSES).takeIf { it > 0 }
            ?.let { "\n($it further sense${if (it == 1) "" else "s"} not shown.)" }
            .orEmpty()

        return "$heading\n" + body.joinToString("\n") + more
    }
}
