package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatTitlesTest {

    @Test
    fun aCleanTitlePassesThrough() {
        assertEquals("Vermicelli pasta basics", ChatTitles.clean("Vermicelli pasta basics"))
    }

    @Test
    fun theUsualModelDecorationIsRemoved() {
        assertEquals("Vermicelli pasta basics", ChatTitles.clean("\"Vermicelli pasta basics.\""))
        assertEquals("Vermicelli pasta basics", ChatTitles.clean("Title: Vermicelli pasta basics"))
        assertEquals("Vermicelli pasta basics", ChatTitles.clean("**Vermicelli pasta basics**"))
        assertEquals("Electricity bill, August 2026", ChatTitles.clean("“Electricity bill, August 2026”"))
    }

    @Test
    fun indianScriptsAndTheirFullStopAreHandled() {
        assertEquals("વડોદરા રસ્તે વરસાદ", ChatTitles.clean("વડોદરા રસ્તે વરસાદ"))
        assertEquals("बारिश का हाल", ChatTitles.clean("बारिश का हाल।"))
    }

    @Test
    fun onlyTheFirstLineCountsAndThinkingIsIgnored() {
        assertEquals("Rain near Anand", ChatTitles.clean("<think>the user asked…</think>\n\nRain near Anand\nThis title fits because…"))
    }

    @Test
    fun aRamblingSentenceIsCutToATitlesLength() {
        val title = ChatTitles.clean("This conversation is about how vermicelli pasta is made and cooked at home")
        // Eight words, then the length cap: never a paragraph in the chat list.
        assertEquals("This conversation is about how vermicelli pasta…", title)
    }

    @Test
    fun overlongTitlesAreShortened() {
        val title = ChatTitles.clean("Supercalifragilisticexpialidocious and antidisestablishmentarianism")!!
        assertEquals(48, title.length)
        assertEquals('…', title.last())
    }

    @Test
    fun nothingUsableMeansKeepTheExistingName() {
        assertNull(ChatTitles.clean(null))
        assertNull(ChatTitles.clean("   "))
        assertNull(ChatTitles.clean("\"\""))
        assertNull(ChatTitles.clean("<think>…</think>"))
    }

    @Test
    fun materialIsBounded() {
        val long = "x".repeat(5_000)
        assert(ChatTitles.forExchange(long, long).length < 1_300)
        assert(ChatTitles.forDocument("bill.jpg", long).length < 1_300)
    }
}
