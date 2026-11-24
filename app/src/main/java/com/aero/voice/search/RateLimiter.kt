package com.aero.voice.search

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.max
import kotlin.math.min

class RateLimitedException(val retryAfterMs: Long? = null) : Exception("HTTP rate limited")

/**
 * Per-source rate limiter.
 *
 * - Enforces a minimum interval between requests (token bucket style).
 * - Caps concurrent requests per source.
 * - On 429/403 it enters an exponential cooldown and fails fast (returns null)
 *   instead of blocking the whole search.
 * - Repeated generic failures (blocked/parse errors) also trigger a cooldown,
 *   so a flaky backend cannot eat our whole frame budget forever.
 */
class RateLimiter(
    private val minIntervalMs: Long,
    maxConcurrent: Int = 1,
    private val baseCooldownMs: Long = 45_000L,
    private val maxCooldownMs: Long = 10 * 60_000L,
    private val genericFailsBeforeCooldown: Int = 3
) {
    private val gate = Mutex()
    private val permits = Semaphore(maxConcurrent)
    private var nextSlotAt = 0L
    private var cooldownUntil = 0L
    private var backoffMs = 0L
    private var genericFails = 0

    suspend fun <T> run(block: suspend () -> T): T? = permits.withPermit {
        val ready = gate.withLock {
            val now = SystemClock.elapsedRealtime()
            if (cooldownUntil > now) return@withLock false
            val wait = nextSlotAt - now
            if (wait > 0) delay(wait)
            nextSlotAt = SystemClock.elapsedRealtime() + minIntervalMs
            true
        }
        if (!ready) return@withPermit null

        try {
            val result = block()
            gate.withLock {
                genericFails = 0
                backoffMs = 0L
            }
            result
        } catch (e: RateLimitedException) {
            gate.withLock {
                backoffMs = if (backoffMs == 0L) baseCooldownMs else min(backoffMs * 2, maxCooldownMs)
                cooldownUntil = SystemClock.elapsedRealtime() + max(e.retryAfterMs ?: 0L, backoffMs)
            }
            null
        } catch (_: Exception) {
            gate.withLock {
                genericFails++
                if (genericFails >= genericFailsBeforeCooldown) {
                    val cool = min(baseCooldownMs * genericFails, maxCooldownMs)
                    cooldownUntil = SystemClock.elapsedRealtime() + cool
                    genericFails = 0
                }
            }
            null
        }
    }
}
