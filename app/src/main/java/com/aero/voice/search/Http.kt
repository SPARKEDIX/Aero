package com.aero.voice.search

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Shared HTTP layer for all free web sources. */
internal object Http {
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 AeroVoice/1.0"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8")

        headers.forEach { (k, v) -> builder.header(k, v) }

        client.newCall(builder.build()).execute().use { resp ->
            when (resp.code) {
                429 -> throw RateLimitedException(retryAfterMs(resp))
                403 -> throw RateLimitedException(retryAfterMs(resp) ?: 90_000L)
            }
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} from ${resp.request.url.host}")
            }
            return resp.body?.string() ?: ""
        }
    }

    private fun retryAfterMs(resp: Response): Long? {
        resp.header("Retry-After")?.let { v ->
            v.toLongOrNull()?.let { return it * 1000L }
        }
        resp.header("X-RateLimit-Reset")?.toLongOrNull()?.let { reset ->
            val nowSec = System.currentTimeMillis() / 1000L
            return (reset - nowSec).coerceAtLeast(0L) * 1000L
        }
        return null
    }
}
