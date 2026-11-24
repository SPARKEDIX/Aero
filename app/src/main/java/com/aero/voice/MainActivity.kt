package com.aero.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
    private val leftActions by lazy { findViewById<LinearLayout>(R.id.leftActions) }
    private val rightActions by lazy { findViewById<LinearLayout>(R.id.rightActions) }
    private val muteButton by lazy { findViewById<ImageButton>(R.id.muteButton) }
    private val cameraWindow by lazy { findViewById<View>(R.id.cameraWindow) }
    private val previewView by lazy { findViewById<PreviewView>(R.id.previewView) }
    private val cameraFlipButton by lazy { findViewById<ImageButton>(R.id.cameraFlipButton) }
    private val actionsRow by lazy { findViewById<View>(R.id.actionsRow) }
    private val settingsButton by lazy { findViewById<ImageView>(R.id.settingsButton) }

    private var micMuted = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraLive = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

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

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) toggleCameraLive()
        }

    private val docPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris?.forEach { handleDocument(it) }
        }

    private val screenPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                captureScreen(result.resultCode, result.data!!)
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

        // Expanded actions (visible only while the mic session is live).
        findViewById<ImageButton>(R.id.screenShareButton).setOnClickListener {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            screenPicker.launch(mgr.createScreenCaptureIntent())
        }
        findViewById<ImageButton>(R.id.liveButton).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                toggleCameraLive()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        cameraFlipButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            bindCamera()
        }
        findViewById<ImageButton>(R.id.cameraCloseButton).setOnClickListener {
            toggleCameraLive()
        }
        muteButton.setOnClickListener {
            micMuted = !micMuted
            muteButton.setImageResource(if (micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        }
        findViewById<ImageButton>(R.id.attachButton).setOnClickListener {
            docPicker.launch(
                arrayOf(
                    "application/pdf",
                    "image/*",
                    "text/plain",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )
            )
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
        closeCameraLive()
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
        orbView.onAiTurnEnd()
    }

    private fun setMicActiveUI(active: Boolean) {
        micButton.setImageResource(if (active) R.drawable.ic_stop else R.drawable.ic_mic)
        micButton.backgroundTintList = ColorStateList.valueOf(
            if (active) getColor(R.color.orb_purple) else getColor(R.color.bg_circle)
        )
        micButton.imageTintList = ColorStateList.valueOf(
            if (active) android.graphics.Color.WHITE else getColor(R.color.text_dark)
        )
        setActionsExpanded(active)
        if (active) {
            micMuted = false
            muteButton.setImageResource(R.drawable.ic_mic)
            orbView.micBoost = 0f
            orbView.aiBoost = 0f
        }
    }

    /** Side buttons fly out of the mic when a session starts, and back in when it ends. */
    private fun setActionsExpanded(expanded: Boolean) {
        for (group in listOf(leftActions, rightActions)) {
            if (expanded) {
                group.visibility = View.VISIBLE
                group.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .start()
            } else {
                group.animate()
                    .scaleX(0f).scaleY(0f)
                    .setDuration(160)
                    .withEndAction { group.visibility = View.GONE }
                    .start()
            }
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

                if (!micMuted) client?.sendAudio(chunk)
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
        orbView.onAiToolCall()
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

    // ---- Camera / screen / document vision (on-device Mirage OCR) ----

    private fun toggleCameraLive() {
        if (cameraLive) closeCameraLive() else openCameraLive()
    }

    private fun openCameraLive() {
        if (cameraLive) return
        cameraLive = true
        animateCameraWindow(true)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
                captureFrame()
                handler.post(captureRunnable)
            } catch (t: Throwable) {
                Log.e("AeroCamera", "setup failed", t)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun closeCameraLive() {
        if (!cameraLive) return
        cameraLive = false
        handler.removeCallbacks(captureRunnable)
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        animateCameraWindow(false)
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!cameraLive) return
            captureFrame()
            handler.postDelayed(this, 6_000L)
        }
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        try {
            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(320, 240))
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(android.util.Size(320, 240))
                .build()
            imageCapture = capture

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, capture)
        } catch (t: Throwable) {
            Log.e("AeroCamera", "bind failed", t)
        }
    }

    private fun captureFrame() {
        val capture = imageCapture ?: return
        try {
            capture.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bitmap = yuvToBitmap(image)
                            val baos = java.io.ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 65, baos)
                            bitmap.recycle()
                            client?.sendImage(baos.toByteArray())
                        } catch (t: Throwable) {
                            Log.e("AeroCamera", "frame encode failed", t)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("AeroCamera", "frame failed", exception)
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e("AeroCamera", "capture failed", t)
        }
    }

    /** Orb shrinks away and the rounded camera window pops in — and reverses. */
    private fun animateCameraWindow(open: Boolean) {
        if (open) {
            orbView.animate()
                .scaleX(0f).scaleY(0f).alpha(0f)
                .setDuration(260)
                .withEndAction { orbView.visibility = View.GONE }
                .start()
            actionsRow.animate().alpha(0f).setDuration(180)
                .withEndAction { actionsRow.visibility = View.GONE }.start()
            settingsButton.animate().alpha(0f).setDuration(180)
                .withEndAction { settingsButton.visibility = View.GONE }.start()
            cameraWindow.visibility = View.VISIBLE
            cameraWindow.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        } else {
            cameraWindow.animate()
                .scaleX(0f).scaleY(0f)
                .setDuration(220)
                .withEndAction { cameraWindow.visibility = View.GONE }
                .start()
            actionsRow.visibility = View.VISIBLE
            actionsRow.animate().alpha(1f).setDuration(220).start()
            settingsButton.visibility = View.VISIBLE
            settingsButton.animate().alpha(1f).setDuration(220).start()
            orbView.visibility = View.VISIBLE
            orbView.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(320)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
    }

    /** CameraX YUV_420_888 frame -> RGB bitmap for the on-device model. */
    private fun yuvToBitmap(image: ImageProxy): Bitmap {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val yPix = yPlane.pixelStride
        val uPix = uPlane.pixelStride
        val vPix = vPlane.pixelStride

        val out = IntArray(w * h)
        for (j in 0 until h) {
            val ys = ByteArray(yRow)
            yBuf.position(j * yRow)
            yBuf.get(ys)
            val us = ByteArray(uRow)
            uBuf.position((j / 2) * uRow)
            uBuf.get(us)
            val vs = ByteArray(vRow)
            vBuf.position((j / 2) * vRow)
            vBuf.get(vs)

            for (i in 0 until w) {
                val y = ys[i * yPix].toInt() and 0xFF
                val u = (us[(i / 2) * uPix].toInt() and 0xFF) - 128
                val v = (vs[(i / 2) * vPix].toInt() and 0xFF) - 128
                val r = (y + 1.402 * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344136 * u - 0.714136 * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772 * u).toInt().coerceIn(0, 255)
                out[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, w, 0, 0, w, h)
        return bitmap
    }

    @Suppress("MissingPermission")
    private fun captureScreen(code: Int, data: Intent) {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(code, data)
            val reader = ImageReader.newInstance(
                480, 800, android.graphics.PixelFormat.RGBA_8888, 2
            )
            val display = projection.createVirtualDisplay(
                "aero-screen", 480, 800, 320,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
            handler.postDelayed({
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val bitmap = imageToBitmap(image)
                    image.close()
                    val baos = java.io.ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 65, baos)
                    bitmap.recycle()
                    client?.sendImage(baos.toByteArray())
                }
                display.release()
                projection.stop()
                reader.close()
            }, 1200L)
        } catch (t: Throwable) {
            Log.e("AeroScreen", "capture failed", t)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buffer = plane.buffer
        val row = ByteArray(rowStride)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, rowStride)
            for (x in 0 until w) {
                val i = x * pixelStride
                val r = row[i].toInt() and 0xFF
                val g = row[i + 1].toInt() and 0xFF
                val b = row[i + 2].toInt() and 0xFF
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun handleDocument(uri: Uri) {
        searchScope.launch {
            try {
                val bitmap: Bitmap
                if (contentResolver.getType(uri) == "application/pdf") {
                    val fd = contentResolver.openFileDescriptor(uri, "r") ?: return@launch
                    val renderer = PdfRenderer(fd)
                    val page = renderer.openPage(0)
                    val b = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    b.eraseColor(android.graphics.Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap = b
                    page.close()
                    renderer.close()
                    fd.close()
                } else {
                    val stream = contentResolver.openInputStream(uri) ?: return@launch
                    bitmap = BitmapFactory.decodeStream(stream)
                    stream.close()
                }
                analyzeImage(bitmap, "Document")
            } catch (t: Throwable) {
                Log.e("AeroDoc", "read failed", t)
            }
        }
    }

    /** Runs the 22 MB on-device Mirage model and feeds the result to the conversation. */
    private fun analyzeImage(bitmap: Bitmap, label: String) {
        searchScope.launch {
            if (!Mirage.load(this@MainActivity)) {
                Log.e("Mirage", "model not loaded")
                return@launch
            }
            val text = Mirage.describe(bitmap)
            withContext(Dispatchers.Main) {
                if (text.isNotBlank()) {
                    client?.sendContextText("$label analysis: $text")
                }
            }
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
        searchScope.cancel()
    }

    companion object {
        /** Do not speak over the assistant: wait this long after its last audio chunk. */
        private const val SPEECH_GAP_MS = 3_000L
    }
}
