package com.aero.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * In-app internet radio (free + legal via radio-browser.info).
 * Plays full broadcasts (Bollywood/Hindi stations) inside Aero — no other
 * app is opened. Supports play/stop/next, and announces the station.
 */
object RadioPlayer {

    private const val TAG = "AeroRadio"
    private const val API_HOST = "https://de1.api.radio-browser.info/json/stations"

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var player: MediaPlayer? = null
    private var stations = listOf<Station>()
    private var index = 0
    private var currentName = ""

    data class Station(val name: String, val url: String)

    /** Finds stations by tag (bollywood, hindi, desi…) / name. */
    fun search(query: String): List<Station> {
        val tag = query.trim().lowercase().replace(Regex("\\s+"), "-")
        val url = "$API_HOST/search?tag=$tag&hidebroken=true&limit=8&order=votes&reverse=true"
        return try {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("name")
                    val stream = o.optString("url")
                    if (name.isBlank() || stream.isBlank()) null
                    else Station(name, stream)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "search failed", t)
            emptyList()
        }
    }

    /** Starts playing a station; returns spoken status. */
    fun play(ctx: Context, query: String): String {
        val found = if (query.isBlank()) search("bollywood") else search(query)
        if (found.isEmpty()) {
            return "Radio station nahi mili \"$query\" ke liye."
        }
        stations = found
        index = 0
        return playIndex(ctx)
    }

    fun next(ctx: Context): String {
        if (stations.isEmpty()) return "Radio pehle chalao — phir next karte hain."
        index = (index + 1) % stations.size
        return playIndex(ctx)
    }

    private fun playIndex(ctx: Context): String {
        val s = stations[index]
        currentName = s.name
        return try {
            stopInternal()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(s.url)
            mp.setOnPreparedListener { it.start() }
            mp.setOnErrorListener { _, _, _ -> false }
            mp.prepareAsync()
            player = mp
            Log.d(TAG, "playing ${s.name} :: ${s.url}")
            "Radio chalu: ${s.name}. Gaane chal rahe hain."
        } catch (t: Throwable) {
            Log.w(TAG, "play failed", t)
            "Radio start nahi ho paya: ${s.name}"
        }
    }

    fun stop() {
        stopInternal()
        currentName = ""
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun currentStation(): String = currentName

    private fun stopInternal() {
        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
    }
}
