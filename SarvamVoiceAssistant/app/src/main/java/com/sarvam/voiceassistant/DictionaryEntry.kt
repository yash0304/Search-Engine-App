package com.sarvam.voiceassistant

/** One dictionary sense, straight out of WordNet. */
data class Sense(
    val partOfSpeech: String,
    val definition: String,
    val synonyms: List<String>,
    val examples: List<String> = emptyList(),
)

/**
 * Formats dictionary results for the model.
 *
 * The whole point of the offline dictionary is that the answer is looked up rather than
 * invented, so this hands over the real wording and states plainly when a word is absent.
 * "Not in the dictionary" is a correct answer, and the model must be able to give it.
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

    fun format(word: String, senses: List<Sense>, matchedForm: String? = null): String {
        if (senses.isEmpty()) {
            return "\"$word\" is not in the offline dictionary. Say so plainly; do not invent " +
                "a meaning. You may offer to search the web instead."
        }

        val heading = buildString {
            append("Dictionary entry for \"$word\"")
            if (matchedForm != null && !matchedForm.equals(word, ignoreCase = true)) {
                append(" (found under \"$matchedForm\")")
            }
            append(", from WordNet. Read the definition as written, then explain it briefly.")
        }

        val body = senses.take(MAX_SENSES).mapIndexed { index, sense ->
            buildString {
                append("${index + 1}. [${partOfSpeechName(sense.partOfSpeech)}] ${sense.definition}")
                if (sense.synonyms.isNotEmpty()) {
                    append(" Synonyms: ${sense.synonyms.take(6).joinToString(", ")}.")
                }
                sense.examples.firstOrNull()?.let { append(" Example: \"$it\"") }
            }
        }

        val more = (senses.size - MAX_SENSES).takeIf { it > 0 }
            ?.let { "\n(${it} further sense${if (it == 1) "" else "s"} not shown.)" }
            .orEmpty()

        return "$heading\n" + body.joinToString("\n") + more
    }
}
