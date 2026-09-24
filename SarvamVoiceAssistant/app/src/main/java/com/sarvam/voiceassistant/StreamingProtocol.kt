package com.sarvam.voiceassistant

import org.json.JSONObject
import java.util.Base64

/**
 * Message formats for Sarvam's two WebSocket APIs, taken from the official `sarvamai` SDK
 * (v0.1.34) rather than guessed: the SDK is generated from Sarvam's own API definition.
 *
 * Kept free of Android and networking so every message can be unit-tested. Uses
 * [java.util.Base64] rather than android.util.Base64 for the same reason.
 */
object StreamingProtocol {

    const val WS_BASE = "wss://api.sarvam.ai"

    /** Header name the SDK sends on both sockets. */
    const val AUTH_HEADER = "Api-Subscription-Key"

    // ── Text to speech: wss://api.sarvam.ai/text-to-speech/ws ────────────

    /** Raw PCM plays chunk by chunk through AudioTrack; the MP3 default would need decoding. */
    const val TTS_CODEC = "linear16"

    /** bulbul:v3's native rate, so the server never resamples. */
    const val TTS_SAMPLE_RATE = 24_000

    fun ttsUrl(model: String, base: String = WS_BASE): String =
        "$base/text-to-speech/ws?model=${encode(model)}&send_completion_event=true"

    /** Must be the first message on the socket. */
    fun ttsConfig(languageCode: String, speaker: String, model: String): String =
        JSONObject()
            .put("type", "config")
            .put(
                "data",
                JSONObject()
                    .put("model", model)
                    .put("language_code", languageCode)
                    .put("speaker", speaker)
                    .put("speech_sample_rate", TTS_SAMPLE_RATE)
                    .put("output_audio_codec", TTS_CODEC)
                    // Small first chunk so audio starts after the first phrase, not the
                    // first paragraph.
                    .put("min_buffer_size", 30)
                    .put("max_chunk_length", 200),
            )
            .toString()

    fun ttsText(text: String): String =
        JSONObject().put("type", "text").put("data", JSONObject().put("text", text)).toString()

    /** Forces synthesis of whatever is buffered, below min_buffer_size included. */
    const val FLUSH = """{"type":"flush"}"""

    sealed interface TtsEvent {
        class Audio(val pcm: ByteArray) : TtsEvent
        data object Final : TtsEvent
        data class Failure(val message: String) : TtsEvent
        data object Ignored : TtsEvent
    }

    fun parseTts(message: String): TtsEvent {
        val json = runCatching { JSONObject(message) }.getOrNull()
            ?: return TtsEvent.Failure("Unreadable message from the speech service.")
        val data = json.optJSONObject("data")

        return when (json.optString("type")) {
            "audio" -> {
                val encoded = data?.stringOrNull("audio") ?: return TtsEvent.Ignored
                val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                    ?: return TtsEvent.Failure("Speech audio could not be decoded.")
                TtsEvent.Audio(Pcm.withoutWavHeader(bytes))
            }
            "event" -> if (data?.optString("event_type") == "final") TtsEvent.Final else TtsEvent.Ignored
            "error" -> TtsEvent.Failure(data?.stringOrNull("message") ?: "The speech service reported an error.")
            else -> TtsEvent.Ignored
        }
    }

    // ── Speech to text: wss://api.sarvam.ai/speech-to-text/ws ────────────

    const val STT_SAMPLE_RATE = 16_000

    /**
     * @param languageCode `unknown` lets the server detect the language.
     * @param mode transcribe, translate, verbatim, translit or codemix.
     */
    fun sttUrl(languageCode: String, model: String, mode: String, base: String = WS_BASE): String = buildString {
        append("$base/speech-to-text/ws")
        append("?language_code=").append(encode(languageCode))
        append("&model=").append(encode(model))
        append("&mode=").append(encode(mode))
        append("&sample_rate=").append(STT_SAMPLE_RATE)
        // Raw microphone samples, so no WAV header per chunk.
        append("&input_audio_codec=pcm_s16le")
        // START_SPEECH / END_SPEECH events: what lets the app stop when you stop talking.
        append("&vad_signals=true")
        append("&flush_signal=true")
    }

    /** One chunk of 16-bit little-endian mono microphone samples. */
    fun sttAudio(pcm: ByteArray, length: Int = pcm.size): String {
        val encoded = Base64.getEncoder().encodeToString(pcm.copyOf(length))
        return JSONObject()
            .put(
                "audio",
                JSONObject()
                    .put("data", encoded)
                    .put("sample_rate", STT_SAMPLE_RATE)
                    // The SDK's only accepted literal; the codec is set on the connection.
                    .put("encoding", "audio/wav"),
            )
            .toString()
    }

    sealed interface SttEvent {
        data class Transcript(val text: String, val languageCode: String?) : SttEvent
        data object SpeechStarted : SttEvent
        data object SpeechEnded : SttEvent
        data class Failure(val message: String) : SttEvent
        data object Ignored : SttEvent
    }

    fun parseStt(message: String): SttEvent {
        val json = runCatching { JSONObject(message) }.getOrNull()
            ?: return SttEvent.Failure("Unreadable message from the transcription service.")
        val data = json.optJSONObject("data") ?: return SttEvent.Ignored

        return when (json.optString("type")) {
            "data" -> SttEvent.Transcript(
                text = data.stringOrNull("transcript").orEmpty(),
                languageCode = data.stringOrNull("language_code"),
            )
            "events" -> when (data.optString("signal_type")) {
                "START_SPEECH" -> SttEvent.SpeechStarted
                "END_SPEECH" -> SttEvent.SpeechEnded
                else -> SttEvent.Ignored
            }
            "error" -> SttEvent.Failure(
                data.stringOrNull("error") ?: data.stringOrNull("message")
                    ?: "The transcription service reported an error.",
            )
            else -> SttEvent.Ignored
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}

/** PCM helpers shared by streaming playback and its tests. */
object Pcm {
    /**
     * The audio bytes of a chunk, minus a WAV header if the server wrapped one around it.
     * "linear16" should be bare samples, but playing a 44-byte header as sound is an audible
     * click on every chunk, so this is checked rather than assumed.
     */
    fun withoutWavHeader(bytes: ByteArray): ByteArray {
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return bytes

        // Walk the RIFF chunks to "data" rather than assuming a fixed 44-byte header.
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = (bytes[offset + 4].toInt() and 0xFF) or
                ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            val start = offset + 8
            if (id == "data") {
                // Streaming writers often put 0 or 0xFFFFFFFF here; take what is actually present.
                val end = if (size <= 0 || start + size > bytes.size) bytes.size else start + size
                return bytes.copyOfRange(start, end)
            }
            if (size < 0) break
            offset = start + size + (size and 1)
        }
        return bytes
    }
}

/**
 * Reads a string field, treating JSON null as absent.
 *
 * Android's [JSONObject.optString] stringifies `JSONObject.NULL` to the literal `"null"`
 * instead of returning the fallback, so a null field silently becomes the four-character
 * word "null" — which once made every reply read "null" aloud. Every string read from an
 * API response must go through this.
 */
internal fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key).trim()
    return value.takeIf { it.isNotEmpty() && it != "null" }
}
