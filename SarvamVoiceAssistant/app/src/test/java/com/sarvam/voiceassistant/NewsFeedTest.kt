package com.sarvam.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsFeedTest {

    /** The shape Google News RSS search returns, trimmed to what matters. */
    private val feed = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
        <title>"HDFC ATM charges" - Google News</title>
        <item>
          <title>HDFC Bank revises ATM withdrawal charges from May 1 - The Economic Times</title>
          <link>https://news.google.com/rss/articles/abc</link>
          <pubDate>Thu, 24 Sep 2026 07:00:00 GMT</pubDate>
          <description>&lt;a href="https://example"&gt;HDFC Bank revises…&lt;/a&gt;</description>
          <source url="https://economictimes.indiatimes.com">The Economic Times</source>
        </item>
        <item>
          <title><![CDATA[RBI allows banks to charge ₹23 per ATM transaction beyond free limit - Mint]]></title>
          <pubDate>Mon, 22 Sep 2026 10:30:00 GMT</pubDate>
          <source url="https://www.livemint.com">Mint</source>
        </item>
        <item>
          <title>SBI &amp; HDFC: what &quot;free transactions&quot; really means - Moneycontrol</title>
          <pubDate>not a date</pubDate>
          <source url="https://www.moneycontrol.com">Moneycontrol</source>
        </item>
        <item><title>Fourth - A</title><source>A</source></item>
        <item><title>Fifth - B</title><source>B</source></item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun parsesHeadlinesWithOutletAndDate() {
        val first = NewsFeed.parse(feed).first()
        assertEquals("HDFC Bank revises ATM withdrawal charges from May 1", first.title)
        assertEquals("The Economic Times", first.source)
        assertEquals("24 Sep 2026", first.published)
    }

    @Test
    fun handlesCdataRupeeSignAndEntities() {
        val headlines = NewsFeed.parse(feed)
        assertEquals("RBI allows banks to charge ₹23 per ATM transaction beyond free limit", headlines[1].title)
        assertEquals("SBI & HDFC: what \"free transactions\" really means", headlines[2].title)
    }

    @Test
    fun aWrongWeekdayDoesNotCostTheDate() {
        // 22 Sep 2026 is a Tuesday; the strict RFC parser would reject the whole date.
        assertEquals("22 Sep 2026", NewsFeed.parse(feed)[1].published)
    }

    @Test
    fun anUnreadableDateIsDroppedNotFatal() {
        assertNull(NewsFeed.parse(feed)[2].published)
    }

    @Test
    fun theChannelTitleIsNotMistakenForAHeadline() {
        assertTrue(NewsFeed.parse(feed).none { it.title.contains("Google News") })
    }

    @Test
    fun keepsOnlyAFewSoTheSpokenAnswerStaysShort() {
        assertEquals(NewsFeed.MAX_HEADLINES, NewsFeed.parse(feed).size)
    }

    @Test
    fun anEmptyOrBrokenFeedIsNoHeadlines() {
        assertTrue(NewsFeed.parse("").isEmpty())
        assertTrue(NewsFeed.parse("<html>blocked</html>").isEmpty())
    }

    @Test
    fun resultsTellTheModelWhenAndWhere() {
        val result = NewsFeed.toResults(NewsFeed.parse(feed)).first()
        assertEquals("News, 24 Sep 2026", result.title)
        assertEquals("The Economic Times", result.source)
        val context = SearchFormatting.toContext(NewsFeed.toResults(NewsFeed.parse(feed)))
        assertTrue(context.contains("HDFC Bank revises ATM withdrawal charges from May 1"))
    }

    @Test
    fun searchesTheIndianEdition() {
        val url = NewsFeed.url("HDFC ATM charges")
        assertTrue(url.startsWith("https://news.google.com/rss/search?q=HDFC+ATM+charges"))
        assertTrue(url.contains("gl=IN"))
    }
}
