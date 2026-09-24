package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {

    @Test
    fun resolvesNamesAndCodesTheModelMightUse() {
        assertEquals("gu-IN", TextTools.languageCode("Gujarati"))
        assertEquals("gu-IN", TextTools.languageCode("gu"))
        assertEquals("gu-IN", TextTools.languageCode("GU-in"))
        assertEquals("od-IN", TextTools.languageCode("oriya"))
        assertEquals("kok-IN", TextTools.languageCode("Konkani"))
        assertNull(TextTools.languageCode("Klingon"))
        assertNull(TextTools.languageCode(""))
    }

    @Test
    fun commonLanguagesUseMayuraWithDetectionAndTone() {
        val body = request(TextTools.translation("How are you?", "Gujarati", tone = "modern-colloquial"))

        assertEquals("mayura:v1", body.getString("model"))
        assertEquals("auto", body.getString("source_language_code"))
        assertEquals("gu-IN", body.getString("target_language_code"))
        assertEquals("modern-colloquial", body.getString("mode"))
    }

    @Test
    fun unknownToneFallsBackToFormalRatherThanA400() {
        val body = request(TextTools.translation("hi", "Hindi", tone = "pirate"))
        assertEquals("formal", body.getString("mode"))
    }

    @Test
    fun otherScheduledLanguagesUseSarvamTranslateWithAnExplicitSource() {
        val body = request(TextTools.translation("Good morning", "Sanskrit", source = "English", tone = "code-mixed"))

        assertEquals("sarvam-translate:v1", body.getString("model"))
        assertEquals("en-IN", body.getString("source_language_code"))
        assertEquals("sa-IN", body.getString("target_language_code"))
        assertEquals("formal", body.getString("mode")) // Its only mode.
    }

    @Test
    fun sarvamTranslateCannotDetectSoItAsksInsteadOfFailing() {
        val plan = TextTools.translation("Good morning", "Sanskrit")
        assertTrue(plan is TextTools.Plan.Refused)
    }

    @Test
    fun inputIsCappedPerModel() {
        val long = "a".repeat(5_000)
        assertEquals(1000, request(TextTools.translation(long, "Hindi")).getString("input").length)
        assertEquals(2000, request(TextTools.translation(long, "Urdu", source = "en")).getString("input").length)
    }

    @Test
    fun refusesUnsupportedAndPointlessRequests() {
        assertTrue(TextTools.translation("x", "Klingon") is TextTools.Plan.Refused)
        assertTrue(TextTools.translation("x", "Hindi", source = "hi-IN") is TextTools.Plan.Refused)
    }

    @Test
    fun transliterationKeepsTheWordsAndChangesTheScript() {
        val body = request(TextTools.transliteration("namaste", "Hindi"))
        assertEquals("auto", body.getString("source_language_code"))
        assertEquals("hi-IN", body.getString("target_language_code"))
        assertEquals("namaste", body.getString("input"))
    }

    @Test
    fun transliterationOnlyCoversMayuraLanguages() {
        assertTrue(TextTools.transliteration("namaste", "Urdu") is TextTools.Plan.Refused)
    }

    private fun request(plan: TextTools.Plan) = (plan as TextTools.Plan.Request).body
}
