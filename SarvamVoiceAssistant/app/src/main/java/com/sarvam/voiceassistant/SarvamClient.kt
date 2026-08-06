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
 * endpoints additionally try `Authorization: Bearer`, falling back to the subscription key
 * alone if that is rejected — see [sendAuthenticated].
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

    private val webSearch = WebSearch()

    /** Explicit user choice from Settings. Blank or null means auto-resolve. */
    @Volatile
    var preferredChatModel: String? = null

    /** When off, no tool is offered and the model answers from training alone. */
    @Volatile
    var webSearchEnabled: Boolean = true

    @Volatile
    private var resolvedChatModel: String? = null

    @Volatile
    private var cachedModels: List<String>? = null

    companion object {
        private const val BASE_URL = "https://api.sarvam.ai"
        private const val STT_MODEL = "saaras:v3"
        private const val TTS_MODEL = "bulbul:v3"

        private const val TTS_CHAR_LIMIT = 1500
        private const val HISTORY_TURNS = 8

        /** Headroom in case a deployment ignores `reasoning_effort` and thinks anyway. */
        private const val MAX_TOKENS = 800

        const val TOOL_NAME = "web_search"

        /** How many times the model may search before it has to answer. */
        private const val MAX_TOOL_ROUNDS = 2
    }

    // ── Model discovery ──────────────────────────────────────────────────

    /** Chat models this API key can actually use, newest-preferred first. Cached per client. */
    suspend fun listChatModels(): List<String> = withContext(Dispatchers.IO) {
        cachedModels?.let { return@withContext it }

        val result = sendAuthenticated { builder ->
            builder.url("$BASE_URL/v1/models").get().build()
        }
        if (!result.success) throw SarvamException(errorMessage("Model list", result.code, result.body))

        val data = runCatching { JSONObject(result.body).optJSONArray("data") }.getOrNull()
            ?: throw SarvamException("Model list returned an unexpected response.")

        val ids = buildList {
            for (i in 0 until data.length()) {
                data.optJSONObject(i)?.stringOrNull("id")?.let { add(it) }
            }
        }

        val chatModels = ChatModels.rank(ids)
        cachedModels = chatModels
        chatModels
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
        val picked = ChatModels.pick(available)

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
            val transcript = json.stringOrNull("transcript")
                ?: throw SarvamException("Nothing was recognised in that recording. Try speaking a little louder.")

            val detected = json.stringOrNull("language_code")
                ?: json.stringOrNull("language")
                ?: "unknown"

            Transcription(transcript, Language.spokenOrDefault(detected))
        }

    // ── 2. Chat completion ───────────────────────────────────────────────

    /**
     * @param onSearching invoked with the query when the model decides to look something up,
     *   so the UI can say so rather than appearing to stall.
     */
    suspend fun chat(
        userText: String,
        onSearching: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        // Build the turn without mutating history, so a failed call leaves no residue.
        val pending = JSONObject().put("role", "user").put("content", userText)
        val messages = JSONArray().apply {
            // Rebuilt each turn so the injected date never goes stale mid-session.
            put(JSONObject().put("role", "system").put("content", SystemPrompt.now()))
            history.takeLast(HISTORY_TURNS).forEach { put(it) }
            put(pending)
        }

        // The model may ask to search, read the results, then answer — hence a loop rather
        // than a single call. Bounded so a model that keeps searching cannot spin forever.
        repeat(MAX_TOOL_ROUNDS + 1) { round ->
            val completion = requestCompletion(messages)
            val message = completion.message
            val toolCalls = message.optJSONArray("tool_calls")

            val wantsSearch = webSearchEnabled &&
                toolCalls != null &&
                toolCalls.length() > 0 &&
                round < MAX_TOOL_ROUNDS

            if (!wantsSearch) {
                val reply = extractReply(completion)
                history.add(pending)
                history.add(JSONObject().put("role", "assistant").put("content", reply))
                return@withContext reply
            }

            // Echo the assistant's tool-call turn back verbatim; the API requires it to
            // precede the tool results it is matching against.
            messages.put(message)
            for (i in 0 until toolCalls.length()) {
                messages.put(runToolCall(toolCalls.optJSONObject(i), onSearching))
            }
        }

        throw SarvamException("The assistant kept searching without answering. Try rephrasing.")
    }

    /** Runs one tool call and returns the `tool` role message carrying its output. */
    private suspend fun runToolCall(call: JSONObject?, onSearching: (String) -> Unit): JSONObject {
        val id = call?.stringOrNull("id").orEmpty()
        val function = call?.optJSONObject("function")
        val name = function?.stringOrNull("name").orEmpty()

        // Arguments arrive as a JSON string, not an object.
        val query = runCatching {
            JSONObject(function?.stringOrNull("arguments").orEmpty()).stringOrNull("query")
        }.getOrNull().orEmpty()

        val output = when {
            name != TOOL_NAME -> "Unknown tool: $name"
            query.isBlank() -> "No search query was provided."
            else -> {
                onSearching(query)
                // A failed lookup must not fail the turn — the model can still answer.
                runCatching { webSearch.search(query) }
                    .getOrElse { "The search could not be completed: ${it.message}" }
            }
        }

        return JSONObject()
            .put("role", "tool")
            .put("tool_call_id", id)
            .put("name", name.ifBlank { TOOL_NAME })
            .put("content", output)
    }

    /**
     * OpenAI-style tool declaration. The description is what the model reasons over when
     * deciding whether to search, so it spells out when *not* to — an unnecessary lookup
     * adds a full round trip, which is felt in a spoken conversation.
     */
    private fun searchToolSchema(): JSONArray {
        val parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "query",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Search keywords, in English, for the fact to look up."),
                ),
            )
            .put("required", JSONArray().put("query"))

        val function = JSONObject()
            .put("name", TOOL_NAME)
            .put(
                "description",
                "Look up current information on the web. Use this for anything that happened " +
                    "recently, for facts that change over time, or when you are unsure whether " +
                    "your knowledge is current. Do NOT use it for greetings, chit-chat, " +
                    "opinions, translation, or arithmetic.",
            )
            .put("parameters", parameters)

        return JSONArray().put(JSONObject().put("type", "function").put("function", function))
    }

    private data class Completion(val message: JSONObject, val finishReason: String)

    /** One round trip to the chat endpoint, including recovery from a retired model. */
    private suspend fun requestCompletion(messages: JSONArray): Completion {
        var model = resolveChatModel()
        var result = sendChat(model, messages)

        if (!result.success && isModelRejected(result)) {
            // The model went away underneath us. Take the replacement the error names,
            // otherwise re-ask /v1/models, then try exactly once more.
            val suggested = ChatModels.suggestedFrom(result.body, model)
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

        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: throw SarvamException("Assistant reply contained no choices.")

        val message = choice.optJSONObject("message")
            ?: throw SarvamException("Assistant reply contained no message.")

        // Never mutate `message`: it gets echoed back to the API verbatim on a tool round,
        // and an unrecognised field there risks a 400.
        return Completion(message, choice.stringOrNull("finish_reason").orEmpty())
    }

    private fun extractReply(completion: Completion): String {
        val message = completion.message
        // Only `content` is ever the answer. `reasoning_content` is the model's private
        // chain-of-thought — never read it here, or the assistant reads its own thinking
        // aloud. stringOrNull matters too: Android's optString turns a JSON null into the
        // literal string "null".
        val reply = message.stringOrNull("content")?.let(ChatModels::stripThinking).orEmpty()
        if (reply.isNotEmpty()) return reply

        val thoughtInstead = message.stringOrNull("reasoning_content") != null
        val ranOutOfTokens = completion.finishReason == "length"
        throw SarvamException(
            if (thoughtInstead || ranOutOfTokens) {
                "The model used its whole budget thinking and never answered. " +
                    "Try again, or pick a different model in Settings."
            } else {
                "The model returned an empty reply."
            },
        )
    }

    private fun sendChat(model: String, messages: JSONArray): HttpResult {
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", MAX_TOKENS)
            // Thinking is on by default on sarvam-30b/105b and its tokens are billed as
            // completion tokens. A spoken two-sentence reply needs no chain-of-thought, and
            // with a small budget the reasoning consumes everything — leaving content null,
            // finish_reason "length", and only reasoning_content populated.
            // JSONObject.NULL is required: put(key, null) would drop the field entirely.
            .put("reasoning_effort", JSONObject.NULL)
            .apply { if (webSearchEnabled) put("tools", searchToolSchema()) }

        return sendAuthenticated { builder ->
            builder.url("$BASE_URL/v1/chat/completions")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }
    }

    /** Does this failure look like "that model is gone" rather than a real error? */
    private fun isModelRejected(result: HttpResult): Boolean {
        if (result.code !in listOf(400, 403, 404, 422)) return false
        val body = result.body.lowercase()
        if (!body.contains("model")) return false
        return listOf("deprecat", "not found", "unsupported", "invalid", "unavailable", "retired")
            .any { body.contains(it) }
    }

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
        val encoded = json.optJSONArray("audios")
            ?.optString(0)
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: json.stringOrNull("audio")
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

    private fun authenticatedBuilder(bearer: Boolean): Request.Builder {
        val builder = Request.Builder().addHeader("api-subscription-key", apiKey)
        if (bearer) builder.addHeader("Authorization", "Bearer $apiKey")
        return builder
    }

    /**
     * Calls an OpenAI-compatible endpoint, retrying without the `Authorization` header if the
     * first attempt is rejected.
     *
     * Sending both `api-subscription-key` and `Authorization: Bearer` is a guess about which
     * one a deployment wants, and a server that validates `Authorization` strictly answers 401
     * even though the subscription key alone would have worked. Rather than guess, try both.
     */
    private fun sendAuthenticated(build: (Request.Builder) -> Request): HttpResult {
        val withBearer = send(build(authenticatedBuilder(bearer = true)))
        if (withBearer.success || withBearer.code !in listOf(401, 403)) return withBearer
        return send(build(authenticatedBuilder(bearer = false)))
    }

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
            json.stringOrNull("error")
                ?: json.optJSONObject("error")?.stringOrNull("message")
                ?: json.stringOrNull("message")
        }.getOrNull().orEmpty()

        val hint = when (code) {
            // Include the API's own wording — it distinguishes a bad key from a plan or
            // permission problem, which the generic message used to hide.
            401, 403 -> "Your API key was rejected${detail.ifBlank { "" }.let { if (it.isBlank()) "" else " ($it)" }}. " +
                "Check it in Settings — copy it fresh from dashboard.sarvam.ai with no spaces."
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

/**
 * Reads a string field, treating JSON null as absent.
 *
 * Android's [JSONObject.optString] stringifies `JSONObject.NULL` to the literal `"null"`
 * instead of returning the fallback, so a null field silently becomes the four-character
 * word "null". Every string read from an API response must go through this.
 */
private fun JSONObject.stringOrNull(key: String): String? {
    if (isNull(key)) return null
    val value = optString(key).trim()
    return value.takeIf { it.isNotEmpty() && it != "null" }
}

