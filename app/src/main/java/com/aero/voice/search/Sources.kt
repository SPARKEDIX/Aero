package com.aero.voice.search

import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

internal fun stripHtml(s: String?): String =
    Html.fromHtml(s ?: "", Html.FROM_HTML_MODE_LEGACY).toString()
        .replace(Regex("\\s+"), " ")
        .trim()

/** A free, key-less web source. */
abstract class Source(val name: String, val limiter: RateLimiter) {
    open fun matches(query: String): Boolean = true
    abstract suspend fun fetch(query: String): List<WebResult>
}

// ---------------------------------------------------------------------------
// 1. DuckDuckGo Instant Answer API
// ---------------------------------------------------------------------------
class DuckDuckGoSource : Source("DuckDuckGo", RateLimiter(1_200L)) {
    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1"
        val json = JSONObject(Http.get(url))
        val out = ArrayList<WebResult>()

        val abstract = json.optString("AbstractText")
        if (abstract.isNotBlank()) {
            out.add(
                WebResult(
                    json.optString("Heading", query).ifBlank { query },
                    stripHtml(abstract).take(300),
                    json.optString("AbstractURL"),
                    name
                )
            )
        }
        collect(json.optJSONArray("RelatedTopics"), out)
        out.take(3)
    } ?: emptyList()

    private fun collect(arr: JSONArray?, out: MutableList<WebResult>) {
        if (arr == null || out.size >= 3) return
        for (i in 0 until arr.length()) {
            if (out.size >= 3) break
            val o = arr.optJSONObject(i) ?: continue
            val nested = o.optJSONArray("Topics")
            if (nested != null) {
                collect(nested, out)
                continue
            }
            val text = o.optString("Text")
            val url = o.optString("FirstURL")
            if (text.isNotBlank() && url.isNotBlank()) {
                out.add(
                    WebResult(
                        text.substringBefore(" - ").take(140),
                        stripHtml(text).take(300),
                        url,
                        name
                    )
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Wikipedia search API
// ---------------------------------------------------------------------------
class WikipediaSource : Source("Wikipedia", RateLimiter(1_200L)) {
    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search" +
            "&srsearch=${enc(query)}&format=json&utf8=1&srlimit=3&srprop=snippet"
        val json = JSONObject(Http.get(url))
        val arr = json.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
        val out = ArrayList<WebResult>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title")
            if (title.isBlank()) continue
            val link = "https://en.wikipedia.org/wiki/" + enc(title.replace(' ', '_'))
            out.add(WebResult(title, stripHtml(o.optString("snippet")), link, name))
        }
        out
    } ?: emptyList()
}

// ---------------------------------------------------------------------------
// 3. Google News RSS
// ---------------------------------------------------------------------------
class GoogleNewsSource : Source("Google News", RateLimiter(1_500L)) {
    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://news.google.com/rss/search?q=${enc(query)}&hl=en-IN&gl=IN&ceid=IN:en"
        Rss.parse(Http.get(url), name, 3)
    } ?: emptyList()
}

// ---------------------------------------------------------------------------
// 4. Bing News RSS
// ---------------------------------------------------------------------------
class BingNewsSource : Source("Bing News", RateLimiter(1_500L)) {
    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://www.bing.com/news/search?q=${enc(query)}&format=RSS&count=10"
        Rss.parse(Http.get(url), name, 3)
    } ?: emptyList()
}

// ---------------------------------------------------------------------------
// 5. Hacker News (Algolia)
// ---------------------------------------------------------------------------
class HackerNewsSource : Source("Hacker News", RateLimiter(1_200L)) {
    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://hn.algolia.com/api/v1/search?query=${enc(query)}&tags=story&hitsPerPage=4"
        val json = JSONObject(Http.get(url))
        val hits = json.optJSONArray("hits") ?: JSONArray()
        val out = ArrayList<WebResult>()
        for (i in 0 until hits.length()) {
            if (out.size >= 3) break
            val o = hits.optJSONObject(i) ?: continue
            val title = o.optString("title")
            if (title.isBlank()) continue
            val link = o.optString("url")
                .ifBlank { "https://news.ycombinator.com/item?id=${o.optString("objectID")}" }
            val snippet = "HN score ${o.optInt("points")} • ${o.optInt("num_comments")} comments"
            out.add(WebResult(title, snippet, link, name, o.optString("created_at")))
        }
        out
    } ?: emptyList()
}

