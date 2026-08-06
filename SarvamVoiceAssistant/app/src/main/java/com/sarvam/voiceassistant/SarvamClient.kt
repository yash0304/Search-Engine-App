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
 * Thin client over the three Sarvam AI REST endpoints this app uses.
 *
 *  STT  POST /speech-to-text       multipart, model `saaras:v3`
 *  LLM  POST /v1/chat/completions  JSON, model `sarvam-30b`
 *  TTS  POST /text-to-speech       JSON, model `bulbul:v3`
 *
 * Every call authenticates with the `api-subscription-key` header. The chat endpoint is
 * OpenAI-compatible and also accepts `Authorization: Bearer`, so both are sent there.
 *
 * All public functions are `suspend` and move to [Dispatchers.IO] themselves, so they are
 * safe to call directly from a ViewModel coroutine.
 */
class SarvamClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    data class Transcription(val transcript: String, val languageCode: String)

    private val history = mutableListOf<JSONObject>()

    companion object {
        private const val BASE_URL = "https://api.sarvam.ai"
        private const val STT_MODEL = "saaras:v3"

        /**
         * `sarvam-m` (24B) is deprecated on the chat endpoint. `sarvam-30b` is the right
         * default here: replies are 2-3 spoken sentences, so the extra quality of
         * `sarvam-105b` buys little while its latency is felt on every single turn.
         * Swap to "sarvam-105b" if you want stronger reasoning and can accept the wait.
         */
        private const val CHAT_MODEL = "sarvam-30b"
        private const val TTS_MODEL = "bulbul:v3"

        /** Sarvam caps a single text-to-speech request; keep well under it. */
        private const val TTS_CHAR_LIMIT = 1500

        /** How many past turns to replay to the model (excluding the system prompt). */
        private const val HISTORY_TURNS = 8

        private val SYSTEM_PROMPT = """
            You are a helpful, friendly voice assistant.
            You understand Gujarati, Hindi, and English, including code-mixed speech.
            ALWAYS reply in the SAME language the user spoke — never switch unless asked.
            Keep answers short and conversational, at most 2-3 sentences, because they are spoken aloud.
            Never use markdown, bullet points, emoji, or special formatting characters.
        """.trimIndent()
    }

    // ── 1. Speech to text ────────────────────────────────────────────────

    /**
     * @param languageCode a BCP-47 code such as `gu-IN`, or `unknown` to let the model detect it.
     */
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
            // The field name has varied across API versions; fall back rather than crash.
            val detected = json.optString("language_code")
                .ifBlank { json.optString("language") }
                .ifBlank { "unknown" }

            Transcription(transcript, Language.spokenOrDefault(detected))
        }

    // ── 2. Chat completion ───────────────────────────────────────────────

    suspend fun chat(userText: String): String = withContext(Dispatchers.IO) {
        history.add(JSONObject().put("role", "user").put("content", userText))

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            history.takeLast(HISTORY_TURNS).forEach { put(it) }
        }

        val payload = JSONObject()
            .put("model", CHAT_MODEL)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 300)

        val request = Request.Builder()
            .url("$BASE_URL/v1/chat/completions")
            .addHeader("api-subscription-key", apiKey)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val json = execute(request, "Assistant reply")
        val reply = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()

        if (reply.isEmpty()) throw SarvamException("The model returned an empty reply.")

        history.add(JSONObject().put("role", "assistant").put("content", reply))
        reply
    }

    // ── 3. Text to speech ────────────────────────────────────────────────

    /** Returns decoded WAV bytes ready to write to a file and play. */
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

    private fun execute(request: Request, what: String): JSONObject {
        val raw = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw SarvamException(errorMessage(what, response.code, text))
                text
            }
        } catch (e: SarvamException) {
            throw e
        } catch (e: IOException) {
            throw SarvamException("$what failed: check your internet connection.", e)
        }

        return try {
            JSONObject(raw)
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
            else -> detail.ifBlank { "HTTP $code" }
        }
        return "$what failed: $hint"
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val WAV_HEADER_BYTES = 44L
