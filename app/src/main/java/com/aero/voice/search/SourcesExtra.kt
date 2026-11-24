package com.aero.voice.search

import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import java.io.StringReader
import java.net.URLEncoder

// ---------------------------------------------------------------------------
// Extra FREE, key-less web backends.
// Some are scraping-based or open community APIs — each has its own rate
// limiter + circuit breaker, so if one blocks we simply fall through to others.
// ---------------------------------------------------------------------------

/**
 * Mwmbl — free, open-source, community web index with a public JSON API.
 */
class MwmblSource : Source("Mwmbl", RateLimiter(2_000L)) {
    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://api.mwmbl.org/search?q=${enc(query)}"
        val text = Http.get(url)
        if (!text.trimStart().startsWith("{")) return@run emptyList()
        val json = JSONObject(text)
        val results = when {
            json.has("results") -> json.optJSONArray("results")
            json.has("documents") -> json.optJSONArray("documents")
            else -> null
        } ?: return@run emptyList()

        val out = ArrayList<WebResult>()
        for (i in 0 until results.length()) {
            if (out.size >= 3) break
            val o = results.optJSONObject(i) ?: continue
            val title = stripHtml(o.optString("title").ifBlank { o.optString("url") })
            val link = o.optString("url").ifBlank { o.optString("id") }
            if (title.isBlank() || link.isBlank()) continue
            val snippet = o.optString("description")
                .ifBlank { o.optString("snippet") }
                .ifBlank { o.optString("summary") }
            out.add(WebResult(title, stripHtml(snippet).take(300), link, name))
        }
        out
    } ?: emptyList()
}

/** Marginalia — free public API, great for long-tail/indie web pages. */
class MarginaliaSource : Source("Marginalia", RateLimiter(2_500L)) {
    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://api.marginalia.nu/public/search/${enc(query)}?count=5&lowQuality=false"
        val text = Http.get(url)
        if (!text.trimStart().startsWith("{")) return@run emptyList()
        val json = JSONObject(text)
        val results = json.optJSONArray("results") ?: return@run emptyList()

        val out = ArrayList<WebResult>()
        for (i in 0 until results.length()) {
            if (out.size >= 3) break
            val o = results.optJSONObject(i) ?: continue
            val title = stripHtml(o.optString("title"))
            val link = o.optString("url")
            if (title.isBlank() || link.isBlank()) continue
            out.add(WebResult(title, stripHtml(o.optString("description")).take(300), link, name))
        }
        out
    } ?: emptyList()
}

/** YouTube RSS search — latest videos for any query (free, unlimited). */
class YouTubeSource : Source("YouTube", RateLimiter(1_800L)) {
    override fun matches(query: String): Boolean = VIDEO_HINT.containsMatchIn(query)

    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://www.youtube.com/feeds/videos.xml?search_query=${enc(query)}"
        Rss.parse(Http.get(url), name, 3)
    } ?: emptyList()
}

/** arXiv Atom API — fresh research/tech papers, sorted by submission date. */
class ArxivSource : Source("arXiv", RateLimiter(2_000L)) {
    override fun matches(query: String): Boolean = PAPER_HINT.containsMatchIn(query)

    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://export.arxiv.org/api/query?search_query=all:${enc(query)}" +
            "&sortBy=submittedDate&sortOrder=descending&max_results=5"
        parseAtom(Http.get(url))
    } ?: emptyList()

    private fun parseAtom(xml: String): List<WebResult> {
        val out = ArrayList<WebResult>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            var inEntry = false
            var title = ""
            var link = ""
            var summary = ""
            var published = ""
            while (event != XmlPullParser.END_DOCUMENT && out.size < 3) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "entry" -> {
                            inEntry = true
                            title = ""; link = ""; summary = ""; published = ""
                        }
                        "title" -> if (inEntry) title = parser.nextText()
                        "id" -> if (inEntry) link = parser.nextText().trim()
                        "summary" -> if (inEntry) summary = parser.nextText()
                        "published" -> if (inEntry) published = parser.nextText()
                    }
                    XmlPullParser.END_TAG -> if (inEntry && parser.name == "entry") {
                        inEntry = false
                        val t = stripHtml(title)
                        if (t.isNotBlank() && link.isNotBlank()) {
                            out.add(WebResult(t, stripHtml(summary).take(300), link, name, published))
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
        }
        return out
    }
}

