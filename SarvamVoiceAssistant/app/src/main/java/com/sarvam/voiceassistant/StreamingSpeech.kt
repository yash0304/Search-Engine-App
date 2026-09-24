package com.sarvam.voiceassistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Somewhere to send decoded speech. Implemented by AudioTrack on a phone, by a list in tests. */
interface PcmSink {
    fun start()

    /** May block until the audio fits in the output buffer; that is the back-pressure. */
    fun write(pcm: ByteArray)

    /** Waits for everything written so far to be heard, then releases. */
    fun drainAndStop()

    /** Stops immediately, discarding anything not yet played. Safe to call more than once. */
    fun stop()
}

/**
 * A streaming failure. [audioStarted] tells the caller whether falling back to the REST
 * endpoint is sensible: if the user has already heard half the reply, repeating it from
 * the start is worse than stopping.
 */
class StreamingException(message: String, val audioStarted: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Sarvam's WebSocket speech APIs: replies start playing as soon as the first phrase is
 * synthesised, and speech is transcribed while you are still talking.
 *
 * Both are optimisations over REST endpoints that already work, so every failure is
 * reported as a [StreamingException] or a null result and the caller falls back. A protocol
 * detail wrong here costs latency, never the answer.
 */
class StreamingSpeech(
    private val apiKey: String,
    baseClient: OkHttpClient,
    private val wsBase: String = StreamingProtocol.WS_BASE,
) {
    private val client = baseClient.newBuilder()
        // Keeps a socket alive through a long pause in speech; the server closes idle
        // sockets after a minute.
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private fun request(url: String) = Request.Builder()
        .url(url)
        .addHeader(StreamingProtocol.AUTH_HEADER, apiKey)
        .build()

    // ── Text to speech ───────────────────────────────────────────────────

    /**
     * Speaks [text] through [sink], returning once it has been heard in full.
     *
     * @throws StreamingException on any failure, with whether audio had already started.
     */
    suspend fun speak(
        text: String,
        languageCode: String,
        speaker: String,
        model: String,
        sink: PcmSink,
    ): Unit = suspendCancellableCoroutine { continuation ->
        val settled = AtomicBoolean(false)
        val audioStarted = AtomicBoolean(false)
        val carry = SampleAligner()

        fun succeed(socket: WebSocket) {
            if (!settled.compareAndSet(false, true)) return
            socket.close(NORMAL_CLOSURE, null)
            runCatching { sink.drainAndStop() }
            continuation.resume(Unit)
        }

        fun fail(message: String, cause: Throwable? = null) {
            if (!settled.compareAndSet(false, true)) return
            sink.stop()
            continuation.resumeWithException(StreamingException(message, audioStarted.get(), cause))
        }

        try {
            sink.start()
        } catch (e: Exception) {
            continuation.resumeWithException(StreamingException("Could not open audio output.", false, e))
            return@suspendCancellableCoroutine
        }

        val socket = client.newWebSocket(
            request(StreamingProtocol.ttsUrl(model, wsBase)),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(StreamingProtocol.ttsConfig(languageCode, speaker, model))
                    webSocket.send(StreamingProtocol.ttsText(text))
                    webSocket.send(StreamingProtocol.FLUSH)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val event = StreamingProtocol.parseTts(text)) {
                        is StreamingProtocol.TtsEvent.Audio -> {
                            if (settled.get()) return
                            val aligned = carry.align(event.pcm)
                            if (aligned.isNotEmpty()) {
                                audioStarted.set(true)
                                runCatching { sink.write(aligned) }
                            }
                        }
                        StreamingProtocol.TtsEvent.Final -> succeed(webSocket)
                        is StreamingProtocol.TtsEvent.Failure -> fail(event.message)
                        StreamingProtocol.TtsEvent.Ignored -> Unit
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    // Closed without a "final" event: fine if audio arrived, a failure if not.
                    if (audioStarted.get()) succeed(webSocket) else fail("Speech stream closed early ($code $reason).")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail(describe("Speech stream", t, response), t)
                }
            },
        )

        continuation.invokeOnCancellation {
            settled.set(true)
            socket.cancel()
            sink.stop()
        }
    }

    // ── Speech to text ───────────────────────────────────────────────────

    /**
     * A live transcription: feed it microphone audio while recording, then [finish].
     *
     * @param onSpeechEnded called when the server hears you stop talking, after it has
     *   heard you start — so background noise before you speak does not end the turn.
     */
    fun openTranscription(
        languageCode: String,
        model: String,
        mode: String,
        onSpeechEnded: () -> Unit,
    ): TranscriptionStream {
        val url = StreamingProtocol.sttUrl(languageCode, model, mode, wsBase)
        return TranscriptionStream(client, request(url), onSpeechEnded)
    }

    class TranscriptionStream internal constructor(
        client: OkHttpClient,
        request: Request,
        private val onSpeechEnded: () -> Unit,
    ) {
        private val segments = mutableListOf<String>()
        private var language: String? = null

        @Volatile private var failure: String? = null
        @Volatile private var flushed = false
        @Volatile private var heardSpeech = false

        /** Completes with the first transcript after flushing, or when the socket dies. */
        private val finalised = CompletableDeferred<Unit>()

        /** Microphone reads arrive every ~40 ms; batching to ~200 ms cuts per-message overhead. */
        private val pending = ByteArrayOutputStream()

        private val socket: WebSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val event = StreamingProtocol.parseStt(text)) {
                        is StreamingProtocol.SttEvent.Transcript -> {
                            synchronized(segments) {
                                addSegment(event.text)
                                event.languageCode?.let { language = it }
                            }
                            if (flushed) finalised.complete(Unit)
                        }
                        StreamingProtocol.SttEvent.SpeechStarted -> heardSpeech = true
                        StreamingProtocol.SttEvent.SpeechEnded -> if (heardSpeech && !flushed) onSpeechEnded()
                        is StreamingProtocol.SttEvent.Failure -> {
                            failure = event.message
                            finalised.complete(Unit)
                        }
                        StreamingProtocol.SttEvent.Ignored -> Unit
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finalised.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure = describe("Live transcription", t, response)
                    finalised.complete(Unit)
                }
            },
        )

        /**
         * Segments normally arrive one per utterance. If the server instead re-sends the
         * whole transcript so far, appending would repeat it; replacing handles both.
         */
        private fun addSegment(text: String) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            val soFar = segments.joinToString(" ")
            if (soFar.isNotEmpty() && clean.startsWith(soFar)) {
                segments.clear()
            }
            segments.add(clean)
        }

        /** Queues microphone samples; OkHttp buffers them until the socket is open. */
        fun send(pcm: ByteArray, length: Int) {
            if (failure != null) return
            synchronized(pending) {
                pending.write(pcm, 0, length)
                if (pending.size() >= CHUNK_BYTES) sendPending()
            }
        }

        private fun sendPending() {
            if (pending.size() == 0) return
            val bytes = pending.toByteArray()
            pending.reset()
            socket.send(StreamingProtocol.sttAudio(bytes))
        }

        /**
         * Sends the tail of the audio, asks the server to finalise, and waits briefly.
         *
         * @return the transcript, or null when streaming did not produce one — the caller then
         *   transcribes the recorded file over REST instead.
         */
        suspend fun finish(timeoutMillis: Long = FINISH_TIMEOUT_MS): Transcription? {
            synchronized(pending) { sendPending() }
            flushed = true
            socket.send(StreamingProtocol.FLUSH)

            withTimeoutOrNull(timeoutMillis) { finalised.await() }
            socket.close(NORMAL_CLOSURE, null)

            if (failure != null) return null
            val text = synchronized(segments) { segments.joinToString(" ").trim() }
            if (text.isEmpty()) return null
            return Transcription(text, Language.spokenOrDefault(language.orEmpty()))
        }

        /** The failure that made [finish] return null, for the log. */
        fun failureReason(): String? = failure

        fun cancel() {
            socket.cancel()
            finalised.complete(Unit)
        }

        private companion object {
            /** 200 ms of 16 kHz 16-bit mono. */
            const val CHUNK_BYTES = 6_400
            const val FINISH_TIMEOUT_MS = 6_000L
        }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000

        fun describe(what: String, t: Throwable, response: Response?): String = when (response?.code) {
            401, 403 -> "$what rejected the API key."
            null -> "$what failed: ${t.message ?: t::class.java.simpleName}"
            else -> "$what failed: HTTP ${response.code}"
        }
    }
}

/**
 * Keeps 16-bit samples whole across chunk boundaries. A chunk with an odd byte count would
 * otherwise shift every later sample by one byte, which plays as loud static.
 */
internal class SampleAligner {
    private var leftover: Byte? = null

    fun align(chunk: ByteArray): ByteArray {
        val joined = leftover?.let { byteArrayOf(it) + chunk } ?: chunk
        return if (joined.size % 2 == 0) {
            leftover = null
            joined
        } else {
            leftover = joined.last()
            joined.copyOf(joined.size - 1)
        }
    }
}
