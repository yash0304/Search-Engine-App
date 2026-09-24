package com.sarvam.voiceassistant

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the real OkHttp WebSocket code against a local server playing Sarvam's side, as the
 * official SDK describes it. What this cannot prove is that Sarvam's live service behaves
 * exactly as its SDK says; that is why the app falls back to REST on any streaming failure.
 */
class StreamingSpeechTest {

    private lateinit var server: MockWebServer
    private val received = CopyOnWriteArrayList<String>()
    private val requestHeaders = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun speech() = StreamingSpeech(
        apiKey = "test-key",
        baseClient = OkHttpClient(),
        wsBase = "ws://${server.hostName}:${server.port}",
    )

    /** Records what the client sends and answers each message with [reply]. */
    private fun serve(reply: (WebSocket, String) -> Unit) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        requestHeaders.add(response.request.header(StreamingProtocol.AUTH_HEADER).orEmpty())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received.add(text)
                        reply(webSocket, text)
                    }

                    // Answer the client's close frame, as a real server does; otherwise
                    // MockWebServer waits on the half-closed socket at shutdown.
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                },
            ),
        )
    }

    private fun type(message: String) = runCatching { JSONObject(message).optString("type") }.getOrDefault("")

    // ── Text to speech ───────────────────────────────────────────────────

    @Test
    fun speakSendsConfigTextFlushAndPlaysEveryChunk() = runBlocking {
        val first = byteArrayOf(1, 0, 2, 0)
        val second = byteArrayOf(3, 0, 4, 0)
        serve { socket, message ->
            if (type(message) == "flush") {
                socket.send(audio(first))
                socket.send(audio(second))
                socket.send("""{"type":"event","data":{"event_type":"final"}}""")
            }
        }
        val sink = RecordingSink()

        speech().speak("Hello there", "en-IN", "priya", "bulbul:v3", sink)

        assertEquals(listOf("config", "text", "flush"), received.map(::type))
        assertEquals("Hello there", JSONObject(received[1]).getJSONObject("data").getString("text"))
        assertEquals("test-key", requestHeaders.single())
        assertArrayEquals(first + second, sink.played())
        assertTrue("audio must be drained before speak() returns", sink.drained)
        assertTrue(server.takeRequest().path!!.startsWith("/text-to-speech/ws?model=bulbul%3Av3"))
    }

    @Test
    fun serverErrorBeforeAnyAudioAllowsFallback() = runBlocking {
        serve { socket, message ->
            if (type(message) == "config") socket.send("""{"type":"error","data":{"message":"Invalid speaker"}}""")
        }
        val sink = RecordingSink()

        try {
            speech().speak("Hi", "en-IN", "nobody", "bulbul:v3", sink)
            fail("expected StreamingException")
        } catch (e: StreamingException) {
            assertEquals("Invalid speaker", e.message)
            assertFalse("nothing was heard, so REST fallback is safe", e.audioStarted)
        }
        assertTrue(sink.stopped)
    }

    @Test
    fun failureMidReplyIsReportedAsAlreadyStarted() = runBlocking {
        serve { socket, message ->
            if (type(message) == "flush") {
                socket.send(audio(byteArrayOf(1, 0)))
                socket.send("""{"type":"error","data":{"message":"stream broke"}}""")
            }
        }

        try {
            speech().speak("Hi", "en-IN", "priya", "bulbul:v3", RecordingSink())
            fail("expected StreamingException")
        } catch (e: StreamingException) {
            assertTrue("half a reply was heard; replaying it from the start would be worse", e.audioStarted)
        }
    }

    @Test
    fun rejectedKeyIsReportedClearly() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))

        try {
            speech().speak("Hi", "en-IN", "priya", "bulbul:v3", RecordingSink())
            fail("expected StreamingException")
        } catch (e: StreamingException) {
            assertEquals("Speech stream rejected the API key.", e.message)
            assertFalse(e.audioStarted)
        }
    }

    // ── Speech to text ───────────────────────────────────────────────────

    @Test
    fun transcriptionStreamsAudioAndReturnsTheTranscriptAfterFlush() = runBlocking {
        serve { socket, message ->
            if (type(message) == "flush") {
                socket.send("""{"type":"data","data":{"transcript":"what is the weather","language_code":"en-IN"}}""")
            }
        }
        val stream = speech().openTranscription("unknown", "saaras:v3", "transcribe") {}

        // 16,000 bytes in 400-byte reads: batched, and nothing is lost.
        val chunk = ByteArray(400) { (it % 100).toByte() }
        repeat(40) { stream.send(chunk, chunk.size) }
        val result = stream.finish()

        assertEquals("what is the weather", result?.transcript)
        assertEquals("en-IN", result?.languageCode)

        val sentAudio = ByteArrayOutputStream()
        received.filter { it.contains("\"audio\"") }.forEach {
            sentAudio.write(Base64.getDecoder().decode(JSONObject(it).getJSONObject("audio").getString("data")))
        }
        assertEquals(16_000, sentAudio.size())
        assertTrue("audio should be batched, not sent per read", received.count { it.contains("\"audio\"") } < 40)
        assertEquals("flush", type(received.last()))

        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/speech-to-text/ws?language_code=unknown"))
    }

    @Test
    fun multipleUtterancesAreJoinedInOrder() = runBlocking {
        val segmentsSent = CountDownLatch(1)
        serve { socket, message ->
            if (type(message) != "flush" && received.size == 1) {
                socket.send("""{"type":"data","data":{"transcript":"is it raining"}}""")
                segmentsSent.countDown()
            }
            if (type(message) == "flush") {
                socket.send("""{"type":"data","data":{"transcript":"on the way to Surat"}}""")
            }
        }
        val stream = speech().openTranscription("unknown", "saaras:v3", "transcribe") {}
        stream.send(ByteArray(7_000), 7_000)
        segmentsSent.await(5, TimeUnit.SECONDS)
        Thread.sleep(200) // Let the first segment reach the client before flushing.

        assertEquals("is it raining on the way to Surat", stream.finish()?.transcript)
    }

    @Test
    fun cumulativeTranscriptsAreNotRepeated() = runBlocking {
        serve { socket, message ->
            if (type(message) == "flush") {
                socket.send("""{"type":"data","data":{"transcript":"is it"}}""")
                socket.send("""{"type":"data","data":{"transcript":"is it raining"}}""")
            }
        }
        val stream = speech().openTranscription("unknown", "saaras:v3", "transcribe") {}
        stream.send(ByteArray(100), 100)
        val result = stream.finish()

        // Either server behaviour must give one clean sentence, never "is it is it raining".
        assertTrue(result?.transcript == "is it raining" || result?.transcript == "is it")
    }

    @Test
    fun endOfSpeechStopsTheTurnOnlyAfterSpeechStarted() = runBlocking {
        val ended = CountDownLatch(1)
        var endCalls = 0
        serve { socket, message ->
            if (received.size == 1) {
                // Noise before speaking must not end the turn...
                socket.send("""{"type":"events","data":{"signal_type":"END_SPEECH"}}""")
                // ...but a real utterance ending should.
                socket.send("""{"type":"events","data":{"signal_type":"START_SPEECH"}}""")
                socket.send("""{"type":"events","data":{"signal_type":"END_SPEECH"}}""")
            }
            if (type(message) == "flush") socket.send("""{"type":"data","data":{"transcript":"hello"}}""")
        }
        val stream = speech().openTranscription("unknown", "saaras:v3", "transcribe") {
            endCalls++
            ended.countDown()
        }
        stream.send(ByteArray(6_400), 6_400)

        assertTrue(ended.await(5, TimeUnit.SECONDS))
        stream.finish()
        assertEquals(1, endCalls)
    }

    @Test
    fun serverErrorMeansFallBackToRest() = runBlocking {
        serve { socket, message ->
            if (type(message) == "flush") socket.send("""{"type":"error","data":{"error":"bad audio","code":"400"}}""")
        }
        val stream = speech().openTranscription("unknown", "saaras:v3", "transcribe") {}
        stream.send(ByteArray(100), 100)

        assertNull(stream.finish())
        assertEquals("bad audio", stream.failureReason())
    }

    @Test
    fun noServerAtAllMeansFallBackRatherThanHang() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val stream = speech().openTranscription("unknown", "saaras:v3", "transcribe") {}
        stream.send(ByteArray(100), 100)

        val started = System.currentTimeMillis()
        assertNull(stream.finish(timeoutMillis = 3_000))
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun audio(pcm: ByteArray) =
        """{"type":"audio","data":{"content_type":"audio/wav","audio":"${Base64.getEncoder().encodeToString(pcm)}"}}"""

    private class RecordingSink : PcmSink {
        private val bytes = ByteArrayOutputStream()
        @Volatile var drained = false
        @Volatile var stopped = false

        override fun start() = Unit
        override fun write(pcm: ByteArray) = synchronized(bytes) { bytes.write(pcm) }
        override fun drainAndStop() { drained = true }
        override fun stop() { stopped = true }
        fun played(): ByteArray = synchronized(bytes) { bytes.toByteArray() }
    }
}
