package com.aero.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.ServiceCompat
/**
 * Always-on background hotword listener.
 *
 * Listens for "Hey Aero" / "OK Aero" while no voice session is running. On a
 * wake word it silently starts the background voice session and shows the
 * floating orb — the main app never opens. While a session IS running the
 * recognizer pauses so it never fights the session's microphone.
 */
class HotwordService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recognizing = false
    private var destroyed = false

    private val monitor = object : Runnable {
        override fun run() {
            if (destroyed) return
            val sessionActive = AeroState.sessionActive
            if (sessionActive && recognizing) {
                stopListening()
            } else if (!sessionActive && !recognizing) {
                startListening()
            }
            handler.postDelayed(this, 2000L)
        }
    }

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
        Log.d(TAG, "hotword service started")
        handler.post(monitor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startListening() {
        if (recognizing || destroyed) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(this)
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Log.w(TAG, "recognizer error=$error")
                    recognizing = false
                    sr.destroy()
                    if (recognizer === sr) recognizer = null
                    handler.postDelayed({ if (!destroyed) startListening() }, 1500L)
                }
                override fun onResults(results: Bundle?) {
                    recognizing = false
                    sr.destroy()
                    if (recognizer === sr) recognizer = null
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
                    if (list.isEmpty()) {
                        Log.w(TAG, "empty recognition result")
                    } else {
                        Log.d(TAG, "heard: ${list.joinToString(" | ")}")
                        // Check every candidate, not just the top one.
                        val wake = list.any { isWakePhrase(it) }
                        if (wake) {
                            Log.d(TAG, "WAKE WORD DETECTED")
                            onWake()
                        }
                    }
                    handler.postDelayed({ if (!destroyed) startListening() }, 500L)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                // Force English recognition: wake words like "hey aero" are
                // most reliably transcribed in English on most devices.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Restart quickly so hotword stays responsive.
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1500L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1500L
                )
            }
            recognizer = sr
            recognizing = true
            sr.startListening(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "startListening failed", t)
            recognizing = false
            handler.postDelayed({ if (!destroyed) startListening() }, 2000L)
        }
    }

    private fun stopListening() {
        if (!recognizing) return
        recognizing = false
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
    }

    private fun isWakePhrase(text: String): Boolean {
        val t = normalize(text)
        if (t.length < 4) return false
        // Accept common phonetic variants users say: "hey aero", "hero aero",
        // "ok aero", "airo", "ayro", "ero", "hero", "auro" …
        val variants = listOf("aero", "airo", "ayro", "ero", "hero", "auro", "eyro", "eero")
        if (variants.any { t.contains(it) }) return true
        // Also accept the user-defined wake command from Settings.
        val custom = normalize(Prefs.wakePhrase(this))
        if (custom.length >= 3 && t.contains(custom)) return true
        return false
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun onWake() {
        // No session → start an invisible background voice session.
        if (!AeroState.sessionActive) {
            AeroState.sessionActive = true
            try {
                startForegroundService(Intent(this, BackgroundSessionService::class.java))
            } catch (t: Throwable) {
                Log.e(TAG, "failed to start background session", t)
            }
        }
        // Show the floating orb (respects overlay permission).
        FloatingOrbService.show(this)
    }

    private fun buildNotification(): Notification {
        val channelId = "aero_hotword"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Aero hotword", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Aero is listening for \"Hey Aero\"")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(monitor)
        stopListening()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AeroHotword"
        private const val NOTIFICATION_ID = 99

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, HotwordService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HotwordService::class.java))
        }
    }
}
