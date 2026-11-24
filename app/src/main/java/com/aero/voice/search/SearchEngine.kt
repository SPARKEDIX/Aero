package com.aero.voice.search

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Fans a query out to all matching free sources in parallel, then ranks and
 * de-duplicates the results.
 *
 * Protection against "too many requests":
 *  - per-source [RateLimiter] (interval + exponential cooldown),
 *  - global HTTP concurrency cap,
 *  - 5 minute result cache,
 *  - in-flight de-duplication of identical queries.
 */
class SearchEngine(
    private val sources: List<Source> = defaultSources()
) {
    private val httpPermits = Semaphore(6)
    private val cache = SearchCache(ttlMs = 5 * 60_000L, maxEntries = 64)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<SearchPayload>>()

    suspend fun search(query: String): SearchPayload {
        val q = query.trim()
        if (q.isEmpty()) {
            return SearchPayload(q, emptyList(), emptyList(), emptyList(), 0L)
        }
        val key = q.lowercase()
        cache.get(key)?.let { return it.copy(cached = true) }

        val mine = CompletableDeferred<SearchPayload>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return existing.await()

        return try {
            val payload = doSearch(q)
            cache.put(key, payload)
            mine.complete(payload)
            payload
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private suspend fun doSearch(query: String): SearchPayload {
        val started = SystemClock.elapsedRealtime()
        val relevant = sources.filter { it.matches(query) }

        val perSource = coroutineScope {
            relevant.map { source ->
                async {
                    httpPermits.withPermit {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            try {
                                source.fetch(query)
                            } catch (_: Exception) {
                                emptyList()
                            }
                        } ?: emptyList()
                    }
                }
            }.awaitAll()
        }

        val ranked = rank(query, perSource.flatten())
        val failed = relevant.indices
            .filter { perSource[it].isEmpty() }
            .map { relevant[it].name }

        return SearchPayload(
            query = query,
            results = ranked,
            sourcesUsed = relevant.map { it.name },
            sourcesFailed = failed,
            tookMs = SystemClock.elapsedRealtime() - started
        )
    }

    private fun rank(query: String, results: List<WebResult>): List<WebResult> {
        if (results.isEmpty()) return emptyList()

        val tokens = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .toSet()

        val urlSeen = HashSet<String>()
        val titleSeen = HashSet<String>()
        val scored = ArrayList<Pair<WebResult, Double>>()

        for (r in results) {
            val urlKey = r.url.lowercase().substringBefore('?').trimEnd('/')
            val titleKey = r.title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            if (urlKey.isBlank() || !urlSeen.add(urlKey)) continue
            if (titleKey.isBlank() || !titleSeen.add(titleKey)) continue

            var score = when (r.source) {
                "Open-Meteo", "CoinGecko" -> 1.70
                "DuckDuckGo" -> 1.20
                "Wikipedia" -> 1.15
                "Google News", "Bing News" -> 1.05
                "Stack Overflow" -> 1.00
                "GitHub" -> 0.95
                "Hacker News" -> 0.90
                "Reddit" -> 0.85
                else -> 0.80
            }
            val text = (r.title + " " + r.snippet).lowercase()
            score += tokens.count { text.contains(it) } * 0.10
            scored.add(r to score)
        }

        val out = ArrayList<WebResult>()
        val perSource = HashMap<String, Int>()
        for ((result, _) in scored.sortedByDescending { it.second }) {
            val count = perSource.getOrDefault(result.source, 0)
            if (count >= 2) continue
            perSource[result.source] = count + 1
            out.add(result)
            if (out.size >= MAX_RESULTS) break
        }
        return out
    }

    companion object {
        private const val SOURCE_TIMEOUT_MS = 7_000L
        private const val MAX_RESULTS = 12

        private fun defaultSources(): List<Source> = listOf(
            DuckDuckGoSource(),
            WikipediaSource(),
            GoogleNewsSource(),
            BingNewsSource(),
            HackerNewsSource(),
            RedditSource(),
            StackOverflowSource(),
            GitHubSource(),
            WeatherSource(),
            CryptoSource()
        )
    }
}
