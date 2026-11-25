package com.aero.voice

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Full-screen Aero call UI.
 *
 * Incoming: dark glowing call screen with Answer / Decline.
 * Answered: in-call screen with timer, Speaker (earpiece <-> loudspeaker),
 * Mute, Reminder and End call. The live Aero voice session runs during the
 * call, so the user can talk and set reminders by voice.
 */
class ReminderAlertActivity : ComponentActivity() {

    private var reminderId = -1L
    private var message = ""
    private var retryCount = 0

    private var ringing = false
    private var inCall = false
    private var startedSessionForCall = false
    private var speakerOn = false
    private var callSeconds = 0

    private var player: MediaPlayer? = null
    private var pulse: ObjectAnimator? = null

    private val handler = Handler(Looper.getMainLooper())

    private val retryRunnable = object : Runnable {
        override fun run() {
            ReminderNotifier.cancel(this@ReminderAlertActivity, reminderId)
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

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!inCall) return
            callSeconds++
            findViewById<TextView>(R.id.callTimer).text =
                String.format("%02d:%02d", callSeconds / 60, callSeconds % 60)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reminderId = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1L)
        message = intent.getStringExtra(ReminderScheduler.EXTRA_MESSAGE).orEmpty()
        val r = ReminderStore.all(this).firstOrNull { it.id == reminderId }
        retryCount = r?.retryCount ?: 0

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_reminder_alert)

        findViewById<TextView>(R.id.reminderTitle).text = "Aero"
        findViewById<TextView>(R.id.reminderSub).text = "Incoming Aero call…"
        findViewById<TextView>(R.id.reminderMessage).text = message
        findViewById<TextView>(R.id.callMessage).text = message.takeIf { it.isNotBlank() } ?: "Aero call"

        findViewById<ImageButton>(R.id.reminderClose).setOnClickListener { decline() }
        findViewById<ImageButton>(R.id.reminderAnswer).setOnClickListener { answer() }
        findViewById<ImageButton>(R.id.btnEnd).setOnClickListener { endCall() }
        findViewById<ImageButton>(R.id.btnSpeaker).setOnClickListener { toggleSpeaker() }
        findViewById<ImageButton>(R.id.btnMute).setOnClickListener { toggleMute() }
        findViewById<ImageButton>(R.id.btnReminder).setOnClickListener { askForReminder() }

        startPulse()
        startRinging()
        handler.postDelayed(retryRunnable, 60_000L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (inCall) return
        reminderId = intent.getLongExtra(ReminderScheduler.EXTRA_ID, reminderId)
        message = intent.getStringExtra(ReminderScheduler.EXTRA_MESSAGE) ?: message
        findViewById<TextView>(R.id.reminderMessage).text = message
        findViewById<TextView>(R.id.callMessage).text = message
    }

    // ---------------- incoming ----------------

    private fun startPulse() {
        val avatar = findViewById<View>(R.id.avatarPulse)
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            avatar,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.35f, 0.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.18f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.18f)
        ).apply {
            duration = 1500L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
    }

    private fun startRinging() {
        ringing = true
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION") vibrator.vibrate(pattern, 0)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun stopRinging() {
        ringing = false
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

    // ---------------- answer / in-call ----------------

    private fun answer() {
        if (inCall) return
        stopRinging()
        stopPulse()
        ReminderNotifier.cancel(this, reminderId)
        Log.d(TAG, "call answered: $message")

        inCall = true
        findViewById<View>(R.id.incomingGroup).visibility = View.GONE
        findViewById<View>(R.id.inCallGroup).visibility = View.VISIBLE

        // Earpiece by default (user can switch to speaker).
        speakerOn = false
        CallAudio.enterCall(this)
        updateSpeakerUi()
        updateMuteUi()

        // Start a session only if one is not already running.
        startedSessionForCall = !AeroState.sessionActive
        if (startedSessionForCall) {
            AeroState.sessionActive = true
            AeroState.micMuted = false
            try {
                startForegroundService(Intent(this, BackgroundSessionService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "session start failed", t)
            }
        } else {
            AeroState.micMuted = false
        }

        // Do not show the floating ball over the call screen.
        FloatingOrbService.hide(this)

        announceReminder(attempt = 0)
        callSeconds = 0
        handler.post(timerRunnable)
        ReminderStore.remove(this, reminderId)
    }

    private fun announceReminder(attempt: Int) {
        val client = AeroState.liveClient
        if (client != null) {
            client.sendContextText(
                "[Aero call answered] The user just picked up the Aero call. " +
                    "First, greet briefly in Hindi and say this reminder naturally: $message. " +
                    "Then stay in the call and listen — the user may talk or ask to set another reminder."
            )
            Log.d(TAG, "reminder announced to model (attempt $attempt)")
            return
        }
        if (attempt >= 15) {
            Log.w(TAG, "live client never connected; reminder not announced")
            return
        }
        handler.postDelayed({ announceReminder(attempt + 1) }, 1000L)
    }

    private fun toggleSpeaker() {
        speakerOn = !speakerOn
        CallAudio.setSpeaker(this, speakerOn)
        updateSpeakerUi()
        Toast.makeText(
            this,
            if (speakerOn) "Speaker on" else "Earpiece (kaan par)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateSpeakerUi() {
        val btn = findViewById<ImageButton>(R.id.btnSpeaker)
        btn.setBackgroundResource(
            if (speakerOn) R.drawable.bg_call_button_active else R.drawable.bg_call_button
        )
        findViewById<TextView>(R.id.labelSpeaker).text = if (speakerOn) "Speaker on" else "Speaker"
    }

    private fun toggleMute() {
        AeroState.micMuted = !AeroState.micMuted
        updateMuteUi()
    }

    private fun updateMuteUi() {
        val muted = AeroState.micMuted
        val btn = findViewById<ImageButton>(R.id.btnMute)
        btn.setImageResource(if (muted) R.drawable.ic_mic else R.drawable.ic_mic_off)
        btn.setBackgroundResource(
            if (muted) R.drawable.bg_call_button_active else R.drawable.bg_call_button
        )
        findViewById<TextView>(R.id.labelMute).text = if (muted) "Muted" else "Mute"
    }

    private fun askForReminder() {
        AeroState.liveClient?.sendContextText(
            "[Call UI] The user tapped the Reminder button during the Aero call. " +
                "In Hindi, ask what they want to remember and at what time, then call set_reminder."
        )
        Toast.makeText(this, "Bolo — kya aur kab yaad karna hai?", Toast.LENGTH_SHORT).show()
    }

    // ---------------- end ----------------

    private fun decline() {
        stopRinging()
        ReminderNotifier.cancel(this, reminderId)
        ReminderStore.remove(this, reminderId)
        finish()
    }

    private fun endCall() {
        if (!inCall) {
            decline()
            return
        }
        inCall = false
        handler.removeCallbacks(timerRunnable)
        CallAudio.exitCall(this)
        ReminderNotifier.cancel(this, reminderId)
        AeroState.micMuted = false

        if (startedSessionForCall) {
            AeroState.sessionActive = false
            AeroState.liveClient = null
            BackgroundSessionService.stop(this)
            FloatingOrbService.hide(this)
        } else if (AeroState.sessionActive) {
            // Session was already running before the call — bring the ball back.
            FloatingOrbService.show(this)
        }
        Log.d(TAG, "call ended after ${callSeconds}s")
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inCall) endCall() else decline()
    }

    override fun onDestroy() {
        stopRinging()
        stopPulse()
        handler.removeCallbacks(timerRunnable)
        ReminderNotifier.cancel(this, reminderId)
        if (inCall) CallAudio.exitCall(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AeroReminder"
    }
}
