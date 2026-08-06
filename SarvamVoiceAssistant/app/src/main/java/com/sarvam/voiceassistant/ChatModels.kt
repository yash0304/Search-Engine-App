package com.sarvam.voiceassistant

/**
 * Chat-model selection rules, kept free of Android and network types so they can be unit
 * tested. Sarvam has retired chat models twice, so this logic is what keeps the app working
 * across the next retirement — it is worth testing directly.
 */
object ChatModels {

    /** Used only when the model listing is unreachable. */
    const val FALLBACK = "sarvam-105b"

    /** Auto-selection order. Anything unlisted is still usable if it is all that is offered. */
    val PREFERENCE = listOf("sarvam-105b", "sarvam-30b")

    /** Substrings marking a model id as speech/translation rather than chat. */
    private val NON_CHAT_HINTS = listOf(
        "saaras", "bulbul", "mayura", "translate", "vision",
        "tts", "stt", "embed", "ocr", "parse", "rerank",
    )

    private val MODEL_ID = Regex("""sarvam[a-zA-Z0-9_.\-]*""")
    private val THINK_BLOCK = Regex("""(?s)<think>.*?</think>""")

    fun looksLikeChat(id: String): Boolean {
        val lower = id.lowercase().trim()
        if (lower.isEmpty() || lower == "null") return false
        // Speech models use a colon-versioned form such as `saaras:v3`.
        if (lower.contains(':')) return false
        return NON_CHAT_HINTS.none { lower.contains(it) }
    }

    /** Keeps preferred models on top; everything else follows in the order given. */
    fun rank(ids: List<String>): List<String> =
        ids.filter(::looksLikeChat)
            .sortedBy { id -> PREFERENCE.indexOf(id).takeIf { it >= 0 } ?: PREFERENCE.size }

    /** The model to use given what the API offers. Never returns blank. */
    fun pick(available: List<String>): String =
        PREFERENCE.firstOrNull { it in available }
            ?: available.firstOrNull(String::isNotBlank)
            ?: FALLBACK

    /**
     * Pulls a replacement model id out of an error body, ignoring the one just attempted.
     * "Model sarvam-30b is deprecated, use sarvam-105b" yields "sarvam-105b".
     */
    fun suggestedFrom(errorBody: String, tried: String): String? =
        MODEL_ID.findAll(errorBody)
            .map { it.value.trimEnd('.', ',', ';', ':', '-', '_', ')', '"', '\'') }
            .firstOrNull { it != tried && looksLikeChat(it) }

    /** Removes `<think>…</think>` blocks some models inline into the reply. */
    fun stripThinking(text: String): String = text.replace(THINK_BLOCK, "").trim()
}
