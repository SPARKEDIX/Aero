package com.aero.voice

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Full-screen "incoming call" ring for reminders. Rings + vibrates for
 * ~60 seconds; if unanswered, schedules a retry 1 minute later (up to 3).
 * Answer → opens the Aero session so the user can talk to the AI.
 */
class ReminderAlertActivity : ComponentActivity() {

    private var reminderId = -1L
    private var message = ""
    private var retryCount = 0

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val retryRunnable = object : Runnable {
        override fun run() {
            // Not answered in 60s → retry in 1 minute.
            if (retryCount < 3) {
                val next = ReminderStore.Reminder(
                    reminderId, message, System.currentTimeMillis() + 60_000L,
                    retryCount + 1
                )
                ReminderStore.update(this@ReminderAlertActivity, next)
                ReminderScheduler.schedule(this@ReminderAlertActivity, next)
                Log.d(TAG, "retry scheduled ($retryCount)")
            } else {
                ReminderStore.remove(this@ReminderAlertActivity, reminderId)
                Log.d(TAG, "max retries reached, dropped")
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reminderId = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1L)
        message = intent.getStringExtra(ReminderScheduler.EXTRA_MESSAGE) ?: ""
        val r = ReminderStore.all(this).firstOrNull { it.id == reminderId }
        retryCount = r?.retryCount ?: 0

        // Full-screen, keep screen on, show over lock screen.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        setContentView(R.layout.activity_reminder_alert)

        findViewById<TextView>(R.id.reminderTitle).text = "Aero Reminder"
        findViewById<TextView>(R.id.reminderMessage).text = message
        findViewById<TextView>(R.id.reminderSub).text = "Answer to talk to Aero"

        findViewById<ImageButton>(R.id.reminderAnswer).setOnClickListener { answer() }
        findViewById<ImageButton>(R.id.reminderClose).setOnClickListener { dismiss() }

        startRinging()
        handler.postDelayed(retryRunnable, 60_000L)
    }

    private fun startRinging() {
        try {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ReminderAlertActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
        }
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                val pattern = longArrayOf(0, 800, 400, 800, 400, 800)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION") vibrator.vibrate(pattern, 0)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun stopRinging() {
        handler.removeCallbacks(retryRunnable)
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.cancel()
        } catch (_: Exception) {
        }
    }

    private fun answer() {
        stopRinging()
        // Open the Aero session + floating orb; AI will announce the reminder.
        if (!AeroState.sessionActive) {
            AeroState.sessionActive = true
            try {
                startForegroundService(Intent(this, BackgroundSessionService::class.java))
            } catch (_: Exception) {
            }
        }
        FloatingOrbService.show(this)
        AeroState.liveClient?.sendContextText("[Reminder] $message")
        ReminderStore.remove(this, reminderId)
        finish()
    }

    private fun dismiss() {
        stopRinging()
        ReminderStore.remove(this, reminderId)
        finish()
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AeroReminder"
    }
}
