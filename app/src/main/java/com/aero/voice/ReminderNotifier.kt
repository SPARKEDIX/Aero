package com.aero.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Full-screen-intent notification used as the reliable "incoming call" path.
 * If Android blocks a direct activity start from the background, the system
 * still shows the full-screen call UI from this notification.
 */
object ReminderNotifier {

    private const val CHANNEL_ID = "aero_calls"
    private const val CHANNEL_NAME = "Aero Calls & Reminders"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming Aero reminder calls"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun show(ctx: Context, id: Long, message: String, ringing: Boolean = true) {
        ensureChannel(ctx)
        val open = Intent(ctx, ReminderAlertActivity::class.java).apply {
            putExtra(ReminderScheduler.EXTRA_ID, id)
            putExtra(ReminderScheduler.EXTRA_MESSAGE, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(ctx, id.toInt(), open, flags)

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (ringing) "Aero is calling" else "Aero reminder")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()

        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id.toInt(), notification)
        } catch (_: Throwable) {
        }
    }

    fun cancel(ctx: Context, id: Long) {
        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(id.toInt())
        } catch (_: Throwable) {
        }
    }
}
