package com.aero.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
import android.media.projection.MediaProjectionManager
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
import kotlinx.coroutines.withTimeout
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
    private val miniOrb by lazy { findViewById<View>(R.id.miniOrb) }
    private val miniOrbView by lazy { findViewById<OrbView>(R.id.miniOrbView) }

    private var micMuted: Boolean
        get() = AeroState.micMuted
        set(value) { AeroState.micMuted = value }
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
    private var loudFrames = 0
    private var quietFrames = 0

    // ---- Internet access (background searches + queued tool responses) ----
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val searchOrchestrator = SearchOrchestrator()
    private val pendingResponses = ArrayDeque<PendingBatch>()
    // Only touched on the main thread (handler), so a plain HashMap is safe.
    private val toolJobs = HashMap<String, Job>()
    private var lastDequeuedAt = 0L
    private var pendingStartedAt = 0L

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
                Log.d("AeroScreen", "consent OK, starting projection service")
                // Android 14+ requires the MediaProjection to live inside a
                // foreground service of type mediaProjection.
                val intent = Intent(this, ScreenShareService::class.java).apply {
                    putExtra(ScreenShareService.EXTRA_CODE, result.resultCode)
                    putExtra(ScreenShareService.EXTRA_DATA, result.data)
                }
                try {
                    startForegroundService(intent)
                } catch (t: Throwable) {
                    Log.e("AeroScreen", "failed to start projection service", t)
                }
                updateScreenShareButton()
            } else {
                Log.w("AeroScreen", "consent cancelled result=${result.resultCode}")
            }
            // If the picker was opened from the floating orb, drop the user
            // back to the background when done, so they can keep using the orb.
            if (launchedFromOrb) {
                launchedFromOrb = false
                moveTaskToBack(true)
            }
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) {
                startFloatingOrb()
                if (pendingScreenShare) {
                    pendingScreenShare = false
                    launchScreenPicker()
                }
            }
        }

    private var pendingScreenShare = false

    /** True while the screen picker was opened from the floating orb. */
    private var launchedFromOrb = false

    /** Temporary holder for a notifications dump fetched during a tool call. */
    private var pendingNotificationsDump: String? = null

    /** Fired by the floating orb's close button to force a full exit. */
    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AeroActions.ACTION_EXIT_SESSION) {
                Log.d("AeroDismiss", "exit broadcast received")
                handler.post {
                    if (sessionActive) stopSession()
                    stopProjection()
                    stopFloatingOrb()
                }
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* silent */ }

    private val phonePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> /* silent */ }

    /** Ensures CALL_PHONE + READ_CONTACTS are granted. */
    private fun ensurePhonePermissions() {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) missing.add(Manifest.permission.CALL_PHONE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) missing.add(Manifest.permission.READ_CONTACTS)
        if (missing.isNotEmpty()) phonePermissionsLauncher.launch(missing.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        orbView.setMood(Prefs.mood(this))

        findViewById<ImageView>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        micButton.setOnClickListener {
            if (sessionActive) {
                stopSession()
                stopProjection()
                stopFloatingOrb()
            } else {
                startSession()
            }
        }

        // Expanded actions (visible only while the mic session is live).
        findViewById<ImageButton>(R.id.screenShareButton).setOnClickListener {
            launchScreenPicker()
        }
        updateScreenShareButton()
        updateMuteButton()
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
            updateMuteButton()
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

        // Hotword listening ("Hey Aero") only runs when the user opted in.
        // Listen for the floating orb's "close" full-exit broadcast.
        registerReceiver(
            exitReceiver,
            IntentFilter(AeroActions.ACTION_EXIT_SESSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Launched from the floating orb to (re)start screen sharing.
        if (intent.action == MainActivity.ACTION_START_SCREEN) {
            launchedFromOrb = true
            launchScreenPicker()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh status indicators (share on/off, mute) — state may have
        // changed via the floating orb or the consent flow.
        updateScreenShareButton()
        updateMuteButton()
        // If this resume was caused by the assistant proxy (invisible) wake,
        // keep the floating orb alive. Otherwise hide it — the orb only lives
        // while the app is backgrounded.
        if (AeroState.consumeProxyWake()) {
            return
        }
        stopFloatingOrb()
        miniOrb.visibility = View.GONE
        // Settings may have changed the mood while we were away.
        orbView.setMood(Prefs.mood(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            MainActivity.ACTION_START_SCREEN -> {
                launchedFromOrb = true
                launchScreenPicker()
            }
            FloatingOrbService.ACTION_OPEN_FILES -> {
                docPicker.launch(
                    arrayOf(
                        "application/pdf", "image/*", "text/plain",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    )
                )
            }
            FloatingOrbService.ACTION_OPEN_CAMERA -> {
                stopFloatingOrb()
                stopProjection()
                val lens = intent.getStringExtra(EXTRA_LENS)
                if (lens != null) {
                    lensFacing = if (lens.equals("front", ignoreCase = true)) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                }
                val turnOn = intent.getBooleanExtra(EXTRA_CAMERA_ON, true)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    if (turnOn) openCameraLive() else closeCameraLive()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            FloatingOrbService.ACTION_STOP_SCREEN -> {
                stopProjection()
                stopFloatingOrb()
            }
        }
    }

    private fun launchScreenPicker() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        Log.d("AeroScreen", "launching consent")
        screenPicker.launch(mgr.createScreenCaptureIntent())
    }

    private fun startFloatingOrb() {
        // Overlay permission is required to draw on top of other apps;
        // without it, addView crashes. Bail out silently instead.
        if (!Settings.canDrawOverlays(this)) {
            Log.w("AeroOverlay", "overlay permission missing, skipping floating orb")
            return
        }
        try {
            startForegroundService(Intent(this, FloatingOrbService::class.java))
        } catch (t: Throwable) {
            Log.e("AeroOverlay", "start failed", t)
        }
    }

    private fun stopFloatingOrb() {
        stopService(Intent(this, FloatingOrbService::class.java))
    }

    private fun stopProjection() {
        stopService(Intent(this, ScreenShareService::class.java)) // stops MediaProjectionHolder
        stopFloatingOrb() // removes overlay
        miniOrb.visibility = View.GONE
        updateScreenShareButton()
    }

    override fun onStop() {
        super.onStop()
        // Whenever the app goes to the background, show the floating ball so the
        // user can keep talking to the assistant — regardless of whether screen
        // sharing is currently active. Use the GLOBAL session flag: with
        // multiple activity instances (relaunch) the local field can be stale.
        if (AeroState.sessionActive) startFloatingOrb()
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
        AeroState.sessionActive = true
        AeroState.micMuted = false
        KeepAliveService.start(this)
        setMicActiveUI(true)
        handler.post(drainRunnable)

        player = AudioPlayer().also { it.start() }
        val mood = MoodPalette.forKey(Prefs.mood(this))
        client = GeminiLiveClient(
            key,
            Prefs.modelId(this),
            Prefs.voice(this),
            AeroPrompt.build(this, mood.instruction),
            this
        ).also { it.connect() }
        // MUST set after client is created so screen-share frames can reach it.
        AeroState.liveClient = client
    }

    private fun stopSession() {
        sessionActive = false
        AeroState.sessionActive = false
        AeroState.micBoost = 0f
        AeroState.aiBoost = 0f
        AeroState.micMuted = false
        AeroState.liveClient = null
        KeepAliveService.stop(this)
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
        AeroUi.applyAppButton(
            micButton,
            if (active) AeroUi.State.ACTIVE else AeroUi.State.NORMAL
        )
        setActionsExpanded(active)
        if (active) {
            micMuted = false
            muteButton.setImageResource(R.drawable.ic_mic)
            orbView.micBoost = 0f
            orbView.aiBoost = 0f
        }
        updateMuteButton()
        updateScreenShareButton()
    }

    /** Mute button turns red while the mic is muted. */
    private fun updateMuteButton() {
        AeroUi.applyAppButton(
            muteButton,
            if (micMuted) AeroUi.State.DANGER else AeroUi.State.NORMAL
        )
    }

    /** Screen-share button turns red while sharing is OFF. */
    private fun updateScreenShareButton() {
        AeroUi.applyAppButton(
            findViewById<ImageButton>(R.id.screenShareButton),
            if (MediaProjectionHolder.isRunning) AeroUi.State.NORMAL else AeroUi.State.DANGER
        )
    }

    /** Side buttons pop out of the mic like bubbles and slide back in on stop. */
    private fun setActionsExpanded(expanded: Boolean) {
        val groups = listOf(leftActions, rightActions)
        groups.forEachIndexed { gi, group ->
            val children = (0 until group.childCount).map { group.getChildAt(it) }
            if (expanded) {
                group.visibility = View.VISIBLE
                group.alpha = 1f
                group.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(OvershootInterpolator(1.35f))
                    .start()
                children.forEachIndexed { i, child ->
                    child.alpha = 0f
                    child.scaleX = 0.4f
                    child.scaleY = 0.4f
                    child.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f)
                        .setStartDelay((i * 35L) + (gi * 20L))
                        .setDuration(240)
                        .setInterpolator(OvershootInterpolator(1.4f))
                        .start()
                }
            } else {
                children.forEach { child ->
                    child.animate()
                        .alpha(0f).scaleX(0.4f).scaleY(0.4f)
                        .setDuration(140)
                        .setInterpolator(android.view.animation.AnticipateInterpolator(1.4f))
                        .start()
                }
                group.animate()
                    .scaleX(0f).scaleY(0f)
                    .setStartDelay(30L)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.AnticipateInterpolator(1.3f))
                    .withEndAction { group.visibility = View.INVISIBLE }
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
                AeroState.micBoost = orbView.micBoost

                // While the assistant is speaking, our mic feed is closed to
                // prevent self-echo — but we still watch for the USER's voice.
                // Only REAL speech (2 consecutive loud frames) reopens the mic —
                // a single cough / chair-squeak won't interrupt the assistant.
                if (!micOpen) {
                    if (energy > 0.055f) loudFrames++ else loudFrames = 0
                    if (loudFrames >= 2 && now - lastBargeInAt > 400) {
                        lastBargeInAt = now
                        micOpen = true
                        loudFrames = 0
                        player?.flush()
                        android.util.Log.d("BargeIn", "user speech detected during assistant turn")
                    }
                    if (!micOpen) return@AudioRecorder
                } else {
                    // Auto-close the mic after ~3s of silence so ambient noise
                    // doesn't leak into the next assistant turn.
                    if (energy < 0.012f) quietFrames++ else quietFrames = 0
                    if (quietFrames > 30) {
                        micOpen = false
                        quietFrames = 0
                    }
                }

                if (!AeroState.micMuted) client?.sendAudio(chunk)
                val p = player
                if (p != null && now - lastFlushAt > 400) {
                    lastFlushAt = now
                    p.flush()
                }
            }.also { it.start() }
        }
    }

    override fun onUserText(text: String) {
        // Spoken dismiss intents ("tu jaa", "bye", …) close the session + orb.
        if (DismissIntents.matches(text)) {
            Log.d("AeroDismiss", "dismiss intent heard: \"$text\"")
            handler.post {
                stopSession()
                stopProjection()
                stopFloatingOrb()
            }
            return
        }
        // App-control voice commands ("open whatsapp", "clear recents", "call …").
        val cmd = AppControl.parse(text)
        if (cmd != null) {
            Log.d("AeroApp", "app command heard: \"$text\" -> $cmd")
            handler.post {
                // Call/message commands need phone + contacts permissions.
                when (cmd) {
                    is AppControl.Command.Call,
                    is AppControl.Command.WhatsAppMessage,
                    is AppControl.Command.WhatsAppCall -> ensurePhonePermissions()
                    else -> {}
                }
                val spoken = AppControl.run(this, cmd)
                client?.sendContextText("[Action performed] $spoken")
            }
        } else {
            // Internet-style request → non-blocking background query. The model
            // keeps talking; results are injected when ready.
            BackgroundQuery.maybeStart(text)
        }
    }

    override fun onAssistantText(text: String) {
        // text is intentionally not shown
    }

    override fun onAudio(bytes: ByteArray) {
        lastAssistantAudioAt = SystemClock.elapsedRealtime()
        micOpen = false
        // Vibrate the orb with the assistant's own voice + speed up its smoke.
        // A floor guarantees a clearly visible vibration even in quiet passages,
        // and aiSpeaking keeps the ball vibrating for the WHOLE turn (even
        // between sentences), not just while a loud frame arrives.
        val level = (rms(bytes) / 0.06f).coerceIn(0f, 1f)
        AeroState.aiSpeaking = true
        orbView.onAiAudio(level)
        AeroState.aiBoost = 0.55f + 0.45f * level
        player?.enqueue(bytes)
    }

    override fun onTurnComplete() {
        handler.post {
            micOpen = true
            orbView.micBoost = 0f
            AeroState.aiSpeaking = false
            AeroState.aiBoost = 0f
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
                    when (call.name) {
                        "web_search" -> {
                            val query = call.args.optString("query").trim()
                            val result = if (query.isEmpty()) {
                                JSONObject().put("error", "empty search query")
                            } else {
                                try {
                                    // Hard timeout so a slow/hanging source never
                                    // leaves the model waiting forever.
                                    withTimeout(10_000L) {
                                        searchOrchestrator.search(query).toJson()
                                    }
                                } catch (t: Throwable) {
                                    JSONObject().put("error", "search timed out or failed: ${t.message}")
                                }
                            }
                            ToolResponse(call.id, call.name, result)
                        }
                        "open_app", "close_app" -> {
                            val appName = call.args.optString("app_name").trim()
                            val spoken = if (call.name == "open_app") {
                                AppControl.openApp(this@MainActivity, appName)
                            } else {
                                AppControl.closeApp(this@MainActivity, appName)
                            }
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "clear_recents" -> {
                            val spoken = AppControl.clearRecents(this@MainActivity)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "make_phone_call" -> {
                            val target = call.args.optString("target").trim()
                            val spoken = DeviceActions.call(this@MainActivity, target)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "end_call" -> {
                            val spoken = DeviceActions.endCall(this@MainActivity)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "send_whatsapp_message" -> {
                            val target = call.args.optString("target").trim()
                            val message = call.args.optString("message").trim()
                            val spoken = DeviceActions.whatsAppMessage(this@MainActivity, target, message)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "whatsapp_call" -> {
                            val target = call.args.optString("target").trim()
                            val video = call.args.optBoolean("video", false)
                            val spoken = DeviceActions.whatsAppCall(this@MainActivity, target, video)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "app_click" -> {
                            val text = call.args.optString("text").trim()
                            val spoken = DeviceActions.clickByText(this@MainActivity, text)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "app_type" -> {
                            val text = call.args.optString("text").trim()
                            val spoken = DeviceActions.typeText(this@MainActivity, text)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "go_home" -> {
                            val spoken = DeviceActions.goHome(this@MainActivity)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "go_back" -> {
                            val spoken = DeviceActions.goBack(this@MainActivity)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "camera_live" -> {
                            val lens = call.args.optString("lens", "back").trim()
                            val spoken = DeviceActions.cameraLive(this@MainActivity, lens)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "camera_stop" -> {
                            val spoken = DeviceActions.cameraStop(this@MainActivity)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "set_volume" -> {
                            val percent = call.args.optInt("percent", 50)
                            val spoken = DeviceControl.volume(this@MainActivity, percent)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "read_notifications" -> {
                            AeroAccessibilityService.requestNotificationsDump { dump ->
                                pendingNotificationsDump = dump
                            }
                            // Feed the dump after this batch responds.
                            handler.postDelayed({
                                pendingNotificationsDump?.let {
                                    client?.sendContextText("Notifications: $it")
                                    pendingNotificationsDump = null
                                }
                            }, 1500L)
                            ToolResponse(call.id, call.name, JSONObject().put("status", "reading notifications"))
                        }
                        "clear_notifications" -> {
                            val spoken = if (AeroAccessibilityService.requestClearNotifications()) {
                                "clearing notifications"
                            } else {
                                "accessibility not enabled"
                            }
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "scroll_down" -> {
                            val spoken = if (AeroAccessibilityService.requestScroll(true)) "scrolling down" else "accessibility not enabled"
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "scroll_up" -> {
                            val spoken = if (AeroAccessibilityService.requestScroll(false)) "scrolling up" else "accessibility not enabled"
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "torch_set" -> {
                            val on = call.args.optBoolean("on", true)
                            val spoken = DeviceControl.torch(this@MainActivity, on)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "bluetooth_set" -> {
                            val on = call.args.optBoolean("on", true)
                            val spoken = DeviceControl.bluetooth(this@MainActivity, on)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "set_brightness" -> {
                            val percent = call.args.optInt("percent", 50)
                            val spoken = DeviceControl.brightness(this@MainActivity, percent * 255 / 100)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "take_screenshot" -> {
                            val spoken = DeviceControl.screenshot(this@MainActivity)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "open_wifi_settings" -> {
                            val spoken = DeviceControl.openWifiSettings(this@MainActivity)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "open_data_settings" -> {
                            val spoken = DeviceControl.openDataSettings(this@MainActivity)
                            ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                        }
                        "remember_fact", "forget_fact", "recall_memory",
                        "set_reminder", "call_me", "get_current_time",
                        "manage_reminders", "youtube_control", "screen_lock" -> {
                            AeroTools.handle(this@MainActivity, call)
                                ?: ToolResponse(
                                    call.id, call.name,
                                    JSONObject().put("error", "unknown tool ${call.name}")
                                )
                        }
                        else -> {
                            ToolResponse(
                                call.id, call.name,
                                JSONObject().put("error", "unknown tool ${call.name}")
                            )
                        }
                    }
                }
            }.awaitAll()

            withContext(Dispatchers.Main) {
                if (sessionActive) {
                    pendingResponses.addLast(PendingBatch(responses))
                    if (pendingResponses.size == 1) {
                        pendingStartedAt = SystemClock.elapsedRealtime()
                    }
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

        // Hard safety: if a tool response has been waiting more than 8s,
        // send it regardless of speech gaps (never lose it silently).
        val force = pendingStartedAt > 0 && now - pendingStartedAt > 8_000L
        if (!force) {
            if (now - lastAssistantAudioAt < SPEECH_GAP_MS) return
            if (now - lastDequeuedAt < SPEECH_GAP_MS) return
        }

        val batch = pendingResponses.removeFirst()
        lastDequeuedAt = now
        pendingStartedAt = if (pendingResponses.isEmpty()) 0L else now
        Log.d(
            "Search",
            "sending ${batch.responses.size} tool response(s) after " +
                "${now - lastAssistantAudioAt} ms of silence (force=$force)"
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
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
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
        val capture = imageCapture
        Log.d("AeroCamera", "captureFrame called, capture=$capture")
        if (capture == null) return
        try {
            capture.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        Log.d("AeroCamera", "frame captured ${image.width}x${image.height} fmt=${image.format}")
                        try {
                            val bitmap = frameToBitmap(image)
                            val scaled = scaleDown(bitmap, 640)
                            val baos = java.io.ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                            if (scaled !== bitmap) scaled.recycle()
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
            setActionsExpanded(sessionActive)
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

    /** CameraX frame -> bitmap. Handles both JPEG (single plane) and YUV_420_888. */
    private fun scaleDown(source: Bitmap, maxSide: Int): Bitmap {
        if (maxOf(source.width, source.height) <= maxSide) return source
        val scale = maxSide.toFloat() / maxOf(source.width, source.height)
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    /** CameraX frame -> bitmap. Handles both JPEG (single plane) and YUV_420_888. */
    private fun frameToBitmap(image: ImageProxy): Bitmap {
        if (image.format == android.graphics.ImageFormat.JPEG || image.planes.size < 3) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            return bitmap
        }
        return yuvToBitmap(image)
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

    private var screenFrameCount = 0

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
        try {
            unregisterReceiver(exitReceiver)
        } catch (_: Exception) {
        }
        if (sessionActive) stopSession()
        stopProjection()
        stopFloatingOrb()
        searchScope.cancel()
    }

    companion object {
        const val ACTION_START_SCREEN = "com.aero.voice.START_SCREEN"
        const val EXTRA_LENS = "camera_lens"
        const val EXTRA_CAMERA_ON = "camera_on"

        /** Do not speak over the assistant: wait this long after its last audio chunk. */
        private const val SPEECH_GAP_MS = 3_000L
    }
}
