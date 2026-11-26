package com.aero.voice

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.nio.ByteBuffer

/** Voice Verification: enroll (record or MP3), list, delete with re-verify. */
class VoiceVerificationActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var recording = false

    private val mp3Picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handleMp3(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_verification)

        findViewById<TextView>(R.id.vvBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.vvRecord).setOnClickListener { startRecord() }
        findViewById<MaterialButton>(R.id.vvUpload).setOnClickListener {
            try {
                mp3Picker.launch(arrayOf("audio/*", "audio/mpeg"))
            } catch (_: Exception) {
            }
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    // ---------------- enroll via microphone ----------------

    private fun startRecord() {
        if (recording) return
        val name = findViewById<EditText>(R.id.vvName).text.toString().trim()
        val role = findViewById<EditText>(R.id.vvRole).text.toString().trim()
        if (name.isEmpty()) {
            status("Pehle naam likho.")
            return
        }
        recording = true
        status("Recording… 5 seconds. Ab bolo: \"mera naam $name hai\"")
        findViewById<MaterialButton>(R.id.vvRecord).isEnabled = false

        Thread {
            val frames = recordFrames(5_000)
            handler.post {
                recording = false
                findViewById<MaterialButton>(R.id.vvRecord).isEnabled = true
                if (frames == null) {
                    status("Recording failed — microphone nahi chala.")
                    return@post
                }
                val profile = VoiceFingerprint.profile(frames)
                if (profile == null) {
                    status("Itni awaaz nahi mili — dobara try karo.")
                    return@post
                }
                VoiceStore.add(this, name, role, profile)
                status("✅ \"$name\" enroll ho gaya!")
                refreshList()
            }
        }.start()
    }

    private fun recordFrames(durationMs: Int): List<FloatArray>? {
        return try {
            val sampleRate = VoiceFingerprint.SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 3200)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return null
            recorder.startRecording()
            val buf = ShortArray(800) // 50ms at 16k
            val frames = ArrayList<FloatArray>()
            val total = durationMs / 50
            for (i in 0 until total) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    frames.add(VoiceFingerprint.frameFeatures(buf.copyOf(read)))
                }
            }
            recorder.stop()
            recorder.release()
            frames
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- enroll via MP3 ----------------

    private fun handleMp3(uri: Uri) {
        val name = findViewById<EditText>(R.id.vvName).text.toString().trim()
        if (name.isEmpty()) {
            status("Pehle naam likho.")
            return
        }
        status("MP3 decode ho raha hai…")
        Thread {
            val frames = decodeMp3Frames(uri)
            handler.post {
                if (frames == null || frames.isEmpty()) {
                    status("MP3 decode nahi hua — doosri file try karo.")
                    return@post
                }
                val profile = VoiceFingerprint.profile(frames)
                if (profile == null) {
                    status("File mein awaaz kaafi nahi.")
                    return@post
                }
                VoiceStore.add(this, name, findViewById<EditText>(R.id.vvRole).text.toString().trim(), profile)
                status("✅ MP3 se \"$name\" enroll ho gaya!")
                refreshList()
            }
        }.start()
    }

    private fun decodeMp3Frames(uri: Uri): List<FloatArray>? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(this, uri, null)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime == null || !mime.startsWith("audio/")) {
                extractor.release()
                return null
            }
            extractor.selectTrack(0)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ArrayList<FloatArray>()
            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L
            var sawInputEOS = false
            var sawOutputEOS = false
            var guard = 0

            while (!sawOutputEOS && guard < 4000) {
                guard++
                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(inBuf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        outBuf.get(bytes)
                        val shorts = ShortArray(bytes.size / 2)
                        for (i in shorts.indices) {
                            shorts[i] = ((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)).toShort()
                        }
                        // 800-sample frames (≈50ms @16k; fine for other rates too)
                        var i = 0
                        while (i < shorts.size) {
                            val end = minOf(i + 800, shorts.size)
                            out.add(VoiceFingerprint.frameFeatures(shorts.copyOfRange(i, end)))
                            i = end
                        }
                    }
                    val flags = bufferInfo.flags
                    codec.releaseOutputBuffer(outIdx, false)
                    if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                }
            }

            codec.stop()
            codec.release()
            extractor.release()
            out
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- list + delete with re-verify ----------------

    private fun refreshList() {
        val container = findViewById<LinearLayout>(R.id.vvList)
        container.removeAllViews()
        val voices = VoiceStore.all(this)
        if (voices.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Abhi koi voice enroll nahi hui."
                setTextColor(resources.getColor(R.color.text_secondary, null))
                textSize = 13f
            }
            container.addView(tv)
            return
        }
        voices.forEach { v ->
            val row = TextView(this).apply {
                text = "• ${v.label}"
                setTextColor(resources.getColor(R.color.text_primary, null))
                textSize = 15f
                setPadding(0, resources.displayMetrics.densityDpi / 10, 0, 0)
            }
            container.addView(row)
            val del = MaterialButton(this).apply {
                text = "Delete ${v.name}"
                setTextColor(android.graphics.Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.rgb(239, 68, 68)
                )
                textSize = 13f
                isAllCaps = false
                setOnClickListener { confirmDelete(v) }
            }
            container.addView(del)
        }
    }

    private fun confirmDelete(v: VoiceStore.Voice) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${v.name}\"?")
            .setMessage("Safety: delete ke liye \"${v.name}\" ko ab bolke verify karna hoga " +
                "(record 4 seconds).")
            .setPositiveButton("Verify & Delete") { _, _ -> verifyThenDelete(v) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun verifyThenDelete(v: VoiceStore.Voice) {
        status("Bol: \"mera naam ${v.name} hai\" (4 seconds)")
        findViewById<MaterialButton>(R.id.vvRecord).isEnabled = false
        Thread {
            val frames = recordFrames(4_000)
            handler.post {
                findViewById<MaterialButton>(R.id.vvRecord).isEnabled = true
                if (frames == null) {
                    status("Recording failed.")
                    return@post
                }
                val profile = VoiceFingerprint.profile(frames) ?: run {
                    status("Awaaz nahi mili.")
                    return@post
                }
                val sim = VoiceFingerprint.similarity(profile, v.profile)
                if (sim >= VoiceFingerprint.DEFAULT_THRESHOLD) {
                    VoiceStore.remove(this, v.id)
                    status("✅ \"${v.name}\" delete ho gaya.")
                    refreshList()
                } else {
                    status("❌ Voice match nahi hui (score ${"%.2f".format(sim)}) — delete cancel.")
                }
            }
        }.start()
    }

    private fun status(msg: String) {
        findViewById<TextView>(R.id.vvStatus).text = msg
    }

    companion object {
        fun open(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(ctx, VoiceVerificationActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }
}
