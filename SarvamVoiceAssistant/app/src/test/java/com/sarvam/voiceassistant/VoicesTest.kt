package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicesTest {

    @Test
    fun `rejects the invalid speaker that broke gujarati`() {
        // "dhawal" was shipped in the prototype and is not a bulbul:v3 voice, so every
        // Gujarati request failed with HTTP 400.
        assertFalse(Voices.isValid("dhawal"))
    }

    @Test
    fun `accepts documented speakers`() {
        assertTrue(Voices.isValid("shubh"))
        assertTrue(Voices.isValid("ritu"))
        assertTrue(Voices.isValid("priya"))
    }

    @Test
    fun `rejects voices that are not bulbul v3 speakers`() {
        // Both were listed here once, and "amelia" was the English default. This test used
        // to assert amelia WAS valid, which locked the mistake in.
        assertFalse(Voices.isValid("amelia"))
        assertFalse(Voices.isValid("sophia"))
    }

    @Test
    fun `list matches the official SDK exactly`() {
        // bulbul:v3 speakers from sarvamai 0.1.34, ConfigureConnectionDataSpeaker.
        val sdk = setOf(
            "shubh", "aditya", "ritu", "priya", "neha", "rahul", "pooja", "rohan", "simran",
            "kavya", "amit", "dev", "ishita", "shreya", "ratan", "varun", "manan", "sumit",
            "roopa", "kabir", "aayan", "ashutosh", "advait", "anand", "tanya", "tarun", "sunny",
            "mani", "gokul", "vijay", "shruti", "suhani", "mohit", "kavitha", "rehan", "soham",
            "rupali",
        )
        assertEquals(sdk, Voices.ALL.toSet())
    }

    @Test
    fun `speaker names are lowercase and unique`() {
        // The API is case-sensitive and rejects anything not lowercase.
        Voices.ALL.forEach { assertEquals(it.lowercase(), it) }
        assertEquals(Voices.ALL.size, Voices.ALL.toSet().size)
    }

    @Test
    fun `every per-language default is itself valid`() {
        listOf("gu-IN", "hi-IN", "en-IN", "unknown").forEach { code ->
            assertTrue(
                "default for $code must be a real voice",
                Voices.isValid(Voices.defaultSpeakerFor(code)),
            )
        }
    }

    @Test
    fun `the documented default is valid`() {
        assertTrue(Voices.isValid(Voices.DEFAULT))
    }

    @Test
    fun `unknown language falls back to a real voice`() {
        assertEquals(Voices.DEFAULT, Voices.defaultSpeakerFor("xx-XX"))
    }
}

class LanguageTest {

    @Test
    fun `unknown maps to a concrete language for speech`() {
        // Neither TTS nor the voice map can act on "unknown".
        assertEquals("en-IN", Language.spokenOrDefault("unknown"))
        assertEquals("en-IN", Language.spokenOrDefault(""))
    }

    @Test
    fun `real language codes pass through untouched`() {
        assertEquals("gu-IN", Language.spokenOrDefault("gu-IN"))
        assertEquals("hi-IN", Language.spokenOrDefault("hi-IN"))
    }

    @Test
    fun `auto is the only entry without a concrete code`() {
        assertEquals("unknown", Language.AUTO.code)
        Language.entries.filter { it != Language.AUTO }.forEach {
            assertTrue("${it.code} should be a BCP-47 code", it.code.contains("-"))
        }
    }
}
