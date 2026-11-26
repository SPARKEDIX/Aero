package com.aero.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aero.voice.search.SearchOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Fully background internet queries.
 *
 * When the user asks something that needs the internet, we start a search in
 * the background WITHOUT blocking the assistant — the model keeps talking and
 * listening. When results arrive, they are injected into the conversation and
 * the model announces them naturally (after finishing what it was saying, or
 * mid-flow).
 *
 * The model is instructed NEVER to call web_search itself, so its turn never
 * pauses waiting for a tool result.
 */
object BackgroundQuery {

    private const val TAG = "AeroBgQuery"
    private const val SEARCH_TIMEOUT_MS = 12_000L
    private const val MAX_QUERY_LEN = 90

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = SearchOrchestrator()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val TRIGGERS = listOf(
        // Explicit internet/search requests only — avoids auto-triggering on
        // normal chat ("kya hai", "kaise ho" etc).
        Regex("(?i)\\b(internet|online|google|web|net)\\b"),
        Regex("(?i)\\b(search|dhundho|dhund|pata karo|pata laga|khojo|check karo|google karo)\\b"),
        Regex("(?i)\\b(news|weather|temperature|price|prices|score|match|live score|stock|currency)\\b"),
        Regex("(?i)\\bwhat is\\b|\\bwhat are\\b|\\bhow to\\b|\\bwhy is\\b|\\bwhat happened\\b"),
        Regex("(?i)\\bdekh\\s+(kya|kaise|kaun)\\b|\\bdekh\\s+.*\\b(kya hota hai|kya chal raha hai|kya khabar)\\b")
    )

    private val FILLERS = listOf(
        "internet", "online", "google", "web", "sir", "please", "pls", "bhai",
        "dekh", "dhundho", "dhund", "pata karo", "pata laga", "laga", "karo",
        "kar", "karna", "search", "check", "khojo", "kya hota hai", "kya hai",
        "kya hua", "kya chal raha hai", "kya scene hai", "kya khabar", "about",
        "kya", "batao", "bata", "batana", "jaan", "jaanna", "mujhe", "mujhko",
        "mere", "ko", "ki", "ka", "ke", "se", "mein", "me", "par", "ho"
    )

    /**
     * Starts a background search if [text] looks like an internet request.
     * Safe to call from any session; no-op when nothing matches.
     */
    fun maybeStart(text: String) {
        val t = text.trim()
        if (t.length < 4) return
        if (!TRIGGERS.any { it.containsMatchIn(t) }) return

        val query = extractQuery(t)
        if (query.length < 2) return
        if (!inFlight.add(query.lowercase())) {
            Log.d(TAG, "search already in flight: $query")
            return // already running
        }

        val client = AeroState.liveClient
        if (client != null) {
            client.sendContextText(
                "[System] User ne internet par poocha hai. Main background mein search shuru kar raha hoon: \"$query\". " +
                    "Tu normal baat karta reh — result milte hi main bata dunga."
            )
        }
        Log.d(TAG, "background search started: $query")

        scope.launch {
            try {
                val payload = withTimeout(SEARCH_TIMEOUT_MS) { engine.search(query) }
                val summary = summarize(payload)
                mainHandler.post {
                    inFlight.remove(query.lowercase())
                    val live = AeroState.liveClient
                    if (live != null) {
                        live.sendContextText("[Search result for \"$query\"] $summary")
                        Log.d(TAG, "search result injected: ${summary.take(120)}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "search failed: ${t.message}")
                mainHandler.post {
                    inFlight.remove(query.lowercase())
                    val live = AeroState.liveClient
                    if (live != null) {
                        live.sendContextText(
                            "[Search failed for \"$query\"] Internet sources tak nahi pahunch paya. " +
                                "User ko batao ki thodi der baad try kare."
                        )
                    }
                }
            }
        }
    }

    private fun extractQuery(text: String): String {
        var s = " ${text.trim()} "
        FILLERS.forEach { f ->
            // Replace whole words only, case-insensitive.
            s = Regex("(?i)\\b${Regex.escape(f)}\\b").replace(s, " ")
        }
        s = Regex("\\s+").replace(s, " ").trim()
        s = s.trim(' ', ',', '.', '?', '!', ':', '-')
        return if (s.length <= MAX_QUERY_LEN) s else s.take(MAX_QUERY_LEN)
    }

    private fun summarize(p: com.aero.voice.search.SearchPayload): String {
        val results = p.results.take(3)
        if (results.isEmpty()) {
            return "Koi solid result nahi mila. Batao ki search kaam nahi kar paya."
        }
        return buildString {
            append("Yeh raha jo mila:\n")
            results.forEachIndexed { i, r ->
                append("${i + 1}. ${r.title}")
                r.snippet?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
                append("\n")
            }
            append("Short, accurate jawab de — sirf zaroori baat, 2-3 lines.")
        }
    }
}
