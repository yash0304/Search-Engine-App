package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechOptionsTest {

    @Test
    fun unknownSettingsFallBackToDefaults() {
        assertEquals("transcribe", SpeechOptions.validMode("shout"))
        assertEquals("translate", SpeechOptions.validMode("translate"))
        assertEquals("saaras:v3", SpeechOptions.validModel("saaras:v1"))
        assertEquals("saaras:v4", SpeechOptions.validModel("saaras:v4"))
    }

    @Test
    fun gujaratiReplyIsSpokenInGujaratiEvenWhenAskedInEnglish() {
        assertEquals("gu-IN", ReplyLanguage.detect("કેમ છો? હું મજામાં છું.", fallback = "en-IN"))
    }

    @Test
    fun hindiReplyToAnEnglishQuestion() {
        assertEquals("hi-IN", ReplyLanguage.detect("नमस्ते, आप कैसे हैं?", fallback = "en-IN"))
    }

    @Test
    fun devanagariStaysMarathiForAMarathiSpeaker() {
        assertEquals("mr-IN", ReplyLanguage.detect("तुम्ही कसे आहात?", fallback = "mr-IN"))
    }

    @Test
    fun englishReplyQuotingOneWordStaysEnglish() {
        assertEquals(
            "en-IN",
            ReplyLanguage.detect("The Gujarati word for water is પાણી, pronounced paani.", fallback = "en-IN"),
        )
    }

    @Test
    fun latinScriptKeepsTheQuestionsLanguage() {
        assertEquals("hi-IN", ReplyLanguage.detect("aap kaise hain", fallback = "hi-IN"))
    }

    @Test
    fun unspeakableFallbackBecomesEnglish() {
        assertEquals("en-IN", ReplyLanguage.detect("hello", fallback = "unknown"))
        assertEquals("en-IN", ReplyLanguage.detect("hello", fallback = "sa-IN"))
    }
}
