package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefinitionRequestTest {

    // ── The failure that prompted this ───────────────────────────────────

    @Test
    fun `the word weather is a dictionary question, not a forecast`() {
        // "weather" collides with the live-weather tool, so the model reached for the
        // forecast instead of the dictionary. Detection must not be left to its judgement.
        assertEquals("weather", DefinitionRequest.detect("What does weather mean?"))
        assertEquals("weather", DefinitionRequest.detect("meaning of weather"))
        assertEquals("weather", DefinitionRequest.detect("weather ka matlab"))
    }

    @Test
    fun `an actual forecast question is not treated as a lookup`() {
        assertNull(DefinitionRequest.detect("what is the weather in Surat"))
        assertNull(DefinitionRequest.detect("is it raining here"))
        assertNull(DefinitionRequest.detect("will the weather be good tomorrow"))
    }

    // ── English phrasings ────────────────────────────────────────────────

    @Test
    fun `recognises the common english phrasings`() {
        assertEquals("ephemeral", DefinitionRequest.detect("What does ephemeral mean?"))
        assertEquals("ephemeral", DefinitionRequest.detect("what does the word ephemeral mean"))
        assertEquals("kismet", DefinitionRequest.detect("What is the meaning of kismet?"))
        assertEquals("kismet", DefinitionRequest.detect("meaning of kismet"))
        assertEquals("serendipity", DefinitionRequest.detect("define serendipity"))
        assertEquals("monsoon", DefinitionRequest.detect("what is the definition of monsoon"))
        assertEquals("happy", DefinitionRequest.detect("synonyms for happy"))
        assertEquals("happy", DefinitionRequest.detect("synonym of happy"))
    }

    @Test
    fun `handles quotes and punctuation from speech`() {
        assertEquals("kismet", DefinitionRequest.detect("""what does "kismet" mean?"""))
        assertEquals("ephemeral", DefinitionRequest.detect("Meaning of 'ephemeral'."))
    }

    // ── Indian language phrasings ────────────────────────────────────────

    @Test
    fun `recognises romanised hindi and gujarati`() {
        assertEquals("ephemeral", DefinitionRequest.detect("ephemeral ka matlab kya hai"))
        assertEquals("ephemeral", DefinitionRequest.detect("ephemeral no matlab shu che"))
        assertEquals("kismet", DefinitionRequest.detect("kismet nu meaning"))
    }

    @Test
    fun `recognises devanagari and gujarati script`() {
        assertEquals("ephemeral", DefinitionRequest.detect("ephemeral का मतलब क्या है"))
        assertEquals("serendipity", DefinitionRequest.detect("serendipity નો અર્થ શું છે"))
    }

    // ── Things that are not definition questions ─────────────────────────

    @Test
    fun `ordinary conversation is left alone`() {
        assertNull(DefinitionRequest.detect("how are you"))
        assertNull(DefinitionRequest.detect("what time is it"))
        assertNull(DefinitionRequest.detect("tell me a joke"))
        assertNull(DefinitionRequest.detect("who is the chief minister of Gujarat"))
    }

    @Test
    fun `grammar words are not looked up`() {
        // "what does it mean" has no word to define.
        assertNull(DefinitionRequest.detect("what does it mean"))
        assertNull(DefinitionRequest.detect("what does that mean"))
        assertNull(DefinitionRequest.detect("what does this error mean"))
    }

    @Test
    fun `empty and nonsense input is safe`() {
        assertNull(DefinitionRequest.detect(""))
        assertNull(DefinitionRequest.detect("     "))
        assertNull(DefinitionRequest.detect("?!,.;"))
    }

    @Test
    fun `an absent word is still detected so the app can say it is absent`() {
        // Detection is about intent; whether the word exists is the dictionary's business.
        assertEquals("blorptastic", DefinitionRequest.detect("what does blorptastic mean"))
    }
}
