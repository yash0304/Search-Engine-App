package com.sarvam.voiceassistant

import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Recent headlines from Google News's public RSS search — keyless and free, and the only
 * source here that knows what happened this week.
 *
 * Wikipedia and DuckDuckGo's instant answers cover settled facts but carry no news, so a
 * question like "latest HDFC ATM charges" found nothing at all and the assistant could only
 * say "check their website". Headlines with their outlet and date give it something current
 * to answer from.
 *
 * Parsed with plain string matching rather than an XML library so it runs, and is tested,
 * on the JVM; Google News RSS is simple and regular.
 */
object NewsFeed {

    data class Headline(val title: String, val source: String, val published: String?)

    const val MAX_HEADLINES = 4

    /** Indian edition in English, which is where Indian banking and civic news is densest. */
    fun url(query: String): String =
        "https://news.google.com/rss/search?q=${URLEncoder.encode(query, "UTF-8")}&hl=en-IN&gl=IN&ceid=IN:en"

    fun parse(xml: String, max: Int = MAX_HEADLINES): List<Headline> =
        ITEM.findAll(xml)
            .mapNotNull { match ->
                val item = match.groupValues[1]
                val source = field(item, SOURCE).orEmpty()
                val title = field(item, TITLE)?.let { stripSourceSuffix(it, source) } ?: return@mapNotNull null
                Headline(title, source, field(item, PUB_DATE)?.let(::shortDate))
            }
            .take(max)
            .toList()

    fun toResults(headlines: List<Headline>): List<SearchResult> = headlines.map { headline ->
        SearchResult(
            title = "News" + (headline.published?.let { ", $it" } ?: ""),
            snippet = headline.title,
            source = headline.source.ifBlank { "Google News" },
        )
    }

    private val ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
    private val PUB_DATE = Regex("<pubDate>(.*?)</pubDate>", RegexOption.DOT_MATCHES_ALL)
    private val SOURCE = Regex("<source[^>]*>(.*?)</source>", RegexOption.DOT_MATCHES_ALL)

    private fun field(item: String, pattern: Regex): String? =
        pattern.find(item)?.groupValues?.get(1)?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() }

    /** Google appends " - Outlet" to every title; the outlet is reported separately. */
    private fun stripSourceSuffix(title: String, source: String): String =
        if (source.isNotBlank() && title.endsWith(" - $source")) title.dropLast(source.length + 3).trim() else title

    /**
     * "Thu, 24 Sep 2026 07:00:00 GMT" → "24 Sep 2026"; unparseable dates are dropped.
     *
     * The weekday is ignored: the strict RFC 1123 parser rejects a whole date over a
     * mismatched weekday, and the day name adds nothing the date does not already say.
     */
    private fun shortDate(rfc822: String): String? = runCatching {
        val withoutWeekday = rfc822.trim().replace(Regex("^[A-Za-z]{3},\\s*"), "")
        ZonedDateTime.parse(withoutWeekday, DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH))
            .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    }.getOrNull()

    /** CDATA and the XML/HTML entities that appear in headlines. */
    private fun decode(raw: String): String {
        val unwrapped = raw.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
        return unwrapped
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }
}
