package com.aero.voice

import android.media.AudioFormat
import android.util.Log
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures microphone audio at 16 kHz mono PCM16 and pushes 100 ms chunks
 * to the [onChunk] callback (the exact format Gemini Live expects upstream).
 */
class AudioRecorder(private val onChunk: (ByteArray) -> Unit) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    companion object {
        private const val TAG = "AudioRec"
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread({
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf * 2, 3200)
            val recorder = try {
                // VOICE_COMMUNICATION enables hardware acoustic echo cancellation +
                // noise suppression on virtually all devices — critical so the
                // assistant does not hear its own voice and answer itself.
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
            } catch (e: Exception) {
                running.set(false)
                return@Thread
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                running.set(false)
                return@Thread
            }

            recorder.startRecording()
            Log.d(TAG, "recording started (state=${recorder.recordingState})")
            val buf = ByteArray(1600) // 100 ms at 16 kHz
            var chunks = 0
            while (running.get()) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    onChunk(buf.copyOf(read))
                    chunks++
                    if (chunks % 20 == 1) Log.d(TAG, "captured $chunks chunks")
                }
            }
            recorder.stop()
            recorder.release()
        }, "audio-recorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running.set(false)
        thread?.join(500)
        thread = null
    }
}
