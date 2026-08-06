package com.sarvam.voiceassistant

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An API failure with a message that is safe to show in the UI. */
class SarvamException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thin client over the Sarvam AI REST endpoints this app uses.
 *
 *  STT     POST /speech-to-text       multipart, model `saaras:v3`
 *  LLM     POST /v1/chat/completions  JSON, model discovered at runtime
 *  TTS     POST /text-to-speech       JSON, model `bulbul:v3`
 *  Models  GET  /v1/models            OpenAI-compatible model listing
 *
 * Every call authenticates with the `api-subscription-key` header. The OpenAI-compatible
 * endpoints also accept `Authorization: Bearer`, so both are sent there.
 *
 * The chat model is *not* hardcoded. Sarvam has retired chat models more than once
 * (`sarvam-m`, then `sarvam-30b`), and each retirement broke every pinned client. Instead
 * this asks `/v1/models` what the account can actually use, and if a request is still
 * rejected for the model it retries once with the replacement named in the error.
 *
 * All public functions are `suspend` and move to [Dispatchers.IO] themselves.
 */
class SarvamClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    data class Transcription(val transcript: String, val languageCode: String)

    private val history = mutableListOf<JSONObject>()

    /** Explicit user choice from Settings. Blank or null means auto-resolve. */
    @Volatile
    var preferredChatModel: String? = null

    @Volatile
    private var resolvedChatModel: String? = null

    @Volatile
    private var cachedModels: List<String>? = null

    companion object {
        private const val BASE_URL = "https://api.sarvam.ai"
        private const val STT_MODEL = "saaras:v3"
        private const val TTS_MODEL = "bulbul:v3"

        /** Used only when `/v1/models` cannot be reached at all. */
        const val FALLBACK_CHAT_MODEL = "sarvam-105b"

        /**
         * Auto-selection order among whatever the account actually exposes. Anything not
         * listed here still gets used if it is the only chat model available, so a future
         * model works without a code change.
         */
        private val CHAT_MODEL_PREFERENCE = listOf("sarvam-105b", "sarvam-30b")

        /** Substrings that mark a model id as speech/translation rather than chat. */
        private val NON_CHAT_HINTS = listOf(
            "saaras", "bulbul", "mayura", "translate", "vision",
            "tts", "stt", "embed", "ocr", "parse", "rerank",
        )

        private val MODEL_ID_PATTERN = Regex("""sarvam[a-zA-Z0-9_.\-]*""")

        private const val TTS_CHAR_LIMIT = 1500
        private const val HISTORY_TURNS = 8

        private val SYSTEM_PROMPT = """
            You are a helpful, friendly voice assistant.
            You understand Gujarati, Hindi, and English, including code-mixed speech.
            ALWAYS reply in the SAME language the user spoke — never switch unless asked.
            Keep answers short and conversational, at most 2-3 sentences, because they are spoken aloud.
            Never use markdown, bullet points, emoji, or special formatting characters.
        """.trimIndent()
    }

    // ── Model discovery ──────────────────────────────────────────────────

    /** Chat models this API key can actually use, newest-preferred first. Cached per client. */
    suspend fun listChatModels(): List<String> = withContext(Dispatchers.IO) {
        cachedModels?.let { return@withContext it }

        val request = Request.Builder()
            .url("$BASE_URL/v1/models")
            .addHeader("api-subscription-key", apiKey)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        val result = send(request)
        if (!result.success) throw SarvamException(errorMessage("Model list", result.code, result.body))

        val data = runCatching { JSONObject(result.body).optJSONArray("data") }.getOrNull()
            ?: throw SarvamException("Model list returned an unexpected response.")

        val ids = buildList {
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) add(id)
            }
        }

        val chatModels = ids.filter(::looksLikeChatModel)
            .sortedBy { id ->
                // Keep the preferred ones on top; everything else follows in API order.
                CHAT_MODEL_PREFERENCE.indexOf(id).takeIf { it >= 0 } ?: CHAT_MODEL_PREFERENCE.size
            }

        cachedModels = chatModels
        chatModels
    }

    private fun looksLikeChatModel(id: String): Boolean {
        val lower = id.lowercase()
        // Speech models use a colon-versioned form such as `saaras:v3`.
        if (lower.contains(':')) return false
        return NON_CHAT_HINTS.none { lower.contains(it) }
    }

    /** Forget the discovered list so the next call re-queries the API. */
    fun invalidateModelCache() {
        cachedModels = null
        resolvedChatModel = null
    }

    /** The model currently in use, or null before the first chat request resolves one. */
    fun activeChatModel(): String? = preferredChatModel?.takeIf { it.isNotBlank() } ?: resolvedChatModel

    private suspend fun resolveChatModel(): String {
        preferredChatModel?.takeIf { it.isNotBlank() }?.let { return it }
        resolvedChatModel?.let { return it }

        // A failure here must not block chatting — fall back and let the retry path correct us.
        val available = runCatching { listChatModels() }.getOrDefault(emptyList())
        val picked = CHAT_MODEL_PREFERENCE.firstOrNull { it in available }
            ?: available.firstOrNull()
            ?: FALLBACK_CHAT_MODEL

        resolvedChatModel = picked
        return picked
    }

    // ── 1. Speech to text ────────────────────────────────────────────────

    suspend fun transcribe(audioFile: File, languageCode: String = "unknown"): Transcription =
        withContext(Dispatchers.IO) {
            if (!audioFile.exists() || audioFile.length() <= WAV_HEADER_BYTES) {
                throw SarvamException("No audio was captured. Hold the button and speak, then release.")
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", STT_MODEL)
                .addFormDataPart("mode", "transcribe")
                .apply { if (languageCode != "unknown") addFormDataPart("language_code", languageCode) }
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/speech-to-text")
                .addHeader("api-subscription-key", apiKey)
                .post(body)
                .build()

            val json = execute(request, "Transcription")
            val transcript = json.optString("transcript").trim()
            if (transcript.isEmpty()) {
                throw SarvamException("Nothing was recognised in that recording. Try speaking a little louder.")
            }
            val detected = json.optString("language_code")
                .ifBlank { json.optString("language") }
                .ifBlank { "unknown" }

            Transcription(transcript, Language.spokenOrDefault(detected))
        }

    // ── 2. Chat completion ───────────────────────────────────────────────

    suspend fun chat(userText: String): String = withContext(Dispatchers.IO) {
        // Build the turn without mutating history, so a failed call leaves no residue.
        val pending = JSONObject().put("role", "user").put("content", userText)
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            history.takeLast(HISTORY_TURNS).forEach { put(it) }
            put(pending)
        }

        var model = resolveChatModel()
        var result = sendChat(model, messages)

        if (!result.success && isModelRejected(result)) {
            // The model went away underneath us. Take the replacement the error names,
            // otherwise re-ask /v1/models, then try exactly once more.
            val suggested = suggestedModelFrom(result.body, model)
            invalidateModelCache()
            val retryModel = suggested ?: resolveChatModel().takeIf { it != model }

            if (retryModel != null) {
                model = retryModel
                resolvedChatModel = retryModel
                result = sendChat(model, messages)
            }
        }

        if (!result.success) throw SarvamException(errorMessage("Assistant reply", result.code, result.body))

        val json = runCatching { JSONObject(result.body) }.getOrNull()
            ?: throw SarvamException("Assistant reply returned an unexpected response.")

        val reply = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()

        if (reply.isEmpty()) throw SarvamException("The model returned an empty reply.")

        history.add(pending)
        history.add(JSONObject().put("role", "assistant").put("content", reply))
        reply
    }

    private fun sendChat(model: String, messages: JSONArray): HttpResult {
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 300)

        val request = Request.Builder()
            .url("$BASE_URL/v1/chat/completions")
            .addHeader("api-subscription-key", apiKey)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return send(request)
    }

    /** Does this failure look like "that model is gone" rather than a real error? */
    private fun isModelRejected(result: HttpResult): Boolean {
        if (result.code !in listOf(400, 403, 404, 422)) return false
        val body = result.body.lowercase()
        if (!body.contains("model")) return false
        return listOf("deprecat", "not found", "unsupported", "invalid", "unavailable", "retired")
            .any { body.contains(it) }
    }

    /** Pull a replacement model id out of an error message, ignoring the one we just tried. */
    private fun suggestedModelFrom(body: String, tried: String): String? =
        MODEL_ID_PATTERN.findAll(body)
            .map { it.value.trimEnd('.', ',', ';', ':', '-', '_', ')', '"', '\'') }
            .filter { it.isNotBlank() && it != tried && looksLikeChatModel(it) }
            .firstOrNull()

    // ── 3. Text to speech ────────────────────────────────────────────────

    suspend fun synthesize(
        text: String,
        languageCode: String,
        speaker: String = Voices.DEFAULT,
    ): ByteArray = withContext(Dispatchers.IO) {
        val safeSpeaker = if (Voices.isValid(speaker)) speaker else Voices.DEFAULT
        val safeText = if (text.length > TTS_CHAR_LIMIT) text.take(TTS_CHAR_LIMIT) else text

        val payload = JSONObject()
            .put("text", safeText)
            .put("target_language_code", Language.spokenOrDefault(languageCode))
            .put("model", TTS_MODEL)
            .put("speaker", safeSpeaker)

        val request = Request.Builder()
            .url("$BASE_URL/text-to-speech")
            .addHeader("api-subscription-key", apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val json = execute(request, "Speech synthesis")
        // Documented shape is {"audios": ["<base64>"]}; accept a bare string too.
        // optString returns "" rather than null for an empty array, so blank-check both branches.
        val encoded = json.optJSONArray("audios")?.optString(0)?.takeIf { it.isNotBlank() }
            ?: json.optString("audio").takeIf { it.isNotBlank() }
            ?: throw SarvamException("Speech synthesis returned no audio.")

        try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw SarvamException("Speech synthesis returned audio that could not be decoded.", e)
        }
    }

    fun resetConversation() = history.clear()

    // ── Shared request plumbing ──────────────────────────────────────────

    private data class HttpResult(val code: Int, val body: String, val success: Boolean)

    private fun send(request: Request): HttpResult = try {
        client.newCall(request).execute().use { response ->
            HttpResult(response.code, response.body?.string().orEmpty(), response.isSuccessful)
        }
    } catch (e: IOException) {
        throw SarvamException("Request failed: check your internet connection.", e)
    }

    private fun execute(request: Request, what: String): JSONObject {
        val result = send(request)
        if (!result.success) throw SarvamException(errorMessage(what, result.code, result.body))
        return try {
            JSONObject(result.body)
        } catch (e: Exception) {
            throw SarvamException("$what returned an unexpected response.", e)
        }
    }

    private fun errorMessage(what: String, code: Int, body: String): String {
        val detail = runCatching {
            val json = JSONObject(body)
            json.optString("error")
                .ifBlank { json.optJSONObject("error")?.optString("message").orEmpty() }
                .ifBlank { json.optString("message") }
        }.getOrNull().orEmpty()

        val hint = when (code) {
            401, 403 -> "Your API key was rejected. Check it in Settings."
            402 -> "Your Sarvam account is out of credits."
            429 -> "Rate limited by Sarvam. Wait a moment and try again."
            in 500..599 -> "Sarvam's servers returned an error. Try again shortly."
            // Surface the API's own wording — it usually names the replacement model.
            else -> detail.ifBlank { "HTTP $code" }
        }
        return "$what failed: $hint"
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val WAV_HEADER_BYTES = 44L
