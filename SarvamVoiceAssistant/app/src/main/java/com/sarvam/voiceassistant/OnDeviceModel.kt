package com.sarvam.voiceassistant

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The on-device model and everything about it that does not need Android: which file, how
 * big, and how a chat becomes its prompt.
 *
 * Gemma 4 E2B in LiteRT-LM format, the same file Google's AI Edge Gallery app offers. The
 * values are from Gallery's own model list (model_allowlists/1_0_19.json), including its
 * 8 GB minimum device memory.
 */
object OnDeviceModel {

    const val NAME = "Gemma 4 E2B"
    const val REPO = "litert-community/gemma-4-E2B-it-litert-lm"
    const val FILE_NAME = "gemma-4-E2B-it.litertlm"

    /** The revision Gallery pins for this file, so an upstream re-upload cannot change it underneath us. */
    const val COMMIT = "6e5c4f1e395deb959c494953478fa5cec4b8008f"

    const val SIZE_BYTES = 2_588_147_712L
    const val MIN_DEVICE_MEMORY_GB = 8

    val DOWNLOAD_URL = "https://huggingface.co/$REPO/resolve/$COMMIT/$FILE_NAME"

    /** Spoken replies are short; this also bounds how long a slow phone can take. */
    const val MAX_OUTPUT_TOKENS = 512

    /**
     * Earlier turns included as context. Every one is re-read on each question, which on a
     * phone is the main cost of a long chat, so only the recent ones are kept.
     */
    const val HISTORY_MESSAGES = 8

    /** Document text included when a chat has one; the model reads it on every question. */
    const val DOCUMENT_CHARS = 4_000

    fun sizeLabel(): String = "%.1f GB".format(Locale.ENGLISH, SIZE_BYTES / 1_000_000_000.0)

    /** A downloaded file of the wrong size is a partial or corrupt download, not a model. */
    fun isComplete(bytesOnDisk: Long): Boolean = bytesOnDisk == SIZE_BYTES

    /**
     * The system instruction: the same essentials as the cloud prompt, minus the tools the
     * phone does not have — so it says so rather than pretending to check the weather.
     */
    fun systemInstruction(now: ZonedDateTime, documents: List<Pair<String, String>> = emptyList()): String {
        val date = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH).format(now)
        val base = """
            You are Boliyan, a helpful, friendly voice assistant running entirely on the user's phone.
            You understand Gujarati, Hindi and English, including code-mixed speech.
            Reply in the same language the user used. Keep answers to 2-3 short sentences,
            because they are spoken aloud. Never use markdown, lists or emoji.
            Today is $date. Answer everyday questions directly from what you know.
            You are offline: you cannot search the web, check weather, traffic or news. If asked
            for something current, say what you know, that it may be out of date, and that
            switching to online answers would give the latest.
        """.trimIndent()

        if (documents.isEmpty()) return base
        val doc = documents.last()
        return base + "\n\nThe user shared a document named \"${doc.first}\". Use it only for questions " +
            "about it:\n" + doc.second.take(DOCUMENT_CHARS)
    }

    /**
     * Room the download needs beyond the file itself, so a full phone is refused up front
     * instead of failing at 90%.
     */
    const val SPARE_BYTES = 300_000_000L

    fun hasRoomFor(freeBytes: Long): Boolean = freeBytes >= SIZE_BYTES + SPARE_BYTES

    /**
     * Phones sold as 8 GB report a little less, as the system reserves some; anything under
     * this is a smaller phone, where the model may be too slow or be closed by Android.
     */
    const val LOW_MEMORY_BYTES = 7_000_000_000L

    /**
     * The recent turns to replay as context, oldest first — starting with a question, since
     * the model's chat format expects the user to speak first.
     */
    fun history(messages: List<Message>): List<Message> =
        messages.takeLast(HISTORY_MESSAGES).dropWhile { it.role != Role.USER }

    /** What a failed download means, from the HTTP status or DownloadManager reason code. */
    fun downloadFailure(code: Int): String = when (code) {
        401, 403 -> "Hugging Face refused the download. Sign in at huggingface.co, open " +
            "$REPO and accept the Gemma terms, then create a read token and paste it below."
        0 -> "Could not reach Hugging Face. Check your internet connection and try again."
        404 -> "The model file was not found. It may have moved; update the app."
        in 500..599 -> "Hugging Face is having trouble ($code). Try again in a few minutes."
        1006 -> "Not enough free storage. The model needs ${sizeLabel()} free."
        1001, 1004, 1005, 1008 -> "The download was interrupted. Tap Download to try again."
        else -> "The download failed ($code). Tap Download to try again."
    }

    /** The most pages read on the phone; each takes several seconds. */
    const val MAX_OFFLINE_PAGES = 8

    /** Gemma hears at most 30 seconds of audio at a time. */
    const val MAX_AUDIO_MS = 30_000L

    const val TRANSCRIBE_PROMPT =
        "Transcribe this audio exactly as spoken, in the language and script the speaker used: " +
            "Gujarati script for Gujarati, Devanagari for Hindi, English letters for English. " +
            "Output only the spoken words, with no introduction. If there is no speech, output nothing."

    const val READ_PAGE_PROMPT =
        "Read this page. Write out all of its text exactly, in the original language and script, " +
            "in reading order. If it has little or no text, describe what it shows instead. " +
            "Output only that, with no introduction."

    /**
     * What the model heard, without the wrappers it sometimes adds — quotes, a
     * "Transcription:" label, thinking. Null when it heard nothing.
     */
    fun cleanTranscript(raw: String): String? {
        var text = ChatModels.stripThinking(raw).trim()
        text = text.replace(Regex("^(transcription|transcript)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        text = text.trim().removeSurrounding("\"").removeSurrounding("“", "”").trim()
        return text.takeIf { it.isNotEmpty() }
    }

    /** Pages read on the phone, as one document; says so when some were left unread. */
    fun joinPages(pages: List<String>, totalPages: Int): String {
        val body = if (pages.size == 1) {
            pages.single().trim()
        } else {
            pages.mapIndexed { i, page -> "--- Page ${i + 1} ---\n${page.trim()}" }.joinToString("\n\n")
        }
        if (totalPages <= pages.size) return body
        return "$body\n\n(Only the first ${pages.size} of $totalPages pages were read on the phone. " +
            "Read it again online for the rest.)"
    }

    /** Strips any thinking the model emits, and whitespace; null when nothing is left. */
    fun cleanReply(raw: String): String? = ChatModels.stripThinking(raw).trim().takeIf { it.isNotEmpty() }
}

/** Where the on-device model stands, for Settings and for routing. */
data class ModelStatus(
    val phase: Phase = Phase.ABSENT,
    val downloadedBytes: Long = 0,
    /** Why the last download failed; shown as is. */
    val problem: String? = null,
) {
    enum class Phase { ABSENT, DOWNLOADING, READY }

    val ready: Boolean get() = phase == Phase.READY

    /** One line for Settings. */
    fun describe(): String = when (phase) {
        Phase.READY -> "Downloaded — answers work without internet."
        Phase.DOWNLOADING -> if (downloadedBytes <= 0) {
            "Starting download…"
        } else {
            val percent = (downloadedBytes * 100 / OnDeviceModel.SIZE_BYTES).coerceIn(0, 100)
            val done = "%.1f".format(Locale.ENGLISH, downloadedBytes / 1_000_000_000.0)
            "Downloading… $percent% ($done of ${OnDeviceModel.sizeLabel()})"
        }
        Phase.ABSENT -> problem ?: "Not downloaded. ${OnDeviceModel.sizeLabel()}, once; Wi-Fi recommended."
    }
}
