package com.aero.voice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Schedules reminders locally with AlarmManager (exact when allowed). */
object ReminderScheduler {

    const val EXTRA_ID = "reminder_id"
    const val EXTRA_MESSAGE = "reminder_message"

    private const val TAG = "AeroReminder"

    fun schedule(ctx: Context, r: ReminderStore.Reminder) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(ctx, r.id, r.message)
            val whenMillis = r.atMillis

            try {
                // Exact (needs SCHEDULE_EXACT_ALARM on 12+; fallback below).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, whenMillis, pi)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "exact alarm blocked, using inexact", t)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi)
            }
            Log.d(TAG, "scheduled reminder ${r.id} at ${r.atMillis} retry=${r.retryCount}")
        } catch (t: Throwable) {
            Log.e(TAG, "schedule failed", t)
        }
    }

    fun cancel(ctx: Context, id: Long) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(ctx, id, ""))
        } catch (_: Throwable) {
        }
    }

    private fun pendingIntent(ctx: Context, id: Long, message: String): PendingIntent {
        val intent = Intent(ctx, ReminderAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_MESSAGE, message)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(ctx, id.toInt(), intent, flags)
    }
}
