package com.aero.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * Owns the MediaProjection so screen capture does not depend on the floating
 * overlay's lifecycle (Android 14+ requires projection to live in a running
 * foreground service with type mediaProjection).
 *
 * Started from MainActivity right after the consent dialog returns OK.
 */
class ScreenShareService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // NOTE: RESULT_OK == -1 on Android, so -1 is a VALID consent code.
        // Use a sentinel that can never collide.
        val code = intent?.getIntExtra(EXTRA_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (code != Int.MIN_VALUE && data != null) {
            MediaProjectionHolder.start(this, code, data)
            Log.d(TAG, "screen projection started (code=$code)")
        } else {
            Log.w(TAG, "onStartCommand without consent data (code=$code)")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "foreground started")
    }

    override fun onDestroy() {
        MediaProjectionHolder.stop()
        Log.d(TAG, "service destroyed, projection stopped")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "aero_screen"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Aero screen share", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Aero is sharing your screen")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AeroScreen"
        private const val NOTIFICATION_ID = 78
        const val EXTRA_CODE = "media_projection_code"
        const val EXTRA_DATA = "media_projection_data"
    }
}