package com.aero.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aero.voice.search.SearchOrchestrator
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.ArrayDeque

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

    // ---- Internet access (background searches + queued tool responses) ----
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val searchOrchestrator = SearchOrchestrator()
    private val pendingResponses = ArrayDeque<PendingBatch>()
    // Only touched on the main thread (handler), so a plain HashMap is safe.
    private val toolJobs = HashMap<String, Job>()
    private var lastDequeuedAt = 0L

    private data class PendingBatch(val responses: List<ToolResponse>)

    /** Drains queued search results only after the assistant has been quiet for 3s. */
    private val drainRunnable = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            drainQueue()
            handler.postDelayed(this, 400L)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* silent */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        orbView.setMood(Prefs.mood(this))

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

    override fun onResume() {
        super.onResume()
        // Settings may have changed the mood while we were away.
        orbView.setMood(Prefs.mood(this))
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
        handler.post(drainRunnable)

        player = AudioPlayer().also { it.start() }
        val mood = MoodPalette.forKey(Prefs.mood(this))
        client = GeminiLiveClient(
            key,
            Prefs.modelId(this),
            Prefs.voice(this),
            mood.instruction,
            this
        ).also { it.connect() }
    }

    private fun stopSession() {
        sessionActive = false
        handler.removeCallbacks(drainRunnable)
        pendingResponses.clear()
        toolJobs.values.toSet().forEach { it.cancel() }
        toolJobs.clear()
        searchScope.coroutineContext.cancelChildren()
        lastDequeuedAt = 0L

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
            if (active) getColor(R.color.orb_purple) else getColor(R.color.bg_circle)
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
        orbView.onAiAudio(level)
        player?.enqueue(bytes)
    }

    override fun onTurnComplete() {
        handler.post {
            micOpen = true
            orbView.micBoost = 0f
            orbView.onAiTurnEnd()
        }
    }

    // ---- Internet access ----

    override fun onToolCall(calls: List<ToolCall>) {
        if (calls.isEmpty()) return

        val job = searchScope.launch(start = CoroutineStart.LAZY) {
            // All calls in one toolCall batch run in parallel ("multi-instance"),
            // then their responses are sent together.
            val responses = calls.map { call ->
                async {
                    val query = call.args.optString("query").trim()
                    val result = if (query.isEmpty()) {
                        JSONObject().put("error", "empty search query")
                    } else {
                        try {
                            searchOrchestrator.search(query).toJson()
                        } catch (t: Throwable) {
                            JSONObject().put("error", t.message ?: "search failed")
                        }
                    }
                    ToolResponse(call.id, call.name, result)
                }
            }.awaitAll()

            withContext(Dispatchers.Main) {
                if (sessionActive) {
                    pendingResponses.addLast(PendingBatch(responses))
                    Log.d(
                        "Search",
                        "queued ${responses.size} tool response(s), pending=${pendingResponses.size}"
                    )
                }
            }
        }

        handler.post {
            calls.forEach { toolJobs[it.id] = job }
        }
        job.invokeOnCompletion {
            handler.post {
                calls.forEach { call ->
                    if (toolJobs[call.id] === job) toolJobs.remove(call.id)
                }
            }
        }
        job.start()
    }

    override fun onToolCallCancellation(ids: List<String>) {
        if (ids.isEmpty()) return
        handler.post {
            ids.forEach { id -> toolJobs.remove(id)?.cancel() }
            val toRemove = ArrayList<PendingBatch>()
            for (batch in pendingResponses) {
                for (r in batch.responses) {
                    if (ids.contains(r.id)) {
                        toRemove.add(batch)
                        break
                    }
                }
            }
            pendingResponses.removeAll(toRemove)
        }
    }

    /**
     * Sends the next queued search result only when the assistant has been quiet
     * for [SPEECH_GAP_MS]. Results that arrive mid-answer wait their turn instead
     * of interrupting, then follow one by one with the same gap.
     */
    private fun drainQueue() {
        if (pendingResponses.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAssistantAudioAt < SPEECH_GAP_MS) return
        if (now - lastDequeuedAt < SPEECH_GAP_MS) return

        val batch = pendingResponses.removeFirst()
        lastDequeuedAt = now
        Log.d(
            "Search",
            "sending ${batch.responses.size} tool response(s) after " +
                "${now - lastAssistantAudioAt} ms of silence"
        )
        client?.sendToolResponses(batch.responses)
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
        searchScope.cancel()
    }

    companion object {
        /** Do not speak over the assistant: wait this long after its last audio chunk. */
        private const val SPEECH_GAP_MS = 3_000L
    }
}
