package com.sarvam.voiceassistant

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class StreamingProtocolTest {

    // ── Text to speech ───────────────────────────────────────────────────

    @Test
    fun ttsUrlSelectsModelAndAsksForCompletionEvent() {
        assertEquals(
            "wss://api.sarvam.ai/text-to-speech/ws?model=bulbul%3Av3&send_completion_event=true",
            StreamingProtocol.ttsUrl("bulbul:v3"),
        )
    }

    @Test
    fun ttsConfigMatchesTheSdkShape() {
        val json = JSONObject(StreamingProtocol.ttsConfig("gu-IN", "ritu", "bulbul:v3"))
        assertEquals("config", json.getString("type"))

        val data = json.getJSONObject("data")
        assertEquals("gu-IN", data.getString("language_code"))
        assertEquals("ritu", data.getString("speaker"))
        assertEquals("bulbul:v3", data.getString("model"))
        // Raw PCM is what AudioTrack can play chunk by chunk.
        assertEquals("linear16", data.getString("output_audio_codec"))
        assertEquals(24_000, data.getInt("speech_sample_rate"))
    }

    @Test
    fun ttsTextAndFlushMessages() {
        val text = JSONObject(StreamingProtocol.ttsText("नमस्ते"))
        assertEquals("text", text.getString("type"))
        assertEquals("नमस्ते", text.getJSONObject("data").getString("text"))
        assertEquals("flush", JSONObject(StreamingProtocol.FLUSH).getString("type"))
    }

    @Test
    fun parsesAudioChunk() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val message = """{"type":"audio","data":{"content_type":"audio/wav","audio":"${b64(pcm)}"}}"""

        val event = StreamingProtocol.parseTts(message)
        assertTrue(event is StreamingProtocol.TtsEvent.Audio)
        assertArrayEquals(pcm, (event as StreamingProtocol.TtsEvent.Audio).pcm)
    }

    @Test
    fun parsesFinalEventAndError() {
        assertEquals(
            StreamingProtocol.TtsEvent.Final,
            StreamingProtocol.parseTts("""{"type":"event","data":{"event_type":"final"}}"""),
        )
        assertEquals(
            StreamingProtocol.TtsEvent.Failure("Invalid speaker"),
            StreamingProtocol.parseTts("""{"type":"error","data":{"message":"Invalid speaker","code":400}}"""),
        )
    }

    @Test
    fun garbageIsAFailureNotACrash() {
        assertTrue(StreamingProtocol.parseTts("not json") is StreamingProtocol.TtsEvent.Failure)
        assertEquals(StreamingProtocol.TtsEvent.Ignored, StreamingProtocol.parseTts("""{"type":"pong"}"""))
    }

    // ── Speech to text ───────────────────────────────────────────────────

    @Test
    fun sttUrlCarriesEverythingTheServerNeeds() {
        val url = StreamingProtocol.sttUrl("unknown", "saaras:v3", "translate")
        assertTrue(url.startsWith("wss://api.sarvam.ai/speech-to-text/ws?"))
        listOf(
            "language_code=unknown",
            "model=saaras%3Av3",
            "mode=translate",
            "sample_rate=16000",
            "input_audio_codec=pcm_s16le",
            "vad_signals=true",
            "flush_signal=true",
        ).forEach { assertTrue("missing $it in $url", url.contains(it)) }
    }

    @Test
    fun sttAudioSendsOnlyTheBytesRead() {
        val buffer = byteArrayOf(10, 20, 30, 40, 99, 99)
        val audio = JSONObject(StreamingProtocol.sttAudio(buffer, length = 4)).getJSONObject("audio")

        assertArrayEquals(byteArrayOf(10, 20, 30, 40), Base64.getDecoder().decode(audio.getString("data")))
        assertEquals(16_000, audio.getInt("sample_rate"))
        assertEquals("audio/wav", audio.getString("encoding"))
    }

    @Test
    fun parsesTranscriptWithDetectedLanguage() {
        val event = StreamingProtocol.parseStt(
            """{"type":"data","data":{"request_id":"r","transcript":"કેમ છો","language_code":"gu-IN","metrics":{}}}""",
        )
        assertEquals(StreamingProtocol.SttEvent.Transcript("કેમ છો", "gu-IN"), event)
    }

    @Test
    fun parsesVoiceActivitySignals() {
        assertEquals(
            StreamingProtocol.SttEvent.SpeechStarted,
            StreamingProtocol.parseStt("""{"type":"events","data":{"signal_type":"START_SPEECH"}}"""),
        )
        assertEquals(
            StreamingProtocol.SttEvent.SpeechEnded,
            StreamingProtocol.parseStt("""{"type":"events","data":{"signal_type":"END_SPEECH"}}"""),
        )
    }

    @Test
    fun parsesSttErrorUsingTheErrorField() {
        assertEquals(
            StreamingProtocol.SttEvent.Failure("bad audio"),
            StreamingProtocol.parseStt("""{"type":"error","data":{"error":"bad audio","code":"400"}}"""),
        )
    }

    @Test
    fun jsonNullTranscriptIsEmptyNotTheWordNull() {
        val event = StreamingProtocol.parseStt("""{"type":"data","data":{"transcript":null}}""")
        assertEquals(StreamingProtocol.SttEvent.Transcript("", null), event)
    }

    // ── PCM ──────────────────────────────────────────────────────────────

    @Test
    fun bareSamplesPassThrough() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(pcm, Pcm.withoutWavHeader(pcm))
    }

    @Test
    fun wavHeaderIsStripped() {
        val samples = byteArrayOf(5, 6, 7, 8)
        val wav = WavHeader.build(samples.size, 24_000, 1, 16) + samples
        assertArrayEquals(samples, Pcm.withoutWavHeader(wav))
    }

    @Test
    fun streamingWavWithUnknownLengthStillYieldsItsSamples() {
        // Streaming encoders often write 0 as the data size because it is not known yet.
        val samples = byteArrayOf(9, 9, 9, 9)
        val header = WavHeader.build(0, 24_000, 1, 16)
        assertArrayEquals(samples, Pcm.withoutWavHeader(header + samples))
    }

    @Test
    fun oddChunkBoundariesKeepSamplesWhole() {
        val aligner = SampleAligner()
        assertArrayEquals(byteArrayOf(1, 2), aligner.align(byteArrayOf(1, 2, 3)))
        // The held-back byte leads the next chunk, so no sample is split or shifted.
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), aligner.align(byteArrayOf(4, 5, 6)))
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
