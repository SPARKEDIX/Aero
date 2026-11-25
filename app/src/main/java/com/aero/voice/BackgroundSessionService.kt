package com.aero.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import org.json.JSONObject

/**
 * Invisible voice session started by the hotword listener (or an intent).
 *
 * Runs the same pipeline as MainActivity's session — AudioRecorder →
 * GeminiLiveClient → AudioPlayer — but with zero UI. It also hands the client
 * to [AeroState.liveClient] so an already-active screen share keeps feeding
 * frames through it. Spoken dismiss intents ("tu jaa", "bye", …) end the
 * session and close the floating orb.
 */
class BackgroundSessionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var client: GeminiLiveClient? = null
    private var recorder: AudioRecorder? = null
    private var player: AudioPlayer? = null
    private var micOpen = false
    private var lastBargeInAt = 0L
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            started = true
            startSession()
        }
        return START_STICKY
    }

    private fun startSession() {
        val key = Prefs.apiKey(this) ?: run {
            Log.w(TAG, "no API key")
            stopSelf()
            return
        }
        Log.d(TAG, "background session starting")
        AeroState.sessionActive = true
        AeroState.micMuted = false

        player = AudioPlayer().also { it.start() }
        val mood = MoodPalette.forKey(Prefs.mood(this))
        client = GeminiLiveClient(
            key,
            Prefs.modelId(this),
            Prefs.voice(this),
            AeroPrompt.build(this, mood.instruction),
            object : GeminiLiveClient.Listener {
                override fun onOpen() {}
                override fun onSetupComplete() {
                    handler.post {
                        micOpen = true
                        recorder = AudioRecorder { chunk ->
                            val now = SystemClock.elapsedRealtime()
                            val energy = rms(chunk)
                            AeroState.micBoost = (energy / 0.12f).coerceIn(0f, 1f)
                            if (!micOpen) {
                                if (now - lastBargeInAt > 600 && energy > 0.04f) {
                                    lastBargeInAt = now
                                    micOpen = true
                                    player?.flush()
                                }
                                if (!micOpen) return@AudioRecorder
                            }
                            if (!AeroState.micMuted) client?.sendAudio(chunk)
                            player?.flush()
                        }.also { it.start() }
                    }
                }
                override fun onUserText(text: String) {
                    if (DismissIntents.matches(text)) {
                        Log.d(TAG, "dismiss intent heard: \"$text\"")
                        handler.post { dismiss() }
                        return
                    }
                    // App-control voice commands ("open whatsapp", "clear recents").
                    val cmd = AppControl.parse(text)
                    if (cmd != null) {
                        Log.d(TAG, "app command heard: \"$text\" -> $cmd")
                        val spoken = AppControl.run(this@BackgroundSessionService, cmd)
                        // Let the model narrate the result.
                        client?.sendContextText("[Action performed] $spoken")
                    }
                }
                override fun onAssistantText(text: String) {}
                override fun onAudio(bytes: ByteArray) {
                    micOpen = false
                    AeroState.aiBoost = 0.5f + 0.5f * (rms(bytes) / 0.06f).coerceIn(0f, 1f)
                    player?.enqueue(bytes)
                }
                override fun onTurnComplete() {
                    handler.post {
                        micOpen = true
                        AeroState.micBoost = 0f
                    }
                }
                override fun onError(message: String) {
                    Log.w(TAG, "session error: $message")
                }
                override fun onToolCall(calls: List<ToolCall>) {
                    val responses = calls.map { call ->
                        when (call.name) {
                            "open_app", "close_app" -> {
                                val appName = call.args.optString("app_name").trim()
                                val spoken = if (call.name == "open_app") {
                                    AppControl.openApp(this@BackgroundSessionService, appName)
                                } else {
                                    AppControl.closeApp(this@BackgroundSessionService, appName)
                                }
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "clear_recents" -> {
                                val spoken = AppControl.clearRecents(this@BackgroundSessionService)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "make_phone_call" -> {
                                val target = call.args.optString("target").trim()
                                val spoken = DeviceActions.call(this@BackgroundSessionService, target)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "end_call" -> {
                                val spoken = DeviceActions.endCall(this@BackgroundSessionService)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "send_whatsapp_message" -> {
                                val target = call.args.optString("target").trim()
                                val message = call.args.optString("message").trim()
                                val spoken = DeviceActions.whatsAppMessage(this@BackgroundSessionService, target, message)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "whatsapp_call" -> {
                                val target = call.args.optString("target").trim()
                                val video = call.args.optBoolean("video", false)
                                val spoken = DeviceActions.whatsAppCall(this@BackgroundSessionService, target, video)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "app_click" -> {
                                val text = call.args.optString("text").trim()
                                val spoken = DeviceActions.clickByText(this@BackgroundSessionService, text)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "app_type" -> {
                                val text = call.args.optString("text").trim()
                                val spoken = DeviceActions.typeText(this@BackgroundSessionService, text)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "go_home" -> {
                                val spoken = DeviceActions.goHome(this@BackgroundSessionService)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "go_back" -> {
                                val spoken = DeviceActions.goBack(this@BackgroundSessionService)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "camera_live" -> {
                                val lens = call.args.optString("lens", "back").trim()
                                val spoken = DeviceActions.cameraLive(this@BackgroundSessionService, lens)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "camera_stop" -> {
                                val spoken = DeviceActions.cameraStop(this@BackgroundSessionService)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "set_volume" -> {
                                val percent = call.args.optInt("percent", 50)
                                val spoken = DeviceControl.volume(this@BackgroundSessionService, percent)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "read_notifications" -> {
                                AeroAccessibilityService.requestNotificationsDump { dump ->
                                    client?.sendContextText("Notifications: $dump")
                                }
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
                                val spoken = DeviceControl.torch(this@BackgroundSessionService, on)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "bluetooth_set" -> {
                                val on = call.args.optBoolean("on", true)
                                val spoken = DeviceControl.bluetooth(this@BackgroundSessionService, on)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "set_brightness" -> {
                                val percent = call.args.optInt("percent", 50)
                                val spoken = DeviceControl.brightness(this@BackgroundSessionService, percent * 255 / 100)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "take_screenshot" -> {
                                val spoken = DeviceControl.screenshot(this@BackgroundSessionService)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "open_wifi_settings" -> {
                                val spoken = DeviceControl.openWifiSettings(this@BackgroundSessionService)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "open_data_settings" -> {
                                val spoken = DeviceControl.openDataSettings(this@BackgroundSessionService)
                                ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                            }
                            "remember_fact", "forget_fact", "recall_memory",
                            "set_reminder", "call_me", "get_current_time",
                            "manage_reminders", "youtube_control" -> {
                                AeroTools.handle(this@BackgroundSessionService, call)
                                    ?: ToolResponse(
                                        call.id, call.name,
                                        JSONObject().put("error", "unknown tool ${call.name}")
                                    )
                            }
                            "web_search" -> ToolResponse(
                                call.id, call.name,
                                JSONObject().put("error", "web search not available in background session")
                            )
                            else -> ToolResponse(
                                call.id, call.name,
                                JSONObject().put("error", "unknown tool ${call.name}")
                            )
                        }
                    }
                    client?.sendToolResponses(responses)
                }
                override fun onClose(code: Int, reason: String) {
                    handler.post {
                        if (intentionalClose) {
                            dismiss()
                        } else {
                            // Unexpected WebSocket close → don't kill the session;
                            // try to reconnect so the app doesn't die on a
                            // network blip.
                            Log.w(TAG, "unexpected close ($code/$reason) — reconnecting")
                            reconnect()
                        }
                    }
                }
            }
        ).also { it.connect() }
        // Screen-share frames (MediaProjectionHolder) now go through this client.
        AeroState.liveClient = client
        // Keep process alive under aggressive OEM memory managers.
        KeepAliveService.start(this)
    }

    private fun dismiss() {
        Log.d(TAG, "dismissing background session")
        intentionalClose = true
        recorder?.stop()
        recorder = null
        player?.stop()
        player = null
        client?.close()
        client = null
        AeroState.liveClient = null
        AeroState.sessionActive = false
        AeroState.micBoost = 0f
        AeroState.aiBoost = 0f
        KeepAliveService.stop(this)
        FloatingOrbService.hide(this)
        stopSelf()
    }

    private var reconnectAttempts = 0
    private var intentionalClose = false
    private val reconnectJob = object : Runnable {
        override fun run() {
            if (intentionalClose) return
            if (reconnectAttempts >= 3) {
                dismiss()
                return
            }
            reconnectAttempts++
            Log.d(TAG, "reconnect attempt #$reconnectAttempts")
            val key = Prefs.apiKey(this@BackgroundSessionService) ?: run {
                dismiss(); return
            }
            val mood = MoodPalette.forKey(Prefs.mood(this@BackgroundSessionService))
            val newClient = GeminiLiveClient(
                key,
                Prefs.modelId(this@BackgroundSessionService),
                Prefs.voice(this@BackgroundSessionService),
                AeroPrompt.build(this@BackgroundSessionService, mood.instruction),
                object : GeminiLiveClient.Listener {
                    override fun onOpen() {}
                    override fun onSetupComplete() {
                        handler.post {
                            micOpen = true
                            if (recorder == null) {
                                recorder = AudioRecorder { chunk ->
                                    val now = SystemClock.elapsedRealtime()
                                    val energy = rms(chunk)
                                    AeroState.micBoost = (energy / 0.12f).coerceIn(0f, 1f)
                                    if (!micOpen) {
                                        if (now - lastBargeInAt > 600 && energy > 0.04f) {
                                            lastBargeInAt = now
                                            micOpen = true
                                            player?.flush()
                                        }
                                        if (!micOpen) return@AudioRecorder
                                    }
                                    if (!AeroState.micMuted) client?.sendAudio(chunk)
                                    player?.flush()
                                }.also { it.start() }
                            }
                        }
                    }
                    override fun onUserText(text: String) {
                        if (DismissIntents.matches(text)) {
                            handler.post { dismiss() }
                            return
                        }
                        val cmd = AppControl.parse(text)
                        if (cmd != null) {
                            val spoken = AppControl.run(this@BackgroundSessionService, cmd)
                            client?.sendContextText("[Action performed] $spoken")
                        }
                    }
                    override fun onAssistantText(text: String) {}
                    override fun onAudio(bytes: ByteArray) {
                        micOpen = false
                        AeroState.aiBoost = 0.5f + 0.5f * (rms(bytes) / 0.06f).coerceIn(0f, 1f)
                        player?.enqueue(bytes)
                    }
                    override fun onTurnComplete() {
                        handler.post {
                            micOpen = true
                            AeroState.micBoost = 0f
                        }
                    }
                    override fun onError(message: String) {
                        Log.w(TAG, "session error: $message")
                    }
                    override fun onToolCall(calls: List<ToolCall>) {
                        val responses = calls.map { call ->
                            when (call.name) {
                                "open_app", "close_app" -> {
                                    val appName = call.args.optString("app_name").trim()
                                    val spoken = if (call.name == "open_app") {
                                        AppControl.openApp(this@BackgroundSessionService, appName)
                                    } else {
                                        AppControl.closeApp(this@BackgroundSessionService, appName)
                                    }
                                    ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                                }
                                "clear_recents" -> {
                                    val spoken = AppControl.clearRecents(this@BackgroundSessionService)
                                    ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                                }
                                "make_phone_call" -> {
                                    val target = call.args.optString("target").trim()
                                    val spoken = DeviceActions.call(this@BackgroundSessionService, target)
                                    ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                                }
                                "end_call" -> {
                                    val spoken = DeviceActions.endCall(this@BackgroundSessionService)
                                    ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                                }
                                "send_whatsapp_message" -> {
                                    val target = call.args.optString("target").trim()
                                    val message = call.args.optString("message").trim()
                                    val spoken = DeviceActions.whatsAppMessage(this@BackgroundSessionService, target, message)
                                    ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                                }
                                "whatsapp_call" -> {
                                    val target = call.args.optString("target").trim()
                                    val video = call.args.optBoolean("video", false)
                                    val spoken = DeviceActions.whatsAppCall(this@BackgroundSessionService, target, video)
                                    ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                                }
                                "app_click" -> {
                                    val text = call.args.optString("text").trim()
                                    val spoken = DeviceActions.clickByText(this@BackgroundSessionService, text)
                                    ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                                }
                                "app_type" -> {
                                    val text = call.args.optString("text").trim()
                                    val spoken = DeviceActions.typeText(this@BackgroundSessionService, text)
                                    ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                                }
                                "go_home" -> {
                                    val spoken = DeviceActions.goHome(this@BackgroundSessionService)
                                    ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                                }
                                "go_back" -> {
                                    val spoken = DeviceActions.goBack(this@BackgroundSessionService)
                                    ToolResponse(call.id, call.name, JSONObject().put("status", spoken))
                                }
                                "web_search" -> ToolResponse(
                                    call.id, call.name,
                                    JSONObject().put("error", "web search not available in background session")
                                )
                                else -> ToolResponse(
                                    call.id, call.name,
                                    JSONObject().put("error", "unknown tool ${call.name}")
                                )
                            }
                        }
                        client?.sendToolResponses(responses)
                    }
                    override fun onClose(code: Int, reason: String) {                        handler.post {
                            if (intentionalClose) dismiss() else reconnect()
                        }
                    }
                }
            )
            client?.close()
            client = newClient
            AeroState.liveClient = newClient
            newClient.connect()
        }
    }

    private fun reconnect() {
        handler.removeCallbacks(reconnectJob)
        handler.postDelayed(reconnectJob, 1500L)
    }

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

    private fun buildNotification(): Notification {
        val channelId = "aero_background"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Aero assistant", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Aero is talking")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (started) {
            recorder?.stop()
            player?.stop()
            client?.close()
            if (AeroState.liveClient === client) AeroState.liveClient = null
            AeroState.sessionActive = false
            AeroState.micBoost = 0f
            AeroState.aiBoost = 0f
            KeepAliveService.stop(this)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AeroBgSession"
        private const val NOTIFICATION_ID = 90

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, BackgroundSessionService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BackgroundSessionService::class.java))
        }
    }
}
