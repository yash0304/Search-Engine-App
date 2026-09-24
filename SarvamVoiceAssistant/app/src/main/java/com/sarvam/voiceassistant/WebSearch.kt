package com.sarvam.voiceassistant

import android.util.Log
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Looks facts up on the open web so the assistant can answer about things that happened
 * after the model was trained.
 *
 * Uses two keyless sources so nothing extra has to be configured:
 *  - DuckDuckGo's Instant Answer API, good for definitions and direct answers
 *  - Wikipedia search plus article summary, good for people, places and events
 *
 * Neither is a breaking-news feed. They are strong on established facts and reasonably
 * current on notable events, but they will not have this morning's headlines.
 */
class WebSearch {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "WebSearch"

        // Wikipedia's API policy asks for an identifying User-Agent.
        private const val USER_AGENT = "SarvamVoiceAssistant/1.0 (Android; personal project)"
        private const val MAX_ARTICLES = 2
    }

    /**
     * Returns a compact block of context for the model, or a plain "no results" note.
     * Never throws: a failed lookup should degrade to answering without it, not break the turn.
     */
    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext "No results found."

        val results = buildList {
            runCatching { instantAnswer(trimmed) }
                .onFailure { Log.w(TAG, "Instant answer failed", it) }
                .getOrNull()
                ?.let(::add)

            runCatching { wikipedia(trimmed) }
                .onFailure { Log.w(TAG, "Wikipedia lookup failed", it) }
                .getOrDefault(emptyList())
                .forEach(::add)
        }

        SearchFormatting.toContext(results)
    }

    // ── DuckDuckGo Instant Answer ────────────────────────────────────────

    private fun instantAnswer(query: String): SearchResult? {
        val url = "https://api.duckduckgo.com/?q=${encode(query)}" +
            "&format=json&no_html=1&skip_disambig=1"
        val json = JSONObject(get(url))

        val abstract = json.optString("AbstractText").takeIf { it.isNotBlank() && it != "null" }
            ?: json.optJSONArray("RelatedTopics")
                ?.optJSONObject(0)
                ?.optString("Text")
                ?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null

        return SearchResult(
            title = json.optString("Heading").ifBlank { query },
            snippet = abstract,
            source = json.optString("AbstractSource").ifBlank { "DuckDuckGo" },
        )
    }

    // ── Wikipedia ────────────────────────────────────────────────────────

    private fun wikipedia(query: String): List<SearchResult> {
        val searchUrl = "https://en.wikipedia.org/w/api.php?action=query&format=json" +
            "&list=search&srlimit=$MAX_ARTICLES&srsearch=${encode(query)}"
        val hits = JSONObject(get(searchUrl))
            .optJSONObject("query")
            ?.optJSONArray("search")
            ?: return emptyList()

        return buildList {
            for (i in 0 until minOf(hits.length(), MAX_ARTICLES)) {
                val title = hits.optJSONObject(i)?.optString("title").orEmpty()
                if (title.isBlank() || title == "null") continue

                val summary = runCatching { summaryOf(title) }.getOrNull() ?: continue
                add(summary)
            }
        }
    }

    private fun summaryOf(title: String): SearchResult? {
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/${encode(title)}"
        val json = JSONObject(get(url))
        val extract = json.optString("extract").takeIf { it.isNotBlank() && it != "null" }
            ?: return null

        return SearchResult(
            title = json.optString("title").ifBlank { title },
            snippet = extract,
            source = "Wikipedia",
        )
    }

    // ── Plumbing ─────────────────────────────────────────────────────────

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            response.body?.string().orEmpty()
        }
    }
}
