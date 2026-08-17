package com.sarvam.voiceassistant

/**
 * Reduces a spoken word to forms the dictionary might actually contain.
 *
 * Speech gives you "running", "mice" or "happiest"; WordNet stores "run", "mouse", "happy".
 * These are WordNet's own detachment rules. Irregular forms ("ran", "went") are not covered
 * here — they come from the morph table in the database, which is exact rather than guessed.
 *
 * Kept free of Android so it can be unit tested.
 */
object WordForms {

    /** Suffix replacements, longest first so "ches" is tried before "s". */
    private val RULES = listOf(
        // -y adjectives: happy -> happier/happiest, so undo that before the plain -er/-est.
        "iest" to "y",
        "ier" to "y",
        "ches" to "ch",
        "shes" to "sh",
        "ses" to "s",
        "xes" to "x",
        "zes" to "z",
        "ies" to "y",
        "men" to "man",
        "ing" to "",
        "ing" to "e",
        "est" to "",
        "est" to "e",
        "ed" to "",
        "ed" to "e",
        "er" to "",
        "er" to "e",
        "es" to "",
        "es" to "e",
        "s" to "",
    )

    /**
     * Strips punctuation and casing. Speech-to-text often returns "Ephemeral?" or
     * "the word 'kismet'" — the caller is responsible for the latter, this handles the rest.
     */
    fun normalise(raw: String): String =
        raw.trim()
            .trim('"', '\'', '.', ',', '?', '!', ':', ';', '(', ')')
            .lowercase()
            .replace(Regex("\\s+"), " ")

    /**
     * The normalised word first, then plausible base forms in the order they should be tried.
     * Never returns duplicates or empty strings.
     */
    fun candidates(raw: String): List<String> {
        val word = normalise(raw)
        if (word.isEmpty()) return emptyList()

        val found = LinkedHashSet<String>()
        found.add(word)

        for ((suffix, replacement) in RULES) {
            if (word.length > suffix.length && word.endsWith(suffix)) {
                val base = word.dropLast(suffix.length) + replacement
                if (base.isNotEmpty()) found.add(base)
            }
        }

        // Doubled consonant before -ing/-ed: "running" -> "run", "stopped" -> "stop".
        val undoubled = undouble(word)
        if (undoubled != null) found.add(undoubled)

        return found.toList()
    }

    private fun undouble(word: String): String? {
        for (suffix in listOf("ing", "ed")) {
            if (!word.endsWith(suffix)) continue
            val stem = word.dropLast(suffix.length)
            if (stem.length >= 3) {
                val last = stem.last()
                if (last == stem[stem.length - 2] && last.isLetter() && last !in "aeiou") {
                    return stem.dropLast(1)
                }
            }
        }
        return null
    }
}
