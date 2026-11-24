package com.aero.voice.search

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Runs multiple searches at the same time ("multi-instance").
 *
 * At most [MAX_CONCURRENT_SEARCHES] queries are in flight; extra queries wait
 * their turn, which keeps the sources — and the Gemini Live session — healthy.
 */
class SearchOrchestrator(
    private val engine: SearchEngine = SearchEngine()
) {
    private val searchPermits = Semaphore(MAX_CONCURRENT_SEARCHES)

    suspend fun search(query: String): SearchPayload =
        searchPermits.withPermit { engine.search(query) }

    companion object {
        const val MAX_CONCURRENT_SEARCHES = 3
    }
}
