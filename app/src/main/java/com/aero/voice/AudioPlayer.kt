package com.aero.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays Gemini Live audio output (24 kHz mono PCM16) as it streams in,
 * with a small queue so playback stays smooth and low-latency.
 */
class AudioPlayer {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            val sampleRate = 24000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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

            // Moderate volume reduces speaker->mic leakage to the assistant.
            track.setVolume(0.85f)
            track.play()
            while (running.get()) {
                val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                track.write(chunk, 0, chunk.size)
            }
            track.stop()
            track.release()
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