// ---------------------------------------------------------------------------
// 6. Reddit search JSON
// ---------------------------------------------------------------------------
class RedditSource : Source("Reddit", RateLimiter(6_000L, baseCooldownMs = 120_000L)) {
    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://www.reddit.com/search.json?q=${enc(query)}&limit=4&sort=relevance&t=year&raw_json=1"
        val json = JSONObject(Http.get(url))
        val children = json.optJSONObject("data")?.optJSONArray("children") ?: JSONArray()
        val out = ArrayList<WebResult>()
        for (i in 0 until children.length()) {
            if (out.size >= 3) break
            val d = children.optJSONObject(i)?.optJSONObject("data") ?: continue
            val title = d.optString("title")
            if (title.isBlank()) continue
            val link = "https://www.reddit.com" + d.optString("permalink")
            val self = stripHtml(d.optString("selftext")).take(280)
            val snippet = self.ifBlank {
                "r/${d.optString("subreddit")} • ${d.optInt("score")} upvotes • ${d.optInt("num_comments")} comments"
            }
            out.add(WebResult(title, snippet, link, name))
        }
        out
    } ?: emptyList()
}

// ---------------------------------------------------------------------------
// 7. Stack Overflow
// ---------------------------------------------------------------------------
class StackOverflowSource :
    Source("Stack Overflow", RateLimiter(2_000L, baseCooldownMs = 300_000L)) {

    override fun matches(query: String): Boolean = CODE_HINT.containsMatchIn(query)

    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance" +
            "&q=${enc(query)}&site=stackoverflow&pagesize=4&filter=default"
        val json = JSONObject(Http.get(url))
        val items = json.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<WebResult>()
        for (i in 0 until items.length()) {
            if (out.size >= 3) break
            val o = items.optJSONObject(i) ?: continue
            val title = stripHtml(o.optString("title"))
            if (title.isBlank()) continue
            val tags = o.optJSONArray("tags")?.let { t ->
                (0 until t.length()).map { t.optString(it) }.take(3)
            } ?: emptyList()
            val snippet = buildString {
                append("Score ").append(o.optInt("score"))
                append(" • ").append(if (o.optBoolean("is_answered")) "answered" else "unanswered")
                if (tags.isNotEmpty()) append(" • ").append(tags.joinToString(", "))
            }
            out.add(WebResult(title, snippet, o.optString("link"), name))
        }
        out
    } ?: emptyList()
}

// ---------------------------------------------------------------------------
// 8. GitHub repository search
// ---------------------------------------------------------------------------
class GitHubSource : Source("GitHub", RateLimiter(6_500L, baseCooldownMs = 120_000L)) {

    override fun matches(query: String): Boolean = CODE_HINT.containsMatchIn(query)

    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val url = "https://api.github.com/search/repositories?q=${enc(query)}&per_page=4&sort=stars&order=desc"
        val json = JSONObject(Http.get(url, mapOf("Accept" to "application/vnd.github+json")))
        val items = json.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<WebResult>()
        for (i in 0 until items.length()) {
            if (out.size >= 3) break
            val o = items.optJSONObject(i) ?: continue
            val full = o.optString("full_name")
            if (full.isBlank()) continue
            val desc = o.optString("description")
            val stars = o.optInt("stargazers_count")
            val lang = o.optString("language")
            val snippet = buildString {
                if (desc.isNotBlank()) append(desc.take(200)).append(" • ")
                append("★ ").append(stars)
                if (lang.isNotBlank()) append(" • ").append(lang)
            }
            out.add(WebResult(full, snippet, o.optString("html_url"), name))
        }
        out
    } ?: emptyList()
}

// ---------------------------------------------------------------------------
// 9. Open-Meteo (weather, key-less)
// ---------------------------------------------------------------------------
class WeatherSource : Source("Open-Meteo", RateLimiter(1_500L)) {

    override fun matches(query: String): Boolean = WEATHER_HINT.containsMatchIn(query)

    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val place = extractPlace(query) ?: return@run emptyList()
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${enc(place)}" +
            "&count=1&language=en&format=json"
        val results = JSONObject(Http.get(geoUrl)).optJSONArray("results")
            ?: return@run emptyList()
        if (results.length() == 0) return@run emptyList()

        val g = results.getJSONObject(0)
        val lat = g.optDouble("latitude")
        val lon = g.optDouble("longitude")
        val city = g.optString("name")
        val country = g.optString("country")

        val forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation," +
            "weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=3"
        val w = JSONObject(Http.get(forecastUrl))
        val cur = w.optJSONObject("current") ?: return@run emptyList()
        val daily = w.optJSONObject("daily")

