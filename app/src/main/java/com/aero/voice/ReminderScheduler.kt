package com.aero.voice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Schedules reminders locally with AlarmManager (exact when allowed). */
object ReminderScheduler {

    const val EXTRA_ID = "reminder_id"
    const val EXTRA_MESSAGE = "reminder_message"

    private const val TAG = "AeroReminder"

    fun schedule(ctx: Context, r: ReminderStore.Reminder) {
        scheduleAt(ctx, r.id, r.message, r.atMillis)
    }

    fun scheduleAt(ctx: Context, id: Long, message: String, whenMillis: Long) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(ctx, id, message)
            val triggerAt = whenMillis.coerceAtLeast(System.currentTimeMillis() + 1000L)

            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                am.canScheduleExactAlarms()

            if (exactAllowed) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } else {
                        am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "exact alarm blocked, using inexact", t)
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } else {
                // setAlarmClock does NOT need the exact-alarm permission and is
                // the most reliable wake-up path (used by alarm/call apps).
                try {
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, null), pi)
                } catch (t: Throwable) {
                    Log.w(TAG, "alarmClock blocked, using inexact", t)
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            }
            Log.d(TAG, "scheduled reminder $id at $whenMillis exact=$exactAllowed")
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

    /** Fires the call screen right now (used by "mujhe call karo"). */
    fun ringNow(ctx: Context, message: String) {
        val r = ReminderStore.add(ctx, message, System.currentTimeMillis())
        ReminderNotifier.show(ctx, r.id, message, ringing = true)
        try {
            val alert = Intent(ctx, ReminderAlertActivity::class.java).apply {
                putExtra(EXTRA_ID, r.id)
                putExtra(EXTRA_MESSAGE, message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            ctx.startActivity(alert)
        } catch (t: Throwable) {
            Log.w(TAG, "direct ring start blocked; full-screen notification remains", t)
        }
    }
}
