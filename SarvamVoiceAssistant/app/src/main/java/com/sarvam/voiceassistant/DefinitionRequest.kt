package com.sarvam.voiceassistant

/**
 * Recognises "what does X mean" so the dictionary lookup can be forced rather than left to
 * the model's judgement.
 *
 * Tool selection is a decision the model makes, and it gets it wrong in exactly the cases
 * that matter: asking the meaning of "weather" pulls it toward the live-weather tool, and
 * for a common word it will often answer from memory instead of looking anything up. When
 * this recogniser fires, the app performs the lookup itself and hands the real entry over,
 * so the answer is grounded whatever the model then decides to do.
 *
 * Kept free of Android so it can be unit tested.
 */
object DefinitionRequest {

    /** A capture that is grammar rather than a word worth looking up. */
    private val STOPWORDS = setOf(
        "it", "this", "that", "these", "those", "the", "a", "an", "he", "she", "they",
        "you", "i", "we", "there", "here", "what", "which", "who", "your", "my", "his",
        "her", "their", "our", "its", "is", "was", "are", "were", "be", "been", "word",
        "words", "thing", "something", "anything", "everything", "all", "some", "any",
        "error", "message", "question", "answer", "one", "not", "no", "yes", "and", "or",
    )

    private val PATTERNS = listOf(
        // English, word after the phrase.
        Regex("""what\s+(?:does|do)\s+(?:the\s+word\s+)?["']?([a-z][a-z-]*)["']?\s+mean"""),
        Regex("""what(?:'s|s| is)\s+["']?([a-z][a-z-]*)["']?\s+mean"""),
        Regex("""what(?:'s|s| is)\s+the\s+(?:meaning|definition)\s+of\s+["']?([a-z][a-z-]*)"""),
        Regex("""(?:meaning|definition)\s+of\s+(?:the\s+word\s+)?["']?([a-z][a-z-]*)"""),
        Regex("""^\s*define\s+["']?([a-z][a-z-]*)"""),
        Regex("""synonyms?\s+(?:of|for)\s+["']?([a-z][a-z-]*)"""),

        // Hindi and Gujarati, romanised or in script, where the word comes first:
        // "ephemeral ka matlab", "ephemeral no matlab", "ephemeral નો અર્થ".
        Regex("""["']?([a-z][a-z-]*)["']?\s+(?:ka|ki|ke|no|nu|na|nun)\s+(?:matlab|arth|meaning)"""),
        Regex("""["']?([a-z][a-z-]*)["']?\s+(?:\S+\s+)?(?:मतलब|अर्थ|મતલબ|અર્થ)"""),
        Regex("""(?:matlab|arth)\s+(?:of\s+)?["']?([a-z][a-z-]*)"""),
    )

    /**
     * The English word whose meaning is being asked for, or null when this is not a
     * definition question. Only Latin-script words are returned, because the dictionary
     * is English.
     */
    fun detect(text: String): String? {
        val normalised = text.lowercase().replace(Regex("[.,!?;:]"), " ").replace(Regex("\\s+"), " ")

        for (pattern in PATTERNS) {
            val candidate = pattern.find(normalised)?.groupValues?.getOrNull(1)?.trim() ?: continue
            if (isWorthLookingUp(candidate)) return candidate
        }
        return null
    }

    private fun isWorthLookingUp(candidate: String): Boolean =
        candidate.length >= 2 && candidate !in STOPWORDS
}