        val tMax = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)
        val tMin = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)
        val rain = daily?.optJSONArray("precipitation_probability_max")?.optInt(0)

        val snippet = buildString {
            append(weatherText(cur.optInt("weather_code"))).append(". ")
            append("Now ").append(fmt(cur.optDouble("temperature_2m"))).append("°C")
            append(" (feels ").append(fmt(cur.optDouble("apparent_temperature"))).append("°C)")
            append(", humidity ").append(cur.optInt("relative_humidity_2m")).append("%")
            append(", wind ").append(fmt(cur.optDouble("wind_speed_10m"))).append(" km/h. ")
            if (tMin != null && tMax != null) {
                append("Today ").append(fmt(tMin)).append("–").append(fmt(tMax)).append("°C. ")
            }
            if (rain != null) append("Rain chance ").append(rain).append("%.")
        }

        listOf(
            WebResult(
                "Weather in $city, $country",
                snippet.trim(),
                "https://open-meteo.com/",
                name
            )
        )
    } ?: emptyList()

    private fun fmt(v: Double): String = String.format(Locale.US, "%.0f", v)

    private fun extractPlace(query: String): String? {
        val cleaned = query.replace(WEATHER_WORDS, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val place = cleaned.split(' ')
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(" ")
        return place.ifBlank { null }
    }

    private fun weatherText(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Rain showers"
        in 85..86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> "Weather code $code"
    }
}

// ---------------------------------------------------------------------------
// 10. CoinGecko (crypto prices, key-less)
// ---------------------------------------------------------------------------
class CryptoSource : Source("CoinGecko", RateLimiter(4_000L, baseCooldownMs = 120_000L)) {

    override fun matches(query: String): Boolean = CRYPTO_HINT.containsMatchIn(query)

    override suspend fun fetch(query: String): List<WebResult> = limiter.run {
        val coin = extractCoin(query) ?: return@run emptyList()
        val search = JSONObject(Http.get("https://api.coingecko.com/api/v3/search?query=${enc(coin)}"))
        val coins = search.optJSONArray("coins") ?: return@run emptyList()
        if (coins.length() == 0) return@run emptyList()

        val c = coins.getJSONObject(0)
        val id = c.optString("id")
        val symbol = c.optString("symbol").uppercase(Locale.US)
        val name = c.optString("name")
        if (id.isBlank()) return@run emptyList()

        val priceUrl = "https://api.coingecko.com/api/v3/simple/price" +
            "?ids=$id&vs_currencies=usd,inr&include_24hr_change=true"
        val p = JSONObject(Http.get(priceUrl)).optJSONObject(id) ?: return@run emptyList()

        val usd = p.optDouble("usd", 0.0)
        val inr = p.optDouble("inr", 0.0)
        val change = p.optDouble("usd_24h_change", 0.0)

        val snippet = buildString {
            append(name).append(" (").append(symbol).append("): $").append(money(usd))
            if (inr > 0) append(" | ₹").append(money(inr))
            append(" • 24h ").append(if (change >= 0) "+" else "")
                .append(String.format(Locale.US, "%.1f", change)).append("%")
        }

        listOf(
            WebResult(
                "$name price",
                snippet,
                "https://www.coingecko.com/en/coins/$id",
                name
            )
        )
    } ?: emptyList()

    private fun money(v: Double): String =
        if (v >= 1000) String.format(Locale.US, "%,.0f", v)
        else String.format(Locale.US, "%.2f", v)

    private fun extractCoin(query: String): String? {
        val cleaned = query.replace(CRYPTO_WORDS, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(" ")
            .ifBlank { null }
    }
}

// ---------------------------------------------------------------------------
// Keyword routing
// ---------------------------------------------------------------------------
private val CODE_HINT = Regex(
    "(?i)\\b(code|programming|developer|api|library|sdk|error|exception|bug|fix|" +
        "android|kotlin|java|python|javascript|typescript|rust|golang|flutter|react|" +
        "node|gradle|compile|crash|stack|github|repo|repository|open source)\\b"
)

private val WEATHER_HINT = Regex(
    "(?i)\\b(weather|temperature|forecast|mausam|baarish|rain|humidity|garmi|sardi)\\b"
)

private val WEATHER_WORDS = Regex(
    "(?i)\\b(what|what's|whats|is|the|weather|temperature|forecast|today|tomorrow|now|" +
        "currently|in|at|of|for|ka|ki|ke|mein|me|mausam|baarish|rain|humidity|" +
        "kitna|kitni|kitne|hai|hoga|kya|please|tell|about|how)\\b"
)

private val CRYPTO_HINT = Regex(
    "(?i)\\b(crypto|bitcoin|btc|ethereum|eth|solana|sol|dogecoin|doge|cardano|ada|" +
        "xrp|ripple|binance|usdt|tether|polygon|matic|chainlink|link)\\b"
)

private val CRYPTO_WORDS = Regex(
    "(?i)\\b(what|what's|whats|is|the|price|rate|value|today|now|current|currently|" +
        "of|for|in|ka|ki|ke|mein|kitna|kitni|hai|kya|tell|me|please|crypto|coin|market)\\b"
)
