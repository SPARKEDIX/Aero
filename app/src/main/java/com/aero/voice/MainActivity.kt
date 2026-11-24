package com.aero.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity(), GeminiLiveClient.Listener {

    private val handler = Handler(Looper.getMainLooper())

    private val micButton by lazy { findViewById<FloatingActionButton>(R.id.micButton) }
    private val orbView by lazy { findViewById<OrbView>(R.id.orbView) }

    private var client: GeminiLiveClient? = null
    private var recorder: AudioRecorder? = null
    private var player: AudioPlayer? = null
    private var sessionActive = false

    private var lastAssistantAudioAt = 0L
    private var lastFlushAt = 0L
    private var lastBargeInAt = 0L
    private var micOpen = true

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* silent */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ImageView>(R.id.menuButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<ImageView>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        micButton.setOnClickListener {
            if (sessionActive) stopSession() else startSession()
        }

        if (Prefs.apiKey(this) == null) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun startSession() {
        val key = Prefs.apiKey(this)
        if (key == null) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        sessionActive = true
        setMicActiveUI(true)

        player = AudioPlayer().also { it.start() }
        client = GeminiLiveClient(
            key,
            Prefs.modelId(this),
            Prefs.voice(this),
            Prefs.systemPrompt(this),
            this
        ).also { it.connect() }
    }

    private fun stopSession() {
        sessionActive = false
        setMicActiveUI(false)
        recorder?.stop()
        recorder = null
        client?.close()
        client = null
        player?.stop()
        player = null
        orbView.micBoost = 0f
        orbView.aiBoost = 0f
    }

    private fun setMicActiveUI(active: Boolean) {
        micButton.setImageResource(if (active) R.drawable.ic_stop else R.drawable.ic_mic)
        micButton.backgroundTintList = ColorStateList.valueOf(
            if (active) getColor(R.color.orb_purple) else android.graphics.Color.WHITE
        )
        micButton.imageTintList = ColorStateList.valueOf(
            if (active) android.graphics.Color.WHITE else getColor(R.color.text_dark)
        )
        if (active) {
            orbView.micBoost = 0f
            orbView.aiBoost = 0f
        }
    }

    /** Normalized RMS energy (0..1) of a PCM16 chunk. */
    private fun rms(bytes: ByteArray): Float {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            val s = ((hi shl 8) or lo).toShort().toDouble() / 32768.0
            sum += s * s
            n++
            i += 2
        }
        if (n == 0) return 0f
        return Math.sqrt(sum / n).toFloat()
    }

    // ---- GeminiLiveClient.Listener ----
    // All callbacks arrive on OkHttp threads → hop to the main thread for UI work.

    override fun onOpen() {
        // nothing to show
    }

    override fun onSetupComplete() {
        handler.post {
            micOpen = true
            recorder = AudioRecorder { chunk ->
                val now = SystemClock.elapsedRealtime()

                // Let the orb vibrate with the user's voice.
                val energy = rms(chunk)
                orbView.micBoost = (energy / 0.12f).coerceIn(0f, 1f)

                // While the assistant is speaking, our mic feed is closed to
                // prevent self-echo — but we still watch for the USER's voice.
                // If real speech is detected, we barge in: flush playback and
                // reopen the mic stream so the server hears the interruption.
                if (!micOpen) {
                    if (now - lastBargeInAt > 600 && energy > 0.04f) {
                        lastBargeInAt = now
                        micOpen = true
                        player?.flush()
                        android.util.Log.d("BargeIn", "user speech detected during assistant turn")
                    }
                    if (!micOpen) return@AudioRecorder
                }

                client?.sendAudio(chunk)
                val p = player
                if (p != null && now - lastFlushAt > 400) {
                    lastFlushAt = now
                    p.flush()
                }
            }.also { it.start() }
        }
    }

    override fun onUserText(text: String) {
        // text is intentionally not shown
    }

    override fun onAssistantText(text: String) {
        // text is intentionally not shown
    }

    override fun onAudio(bytes: ByteArray) {
        lastAssistantAudioAt = SystemClock.elapsedRealtime()
        micOpen = false
        // Vibrate the orb with the assistant's own voice + speed up its smoke.
        // A floor guarantees a clearly visible vibration even in quiet passages.
        val level = (rms(bytes) / 0.06f).coerceIn(0f, 1f)
        orbView.aiBoost = 0.50f + 0.50f * level
        player?.enqueue(bytes)
    }

    override fun onTurnComplete() {
        handler.post {
            micOpen = true
            orbView.micBoost = 0f
        }
    }

    override fun onError(msg: String) {
        handler.post { stopSession() }
    }

    override fun onClose(code: Int, reason: String) {
        if (sessionActive) handler.post { stopSession() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (sessionActive) stopSession()
    }
}
