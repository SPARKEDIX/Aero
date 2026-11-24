package com.aero.voice.search

import android.os.SystemClock

/** Small LRU cache with a TTL so repeated questions do not hammer the sources. */
internal class SearchCache(private val ttlMs: Long, private val maxEntries: Int) {

    private data class Entry(val payload: SearchPayload, val at: Long)

    private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }
    private val lock = Any()

    fun get(key: String): SearchPayload? = synchronized(lock) {
        val entry = map[key] ?: return@synchronized null
        if (SystemClock.elapsedRealtime() - entry.at > ttlMs) {
            map.remove(key)
            null
        } else {
            entry.payload
        }
    }

    fun put(key: String, payload: SearchPayload) = synchronized(lock) {
        map[key] = Entry(payload, SystemClock.elapsedRealtime())
    }
}