/** Spaceflight News API — very fresh space/rocket news, free, no key. */
class SpaceflightNewsSource : Source("Spaceflight News", RateLimiter(2_000L)) {
    override fun matches(query: String): Boolean = SPACE_HINT.containsMatchIn(query)

    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://api.spaceflightnewsapi.net/v4/articles/?search=${enc(query)}&limit=5"
        val json = JSONObject(Http.get(url))
        val results = json.optJSONArray("results") ?: return@run emptyList()
        val out = ArrayList<WebResult>()
        for (i in 0 until results.length()) {
            if (out.size >= 3) break
            val o = results.optJSONObject(i) ?: continue
            val title = o.optString("title")
            if (title.isBlank()) continue
            out.add(
                WebResult(
                    title,
                    stripHtml(o.optString("summary")).take(300),
                    o.optString("url"),
                    name,
                    o.optString("published_at")
                )
            )
        }
        out
    } ?: emptyList()
}

/** Frankfurter — live FX rates (free, no key, refreshed daily). */
class FrankfurterSource : Source("Frankfurter", RateLimiter(2_000L)) {
    override fun matches(query: String): Boolean = FX_HINT.containsMatchIn(query)

    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val base = extractBaseCurrency(query) ?: "USD"
        val targets = extractTargetCurrencies(query)
        val url = "https://api.frankfurter.app/latest?from=$base&to=${targets.joinToString(",")}"
        val text = Http.get(url)
        if (!text.trimStart().startsWith("{")) return@run emptyList()
        val json = JSONObject(text)
        val rates = json.optJSONObject("rates") ?: return@run emptyList()

        val snippet = buildString {
            append("1 ")
            append(base)
            append(" = ")
            val parts = ArrayList<String>()
            val keys = rates.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                parts.add("$k ${String.format(java.util.Locale.US, "%.4f", rates.optDouble(k))}")
            }
            append(parts.joinToString(", "))
            append(" (live, ${json.optString("date")})")
        }
        listOf(WebResult("Live exchange rates", snippet, "https://frankfurter.app/", name))
    } ?: emptyList()

    private fun extractBaseCurrency(q: String): String? {
        val lowered = q.lowercase()
        listOf("usd" to "USD", "dollar" to "USD", "inr" to "INR", "rupee" to "INR",
            "eur" to "EUR", "euro" to "EUR", "gbp" to "GBP", "pound" to "GBP",
            "jpy" to "JPY", "yen" to "JPY").forEach { (k, v) ->
            if (lowered.contains(k)) return v
        }
        return "USD"
    }

    private fun extractTargetCurrencies(q: String): List<String> {
        val lowered = q.lowercase()
        val out = LinkedHashSet<String>()
        listOf("inr" to "INR", "rupee" to "INR", "usd" to "USD", "dollar" to "USD",
            "eur" to "EUR", "euro" to "EUR", "gbp" to "GBP", "pound" to "GBP",
            "jpy" to "JPY", "yen" to "JPY").forEach { (k, v) ->
            if (lowered.contains(k)) out.add(v)
        }
        if (out.isEmpty()) {
            out.add("USD"); out.add("EUR"); out.add("INR"); out.add("GBP"); out.add("JPY")
        }
        return out.toList()
    }
}

/** Lemmy (federated Reddit-like) public search API. */
class LemmySource : Source("Lemmy", RateLimiter(3_000L)) {
    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://lemmy.ml/api/v3/search?q=${enc(query)}&type_=All&sort=TopAll&limit=5"
        val text = Http.get(url)
        if (!text.trimStart().startsWith("{")) return@run emptyList()
        val json = JSONObject(text)
        val posts = json.optJSONArray("posts") ?: return@run emptyList()
        val out = ArrayList<WebResult>()
        for (i in 0 until posts.length()) {
            if (out.size >= 3) break
            val p = posts.optJSONObject(i)?.optJSONObject("post") ?: continue
            val title = p.optString("name")
            if (title.isBlank()) continue
            val body = stripHtml(p.optString("body")).take(280)
            val snippet = body.ifBlank {
                "Community ${p.optString("community_name")} • ${p.optInt("score")} points"
            }
            val link = p.optString("url").ifBlank { "https://lemmy.ml/post/${p.optString("id")}" }
            out.add(WebResult(title, snippet, link, name, p.optString("published")))
        }
        out
    } ?: emptyList()
}

// ---------------------------------------------------------------------------
// Routing keywords for the new specialized sources
// ---------------------------------------------------------------------------
private val VIDEO_HINT = Regex(
    "(?i)\\b(video|youtube|watch|clip|trailer|song|music|tutorial|review)\\b"
)

private val PAPER_HINT = Regex(
    "(?i)\\b(paper|research|arxiv|study|science|publication|preprint|journal|thesis)\\b"
)

private val SPACE_HINT = Regex(
    "(?i)\\b(space|nasa|rocket|launch|iss|satellite|mars|moon|galaxy|astronaut|starlink)\\b"
)

private val FX_HINT = Regex(
    "(?i)\\b(currency|exchange rate|fx|usd|inr|eur|gbp|jpy|dollar|rupee|pound|euro|yen|convert)\\b"
)
