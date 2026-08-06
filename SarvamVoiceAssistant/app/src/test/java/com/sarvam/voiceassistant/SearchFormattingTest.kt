package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFormattingTest {

    private fun result(title: String, snippet: String, source: String = "Wikipedia") =
        SearchResult(title, snippet, source)

    // ── Truncation ───────────────────────────────────────────────────────

    @Test
    fun `short text is returned unchanged`() {
        assertEquals("Hello there.", SearchFormatting.truncate("Hello there.", 100))
    }

    @Test
    fun `collapses runs of whitespace`() {
        assertEquals("a b c", SearchFormatting.truncate("a   b \n\n c ", 100))
    }

    @Test
    fun `prefers cutting at a sentence boundary`() {
        val text = "First sentence here. Second sentence runs on and on and on and on."
        val cut = SearchFormatting.truncate(text, 40)
        assertTrue(cut.endsWith("."))
        assertFalse(cut.contains("Second sentence runs on and on and on"))
    }

    @Test
    fun `falls back to an ellipsis when there is no sentence break`() {
        val text = "a".repeat(200)
        val cut = SearchFormatting.truncate(text, 50)
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.length <= 51)
    }

    @Test
    fun `recognises the devanagari full stop`() {
        val text = "यह पहला वाक्य है। और यह दूसरा वाक्य है जो बहुत लंबा चलता रहता है।"
        val cut = SearchFormatting.truncate(text, 30)
        assertTrue(cut.endsWith("।"))
    }

    // ── Context assembly ─────────────────────────────────────────────────

    @Test
    fun `formats a result with its source`() {
        val context = SearchFormatting.toContext(listOf(result("Gujarat", "A state in India.")))
        assertTrue(context.contains("Gujarat"))
        assertTrue(context.contains("A state in India."))
        assertTrue(context.contains("Wikipedia"))
    }

    @Test
    fun `reports plainly when there is nothing to say`() {
        assertEquals("No results found.", SearchFormatting.toContext(emptyList()))
    }

    @Test
    fun `drops results with no snippet`() {
        val context = SearchFormatting.toContext(listOf(result("Empty", "   ")))
        assertEquals("No results found.", context)
    }

    @Test
    fun `respects the context budget`() {
        val many = (1..20).map { result("Title $it", "Body text number $it. ".repeat(20)) }
        val context = SearchFormatting.toContext(many, budget = 500)
        assertTrue("was ${context.length}", context.length <= 500)
        assertTrue(context.isNotEmpty())
    }

    @Test
    fun `a single oversized result still yields context`() {
        // Otherwise one very long article would produce an empty block and the model would
        // answer as though the search had returned nothing.
        val huge = result("Long", "word ".repeat(2000))
        val context = SearchFormatting.toContext(listOf(huge), budget = 200)
        assertTrue(context.isNotEmpty())
        assertFalse(context == "No results found.")
    }

    @Test
    fun `blank titles do not produce a leading colon`() {
        val context = SearchFormatting.toContext(listOf(result("", "Some fact.", "")))
        assertFalse(context.startsWith(":"))
        assertTrue(context.contains("Some fact."))
    }
}
