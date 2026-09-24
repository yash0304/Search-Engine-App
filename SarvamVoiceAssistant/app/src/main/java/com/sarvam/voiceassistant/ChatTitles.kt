package com.sarvam.voiceassistant

/**
 * One-line chat titles, written by the model the way ChatGPT names its chats — "Vermicelli
 * pasta basics" rather than the first question verbatim, and "Electricity bill, August 2026"
 * rather than "Read: IMG_0912.jpg".
 *
 * A title is asked for once per chat, after its first exchange, off the main path so it
 * never delays a reply. Until it arrives, or if it fails, the chat keeps the name from
 * [ConversationStore.titleFor].
 */
object ChatTitles {

    const val INSTRUCTION =
        "Write a title for this conversation, like the name of a chat in a chat list: 2 to 6 " +
            "words, in the same language as the conversation, naming its topic. Reply with the " +
            "title only — no quotes, no full stop, no explanation."

    private const val MAX_LENGTH = 48
    private const val MAX_WORDS = 8

    fun forExchange(question: String, reply: String): String =
        "User: ${question.take(600)}\nAssistant: ${reply.take(600)}"

    fun forDocument(name: String, text: String): String =
        "The user shared a document named \"$name\". It begins:\n${text.take(1_200)}"

    /**
     * Turns what the model returned into a clean one-line title, or null when there is no
     * usable title in it — in which case the chat keeps the name it has.
     */
    fun clean(raw: String?): String? {
        val firstLine = raw
            ?.let(ChatModels::stripThinking)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: return null

        var title = firstLine
            .replace(Regex("^(chat\\s+)?title\\s*[:：-]\\s*", RegexOption.IGNORE_CASE), "")
            .trim { it in QUOTES || it.isWhitespace() || it == '*' || it == '#' }
            .trimEnd { it in ".।!?:;," || it.isWhitespace() }
            .replace(Regex("\\s+"), " ")

        if (title.isEmpty()) return null

        // A model that ignored "2 to 6 words" wrote a sentence, not a title; keep its start.
        val words = title.split(' ')
        if (words.size > MAX_WORDS) title = words.take(MAX_WORDS).joinToString(" ")

        return if (title.length <= MAX_LENGTH) title else title.take(MAX_LENGTH - 1).trimEnd() + "…"
    }

    private const val QUOTES = "\"'`“”‘’«»「」"
}
