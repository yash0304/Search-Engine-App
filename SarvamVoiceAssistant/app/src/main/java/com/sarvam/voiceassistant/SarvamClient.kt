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

/**
 * Thin client over the Sarvam AI REST endpoints this app uses.
 *
 *  STT     POST /speech-to-text       multipart, saaras:v3 or v4, any mode
 *  LLM     POST /v1/chat/completions  JSON, model discovered at runtime
 *  TTS     POST /text-to-speech       JSON, model `bulbul:v3`
 *  Models  GET  /v1/models            OpenAI-compatible model listing
 *  Text    POST /translate, /transliterate, /text-lid
 *
 * Streaming speech (WebSockets) lives in [StreamingSpeech], exposed as [streaming]; document
 * reading in [DocumentReader]. Both reuse this client's key and HTTP connection pool.
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

    private val history = mutableListOf<JSONObject>()

    private val webSearch = WebSearch()
    private val weather = WeatherService()

    /** Streaming speech over WebSockets, sharing this client's connection pool. */
    val streaming = StreamingSpeech(apiKey, client)

    /** Reads photos and PDFs with Sarvam's Document Intelligence. */
    val documents = DocumentReader(apiKey, client)

    /** Text of documents the user has shared this conversation, newest last. */
    private val documentContext = mutableListOf<Pair<String, String>>()

    /** Supplies the device position for location-aware tools; null when unavailable. */
    @Volatile
    var locationSource: (suspend () -> Coordinates?)? = null

    /** Looks a word up in the on-device dictionary; returns the matched form and its senses. */
    @Volatile
    var dictionarySource: (suspend (String) -> DictionaryResult)? = null

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
        const val TTS_MODEL = "bulbul:v3"

        private const val TTS_CHAR_LIMIT = 1500
        private const val HISTORY_TURNS = 8

        /** Headroom in case a deployment ignores `reasoning_effort` and thinks anyway. */
        private const val MAX_TOKENS = 800

        /** A title is a few words; this also caps what a runaway reply could cost. */
        private const val TITLE_TOKENS = 40

        const val TOOL_SEARCH = "web_search"
        const val TOOL_WEATHER = "get_weather"
        const val TOOL_RAIN_ROUTE = "rain_on_route"
        const val TOOL_DEFINE = "define_word"
        const val TOOL_TRANSLATE = "translate_text"
        const val TOOL_TRANSLITERATE = "transliterate_text"
        const val TOOL_DETECT_LANGUAGE = "detect_language"

        /** How much of a shared document is kept in context; roughly 3,000 tokens. */
        private const val DOCUMENT_CONTEXT_CHARS = 12_000

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

    suspend fun transcribe(
        audioFile: File,
        languageCode: String = "unknown",
        model: String = SpeechOptions.DEFAULT_STT_MODEL,
        mode: String = SpeechOptions.DEFAULT_MODE,
    ): Transcription =
        withContext(Dispatchers.IO) {
            if (!audioFile.exists() || audioFile.length() <= WAV_HEADER_BYTES) {
                throw SarvamException("No audio was captured. Hold the button and speak, then release.")
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", SpeechOptions.validModel(model))
                .addFormDataPart("mode", SpeechOptions.validMode(mode))
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

        // Definition questions are answered from the dictionary by the app itself rather
        // than left to tool choice. The model picked the live-weather tool when asked what
        // the word "weather" means, and for common words it tends to answer from memory —
        // both of which defeat the point of shipping a dictionary.
        val definitionContext = definitionContextFor(userText, onSearching)

        val messages = JSONArray().apply {
            // Rebuilt each turn so the injected date never goes stale mid-session.
            put(JSONObject().put("role", "system").put("content", SystemPrompt.now()))
            documentPrompt()?.let { put(JSONObject().put("role", "system").put("content", it)) }
            history.takeLast(HISTORY_TURNS).forEach { put(it) }
            // Immediately before the question, so it is the freshest instruction in context.
            definitionContext?.let { put(JSONObject().put("role", "system").put("content", it)) }
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

    /**
     * The dictionary entry for a definition question, or null when the question is not one
     * or the dictionary is unavailable. A failed lookup degrades to normal tool behaviour.
     */
    private suspend fun definitionContextFor(userText: String, onSearching: (String) -> Unit): String? {
        val word = DefinitionRequest.detect(userText) ?: return null
        val lookup = dictionarySource ?: return null

        onSearching(word)
        val result = runCatching { lookup(word) }.getOrElse {
            DictionaryResult.Unavailable(it.message ?: "lookup failed")
        }

        return DictionaryFormatting.format(WordForms.normalise(word), result)
    }

    /** Runs one tool call and returns the `tool` role message carrying its output. */
    private suspend fun runToolCall(call: JSONObject?, onSearching: (String) -> Unit): JSONObject {
        val id = call?.stringOrNull("id").orEmpty()
        val function = call?.optJSONObject("function")
        val name = function?.stringOrNull("name").orEmpty()

        // Arguments arrive as a JSON string, not an object.
        val arguments = runCatching {
            JSONObject(function?.stringOrNull("arguments").orEmpty())
        }.getOrNull() ?: JSONObject()

        // A failed lookup must never fail the turn — the model can still answer without it.
        val output = runCatching {
            when (name) {
                TOOL_SEARCH -> {
                    val query = arguments.stringOrNull("query")
                    if (query == null) "No search query was provided." else {
                        onSearching(query)
                        webSearch.search(query)
                    }
                }

                TOOL_WEATHER -> {
                    val place = arguments.stringOrNull("place")
                    onSearching(place ?: "the weather here")
                    resolvePlace(place)?.let { weather.conditionsAt(it.name, it.coordinates) }
                        ?: unresolved(place)
                }

                TOOL_RAIN_ROUTE -> {
                    val to = arguments.stringOrNull("to")
                    val from = arguments.stringOrNull("from")
                    onSearching(listOfNotNull(from, to).joinToString(" to ").ifBlank { "the route" })

                    val destination = to?.let { weather.geocode(it) }
                    val origin = resolvePlace(from)

                    when {
                        destination == null -> unresolved(to)
                        origin == null -> unresolved(from)
                        else -> weather.rainAlongRoute(origin, destination)
                    }
                }

                TOOL_DEFINE -> {
                    val word = arguments.stringOrNull("word")
                    if (word == null) "No word was provided." else {
                        onSearching(word)
                        val lookup = dictionarySource?.invoke(word)
                            ?: DictionaryResult.Unavailable("no dictionary is configured")
                        DictionaryFormatting.format(WordForms.normalise(word), lookup)
                    }
                }

                TOOL_TRANSLATE -> {
                    val text = arguments.stringOrNull("text")
                    val target = arguments.stringOrNull("target_language")
                    if (text == null) "No text was provided." else {
                        onSearching("a ${target ?: ""} translation")
                        translate(text, target, arguments.stringOrNull("source_language"), arguments.stringOrNull("tone"))
                    }
                }

                TOOL_TRANSLITERATE -> {
                    val text = arguments.stringOrNull("text")
                    val target = arguments.stringOrNull("target_script")
                    if (text == null) "No text was provided." else {
                        onSearching("${target ?: "the"} script")
                        transliterate(text, target, arguments.stringOrNull("source_language"))
                    }
                }

                TOOL_DETECT_LANGUAGE -> {
                    val text = arguments.stringOrNull("text")
                    if (text == null) "No text was provided." else {
                        onSearching("which language it is")
                        identifyLanguage(text)
                    }
                }

                else -> "Unknown tool: $name"
            }
        }.getOrElse { "That lookup could not be completed: ${it.message}" }

        return JSONObject()
            .put("role", "tool")
            .put("tool_call_id", id)
            .put("name", name.ifBlank { TOOL_SEARCH })
            .put("content", output)
    }

    /** A named place, or the device's position when the model omitted the name. */
    private suspend fun resolvePlace(place: String?): GeocodedPlace? {
        if (place != null) return weather.geocode(place)

        val here = locationSource?.invoke() ?: return null
        return GeocodedPlace("your current location", here)
    }

    private fun unresolved(place: String?): String = when (place) {
        null -> "The user's location is not available. Ask them which place they mean."
        else -> "The place \"$place\" could not be found. Ask the user to name it differently."
    }

    /**
     * OpenAI-style tool declarations. Descriptions matter as much as the code: they are what
     * the model reasons over when deciding whether a lookup is worth a round trip, so each
     * says plainly when *not* to use it.
     */
    private fun toolSchemas(): JSONArray {
        fun stringParam(description: String) =
            JSONObject().put("type", "string").put("description", description)

        fun tool(
            name: String,
            description: String,
            properties: JSONObject,
            required: List<String>,
        ): JSONObject {
            val parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray().apply { required.forEach { put(it) } })

            return JSONObject().put("type", "function").put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
        }

        return JSONArray()
            .put(
                tool(
                    name = TOOL_SEARCH,
                    description = "Look up current information on the web, including recent Indian " +
                        "news headlines. Use this for news, prices, fees, rules and rates that " +
                        "change, anything that happened recently, or whenever you " +
                        "would otherwise say you do not know — search instead of offering to. Do " +
                        "NOT use it for greetings, chit-chat, opinions, translation, arithmetic, " +
                        "weather, or general knowledge you already have.",
                    properties = JSONObject().put(
                        "query",
                        stringParam("Search keywords, in English, for the fact to look up."),
                    ),
                    required = listOf("query"),
                ),
            )
            .put(
                tool(
                    name = TOOL_WEATHER,
                    description = "Get live weather and whether it is raining at a place. Use this " +
                        "for any question about rain, temperature or conditions right now. Omit " +
                        "'place' to mean where the user currently is. Do NOT use this when the " +
                        "user asks what the WORD \"weather\" means — that is a dictionary " +
                        "question and belongs to " + TOOL_DEFINE + ".",
                    properties = JSONObject().put(
                        "place",
                        stringParam("City or place name in English. Omit for the user's location."),
                    ),
                    required = emptyList(),
                ),
            )
            .put(
                tool(
                    name = TOOL_DEFINE,
                    description = "Look up what an English word means in the offline dictionary. " +
                        "ALWAYS use this when the user asks the meaning, definition or synonyms " +
                        "of a word — never answer from memory, because inventing a definition is " +
                        "worse than saying the word is not listed.",
                    properties = JSONObject().put(
                        "word",
                        stringParam("The single English word to look up, without punctuation."),
                    ),
                    required = listOf("word"),
                ),
            )
            .put(
                tool(
                    name = TOOL_TRANSLATE,
                    description = "Translate text with Sarvam's dedicated translation models, which " +
                        "are more accurate for Indian languages than translating yourself. Use this " +
                        "whenever the user asks to translate something or asks how to say something " +
                        "in another language. Supports English and all 22 scheduled Indian languages.",
                    properties = JSONObject()
                        .put("text", stringParam("The exact text to translate."))
                        .put("target_language", stringParam("Language to translate into, e.g. Gujarati."))
                        .put(
                            "source_language",
                            stringParam(
                                "Language the text is in. Omit to detect it; required for Assamese, " +
                                    "Urdu, Nepali, Konkani, Kashmiri, Sindhi, Sanskrit, Santali, " +
                                    "Manipuri, Bodo, Maithili and Dogri.",
                            ),
                        )
                        .put(
                            "tone",
                            stringParam(
                                "formal (default), modern-colloquial for everyday speech, " +
                                    "classic-colloquial, or code-mixed to keep common English words.",
                            ),
                        ),
                    required = listOf("text", "target_language"),
                ),
            )
            .put(
                tool(
                    name = TOOL_TRANSLITERATE,
                    description = "Rewrite text in a different script without changing the words, " +
                        "e.g. Hindi in Roman letters or English in Devanagari. Use when the user asks " +
                        "how something is WRITTEN or spelled in a script, not what it means.",
                    properties = JSONObject()
                        .put("text", stringParam("The text to rewrite."))
                        .put("target_script", stringParam("Language whose script to use, e.g. Hindi or English."))
                        .put("source_language", stringParam("Language of the text. Omit to detect it.")),
                    required = listOf("text", "target_script"),
                ),
            )
            .put(
                tool(
                    name = TOOL_DETECT_LANGUAGE,
                    description = "Identify which language and script a piece of text is written in. " +
                        "Use only when the user asks what language something is.",
                    properties = JSONObject().put("text", stringParam("The text to identify.")),
                    required = listOf("text"),
                ),
            )
            .put(
                tool(
                    name = TOOL_RAIN_ROUTE,
                    description = "Find where along a journey it is raining, with distances. Use " +
                        "this whenever the user asks about rain on the way somewhere, or between " +
                        "two places. Omit 'from' to start from where the user currently is.",
                    properties = JSONObject()
                        .put("from", stringParam("Starting place. Omit for the user's location."))
                        .put("to", stringParam("Destination place name, in English.")),
                    required = listOf("to"),
                ),
            )
    }

    private data class Completion(val message: JSONObject, val finishReason: String)

    /** One round trip to the chat endpoint, including recovery from a retired model. */
    private suspend fun requestCompletion(
        messages: JSONArray,
        withTools: Boolean = true,
        maxTokens: Int = MAX_TOKENS,
    ): Completion {
        var model = resolveChatModel()
        var result = sendChat(model, messages, withTools, maxTokens)

        if (!result.success && isModelRejected(result)) {
            // The model went away underneath us. Take the replacement the error names,
            // otherwise re-ask /v1/models, then try exactly once more.
            val suggested = ChatModels.suggestedFrom(result.body, model)
            invalidateModelCache()
            val retryModel = suggested ?: resolveChatModel().takeIf { it != model }

            if (retryModel != null) {
                model = retryModel
                resolvedChatModel = retryModel
                result = sendChat(model, messages, withTools, maxTokens)
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

    private fun sendChat(model: String, messages: JSONArray, withTools: Boolean, maxTokens: Int): HttpResult {
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", maxTokens)
            // Thinking is on by default on sarvam-30b/105b and its tokens are billed as
            // completion tokens. A spoken two-sentence reply needs no chain-of-thought, and
            // with a small budget the reasoning consumes everything — leaving content null,
            // finish_reason "length", and only reasoning_content populated.
            // JSONObject.NULL is required: put(key, null) would drop the field entirely.
            .put("reasoning_effort", JSONObject.NULL)
            .apply { if (withTools && webSearchEnabled) put("tools", toolSchemas()) }

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

        val json = try {
            execute(request, "Speech synthesis")
        } catch (e: SarvamException) {
            // A voice the API no longer has should cost the voice, not the reply.
            if (safeSpeaker == Voices.DEFAULT || e.message?.contains("speaker", ignoreCase = true) != true) throw e
            payload.put("speaker", Voices.DEFAULT)
            execute(
                request.newBuilder().post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build(),
                "Speech synthesis",
            )
        }
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

    /**
     * Speaks a reply over the streaming socket, returning once it has been heard.
     *
     * @throws StreamingException on failure; see [StreamingException.audioStarted].
     */
    suspend fun speakStreaming(text: String, languageCode: String, speaker: String, sink: PcmSink) =
        streaming.speak(
            text = text.take(TTS_CHAR_LIMIT),
            languageCode = Language.spokenOrDefault(languageCode),
            speaker = if (Voices.isValid(speaker)) speaker else Voices.DEFAULT,
            model = TTS_MODEL,
            sink = sink,
        )

    /**
     * A one-line title for a chat, from [material] (see [ChatTitles]). Separate from the
     * conversation — no history, no tools — so it cannot disturb the chat's memory.
     *
     * @return null on any failure; the chat then keeps the name it already has.
     */
    suspend fun suggestTitle(material: String): String? = withContext(Dispatchers.IO) {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", ChatTitles.INSTRUCTION))
            .put(JSONObject().put("role", "user").put("content", material))
        runCatching {
            ChatTitles.clean(requestCompletion(messages, withTools = false, maxTokens = TITLE_TOKENS).message.stringOrNull("content"))
        }.getOrNull()
    }

    // ── 4. Translation and scripts ───────────────────────────────────────

    /** @return the translation, or a sentence explaining why it could not be done. */
    suspend fun translate(text: String, target: String?, source: String? = null, tone: String? = null): String =
        withContext(Dispatchers.IO) {
            when (val plan = TextTools.translation(text, target, source, tone)) {
                is TextTools.Plan.Refused -> plan.reason
                is TextTools.Plan.Request -> postText("translate", plan.body, "Translation")
                    .stringOrNull("translated_text")
                    ?: "The translator returned nothing."
            }
        }

    suspend fun transliterate(text: String, target: String?, source: String? = null): String =
        withContext(Dispatchers.IO) {
            when (val plan = TextTools.transliteration(text, target, source)) {
                is TextTools.Plan.Refused -> plan.reason
                is TextTools.Plan.Request -> postText("transliterate", plan.body, "Transliteration")
                    .stringOrNull("transliterated_text")
                    ?: "Transliteration returned nothing."
            }
        }

    suspend fun identifyLanguage(text: String): String = withContext(Dispatchers.IO) {
        val json = postText("text-lid", JSONObject().put("input", text.take(1000)), "Language detection")
        val language = json.stringOrNull("language_code")
        val script = json.stringOrNull("script_code")
        if (language == null) "The language could not be identified." else {
            "Language: ${TextTools.languageName(language)} ($language)" + (script?.let { ", script: $it" } ?: "")
        }
    }

    private fun postText(path: String, body: JSONObject, what: String): JSONObject = execute(
        Request.Builder()
            .url("$BASE_URL/$path")
            .addHeader("api-subscription-key", apiKey)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build(),
        what,
    )

    // ── 5. Shared documents ──────────────────────────────────────────────

    /** Makes a document's text available to every later turn of this conversation. */
    fun attachDocument(name: String, text: String) {
        documentContext.add(name to text)
    }

    private fun documentPrompt(): String? {
        if (documentContext.isEmpty()) return null
        // Newest first, so if the budget runs out it is the oldest document that is cut.
        var budget = DOCUMENT_CONTEXT_CHARS
        val parts = documentContext.asReversed().mapNotNull { (name, text) ->
            if (budget <= 0) return@mapNotNull null
            val kept = text.take(budget)
            budget -= kept.length
            val cut = if (kept.length < text.length) "\n[…the rest of this document was cut to fit]" else ""
            "=== Document: $name ===\n$kept$cut"
        }
        // Scoped on purpose: an unscoped "answer from this text" made the model refuse
        // ordinary questions ("I don't have details on vermicelli in my resources") for as
        // long as any document stayed in the chat.
        return "The user has shared these documents, read by Sarvam Document Intelligence. " +
            "When a question is about one of them, answer from its text and say so if the " +
            "answer is not there. For any other question, ignore these documents and answer " +
            "normally.\n\n" +
            parts.joinToString("\n\n")
    }

    /**
     * Rebuilds the model's memory from a saved conversation — after the app was closed and
     * reopened, or once expired messages have been dropped — so it still knows what was said.
     */
    fun restoreConversation(messages: List<Message>, documents: List<Pair<String, String>>) {
        history.clear()
        messages.forEach { message ->
            val role = if (message.role == Role.USER) "user" else "assistant"
            history.add(JSONObject().put("role", role).put("content", message.text))
        }
        documentContext.clear()
        documentContext.addAll(documents)
    }

    fun resetConversation() {
        history.clear()
        documentContext.clear()
    }

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

