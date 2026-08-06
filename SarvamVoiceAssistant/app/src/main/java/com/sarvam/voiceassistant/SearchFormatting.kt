package com.sarvam.voiceassistant

/** One retrieved fact, ready to be handed to the model. */
data class SearchResult(
    val title: String,
    val snippet: String,
    val source: String,
)

/**
 * Turns retrieved results into the block of context sent back to the model.
 *
 * Kept free of Android and network types so it can be unit tested. The budget matters: this
 * text is prepended to a spoken conversation, and an over-long context both costs tokens and
 * pushes the model toward reading out an essay instead of two sentences.
 */
object SearchFormatting {

    /** Roughly how much retrieved text is worth sending for a spoken answer. */
    const val CONTEXT_BUDGET = 1200

    private const val SNIPPET_BUDGET = 400

    fun truncate(text: String, max: Int): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= max) return clean
        // Prefer cutting at a sentence end so the model is not fed a half sentence.
        val cut = clean.take(max)
        val lastStop = cut.lastIndexOfAny(charArrayOf('.', '!', '?', '।'))
        // Compare the retained *length* (index + 1), not the index, and accept exactly half.
        val retained = lastStop + 1
        return if (lastStop >= 0 && retained >= max / 2) cut.take(retained) else "${cut.trimEnd()}…"
    }

    fun toContext(results: List<SearchResult>, budget: Int = CONTEXT_BUDGET): String {
        val usable = results.filter { it.snippet.isNotBlank() }
        if (usable.isEmpty()) return "No results found."

        val builder = StringBuilder()
        for (result in usable) {
            val snippet = truncate(result.snippet, SNIPPET_BUDGET)
            val entry = buildString {
                append(result.title.trim().ifBlank { "Result" })
                append(": ")
                append(snippet)
                if (result.source.isNotBlank()) append(" (source: ${result.source.trim()})")
                append('\n')
            }
            // Stop before overshooting rather than truncating mid-entry.
            if (builder.length + entry.length > budget) break
            builder.append(entry)
        }

        return builder.toString().trim().ifBlank {
            truncate(usable.first().snippet, budget)
        }
    }
}
