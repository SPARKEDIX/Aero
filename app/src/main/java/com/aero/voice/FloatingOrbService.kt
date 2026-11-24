package com.aero.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.app.ServiceCompat
import kotlin.math.abs

/** Floating mini-orb shown after screen share. Draggable, tap to expand controls. */
class FloatingOrbService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var root: View
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = false

    private var startX = 0
    private var startY = 0
    private var initialX = 0
    private var initialY = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Projection is owned by ScreenShareService now; this service only
        // draws the floating orb.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): Nothing? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        android.util.Log.d("AeroOverlay", "foreground started")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        root = LayoutInflater.from(this).inflate(R.layout.view_overlay, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        params = WindowManager.LayoutParams(
            dp(110), dp(110), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(root, params)
        placeBottomRight()
        wireViews()
        handler.post(boostPump)
    }

    private fun placeBottomRight() {
        val display = resources.displayMetrics
        params.x = display.widthPixels - dp(110) - dp(4)
        params.y = display.heightPixels - dp(110) - dp(110)
        windowManager.updateViewLayout(root, params)
    }

    private fun wireViews() {
        val orbHost = root.findViewById<View>(R.id.orbHost)
        val panel = root.findViewById<View>(R.id.expandedPanel)
        val overlayOrb = root.findViewById<OrbView>(R.id.overlayOrb)
        val mute = root.findViewById<ImageButton>(R.id.overlayMute)

        mute.setImageResource(
            if (AeroState.micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic
        )

        orbHost.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt()
                    startY = event.rawY.toInt()
                    initialX = params.x
                    initialY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - startX).toInt()
                    params.y = initialY + (event.rawY - startY).toInt()
                    windowManager.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - startX) > 8 || abs(event.rawY - startY) > 8
                    if (!moved) toggleExpanded()
                    true
                }
                else -> true
            }
        }

        // Tap anywhere outside the panel collapses the orb again.
        root.isClickable = true
        root.setOnTouchListener { _, event ->
            if (expanded && event.action == MotionEvent.ACTION_UP) {
                val loc = IntArray(2)
                panel.getLocationOnScreen(loc)
                val outside = event.rawX < loc[0] || event.rawX > loc[0] + panel.width ||
                    event.rawY < loc[1] || event.rawY > loc[1] + panel.height
                if (outside) collapse()
            }
            false
        }

        overlayOrb.setMood(Prefs.mood(this))

        root.findViewById<ImageButton>(R.id.overlayCamera).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_CAMERA
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }

        root.findViewById<ImageButton>(R.id.overlayScreenToggle).setOnClickListener {
            Log.d(TAG, "screen toggle clicked")
            if (MediaProjectionHolder.isRunning) {
                // Share is ON → stop only the projection service. The floating
                // orb stays so the user can keep using the assistant.
                stopService(Intent(this, ScreenShareService::class.java))
            } else {
                // Share is OFF → (re)start it via an INVISIBLE trampoline
                // activity. The main app never comes to the front — the user
                // only sees the system consent dialog, and on accept we return
                // straight back to the background.
                val intent = Intent(this, ScreenShareConsentActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }

        mute.setOnClickListener {
            AeroState.micMuted = !AeroState.micMuted
            mute.setImageResource(
                if (AeroState.micMuted) R.drawable.ic_mic_off else R.drawable.ic_mic
            )
        }

        root.findViewById<ImageButton>(R.id.overlayClose).setOnClickListener {
            Log.d(TAG, "close clicked — full exit")
            // End the background voice session completely (stop listening &
            // speaking) and close the orb. The session service cleans up
            // AeroState and KeepAliveService on destroy.
            stopService(Intent(this, BackgroundSessionService::class.java))
            stopSelf()
        }

        // Keep the mini orb alive with the shared voice levels.
        overlayOrb.visibility = View.VISIBLE
    }

    private val boostPump = object : Runnable {
        override fun run() {
            val orb = root.findViewById<OrbView>(R.id.overlayOrb)
            orb.micBoost = AeroState.micBoost
            orb.aiBoost = AeroState.aiBoost
            handler.postDelayed(this, 80L)
        }
    }

    private fun toggleExpanded() {
        if (expanded) {
            collapse()
        } else {
            expanded = true
            val panel = root.findViewById<View>(R.id.expandedPanel)
            panel.visibility = View.VISIBLE
            val orbHost = root.findViewById<View>(R.id.orbHost)
            (orbHost.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(24)
                leftMargin = 0
                rightMargin = 0
                topMargin = 0
            }
            orbHost.layoutParams = orbHost.layoutParams
            panel.scaleX = 0.7f
            panel.scaleY = 0.7f
            panel.alpha = 0f
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            windowManager.updateViewLayout(root, params)
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
        }
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        root.findViewById<View>(R.id.expandedPanel).visibility = View.INVISIBLE
        val orbHost = root.findViewById<View>(R.id.orbHost)
        (orbHost.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = 0
            leftMargin = 0
            rightMargin = dp(4)
            topMargin = 0
        }
        orbHost.layoutParams = orbHost.layoutParams
        params.width = dp(110)
        params.height = dp(110)
        placeBottomRight()
        windowManager.updateViewLayout(root, params)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildNotification(): Notification {
        val channelId = "aero_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Aero floating orb", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Aero is active")
            .setContentText("Tap the orb to keep talking")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(boostPump)
        // Projection is owned by ScreenShareService; only remove the overlay here.
        if (::root.isInitialized) {
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AeroOverlay"
        const val ACTION_OPEN_FILES = "com.aero.voice.OPEN_FILES"
        const val ACTION_OPEN_CAMERA = "com.aero.voice.OPEN_CAMERA"
        const val ACTION_STOP_SCREEN = "com.aero.voice.STOP_SCREEN"
        const val EXTRA_CODE = "media_projection_code"
        const val EXTRA_DATA = "media_projection_data"
        private const val NOTIFICATION_ID = 77

        /** Shows the floating orb (no-op if overlay permission is missing). */
        fun show(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            try {
                ctx.startForegroundService(Intent(ctx, FloatingOrbService::class.java))
            } catch (t: Throwable) {
                Log.e(TAG, "show failed", t)
            }
        }

        /** Hides the floating orb. */
        fun hide(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingOrbService::class.java))
        }
    }
}
