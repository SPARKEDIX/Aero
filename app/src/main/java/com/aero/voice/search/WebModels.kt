package com.aero.voice.search

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One web result returned to the model. */
data class WebResult(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String,
    val publishedAt: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title.take(200))
        put("snippet", snippet.take(320))
        put("url", url)
        put("source", source)
        if (!publishedAt.isNullOrBlank()) put("publishedAt", publishedAt.take(40))
    }
}

/** Aggregated payload for a single search query. */
data class SearchPayload(
    val query: String,
    val results: List<WebResult>,
    val sourcesUsed: List<String>,
    val sourcesFailed: List<String>,
    val tookMs: Long,
    val cached: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("query", query)
        put("fetchedAt", isoNow())
        put("results", JSONArray().apply { results.forEach { put(it.toJson()) } })
        put("sourcesUsed", JSONArray(sourcesUsed))
        put("sourcesFailed", JSONArray(sourcesFailed))
        put("tookMs", tookMs)
        put("cached", cached)
        put("note", "Live web results from multiple websites. Synthesize a concise answer and mention sources when useful.")
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
