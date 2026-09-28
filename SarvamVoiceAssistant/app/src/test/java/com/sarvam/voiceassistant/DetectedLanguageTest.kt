package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectedLanguageTest {

    @Test
    fun mapsToTheAppsCodes() {
        assertEquals("gu-IN", DetectedLanguage.toAppCode("gu", 0.98f))
        assertEquals("hi-IN", DetectedLanguage.toAppCode("hi", 0.9f))
        assertEquals("en-IN", DetectedLanguage.toAppCode("en", 0.8f))
        assertEquals("od-IN", DetectedLanguage.toAppCode("or", 0.9f))
    }

    @Test
    fun romanisedHindiIsStillHindi() {
        assertEquals("hi-IN", DetectedLanguage.toAppCode("hi-Latn", 0.85f))
    }

    @Test
    fun aLowConfidenceGuessChangesNothing() {
        assertNull(DetectedLanguage.toAppCode("gu", 0.4f))
    }

    @Test
    fun languagesTheAppCannotSpeakAreIgnored() {
        assertNull(DetectedLanguage.toAppCode("fr", 0.99f))
        assertNull(DetectedLanguage.toAppCode("", 0.99f))
        assertNull(DetectedLanguage.toAppCode(null, 0.99f))
    }
}
