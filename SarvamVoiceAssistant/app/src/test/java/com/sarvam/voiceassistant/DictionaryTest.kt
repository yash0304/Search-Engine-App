package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordFormsTest {

    @Test
    fun `strips punctuation and casing from speech`() {
        assertEquals("ephemeral", WordForms.normalise("  Ephemeral?  "))
        assertEquals("kismet", WordForms.normalise("\"kismet\""))
        assertEquals("run", WordForms.normalise("Run."))
    }

    @Test
    fun `the word itself is always tried first`() {
        assertEquals("running", WordForms.candidates("Running").first())
    }

    @Test
    fun `regular plurals reduce to the singular`() {
        assertTrue("boxes -> box", WordForms.candidates("boxes").contains("box"))
        assertTrue("cities -> city", WordForms.candidates("cities").contains("city"))
        assertTrue("dogs -> dog", WordForms.candidates("dogs").contains("dog"))
        assertTrue("churches -> church", WordForms.candidates("churches").contains("church"))
    }

    @Test
    fun `verb endings reduce to the stem`() {
        assertTrue("walked -> walk", WordForms.candidates("walked").contains("walk"))
        assertTrue("walking -> walk", WordForms.candidates("walking").contains("walk"))
        assertTrue("hoping -> hope", WordForms.candidates("hoping").contains("hope"))
    }

    @Test
    fun `doubled consonants are undone`() {
        // "running" must reach "run", not stop at "runn".
        assertTrue(WordForms.candidates("running").contains("run"))
        assertTrue(WordForms.candidates("stopped").contains("stop"))
    }

    @Test
    fun `comparatives reduce to the adjective`() {
        assertTrue(WordForms.candidates("happiest").contains("happy"))
        assertTrue(WordForms.candidates("larger").contains("large"))
    }

    @Test
    fun `candidates are unique and never blank`() {
        val candidates = WordForms.candidates("passes")
        assertEquals(candidates.size, candidates.toSet().size)
        assertTrue(candidates.none { it.isBlank() })
    }

    @Test
    fun `empty input yields nothing rather than crashing`() {
        assertTrue(WordForms.candidates("   ").isEmpty())
        assertTrue(WordForms.candidates("").isEmpty())
    }

    @Test
    fun `short words are not stripped into nonsense`() {
        // "as" must not become "a" via the -s rule when that loses the word entirely.
        assertTrue(WordForms.candidates("as").first() == "as")
    }
}

class DictionaryFormattingTest {

    private fun sense(pos: String, definition: String, synonyms: List<String> = emptyList()) =
        Sense(pos, definition, synonyms)

    // ── Gloss splitting ──────────────────────────────────────────────────

    @Test
    fun `separates the definition from quoted examples`() {
        val (definition, examples) =
            DictionaryFormatting.splitGloss("""lasting a very short time; "the ephemeral joys of childhood"""")
        assertEquals("lasting a very short time", definition)
        assertEquals(listOf("the ephemeral joys of childhood"), examples)
    }

    @Test
    fun `keeps a gloss that has no examples`() {
        val (definition, examples) =
            DictionaryFormatting.splitGloss("good luck in making unexpected and fortunate discoveries")
        assertEquals("good luck in making unexpected and fortunate discoveries", definition)
        assertTrue(examples.isEmpty())
    }

    @Test
    fun `collects several examples`() {
        val (_, examples) = DictionaryFormatting.splitGloss("""x; "one"; "two"; "three"""")
        assertEquals(3, examples.size)
    }

    @Test
    fun `a gloss of only an example still yields a definition`() {
        val (definition, _) = DictionaryFormatting.splitGloss(""""just an example"""")
        assertTrue(definition.isNotBlank())
    }

    // ── Formatting ───────────────────────────────────────────────────────

    @Test
    fun `an absent word is reported, never invented`() {
        val text = DictionaryFormatting.format("blorptastic", emptyList())
        assertTrue(text.contains("not in the offline dictionary"))
        assertTrue("must forbid invention", text.contains("do not invent"))
    }

    @Test
    fun `expands part of speech for speech`() {
        val text = DictionaryFormatting.format("ephemeral", listOf(sense("adj", "lasting a short time")))
        assertTrue(text.contains("adjective"))
        assertFalse("raw tags must not be spoken", text.contains("[adj]"))
    }

    @Test
    fun `includes definition and synonyms`() {
        val text = DictionaryFormatting.format(
            "ephemeral",
            listOf(sense("adj", "lasting a very short time", listOf("passing", "transient"))),
        )
        assertTrue(text.contains("lasting a very short time"))
        assertTrue(text.contains("passing"))
        assertTrue(text.contains("transient"))
    }

    @Test
    fun `instructs the model to read the definition as written`() {
        val text = DictionaryFormatting.format("word", listOf(sense("n", "a unit of language")))
        assertTrue(text.contains("Read the definition as written"))
    }

    @Test
    fun `caps senses so a spoken answer stays short`() {
        val many = (1..10).map { sense("n", "sense number $it") }
        val text = DictionaryFormatting.format("run", many)
        assertTrue(text.contains("sense number 3"))
        assertFalse(text.contains("sense number 4"))
        assertTrue("should say how many were omitted", text.contains("7 further senses"))
    }

    @Test
    fun `notes when a different form was matched`() {
        val text = DictionaryFormatting.format("ran", listOf(sense("v", "move fast")), matchedForm = "run")
        assertTrue(text.contains("found under \"run\""))
    }

    @Test
    fun `does not claim a different form when it matched exactly`() {
        val text = DictionaryFormatting.format("run", listOf(sense("v", "move fast")), matchedForm = "run")
        assertFalse(text.contains("found under"))
    }
}
