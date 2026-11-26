package com.aero.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays Gemini Live audio output (24 kHz mono PCM16) as it streams in,
 * with a small queue so playback stays smooth and low-latency.
 *
 * While an Aero call is active the track switches to VOICE_COMMUNICATION
 * usage, so the call audio can go to the earpiece (default) or speaker.
 */
class AudioPlayer {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private fun buildTrack(callMode: Boolean): AudioTrack {
        val sampleRate = 24000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (callMode) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, 48000))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            var callMode = AeroState.inCall
            var routeRev = AeroState.audioRoute
            var track = buildTrack(callMode)
            // Moderate volume reduces speaker->mic leakage to the assistant.
            track.setVolume(0.85f)
            track.play()
            var playedChunks = 0
            Log.d("AeroAudio", "player started (call=$callMode route=$routeRev)")
            while (running.get()) {
                // Rebuild the track when the audio mode or the speaker routing
                // changes (routing only applies to a freshly created track).
                if (callMode != AeroState.inCall || routeRev != AeroState.audioRoute) {
                    try {
                        track.stop()
                        track.release()
                    } catch (_: Throwable) {
                    }
                    callMode = AeroState.inCall
                    routeRev = AeroState.audioRoute
                    Log.d("AeroAudio", "track rebuilt (call=$callMode route=$routeRev)")
                    track = try {
                        buildTrack(callMode).also {
                            it.setVolume(0.85f)
                            it.play()
                        }
                    } catch (t: Throwable) {
                        Log.e("AeroAudio", "track rebuild failed", t)
                        break
                    }
                }
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                track.write(chunk, 0, chunk.size)
                playedChunks++
                if (playedChunks % 20 == 1) {
                    Log.d("AeroAudio", "played $playedChunks chunks")
                }
            }
            try {
                track.stop()
                track.release()
            } catch (_: Throwable) {
            }
        }, "audio-player").apply {
            start()
        }
    }

    fun enqueue(data: ByteArray) {
        queue.offer(data)
    }

    fun flush() {
        queue.clear()
    }

    fun stop() {
        running.set(false)
        thread?.join(500)
        thread = null
    }
}
